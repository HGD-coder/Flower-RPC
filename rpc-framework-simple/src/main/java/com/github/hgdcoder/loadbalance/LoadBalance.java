package com.github.hgdcoder.loadbalance;

import com.github.hgdcoder.extension.SPI;
import com.github.hgdcoder.remoting.dto.RpcRequest;

import java.util.List;

@SPI
public interface LoadBalance {
    /**
     * 从现有的服务地址列表中选择一个
     * @param serviceUrlList 服务地址列表
     * @param rpcRequest
     * @return target service address 目标服务地址
     */
    String selectServiceAddress(List<String> serviceUrlList, RpcRequest rpcRequest);
}
