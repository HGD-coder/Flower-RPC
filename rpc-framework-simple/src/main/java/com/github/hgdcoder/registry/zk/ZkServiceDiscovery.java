package com.github.hgdcoder.registry.zk;

import com.github.hgdcoder.loadbalance.LoadBalance;
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
    private final LoadBalance loadBalance;

    public ZkServiceDiscovery() {
        // 未指定策略时默认使用随机负载均衡。
        this(new RandomLoadBalance());
    }

    public ZkServiceDiscovery(LoadBalance loadBalance) {
        if (loadBalance == null) {
            throw new IllegalArgumentException("loadBalance must not be null");
        }
        this.loadBalance = loadBalance;
    }

    @Override
    public InetSocketAddress lookupService(RpcRequest rpcRequest) {
        // 完整服务名由 interfaceName、group 和 version 组成。
        String rpcServiceName = rpcRequest.getRpcServiceName();
        CuratorFramework zkClient = CuratorUtils.getZkClient();

        // CuratorUtils 第一次从 ZooKeeper 获取地址，后续优先使用监听器维护的本地缓存。
        List<String> serviceAddresses = CuratorUtils.getChildrenNodes(
                zkClient,
                rpcServiceName
        );

        if (serviceAddresses.isEmpty()) {
            throw new RuntimeException("No service address found for: " + rpcServiceName);
        }

        // 服务发现负责提供候选列表，最终选择规则由负载均衡模块负责。
        String selectedAddress = loadBalance.selectServiceAddress(
                serviceAddresses,
                rpcRequest
        );
        if (selectedAddress == null || selectedAddress.trim().isEmpty()) {
            throw new RuntimeException("Load balancer returned no address for: " + rpcServiceName);
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
}
