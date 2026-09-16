package com.github.hgdcoder.provider;

import com.github.hgdcoder.annotation.RpcSlow;
import com.github.hgdcoder.config.RpcServiceConfig;
import com.github.hgdcoder.remoting.dto.RpcRequest;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 保存 RPC 方法的执行类型。
 *
 * <p>服务注册阶段执行反射扫描；请求处理阶段只进行并发 Set 查询。</p>
 */
public class RpcMethodExecutionRegistry {

    /**
     * 只保存 slow 方法。
     *
     * <p>未出现在该集合中的方法默认是 fast。</p>
     */
    private final Set<MethodKey> slowMethods =
            ConcurrentHashMap.newKeySet();

    /**
     * 扫描并注册一个 RPC 服务中的慢方法。
     */
    public void register(RpcServiceConfig serviceConfig) {
        Objects.requireNonNull(
                serviceConfig,
                "serviceConfig must not be null"
        );
        String rpcServiceName =
                serviceConfig.getRpcServiceName();
        String interfaceName =
                serviceConfig.getServiceName();
        Class<?> implementationClass =
                serviceConfig.getServiceClass();

        Class<?> serviceInterface = findInterface(
                implementationClass,
                interfaceName
        );

        if(serviceInterface==null){
            throw new IllegalArgumentException(
                    "Service implementation "
                            + implementationClass.getName()
                            + " does not implement RPC interface "
                            + interfaceName
            );
        }

        /*
         * 先保存到局部集合。
         * 扫描过程中出现异常时，不会向共享注册表写入部分结果。
         */
        Set<MethodKey> discoveredSlowMethods =
                new HashSet<>();

        for(Method interfaceMethod : serviceInterface.getMethods()){
            /*
             * 接口静态方法属于接口本身，不是实现类需要实现的 RPC 方法。
             * 如果继续按实例方法查找，会把合法的接口辅助方法误判为缺少实现。
             */
            if (Modifier.isStatic(interfaceMethod.getModifiers())) {
                continue;
            }

            Method implementationMethod =
                    findImplementationMethod(
                            implementationClass,
                            interfaceMethod
                    );

            /*
             * 支持两种标注方式：
             *
             * 1. 接口方法标注；
             * 2. 实现类方法标注。
             *
             * 任意一处存在 @RpcSlow 就作为慢方法。
             */
            boolean slow =
                    interfaceMethod.isAnnotationPresent(
                            RpcSlow.class
                    )
                            || implementationMethod.isAnnotationPresent(
                            RpcSlow.class
                    );

            if(slow){
                discoveredSlowMethods.add(
                        MethodKey.of(
                                rpcServiceName,
                                interfaceMethod.getName(),
                                interfaceMethod.getParameterTypes()
                        )
                );
            }
        }
        slowMethods.addAll(discoveredSlowMethods);
    }

    /**
     * 请求处理阶段判断目标方法是否属于慢方法。
     *
     * <p>该方法运行在 Netty EventLoop 中，只允许进行内存查询。</p>
     */
    public boolean isSlow(RpcRequest request){
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        return slowMethods.contains(
                MethodKey.of(
                        request.getRpcServiceName(),
                        request.getMethodName(),
                        request.getParamTypes()
                )
        );
    }

    /**
     * 在类和接口继承结构中查找指定 RPC 接口。
     * 在 Java 类型关系图里，从实现类出发 DFS，寻找名字等于 interfaceName 的接口节点。
     */
    private static Class<?> findInterface(
            Class<?> currentClass,
            String interfaceName
    ) {
        if (currentClass == null) {
            return null;
        }

        if (currentClass.isInterface()
                && currentClass.getName()
                .equals(interfaceName)) {
            return currentClass;
        }

        // 当前类直接实现的接口 clazz.getInterfaces();
        // 如果 clazz 本身是接口，则是它直接继承的接口
        for (Class<?> implementedInterface : currentClass.getInterfaces()) {
            Class<?> match = findInterface(
                    implementedInterface,
                    interfaceName
            );

            if (match != null) {
                return match;
            }
        }

        return findInterface(
                // 当前类的直接父类
                currentClass.getSuperclass(),
                interfaceName
        );
    }

    /**
     * 找到实现类中与 RPC 接口方法对应的方法。
     */
    private static Method findImplementationMethod(
            Class<?> implementationClass,
            Method interfaceMethod
    ) {
        try {
            return implementationClass.getMethod(
                    interfaceMethod.getName(),
                    interfaceMethod.getParameterTypes()
            );
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "RPC method "
                            + interfaceMethod
                            + " is not implemented by "
                            + implementationClass.getName(),
                    e
            );
        }
    }

    /**
     * 唯一标识一个 RPC 方法。
     *
     * <p>必须包含完整服务名和参数类型，否则 group/version
     * 或重载方法之间可能发生错误分类。</p>
     */
    private static final class MethodKey {

        private final String rpcServiceName;
        private final String methodName;
        private final List<String> parameterTypeNames;

        private MethodKey(
                String rpcServiceName,
                String methodName,
                Class<?>[] parameterTypes
        ) {
            this.rpcServiceName = rpcServiceName;
            this.methodName = methodName;

            Class<?>[] safeParameterTypes =
                    parameterTypes == null
                            ? new Class<?>[0]
                            : parameterTypes;

            List<String> names = new ArrayList<>(
                    safeParameterTypes.length
            );

            for (Class<?> parameterType
                    : safeParameterTypes) {
                names.add(
                        parameterType == null
                                ? "<null>"
                                : parameterType.getName()
                );
            }

            this.parameterTypeNames =
                    Collections.unmodifiableList(names);
        }

        private static MethodKey of(
                String rpcServiceName,
                String methodName,
                Class<?>[] parameterTypes
        ) {
            return new MethodKey(
                    rpcServiceName,
                    methodName,
                    parameterTypes
            );
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }

            if (!(other instanceof MethodKey)) {
                return false;
            }

            MethodKey that = (MethodKey) other;


            return Objects.equals(
                    rpcServiceName,
                    that.rpcServiceName
            ) && Objects.equals(
                    methodName,
                    that.methodName
            ) && Objects.equals(
                    parameterTypeNames,
                    that.parameterTypeNames
            );
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    rpcServiceName,
                    methodName,
                    parameterTypeNames
            );
        }
    }
}
