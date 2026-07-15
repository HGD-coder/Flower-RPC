package com.github.hgdcoder.loadbalance.loadbalancer;

import com.github.hgdcoder.loadbalance.AbstractLoadBalance;
import com.github.hgdcoder.remoting.dto.RpcRequest;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class RandomLoadBalance extends AbstractLoadBalance {
    @Override
    protected String doSelect(List<String> serviceAddress, RpcRequest rpcRequest){
        // 随机选择一个服务地址。
        // V5 先用最容易理解的随机负载均衡，后面再接一致性哈希。
        int index = ThreadLocalRandom.current().nextInt(serviceAddress.size());
        return serviceAddress.get(index);
    }
}
