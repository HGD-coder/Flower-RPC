package com.github.hgdcoder.proxy;

import com.github.hgdcoder.enums.RpcErrorMessageEnum;
import com.github.hgdcoder.enums.RpcStatusCode;
import com.github.hgdcoder.exception.RpcException;
import com.github.hgdcoder.exception.RpcRemoteException;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.transport.RpcRequestTransport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

public class RpcClientProxy implements InvocationHandler {
    private final RpcRequestTransport rpcRequestTransport;
    private final String group;
    private final String version;

    /** 当前代理暴露给调用方的接口。 */
    private final Class<?> proxyInterface;

    /** 真正注册在服务端的接口，请求中的 interfaceName 必须使用它。 */
    private final Class<?> serviceInterface;

    public RpcClientProxy(RpcRequestTransport transport, String group, String version) {
        this(transport, group, version, null, null);
    }

    private RpcClientProxy(
            RpcRequestTransport transport,
            String group,
            String version,
            Class<?> proxyInterface,
            Class<?> serviceInterface
    ) {
        if (transport == null) {
            throw new IllegalArgumentException("rpcRequestTransport must not be null");
        }
        this.rpcRequestTransport = transport;
        this.group = group;
        this.version = version;
        this.proxyInterface = proxyInterface;
        this.serviceInterface = serviceInterface;
    }

    /** 创建普通同步代理，方法会等待 RPC 响应后再返回。 */
    public <T> T getProxy(Class<T> serviceInterface) {
        validateInterface(serviceInterface, "RPC service interface");
        return createProxy(serviceInterface,serviceInterface);
    }

    /**
     * 创建异步镜像代理。
     *
     * @param asyncInterface 调用方使用的异步接口，例如 HelloServiceAsync
     * @param serviceInterface 服务端注册的原接口，例如 HelloService
     */
    public <T> T getAsyncProxy(Class<T> asyncInterface, Class<?> serviceInterface) {
        validateAsyncMirror(asyncInterface, serviceInterface);
        return createProxy(asyncInterface, serviceInterface);
    }

    @Override
    public Object invoke(Object proxy, Method method,Object[] args) {
        //getDeclaringClass()获取“这个方法最初是在哪个类或接口里声明的”
        if(method.getDeclaringClass() == Object.class) {
            return handleObjectMethod(proxy,method,args);
        }

        RpcRequest request = createRequest(method, args);
        CompletableFuture<RpcResponse<Object>> responseFuture = sendRequest(request);
        CompletableFuture<Object> resultFuture = adaptResponse(responseFuture, request);

        // 异步方法立即返回；响应到达后由客户端 Handler 完成这个 Future。
        if (isAsyncReturnType(method)) {
            return resultFuture;
        }

        // 同步方法仅在代理边界等待，因此原有调用方式保持不变。
        return awaitResponse(resultFuture, request);
    }

    @SuppressWarnings("unchecked")
    private<T> T createProxy(Class<T> exposedInterface,Class<?> remoteInterface) {
        RpcClientProxy handler = new RpcClientProxy(
                rpcRequestTransport,
                group,
                version,
                exposedInterface,
                remoteInterface
        );
        return (T) Proxy.newProxyInstance(
                exposedInterface.getClassLoader(),
                new Class<?>[]{exposedInterface},
                handler
        );
    }

    private RpcRequest createRequest(Method method, Object[] args) {
        return RpcRequest.builder()
                .requestId(UUID.randomUUID().toString())
                // 异步代理也必须指向服务端实际注册的同步服务接口。
                .interfaceName(serviceInterface.getName())
                .methodName(method.getName())
                .parameters(args)
                .paramTypes(method.getParameterTypes())
                .group(group)
                .version(version)
                .build();
    }

    /** 把传输层同步抛出的异常也统一变成失败 Future。 */
    private CompletableFuture<RpcResponse<Object>> sendRequest(RpcRequest request) {
        try {
            CompletableFuture<RpcResponse<Object>> future =
                    rpcRequestTransport.sendRpcRequest(request);
            if (future == null) {
                return failedFuture(new RpcException(
                        RpcStatusCode.INTERNAL,
                        request.getRequestId(),
                        "RPC transport returned a null future",
                        null
                ));
            }
            return future;
        } catch (RuntimeException e) {
            return failedFuture(normalizeClientFailure(e, request));
        }
    }

    /** 把协议响应 Future 转换成只含业务返回值的 Future。 */
    private CompletableFuture<Object> adaptResponse(
            CompletableFuture<RpcResponse<Object>> responseFuture,
            RpcRequest request
    ) {
        CompletableFuture<Object> resultFuture = new CompletableFuture<>();
        responseFuture.whenComplete((response, throwable) -> {
            if (throwable != null) {
                resultFuture.completeExceptionally(
                        normalizeClientFailure(throwable, request)
                );
                return;
            }
            try {
                check(request, response);
                resultFuture.complete(response.getData());
            } catch (RuntimeException e) {
                resultFuture.completeExceptionally(e);
            }
        });

        // 调用方取消业务 Future 时，继续把取消信号传给传输层 Future。
        resultFuture.whenComplete((result, throwable) -> {
            if (resultFuture.isCancelled()) {
                responseFuture.cancel(true);
            }
        });
        return resultFuture;
    }


    private Object awaitResponse(CompletableFuture<Object> future, RpcRequest request) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new RpcException(
                    RpcStatusCode.CANCELLED,
                    request.getRequestId(),
                    "等待 RPC 响应时线程被中断",
                    e
            );
        } catch (CancellationException e) {
            throw new RpcException(
                    RpcStatusCode.CANCELLED,
                    request.getRequestId(),
                    RpcStatusCode.CANCELLED.getMessage(),
                    e
            );
        } catch (ExecutionException e) {
            Throwable cause = unwrap(e);
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RpcException(
                    RpcStatusCode.UNKNOWN,
                    request.getRequestId(),
                    RpcStatusCode.UNKNOWN.getMessage(),
                    cause
            );
        }
    }

    private void check(RpcRequest request, RpcResponse<?> response) {
        if (response == null) {
            throw new RpcException(
                    RpcErrorMessageEnum.REQUEST_NOT_MATCH_RESPONSE,
                    "响应对象为空，requestId=" + request.getRequestId()
            );
        }
        if (!request.getRequestId().equals(response.getRequestId())) {
            throw new RpcException(
                    RpcErrorMessageEnum.REQUEST_NOT_MATCH_RESPONSE,
                    "requestId=" + request.getRequestId()
                            + ", responseId=" + response.getRequestId()
            );
        }
        if (!response.isSuccess()) {
            RpcStatusCode statusCode;
            try {
                statusCode = RpcStatusCode.fromCode(response.getCode());
            } catch (RuntimeException e) {
                throw new RpcException(
                        RpcStatusCode.DATA_LOSS,
                        request.getRequestId(),
                        "未知响应状态码: " + response.getCode(),
                        e
                );
            }
            throw new RpcRemoteException(
                    statusCode,
                    response.getRequestId(),
                    response.getMessage()
            );
        }
    }

    /** 异步接口中的每个方法都必须能映射到原服务接口。 */
    private static void validateAsyncMirror(
            Class<?> asyncInterface,
            Class<?> serviceInterface
    ) {
        validateInterface(asyncInterface, "RPC async interface");
        validateInterface(serviceInterface, "RPC service interface");

        for (Method asyncMethod : asyncInterface.getMethods()) {
            if (Modifier.isStatic(asyncMethod.getModifiers())) {
                continue;
            }
            if (!isAsyncReturnType(asyncMethod)
                    || !asyncMethod.getReturnType()
                    .isAssignableFrom(CompletableFuture.class)) {
                throw new IllegalArgumentException(
                        "异步 RPC 方法必须返回 CompletableFuture 或 CompletionStage: "
                                + asyncMethod.toGenericString()
                );
            }
            try {
                Method serviceMethod = serviceInterface.getMethod(
                        asyncMethod.getName(),
                        asyncMethod.getParameterTypes()
                );
                if (Modifier.isStatic(serviceMethod.getModifiers())) {
                    throw new IllegalArgumentException("异步 RPC 不能映射静态方法");
                }
                validateAsyncResultType(asyncMethod, serviceMethod);
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException(
                        "异步接口中存在无法映射到服务接口的方法: "
                                + asyncMethod.toGenericString(),
                        e
                );
            }
        }
    }

    /** 校验 Future<T> 中的 T 是否能接收同步方法返回值。 */
    private static void validateAsyncResultType(Method asyncMethod, Method serviceMethod) {
        Type asyncType = futureResultType(asyncMethod);
        Class<?> serviceType = boxed(serviceMethod.getReturnType());
        if (asyncType instanceof Class<?>
                && !((Class<?>) asyncType).isAssignableFrom(serviceType)) {
            throw new IllegalArgumentException(
                    "异步方法的结果类型与服务方法不一致: "
                            + asyncMethod.toGenericString()
            );
        }
    }

    private static Type futureResultType(Method method) {
        Type type = method.getGenericReturnType();
        if (type instanceof ParameterizedType) {
            Type[] arguments = ((ParameterizedType) type).getActualTypeArguments();
            if (arguments.length == 1) {
                return arguments[0];
            }
        }
        return Object.class;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == void.class) {
            return Void.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        return Character.class;
    }

    private static void validateInterface(Class<?> type, String description) {
        if (type == null || !type.isInterface()) {
            throw new IllegalArgumentException(description + " must be an interface");
        }
    }

    private static boolean isAsyncReturnType(Method method) {
        return CompletionStage.class.isAssignableFrom(method.getReturnType());
    }

    private RpcException normalizeClientFailure(Throwable throwable, RpcRequest request) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof RpcException) {
            return (RpcException) cause;
        }
        if (cause instanceof CancellationException) {
            return new RpcException(
                    RpcStatusCode.CANCELLED,
                    request.getRequestId(),
                    RpcStatusCode.CANCELLED.getMessage(),
                    cause
            );
        }
        return new RpcException(
                RpcStatusCode.UNAVAILABLE,
                request.getRequestId(),
                "RPC transport failed",
                cause
        );
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** JDK 8 没有 CompletableFuture.failedFuture，所以在这里补一个小工具。 */
    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(throwable);
        return future;
    }

    /**
     * 为什么要处理 Object 方法：
     * JDK 动态代理会把 toString、equals、hashCode 也交给 invoke。
     * 如果不处理，调用 helloService.toString() 也会被误包装成远程请求。
     * @param proxy
     * @param method
     * @param args
     * @return
     */
    private Object handleObjectMethod(Object proxy,Method method,Object[] args){
        String methodName = method.getName();

        if("toString".equals(methodName)){
            return "RpcClientProxy[group=" + group + ", version=" + version + "]";
        }

        if("hashCode".equals(methodName)){
            return System.identityHashCode(proxy);
        }

        if("equals".equals(methodName)){
            return proxy == args[0];
        }

        throw new UnsupportedOperationException("Unsupported Object method: " + methodName);
    }
}
