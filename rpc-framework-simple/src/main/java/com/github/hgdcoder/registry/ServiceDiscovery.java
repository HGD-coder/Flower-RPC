package com.github.hgdcoder.registry;

import com.github.hgdcoder.extension.SPI;
import com.github.hgdcoder.remoting.dto.RpcRequest;

import java.net.InetSocketAddress;

/**
 * 服务发现扩展点。
 *
 * <p>客户端通过它获得某个 RPC 服务的提供者地址。</p>
 */
@SPI
public interface ServiceDiscovery {
    /**
     * 根据 RPC 请求查找一个可用的服务地址。
     *
     * 原因：
     * 负载均衡可能会用 requestId、interfaceName、methodName 等信息做选择。
     * @param rpcRequest 本次 RPC 请求
     * @return 负载均衡最终选择的服务端地址
     */
    InetSocketAddress lookupService(RpcRequest rpcRequest);
}
