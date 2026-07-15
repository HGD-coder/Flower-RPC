package com.github.hgdcoder.loadbalance.loadbalancer;

import com.github.hgdcoder.loadbalance.AbstractLoadBalance;
import com.github.hgdcoder.remoting.dto.RpcRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ConsistentHashLoadBalance
 * │
 * └── selectors: ConcurrentHashMap
 *     │
 *     ├── "HelloServicetest1.0"
 *     │   └── ConsistentHashSelector
 *     │       ├── addressSignature
 *     │       │   └── "127.0.0.1:9998,127.0.0.1:9999,127.0.0.1:10000"
 *     │       │
 *     │       └── virtualNodes: TreeMap
 *     │           ├── 1001 -> "127.0.0.1:9998"
 *     │           ├── 1530 -> "127.0.0.1:10000"
 *     │           ├── 2205 -> "127.0.0.1:9999"
 *     │           ├── 3199 -> "127.0.0.1:9998"
 *     │           └── ...
 *     │
 *     └── "OrderServicetest1.0"
 *         └── ConsistentHashSelector
 *             ├── addressSignature
 *             └── virtualNodes: TreeMap
 */


public class ConsistentHashLoadBalance extends AbstractLoadBalance {
    /**
     * 每个服务缓存一个一致性哈希选择器。
     *
     * key: rpcServiceName，例如 com.github.hgdcoder.HelloServicetest1.0
     * value: 这个服务对应的哈希环
     */
    private final ConcurrentHashMap<String, ConsistentHashSelector> selectors = new ConcurrentHashMap<>();

    /**
     * 每个真实服务地址对应多少个虚拟节点。
     *
     * 虚拟节点越多，分布越均匀；
     * 但构建哈希环的成本也越高。
     */
    private static final int VIRTUAL_NODE_COUNT = 160;

    @Override
    protected String doSelect(List<String> serviceAddresses, RpcRequest rpcRequest) {
        String rpcServiceName = rpcRequest.getRpcServiceName();

        // 计算当前服务地址列表的签名。
        // 作用：判断服务地址有没有变化。
        String addressSignature = buildAddressSignature(serviceAddresses);

        ConsistentHashSelector selector = selectors.get(rpcServiceName);

        // 第一次访问，或者服务地址列表变化了，就重建哈希环。
        if (selector == null || !selector.matches(addressSignature)) {
            selector = new ConsistentHashSelector(
                    serviceAddresses,
                    VIRTUAL_NODE_COUNT,
                    addressSignature
            );
            selectors.put(rpcServiceName, selector);
        }

        // 当前阶段用 requestId 做负载均衡 key。
        // 这样不同请求会比较均匀地落到不同服务节点。
        String requestKey = rpcServiceName + "#" + rpcRequest.getRequestId();

        return selector.select(requestKey);
    }

    /**
     * 为服务地址列表生成一个稳定签名。
     *
     * 为什么要排序？
     *
     * 127.0.0.1:9998,127.0.0.1:9999
     * 和
     * 127.0.0.1:9999,127.0.0.1:9998
     *
     * 本质上是同一批服务地址，不应该因为顺序不同就重建哈希环。
     * buildAddressSignature 是哈希环缓存的“版本号”。地址集合没变就复用，地址集合变了才重建
     */
    private String buildAddressSignature(List<String> serviceAddresses) {
        List<String> sortedAddresses = new ArrayList<>(serviceAddresses);
        Collections.sort(sortedAddresses);
        return String.join(",", sortedAddresses);
    }

    /**
     * 某一个服务对应的一致性哈希选择器。
     *
     * 一个 selector 里保存一张哈希环。
     */
    private static class ConsistentHashSelector {
        /**
         * 哈希环。
         *
         * key: 虚拟节点 hash 值
         * value: 真实服务地址，例如 127.0.0.1:9998
         */
        private final TreeMap<Long, String> virtualNodes = new TreeMap<>();

        /**
         * 当前服务地址列表的签名。
         *
         * 后续如果签名变了，说明服务节点列表变了，
         * 需要重新构建 selector。
         */
        private final String addressSignature;

        ConsistentHashSelector(List<String> serviceAddresses,
                               int virtualNodeCount,
                               String addressSignature) {
            this.addressSignature = addressSignature;

            for (String serviceAddress : serviceAddresses) {
                for (int i = 0; i < virtualNodeCount; i++) {
                    String virtualNodeName = serviceAddress + "#" + i;
                    long hash = hash(virtualNodeName);
                    virtualNodes.put(hash, serviceAddress);
                }
            }
        }

        boolean matches(String newAddressSignature) {
            return addressSignature.equals(newAddressSignature);
        }

        String select(String requestKey) {
            long requestHash = hash(requestKey);

            // 找到哈希环上第一个 >= requestHash 的虚拟节点。
            SortedMap<Long, String> tailMap = virtualNodes.tailMap(requestHash);

            Long selectedHash;

            if (tailMap.isEmpty()) {
                // 如果 requestHash 比所有虚拟节点都大，
                // 就回到哈希环的第一个节点。
                selectedHash = virtualNodes.firstKey();
            } else {
                selectedHash = tailMap.firstKey();
            }

            return virtualNodes.get(selectedHash);
        }

        private static long hash(String key) {
            try {
                MessageDigest md5 = MessageDigest.getInstance("MD5");
                byte[] digest = md5.digest(key.getBytes(StandardCharsets.UTF_8));

                // 取 MD5 前 8 个字节，拼成一个 long。
                // & 0xFF 是为了把 Java 的有符号 byte 转成 0~255 的无符号值。
                return ((long) (digest[0] & 0xFF) << 56)
                        | ((long) (digest[1] & 0xFF) << 48)
                        | ((long) (digest[2] & 0xFF) << 40)
                        | ((long) (digest[3] & 0xFF) << 32)
                        | ((long) (digest[4] & 0xFF) << 24)
                        | ((long) (digest[5] & 0xFF) << 16)
                        | ((long) (digest[6] & 0xFF) << 8)
                        | (digest[7] & 0xFF);
            } catch (Exception e) {
                throw new RuntimeException("Calculate hash failed", e);
            }
        }
    }
}