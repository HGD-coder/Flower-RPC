package com.github.hgdcoder.registry;

import com.github.hgdcoder.remoting.dto.RpcRequest;

import java.net.InetSocketAddress;

public interface ServiceDiscovery {
    /**
     * V5 开始，服务发现需要拿到完整 RpcRequest。
     *
     * 原因：
     * 负载均衡可能会用 requestId、interfaceName、methodName 等信息做选择。
     */
    InetSocketAddress lookupService(RpcRequest rpcRequest);
}
