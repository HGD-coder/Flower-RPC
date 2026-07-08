package com.github.hgdcoder.proxy;

import com.github.hgdcoder.enums.RpcResponseCodeEnum;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.transport.RpcRequestTransport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

public class RpcClientProxy implements InvocationHandler {
    private final RpcRequestTransport rpcRequestTransport;
    private final String group;
    private final String version;

    public RpcClientProxy(RpcRequestTransport rpcRequestTransport, String group, String version) {
        this.rpcRequestTransport = rpcRequestTransport;
        this.group = group;
        this.version = version;
    }

    @SuppressWarnings("unchecked")
    public <T> T getProxy(Class<T> clazz) {
        return (T) Proxy.newProxyInstance(
                clazz.getClassLoader(),
                new Class<?>[]{clazz},
                this);
    }

    @Override
    public Object invoke(Object proxy, Method method,Object[] args) {
        //getDeclaringClass()获取“这个方法最初是在哪个类或接口里声明的”
        if(method.getDeclaringClass() == Object.class) {
            return handleObjectMethod(proxy,method,args);
        }

        RpcRequest rpcRequest = RpcRequest.builder()
                .requestId(UUID.randomUUID().toString())
                .interfaceName(method.getDeclaringClass().getName())
                .methodName(method.getName())
                .parameters(args)
                .paramTypes(method.getParameterTypes())
                .group(group)
                .version(version)
                .build();

        RpcResponse<?> rpcResponse=(RpcResponse<?>) rpcRequestTransport.sendRpcRequest(rpcRequest);
        check(rpcRequest,rpcResponse);

        return rpcResponse.getData();
    }

    private void check(RpcRequest rpcRequest, RpcResponse<?> rpcResponse) {
        if(rpcResponse==null){
            throw new RuntimeException("Rpc Response is null");
        }

        if(!rpcRequest.getRequestId().equals(rpcResponse.getRequestId())){
            throw new RuntimeException("Request and response id do not match");
        }

        Integer successCode= RpcResponseCodeEnum.SUCCESS.getCode();
        if(!successCode.equals(rpcResponse.getCode())){
            throw new RuntimeException("Rpc call failed: "+rpcResponse.getMessage());
        }
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
