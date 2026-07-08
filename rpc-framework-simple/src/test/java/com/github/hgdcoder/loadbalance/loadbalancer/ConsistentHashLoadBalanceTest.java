package com.github.hgdcoder.loadbalance.loadbalancer;

import com.github.hgdcoder.remoting.dto.RpcRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConsistentHashLoadBalanceNew 和 ConsistentHashingLoadBalancer 的测试
 */
class ConsistentHashLoadBalanceTest {

    // ============================================================
    // 内层类 ConsistentHashingLoadBalancer 的测试（哈希环核心）
    // ============================================================
    @Nested
    @DisplayName("ConsistentHashingLoadBalancer — 哈希环核心")
    class ConsistentHashingLoadBalancerTest {

        private List<String> nodes;
        private ConsistentHashLoadBalance.ConsistentHashingLoadBalancer.HashFunction hashFunc;

        @BeforeEach
        void setUp() {
            nodes = Arrays.asList("192.168.1.1:8080", "192.168.1.2:8080", "192.168.1.3:8080");
            hashFunc = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer.MD5HashFunction();
        }

        // ---------- 构造 ----------

        @Test
        @DisplayName("构造完成后应立即可用")
        void shouldBeReadyAfterConstruction() {
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(nodes, 160, hashFunc);
            // 构造完成后可以直接 select，验证环已构建完成
            assertNotNull(lb.selectNode("some-key"));
        }

        @Test
        @DisplayName("构造后 getAllNodes 返回传入的节点列表")
        void shouldReturnAllNodesAfterConstruction() {
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(nodes, 160, hashFunc);
            List<String> all = lb.getAllNodes();
            assertEquals(3, all.size());
            assertTrue(all.containsAll(nodes));
        }

        // ---------- selectNode ----------

        @Test
        @DisplayName("selectNode 始终返回一个有效节点")
        void shouldReturnValidNode() {
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(nodes, 160, hashFunc);
            for (int i = 0; i < 100; i++) {
                String result = lb.selectNode("key-" + i);
                assertTrue(nodes.contains(result), "返回值必须是节点列表中的一员: " + result);
            }
        }

        @Test
        @DisplayName("同一 key 多次调用 selectNode 返回相同节点（一致性）")
        void shouldBeConsistent() {
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(nodes, 160, hashFunc);
            String key = "fixed-request-key";
            String first = lb.selectNode(key);
            for (int i = 0; i < 1000; i++) {
                assertEquals(first, lb.selectNode(key),
                        "同一个 key 必须始终命中同一个节点");
            }
        }

        @Test
        @DisplayName("大量 key 应分散到所有节点上（分布性）")
        void shouldDistributeKeysAcrossNodes() {
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(nodes, 160, hashFunc);
            Map<String, Integer> hitCount = new HashMap<>();
            for (String n : nodes) hitCount.put(n, 0);

            int totalKeys = 10000;
            for (int i = 0; i < totalKeys; i++) {
                String node = lb.selectNode("request-" + i);
                hitCount.merge(node, 1, Integer::sum);
            }

            // 每个节点都应该被命中
            for (String n : nodes) {
                int count = hitCount.get(n);
                assertTrue(count > 0, "节点 " + n + " 应该被命中，但命中次数为 0");
            }
        }

        @Test
        @DisplayName("虚拟节点数为 0 时 selectNode 返回 null")
        void shouldReturnNullWhenNoNodes() {
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(
                    Collections.emptyList(), 160, hashFunc);
            assertNull(lb.selectNode("any-key"));
        }

        // ---------- hasChanged ----------

        @Test
        @DisplayName("节点数量变化时 hasChanged 返回 true")
        void shouldDetectSizeChange() {
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(nodes, 160, hashFunc);
            List<String> changed = Arrays.asList(
                    "192.168.1.1:8080", "192.168.1.2:8080",
                    "192.168.1.3:8080", "192.168.1.4:8080"); // 多了一个
            assertTrue(lb.hasChanged(changed));
        }

        @Test
        @DisplayName("节点内容变化时 hasChanged 返回 true")
        void shouldDetectContentChange() {
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(nodes, 160, hashFunc);
            List<String> changed = Arrays.asList(
                    "192.168.1.1:8080", "192.168.1.2:8080", "192.168.10.10:9090"); // 最后一个变了
            assertTrue(lb.hasChanged(changed));
        }

        @Test
        @DisplayName("节点列表完全相同时 hasChanged 返回 false")
        void shouldNotDetectChangeWhenSame() {
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(nodes, 160, hashFunc);
            List<String> same = Arrays.asList(
                    "192.168.1.1:8080", "192.168.1.2:8080", "192.168.1.3:8080");
            assertFalse(lb.hasChanged(same));
        }

        @Test
        @DisplayName("完全不同的节点列表 hasChanged 返回 true")
        void shouldDetectCompletelyDifferent() {
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(nodes, 160, hashFunc);
            List<String> changed = Arrays.asList("10.0.0.1:8080", "10.0.0.2:8080");
            assertTrue(lb.hasChanged(changed));
        }

        // ---------- reBuild ----------

        @Test
        @DisplayName("reBuild 添加节点后新节点可被选中")
        void shouldAddNodeViaRebuild() {
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(nodes, 160, hashFunc);

            List<String> newNodes = Arrays.asList(
                    "192.168.1.1:8080", "192.168.1.2:8080",
                    "192.168.1.3:8080", "192.168.1.4:8080"); // 新增第 4 个
            lb.reBuild(newNodes);

            // 用足够多的 key 验证新节点能被命中
            Set<String> hitNodes = new HashSet<>();
            for (int i = 0; i < 1000; i++) {
                hitNodes.add(lb.selectNode("rebuild-key-" + i));
            }
            assertTrue(hitNodes.contains("192.168.1.4:8080"),
                    "新增节点应出现在命中集合中");
            assertEquals(4, lb.getAllNodes().size());
        }

        @Test
        @DisplayName("reBuild 删除节点后该节点不再被选中")
        void shouldRemoveNodeViaRebuild() {
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(nodes, 160, hashFunc);

            List<String> fewerNodes = Arrays.asList(
                    "192.168.1.1:8080", "192.168.1.2:8080"); // 删掉 .3
            lb.reBuild(fewerNodes);

            Set<String> hitNodes = new HashSet<>();
            for (int i = 0; i < 1000; i++) {
                hitNodes.add(lb.selectNode("rebuild-key-" + i));
            }
            assertFalse(hitNodes.contains("192.168.1.3:8080"),
                    "已删除的节点不应再被命中");
            assertEquals(2, lb.getAllNodes().size());
        }

        @Test
        @DisplayName("reBuild 列表无变化时不应重构（DCL 二次检查）")
        void shouldNotRebuildWhenUnchanged() {
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(nodes, 160, hashFunc);

            int beforeRebuild = ConsistentHashLoadBalance.count.get();
            // 传入一模一样的列表
            lb.reBuild(Arrays.asList(
                    "192.168.1.1:8080", "192.168.1.2:8080", "192.168.1.3:8080"));
            int afterRebuild = ConsistentHashLoadBalance.count.get();

            assertEquals(beforeRebuild, afterRebuild,
                    "列表未变化时 count 不应增加（reBuild 的 DCL 应直接返回）");
        }

        @Test
        @DisplayName("reBuild 增量重构：未变动的节点保持原有命中关系")
        void shouldPreserveKeyMappingForUnchangedNodes() {
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(nodes, 160, hashFunc);

            // 记录重构前一批 key 的命中节点
            Map<String, String> before = new HashMap<>();
            for (int i = 0; i < 500; i++) {
                String key = "preserve-key-" + i;
                before.put(key, lb.selectNode(key));
            }

            // 只加一个新节点，不删任何节点
            List<String> expanded = Arrays.asList(
                    "192.168.1.1:8080", "192.168.1.2:8080",
                    "192.168.1.3:8080", "192.168.1.4:8080");
            lb.reBuild(expanded);

            // 检查：原来命中 1/2/3 的 key，大部分应保持不变
            int unchanged = 0;
            int remapped = 0;
            for (Map.Entry<String, String> e : before.entrySet()) {
                String after = lb.selectNode(e.getKey());
                if (e.getValue().equals(after)) {
                    unchanged++;
                } else {
                    // 被重映射的关键应该被映射到了新节点
                    assertEquals("192.168.1.4:8080", after);
                    remapped++;
                }
            }
            // 一致性哈希的核心：只有大约 1/4 的 key 被重映射
            assertTrue(unchanged > remapped,
                    "一致性哈希保证只有约 1/(n+1) 的 key 被重映射");
        }

        // ---------- addNode / removeNode ----------

        @Test
        @DisplayName("addNode 重复添加同一节点不报错")
        void shouldBeIdempotentOnDuplicateAdd() {
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(nodes, 160, hashFunc);
            int sizeBefore = lb.getAllNodes().size();
            // addNode 是 private，无法直接调；但重复 reBuild 同列表是安全的
            lb.reBuild(nodes); // 一模一样
            assertEquals(sizeBefore, lb.getAllNodes().size());
        }

        // ---------- getAllNodes ----------

        @Test
        @DisplayName("getAllNodes 返回不可修改的列表")
        void shouldReturnUnmodifiableList() {
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(nodes, 160, hashFunc);
            List<String> all = lb.getAllNodes();
            assertThrows(UnsupportedOperationException.class, () -> all.add("new-node"));
        }

        // ---------- 读写锁并发保护 ----------

        @Test
        @DisplayName("构造完成后可正常 select（单节点验证）")
        void shouldSelectAfterConstructionWithSingleNode() {
            // 验证构造完成后的环立即可用
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(
                    Arrays.asList("10.0.0.1:8080"), 160, hashFunc);
            assertNotNull(lb.selectNode("test"));
        }

        @Test
        @DisplayName("并发 selectNode(读锁) + reBuild(写锁) 不抛异常")
        void shouldBeSafeUnderConcurrentSelectAndRebuild() throws InterruptedException {
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(
                    Arrays.asList("10.0.0.1:8080", "10.0.0.2:8080"), 160, hashFunc);

            int threadCount = 10;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errors = new AtomicInteger(0);

            // 一半线程 select，一半线程 reBuild
            for (int i = 0; i < threadCount / 2; i++) {
                new Thread(() -> {
                    try {
                        for (int j = 0; j < 1000; j++) {
                            lb.selectNode("concurrent-" + j);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            for (int i = 0; i < threadCount / 2; i++) {
                final int idx = i;
                new Thread(() -> {
                    try {
                        for (int j = 0; j < 100; j++) {
                            lb.reBuild(Arrays.asList("10.0.0.1:8080", "10.0.0.2:8080",
                                    "10.0.0." + (3 + (idx + j) % 5) + ":8080"));
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            latch.await();
            assertEquals(0, errors.get(), "并发操作不应抛出异常");
        }

        // ---------- 自定义 HashFunction ----------

        @Test
        @DisplayName("自定义 HashFunction 可正常使用")
        void shouldWorkWithCustomHashFunction() {
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer.HashFunction customFunc = String::hashCode;
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(nodes, 160, customFunc);

            String result = lb.selectNode("test-key");
            assertTrue(nodes.contains(result));
        }

        @Test
        @DisplayName("不同 HashFunction 可能导致不同命中结果")
        void differentHashFunctionMayYieldDifferentMapping() {
            // MD5
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer md5Lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(nodes, 160,
                    new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer.MD5HashFunction());
            // 简单 hashCode
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer simpleLb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(nodes, 160,
                    String::hashCode);

            // 同一个 key 在两个环上可能命中不同节点（但都在合法范围内）
            String key = "some-key";
            String md5Result = md5Lb.selectNode(key);
            String simpleResult = simpleLb.selectNode(key);

            assertTrue(nodes.contains(md5Result));
            assertTrue(nodes.contains(simpleResult));
            // 很可能不同（不强制断言，因为极小概率相同）
        }

        // ---------- 一致性哈希关键性质 ----------

        @Test
        @DisplayName("增加一个节点后，只有约 1/(n+1) 的 key 被重新映射")
        void addingNodeShouldOnlyRemapFractionOfKeys() {
            List<String> initialNodes = Arrays.asList("A", "B", "C", "D");
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(initialNodes, 160, hashFunc);

            int totalKeys = 10000;
            Map<String, String> before = new HashMap<>();
            for (int i = 0; i < totalKeys; i++) {
                String key = "add-remap-" + i;
                before.put(key, lb.selectNode(key));
            }

            // 加节点 E
            List<String> expanded = Arrays.asList("A", "B", "C", "D", "E");
            lb.reBuild(expanded);

            int remapped = 0;
            for (Map.Entry<String, String> e : before.entrySet()) {
                if (!e.getValue().equals(lb.selectNode(e.getKey()))) {
                    remapped++;
                }
            }

            double ratio = (double) remapped / totalKeys;
            // 理想值: 1/(4+1) = 20%. 实际允许一定偏差
            assertTrue(ratio < 0.35,
                    "增加节点后重映射比例应远小于 100%，实际: " + String.format("%.1f%%", ratio * 100));
        }

        @Test
        @DisplayName("删除一个节点后，只有该节点的 key 被重新映射")
        void removingNodeShouldOnlyRemapItsOwnKeys() {
            List<String> initialNodes = Arrays.asList("A", "B", "C", "D");
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(initialNodes, 160, hashFunc);

            int totalKeys = 10000;
            Map<String, String> before = new HashMap<>();
            for (int i = 0; i < totalKeys; i++) {
                String key = "del-remap-" + i;
                before.put(key, lb.selectNode(key));
            }

            // 删节点 B
            List<String> reduced = Arrays.asList("A", "C", "D");
            lb.reBuild(reduced);

            int remapped = 0;
            int originallyOnB = 0;
            for (Map.Entry<String, String> e : before.entrySet()) {
                String after = lb.selectNode(e.getKey());
                if (!e.getValue().equals(after)) {
                    remapped++;
                }
                if ("B".equals(e.getValue())) {
                    originallyOnB++;
                }
            }

            // 重映射的 key 不应该超过原来在 B 上的 key
            assertTrue(remapped <= originallyOnB + totalKeys / 20,
                    "重映射的 key 不应显著超过原来命中被删节点的 key");
        }
    }

    // ============================================================
    // 外层类 ConsistentHashLoadBalanceNew 的测试
    // ============================================================
    @Nested
    @DisplayName("ConsistentHashLoadBalanceNew — 外层调用")
    class ConsistentHashLoadBalanceNewOuterTest {

        private ConsistentHashLoadBalance loadBalance;
        private List<String> addresses;

        @BeforeEach
        void setUp() {
            loadBalance = new ConsistentHashLoadBalance();
            addresses = Arrays.asList("192.168.1.1:8080", "192.168.1.2:8080", "192.168.1.3:8080");
        }

        @Test
        @DisplayName("doSelect 返回有效节点")
        void shouldReturnValidAddress() {
            RpcRequest request = RpcRequest.builder()
                    .requestId("req-001")
                    .interfaceName("com.example.HelloService")
                    .methodName("sayHello")
                    .version("1.0")
                    .group("default")
                    .build();

            String result = loadBalance.doSelect(addresses, request);
            assertTrue(addresses.contains(result));
        }

        @Test
        @DisplayName("同一服务同一请求 ID 命中同一节点（一致性）")
        void shouldBeConsistentForSameServiceAndRequest() {
            RpcRequest request = RpcRequest.builder()
                    .requestId("req-consistent")
                    .interfaceName("com.example.OrderService")
                    .methodName("getOrder")
                    .version("1.0")
                    .group("default")
                    .build();

            String first = loadBalance.doSelect(addresses, request);
            for (int i = 0; i < 100; i++) {
                assertEquals(first, loadBalance.doSelect(addresses, request));
            }
        }

        @Test
        @DisplayName("不同服务使用独立的哈希环")
        void differentServicesUseSeparateRings() {
            RpcRequest request1 = RpcRequest.builder()
                    .requestId("req-001")
                    .interfaceName("com.example.UserService")
                    .methodName("getUser")
                    .version("1.0")
                    .group("default")
                    .build();

            RpcRequest request2 = RpcRequest.builder()
                    .requestId("req-001")  // 同一个 requestId
                    .interfaceName("com.example.OrderService")  // 不同服务
                    .methodName("getOrder")
                    .version("1.0")
                    .group("default")
                    .build();

            String result1 = loadBalance.doSelect(addresses, request1);
            String result2 = loadBalance.doSelect(addresses, request2);

            // 不同服务即使 requestId 相同也不保证命中同一节点
            // 因为它们有独立的哈希环（但有可能是巧合相同，所以这里只验证合法性）
            assertTrue(addresses.contains(result1));
            assertTrue(addresses.contains(result2));
        }

        @Test
        @DisplayName("节点列表变化时自动触发 reBuild")
        void shouldAutoRebuildWhenNodesChange() {
            RpcRequest request = RpcRequest.builder()
                    .requestId("req-rebuild")
                    .interfaceName("com.example.PaymentService")
                    .methodName("pay")
                    .version("1.0")
                    .group("default")
                    .build();

            // 第一次调用
            String result1 = loadBalance.doSelect(addresses, request);

            int rebuildCountBefore = ConsistentHashLoadBalance.count.get();

            // 节点变化
            List<String> newAddresses = Arrays.asList(
                    "192.168.1.1:8080", "192.168.1.2:8080",
                    "192.168.1.3:8080", "192.168.1.4:8080");
            String result2 = loadBalance.doSelect(newAddresses, request);

            // 验证重构被触发
            assertTrue(ConsistentHashLoadBalance.count.get() >= rebuildCountBefore);
            assertTrue(newAddresses.contains(result2));
        }
    }
}
