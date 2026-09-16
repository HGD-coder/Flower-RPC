package com.github.hgdcoder.provider.impl;

import com.github.hgdcoder.config.RpcServiceConfig;
import com.github.hgdcoder.enums.RpcErrorMessageEnum;
import com.github.hgdcoder.exception.RpcException;
import com.github.hgdcoder.provider.ServiceProvider;
import com.github.hgdcoder.registry.ServiceRegistry;
import com.github.hgdcoder.provider.RpcMethodExecutionRegistry;
import com.github.hgdcoder.remoting.dto.RpcRequest;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultServiceProvider implements ServiceProvider {
    /**
     * key: rpcServiceName
     * value: the real service object, for example HelloServiceImpl
     * 服务名 -> 本地业务对象
     */
    private final Map<String, Object> serviceMap = new ConcurrentHashMap<>();

    // 已加入本地容器的服务名   批量发布时，知道有哪些本地服务需要发布
    private final Set<String> registeredService = ConcurrentHashMap.newKeySet();

    // 已成功写入注册中心的服务名    用于避免重复发布，并记录关闭时需要注销哪些服务
    private final Set<String> publishedService = ConcurrentHashMap.newKeySet();

    /**
     * 服务端方法执行类型注册表。
     */
    private final RpcMethodExecutionRegistry methodExecutionRegistry =
            new RpcMethodExecutionRegistry();

    /** 当前这个服务提供者发布到注册中心的地址。 */
    private InetSocketAddress publishedAddress;

    //注册中心操作对象   委托它执行注册、注销；实际实现可以是 ZooKeeper 或 文件
    private final ServiceRegistry serviceRegistry;

    private final InetSocketAddress serviceAddress;

    public DefaultServiceProvider() {
        this(null, null);
    }

    public DefaultServiceProvider(ServiceRegistry serviceRegistry, InetSocketAddress serviceAddress) {
        this.serviceRegistry = serviceRegistry;
        this.serviceAddress = serviceAddress;
    }

    @Override
    public synchronized void addService(RpcServiceConfig rpcServiceConfig) {
        Objects.requireNonNull(
                rpcServiceConfig,
                "rpcServiceConfig must not be null"
        );

        String rpcServiceName = rpcServiceConfig.getRpcServiceName();
        Object service = Objects.requireNonNull(
                rpcServiceConfig.getService(),
                "RPC service must not be null"
        );

        /*
         * putIfAbsent：只有名称不存在时才放入。
         * 返回 null 表示本次成功添加；
         * 返回非 null 表示原来已经有服务。
         */
        Object existingService = serviceMap.putIfAbsent(
                rpcServiceName,
                service
        );

        if (existingService != null) {
            // 同一个对象重复添加属于幂等调用，直接返回。
            if (existingService == service) {
                return;
            }

            // 相同服务名绑定不同对象通常是配置错误，不能静默覆盖。
            throw new IllegalStateException(
                    "A different service is already registered as "
                            + rpcServiceName
            );
        }


        try {
            methodExecutionRegistry.register(
                    rpcServiceConfig
            );
            registeredService.add(rpcServiceName);
        } catch (RuntimeException registrationFailure) {
            serviceMap.remove(rpcServiceName, service);
            registeredService.remove(rpcServiceName);
            throw registrationFailure;
        }
    }

    @Override
    public boolean isSlowRequest(RpcRequest request) {
        return methodExecutionRegistry.isSlow(request);
    }

    @Override
    public Object getService(String rpcServiceName) {
        Object service = serviceMap.get(rpcServiceName);

        if (service == null) {
            throw new RpcException(
                    RpcErrorMessageEnum.SERVICE_CAN_NOT_BE_FOUND,
                    rpcServiceName
            );
        }

        return service;
    }

    @Override
    public synchronized void publishService(RpcServiceConfig rpcServiceConfig) {
        // 先保证服务存在于本地容器。
        addService(rpcServiceConfig);

        /*
         * 保留手动调用时“立即发布单个服务”的旧语义。
         * Spring 自动装配流程后面会改成只调用 addService()。
         */
        if (serviceRegistry != null && serviceAddress != null) {
            publishOne(
                    rpcServiceConfig.getRpcServiceName(),
                    serviceAddress
            );
        }
    }

    /**
     * Netty 端口绑定成功后，发布所有本地服务。
     *
     * synchronized 保证同一时间只有一个线程执行发布或注销，
     * 防止 publishedService 和 publishedAddress 状态混乱。
     */
    @Override
    public synchronized void publishAllServices(
            InetSocketAddress serverAddress
    ) {
        requireAddress(serverAddress);

        // 没有配置注册中心时，只作为本地服务容器使用。
        if (serviceRegistry == null) {
            return;
        }

        // 不允许同一个 Provider 同时发布到两个不同地址。
        ensureSameAddress(serverAddress);

        /*
         * 只记录“本轮调用”成功发布的服务。
         * 如果后面的服务发布失败，只回滚本轮新增的服务，
         * 不能误删以前已经发布成功的服务。
         */
        List<String> publishedInThisCall = new ArrayList<>();

        try {
            for (String rpcServiceName : registeredService) {
                // 已经发布过的服务直接跳过，实现幂等。
                if (publishedService.contains(rpcServiceName)) {
                    continue;
                }

                serviceRegistry.registerService(
                        rpcServiceName,
                        serverAddress
                );

                // registerService 没有抛异常，才算发布成功。
                publishedService.add(rpcServiceName);

                /*
                 * 立即记录地址。
                 * 后续发布或回滚失败时，仍然知道应该从哪个地址清理。
                 */
                publishedAddress = serverAddress;

                publishedInThisCall.add(rpcServiceName);
            }
        } catch (RuntimeException publishFailure) {
            rollbackPublishedServices(
                    serverAddress,
                    publishedInThisCall,
                    publishFailure
            );

            // 回滚结束后继续抛出最初的发布异常。
            throw publishFailure;
        }
    }

    /**
     * 批量发布中途失败时，撤销本轮已经成功发布的服务。
     */
    private void rollbackPublishedServices(
            InetSocketAddress address,
            List<String> rpcServiceNames,
            RuntimeException publishFailure
    ) {
        for (String rpcServiceName : rpcServiceNames) {
            try {
                serviceRegistry.unregisterService(
                        rpcServiceName,
                        address
                );

                // 注册中心删除成功后，再更新本地发布状态。
                publishedService.remove(rpcServiceName);
            } catch (RuntimeException rollbackFailure) {
                /*
                 * 保留最初的发布异常，把回滚异常挂在它下面。
                 * 调试时可以同时看到“为什么发布失败”和“为什么回滚失败”。
                 */
                publishFailure.addSuppressed(rollbackFailure);
            }
        }

        // 所有已发布服务都成功回滚后，不再记录发布地址。
        if (publishedService.isEmpty()) {
            publishedAddress = null;
        }
    }

    /**
     * 服务端关闭监听端口后，注销这个地址下的全部服务。
     */
    @Override
    public synchronized void unpublishAllServices(
            InetSocketAddress serverAddress
    ) {
        requireAddress(serverAddress);

        // 没有注册中心或没有发布过服务时，重复注销也能安全返回。
        if (serviceRegistry == null || publishedService.isEmpty()) {
            return;
        }

        ensureSameAddress(serverAddress);
        RuntimeException firstFailure = null;

        /*
         * 使用副本遍历，因为循环中会删除 publishedService 的元素。
         */
        for (String rpcServiceName
                : new ArrayList<>(publishedService)) {
            try {
                serviceRegistry.unregisterService(
                        rpcServiceName,
                        serverAddress
                );

                // 只有远程注销成功，才删除本地发布记录。
                publishedService.remove(rpcServiceName);
            } catch (RuntimeException unregisterFailure) {
                /*
                 * 一个服务注销失败时继续注销其他服务。
                 * 最后统一抛出异常，尽量完成更多清理工作。
                 */
                if (firstFailure == null) {
                    firstFailure = unregisterFailure;
                } else {
                    firstFailure.addSuppressed(unregisterFailure);
                }
            }
        }

        if (publishedService.isEmpty()) {
            publishedAddress = null;
        }

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    /**
     * 将一个本地服务发布到注册中心。
     *
     * 该方法由 publishService() 调用，
     * 也可以复用批量发布所需的状态判断。
     */
    private void publishOne(
            String rpcServiceName,
            InetSocketAddress address
    ) {
        // 防止同一个 Provider 把服务发布到两个不同地址。
        ensureSameAddress(address);

        // 已经发布过，直接返回，保证重复调用的幂等性。
        if (publishedService.contains(rpcServiceName)) {
            return;
        }

        // 先真正写入注册中心。
        serviceRegistry.registerService(
                rpcServiceName,
                address
        );

        /*
         * 只有注册中心操作成功后，才能修改本地发布状态。
         * 如果 registerService() 抛出异常，下面两行不会执行。
         */
        publishedService.add(rpcServiceName);
        publishedAddress = address;
    }

    /**
     * 检查当前 Provider 是否已经发布到了另一个地址。
     */
    private void ensureSameAddress(InetSocketAddress address) {
        if (publishedAddress == null) {
            return;
        }

        boolean sameHost = publishedAddress.getHostString()
                .equals(address.getHostString());
        boolean samePort = publishedAddress.getPort()
                == address.getPort();

        if (!sameHost || !samePort) {
            throw new IllegalStateException(
                    "Services are already published at "
                            + publishedAddress
            );
        }
    }

    /** 服务地址是批量发布和注销的必要参数。 */
    private static void requireAddress(
            InetSocketAddress address
    ) {
        if (address == null) {
            throw new IllegalArgumentException(
                    "serverAddress must not be null"
            );
        }
    }
}
