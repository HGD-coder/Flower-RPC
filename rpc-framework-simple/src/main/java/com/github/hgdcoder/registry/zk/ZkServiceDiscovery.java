package com.github.hgdcoder.registry.zk;

import com.github.hgdcoder.config.RpcFrameworkConfig;
import com.github.hgdcoder.enums.RpcErrorMessageEnum;
import com.github.hgdcoder.exception.RpcException;
import com.github.hgdcoder.loadbalance.LoadBalance;
import com.github.hgdcoder.loadbalance.LoadBalanceFactory;
import com.github.hgdcoder.loadbalance.loadbalancer.RandomLoadBalance;
import com.github.hgdcoder.registry.ServiceDiscovery;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import org.apache.curator.framework.CuratorFramework;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * 基于 ZooKeeper 的服务发现实现。
 *
 * 先获取某个服务的全部提供者地址，再交给配置的负载均衡策略选择一个地址。
 */
public class ZkServiceDiscovery implements ServiceDiscovery {
    private final String zkAddress;
    private final LoadBalance loadBalance;

    public ZkServiceDiscovery() {
        // 未指定策略时默认使用随机负载均衡。
        this(RpcFrameworkConfig.load());
    }

    /** 地址和负载均衡策略均来自同一份启动配置快照。 */
    public ZkServiceDiscovery(RpcFrameworkConfig config ) {
        this(
                requireConfig(config).getZkAddress(),
                LoadBalanceFactory.create(config)
        );
    }

    public ZkServiceDiscovery(LoadBalance loadBalance) {
        this(RpcFrameworkConfig.load().getZkAddress(), loadBalance);
    }

    /** 显式地址入口便于非 Spring 场景复用已经创建的配置值。 */
    public ZkServiceDiscovery(String zkAddress, LoadBalance loadBalance) {
        if (zkAddress == null || zkAddress.trim().isEmpty()) {
            throw new IllegalArgumentException("zkAddress must not be empty");
        }
        if (loadBalance == null) {
            throw new IllegalArgumentException("loadBalance must not be null");
        }
        this.zkAddress = zkAddress.trim();
        this.loadBalance = loadBalance;
    }


    @Override
    public InetSocketAddress lookupService(RpcRequest rpcRequest) {
        // 完整服务名由 interfaceName、group 和 version 组成。
        String rpcServiceName = rpcRequest.getRpcServiceName();

        /*
         * 使用创建当前服务发现对象时保存的 ZooKeeper 地址，
         * 不在每次查询服务时重新读取配置文件。
         */
        CuratorFramework zkClient = CuratorUtils.getZkClient(zkAddress);

        // CuratorUtils 第一次从 ZooKeeper 获取地址，后续优先使用监听器维护的本地缓存。
        List<String> serviceAddresses = CuratorUtils.getChildrenNodes(
                zkClient,
                rpcServiceName
        );

        if (serviceAddresses.isEmpty()) {
            throw new RpcException(
                    RpcErrorMessageEnum.SERVICE_CAN_NOT_BE_FOUND,
                    rpcServiceName
            );
        }

        // 服务发现负责提供候选列表，最终选择规则由负载均衡模块负责。
        String selectedAddress = loadBalance.selectServiceAddress(
                serviceAddresses,
                rpcRequest
        );
        if (selectedAddress == null || selectedAddress.trim().isEmpty()) {
            throw new RpcException(
                    RpcErrorMessageEnum.SERVICE_CAN_NOT_BE_FOUND,
                    "负载均衡器没有为 "
                            + rpcServiceName
                            + " 选择节点"
            );
        }

        return parseAddress(selectedAddress);
    }

    private InetSocketAddress parseAddress(String address) {
        // 使用最后一个冒号切分，可以兼容主机部分本身包含冒号的情况。
        int splitIndex = address.lastIndexOf(':');
        if (splitIndex <= 0 || splitIndex == address.length() - 1) {
            throw new IllegalArgumentException("Invalid service address: " + address);
        }

        String host = address.substring(0, splitIndex);
        int port = Integer.parseInt(address.substring(splitIndex + 1));
        return new InetSocketAddress(host, port);
    }

    private static RpcFrameworkConfig requireConfig(RpcFrameworkConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        return config;
    }
}
