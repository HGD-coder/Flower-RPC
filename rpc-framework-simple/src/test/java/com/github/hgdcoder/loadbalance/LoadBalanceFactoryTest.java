package com.github.hgdcoder.loadbalance;

import com.github.hgdcoder.loadbalance.loadbalancer.ConsistentHashLoadBalance;
import com.github.hgdcoder.loadbalance.loadbalancer.RandomLoadBalance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证配置名称通过 Flower-RPC SPI 解析，而不是由装配层直接 new 实现。 */
class LoadBalanceFactoryTest {
    @Test
    void shouldLoadBothConfiguredStrategiesFromSpi() {
        assertTrue(LoadBalanceFactory.create("random") instanceof RandomLoadBalance);
        assertTrue(LoadBalanceFactory.create("consistent-hash")
                instanceof ConsistentHashLoadBalance);
    }
}
