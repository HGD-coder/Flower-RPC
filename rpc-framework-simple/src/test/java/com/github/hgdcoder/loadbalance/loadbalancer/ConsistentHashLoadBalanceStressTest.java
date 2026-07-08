package com.github.hgdcoder.loadbalance.loadbalancer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>ConsistentHashLoadBalance 压力测试</h1>
 *
 * <p>模拟 RPC 框架在生产环境下的六个典型场景：</p>
 * <ol>
 *   <li>边选节点边重构 — 节点频繁上下线 + 持续高并发请求（最重要）</li>
 *   <li>全量重建 — 节点列表完全改变，触发大规模增删</li>
 *   <li>冷启动并发创建 — 多线程同时第一次访问同一个服务</li>
 *   <li>热 Key 倾斜 — 相似前缀的 Key 是否全部集中到单个节点</li>
 *   <li>大集群稳态吞吐 — 500 节点下的 selectNode 延迟</li>
 *   <li>内存占用 — 大量服务 × 大量节点的内存开销（TODO）</li>
 * </ol>
 *
 * <p>运行方式：</p>
 * <pre>mvn test -Dtest=ConsistentHashLoadBalanceStressTest</pre>
 *
 * @author hgdcoder
 */
@DisplayName("ConsistentHashLoadBalance 压力测试")
class ConsistentHashLoadBalanceStressTest {

    // ================================================================
    // 共用工具方法
    // ================================================================

    /**
     * 生成指定数量的模拟服务地址
     *
     * @param count  节点数量
     * @param prefix IP 前缀，如 "192.168.1"
     * @param port   端口号
     * @return 地址列表，如 ["192.168.1.1:8080", "192.168.1.2:8080", ...]
     */
    private static List<String> generateAddresses(int count, String prefix, int port) {
        List<String> result = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            result.add(prefix + "." + i + ":" + port);
        }
        return result;
    }

    /**
     * 打印压测结果的辅助方法。
     * <p>用表格形式输出关键指标，方便一眼看出性能数据。</p>
     *
     * @param scenario       场景名称
     * @param totalRequests  总请求数
     * @param totalTimeNs    总耗时（纳秒）
     * @param errorCount     错误数
     * @param extraInfo      额外信息（可变参数，key1, value1, key2, value2, ...）
     */
    private static void printResult(String scenario, long totalRequests, long totalTimeNs,
                                     long errorCount, String... extraInfo) {
        double totalMs = totalTimeNs / 1_000_000.0;
        // 避免除以 0 显示 Infinity
        String avgStr = totalRequests > 0
                ? String.format("%.3f μs (%.1f ns)", totalTimeNs / 1_000.0 / totalRequests, totalTimeNs / (double) totalRequests)
                : "N/A (无请求)";
        String qpsStr = totalTimeNs > 0
                ? String.format("%,.0f 次/秒", totalRequests * 1_000_000_000.0 / totalTimeNs)
                : "N/A (耗时为0)";

        System.out.println();
        System.out.println("┌──────────────────────────────────────────────────");
        System.out.println("│ " + scenario);
        System.out.println("├──────────────────────────────────────────────────");
        System.out.printf("│ 总请求数:     %d%n", totalRequests);
        System.out.printf("│ 总耗时:       %.2f ms%n", totalMs);
        System.out.printf("│ 平均延迟:     %s%n", avgStr);
        System.out.printf("│ QPS:          %s%n", qpsStr);
        System.out.printf("│ 错误数:       %d%n", errorCount);
        for (int i = 0; i < extraInfo.length; i += 2) {
            System.out.printf("│ %s: %s%n", extraInfo[i], extraInfo[i + 1]);
        }
        System.out.println("└──────────────────────────────────────────────────");
    }

    // ================================================================
    // 场景 1：边选节点边重构 — 生产环境最常见的场景
    // ================================================================

    /**
     * <h2>场景 1：节点频繁上下线 + 持续高并发请求</h2>
     *
     * <h3>线上对应场景</h3>
     * <p>Kubernetes 滚动更新：10 个 Provider Pod 逐个重启。
     * 每重启一个 Pod，ZooKeeper 通知 Consumer 节点列表变了，触发 reBuild。
     * 同时 Consumer 还在以每秒几千次的速度调用 selectNode。
     * 整个过程持续 5~10 分钟。</p>
     *
     * <h3>本测试模拟</h3>
     * <ul>
     *   <li>8 个线程持续执行 selectNode（模拟业务请求）</li>
     *   <li>1 个线程每隔一段随机时间修改节点列表触发 reBuild（模拟 ZK 通知）</li>
     *   <li>持续 30 秒</li>
     * </ul>
     *
     * <h3>关键观察点</h3>
     * <ul>
     *   <li>读写锁是否导致 selectNode 延迟显著增加（读锁开销极小但非零）</li>
     *   <li>reBuild 拿写锁期间，selectNode 的读锁会被阻塞（等写锁释放）</li>
     *   <li>有没有请求因为写锁持有时间过长而超时</li>
     *   <li>CPU 使用率是否正常（读写锁用 AQS 阻塞，不烧 CPU）</li>
     * </ul>
     */
    @Test
    @DisplayName("场景1: 边选节点边重构 — 8线程select + 1线程持续reBuild，持续30秒")
    void scenario01_concurrentSelectAndRebuild() throws InterruptedException {
        // ── 准备工作 ──
        List<String> initialNodes = generateAddresses(10, "192.168.1", 8080);
        ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb =
                new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(
                        initialNodes, 160,
                        new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer.MD5HashFunction());

        int selectThreads = 8;                      // 8 个线程专门 select
        int durationSeconds = 30;                   // 跑 30 秒
        AtomicLong totalRequests = new AtomicLong(0);
        AtomicInteger errors = new AtomicInteger(0);
        AtomicLong spinWaitTotalNs = new AtomicLong(0);  // 统计长时间等待耗时（读锁被写锁阻塞）
        AtomicLong spinWaitCount = new AtomicLong(0);     // 统计阻塞次数
        CountDownLatch stopLatch = new CountDownLatch(selectThreads);

        // 用一个 volatile 列表来模拟 ZooKeeper 推送的节点变化，
        // reBuild 线程会定时修改它，select 线程会读到最新版本
        final List<String>[] currentNodes = new List[]{new ArrayList<>(initialNodes)};
        final Object nodesLock = new Object();  // 保护 currentNodes 的读写

        // ── 启动 select 线程 ──
        for (int t = 0; t < selectThreads; t++) {
            final int threadId = t;
            new Thread(() -> {
                long deadline = System.currentTimeMillis() + durationSeconds * 1000L;
                int localCount = 0;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        // 每次 select 前先记录时间，检测自旋耗时
                        long beforeSelect = System.nanoTime();
                        String key = "thread-" + threadId + "-req-" + localCount;
                        String result = lb.selectNode(key);
                        long afterSelect = System.nanoTime();
                        long elapsedUs = (afterSelect - beforeSelect) / 1000; // 微秒

                        // 如果单次 selectNode 超过 1 毫秒，说明可能碰到了自旋等待
                        if (elapsedUs > 1000) { // > 1ms = 1000μs
                            spinWaitTotalNs.addAndGet(afterSelect - beforeSelect);
                            spinWaitCount.incrementAndGet();
                        }

                        // 验证返回的节点是否合法
                        // （合法节点一定在当前或历史的节点列表中）
                        assertNotNull(result, "selectNode 不应返回 null");
                        localCount++;
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        System.err.println("[" + Thread.currentThread().getName() + "] 异常: "
                                + e.getClass().getSimpleName() + " - " + e.getMessage());
                    }
                }
                totalRequests.addAndGet(localCount);
                stopLatch.countDown();
            }, "select-worker-" + t).start();
        }

        // ── 启动 reBuild 线程（模拟 ZK 推送节点变化） ──
        Thread rebuilder = new Thread(() -> {
            Random rand = new Random();
            long deadline = System.currentTimeMillis() + durationSeconds * 1000L;
            int rebuildCount = 0;

            while (System.currentTimeMillis() < deadline) {
                // 随机间隔 500ms ~ 3s 触发一次重建（模拟不规律的 ZK 推送）
                try {
                    Thread.sleep(500 + rand.nextInt(2500));
                } catch (InterruptedException e) {
                    break;
                }

                // 构造一个新的节点列表：在原有基础上随机增删 1~3 个节点
                List<String> modifiedList;
                synchronized (nodesLock) {
                    modifiedList = new ArrayList<>(currentNodes[0]);

                    // 随机删除 1~2 个节点（如果剩余 >= 3 个的话）
                    int removeCount = rand.nextInt(Math.min(3, modifiedList.size() - 2)) + 1;
                    for (int r = 0; r < removeCount && modifiedList.size() > 2; r++) {
                        modifiedList.remove(rand.nextInt(modifiedList.size()));
                    }

                    // 随机新增 1~3 个节点（用随机 IP 模拟新 Pod 上线）
                    int addCount = rand.nextInt(3) + 1;
                    int maxIp = modifiedList.size() + addCount + 10;
                    for (int a = 0; a < addCount; a++) {
                        String newAddr = "192.168." + (rand.nextInt(10) + 1)
                                + "." + (rand.nextInt(254) + 1) + ":8080";
                        if (!modifiedList.contains(newAddr)) {
                            modifiedList.add(newAddr);
                        }
                    }

                    currentNodes[0] = modifiedList;
                }

                // 触发重构
                try {
                    long beforeRebuild = System.nanoTime();
                    lb.reBuild(new ArrayList<>(modifiedList));
                    long rebuildTimeMs = (System.nanoTime() - beforeRebuild) / 1_000_000;
                    rebuildCount++;

                    // 如果单次 reBuild 超过 100ms，输出告警
                    if (rebuildTimeMs > 100) {
                        System.err.println("[警告] reBuild 耗时 " + rebuildTimeMs
                                + "ms，节点数: " + modifiedList.size());
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    System.err.println("[reBuild 异常] " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                }
            }

            int finalCount = rebuildCount;
            System.out.println("[reBuild 线程] 共执行 " + finalCount + " 次重构");
        }, "rebuilder");
        rebuilder.start();

        // ── 等待所有 select 线程跑完 ──
        stopLatch.await();
        rebuilder.join(2000); // 等 reBuild 线程也停下来（最多 2 秒）

        // ── 输出结果 ──
        long totalTimeNs = durationSeconds * 1_000_000_000L;
        printResult("场景1: 边选节点边重构 (30秒)",
                totalRequests.get(), totalTimeNs, errors.get(),
                "select并发线程", String.valueOf(selectThreads),
                "自旋等待次数", String.valueOf(spinWaitCount.get()),
                "自旋总耗时(ms)", String.valueOf(spinWaitTotalNs.get() / 1_000_000));

        // ── 断言 ──
        assertEquals(0, errors.get(), "压测期间不应出现异常");
        assertTrue(totalRequests.get() > 100_000,
                "30 秒内总请求数应 > 10 万，实际: " + totalRequests.get());
    }

    // ================================================================
    // 场景 2：全量重建 — 节点列表完全不同
    // ================================================================

    /**
     * <h2>场景 2：全量重建 — 节点列表完全改变</h2>
     *
     * <h3>线上对应场景</h3>
     * <p>数据中心故障切换：整个集群从机房 A 切到机房 B。
     * 或者 ZooKeeper 会话超时重连后，发现 Provider 列表完全变了。
     * 此时 readyToAdd 和 readyToRemove 包含全部节点，hash 环需要大量操作。</p>
     *
     * <h3>计算成本</h3>
     * <pre>
     *   假设 N 个旧节点 + N 个新节点：
     *   每个 addNode:    N × 160 次 MD5
     *   每个 removeNode: N × 160 次 MD5
     *   总计: 2N × 160 次 MD5 哈希
     *
     *   若 N=50: 50 × 160 × 2 = 16,000 次 MD5
     *   MD5 每次约 1~5μs → 16,000 × 3μs ≈ 48ms
     * </pre>
     *
     * <h3>关键观察点</h3>
     * <ul>
     *   <li>reBuild 拿写锁期间，所有 selectNode 的读锁请求排队等待</li>
     *   <li>全量重建耗时能否接受（不能超过秒级）</li>
     *   <li>重建前后 selectNode 是否都能正常返回</li>
     * </ul>
     */
    @Test
    @DisplayName("场景2: 全量重建 — 50个节点完全替换为另50个，测reBuild耗时")
    void scenario02_fullRebuild() throws InterruptedException {
        // ── 准备：创建初始 50 个节点 ──
        int nodeCount = 50;
        List<String> oldNodes = generateAddresses(nodeCount, "192.168.1", 8080);
        ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb =
                new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(
                        oldNodes, 160,
                        new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer.MD5HashFunction());

        assertEquals(nodeCount, lb.getAllNodes().size(), "初始应有 50 个物理节点");

        // ── 构造一组完全不同的新节点（不同网段） ──
        List<String> newNodes = generateAddresses(nodeCount, "10.0.0", 9090);

        // ── 开启 4 个线程持续 select，模拟 reBuild 期间有请求进来 ──
        AtomicLong selectDuringRebuild = new AtomicLong(0);
        AtomicInteger selectErrors = new AtomicInteger(0);
        CountDownLatch selectStartLatch = new CountDownLatch(1);  // 同时起跑
        CountDownLatch selectDoneLatch = new CountDownLatch(4);

        for (int t = 0; t < 4; t++) {
            final int threadId = t;
            new Thread(() -> {
                selectStartLatch.countDown();
                try {
                    selectStartLatch.await(); // 等所有线程就绪
                } catch (InterruptedException ignored) {}
                // 持续 select 1 秒（覆盖整个 reBuild 过程）
                long deadline = System.currentTimeMillis() + 1000;
                int count = 0;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        String result = lb.selectNode("full-rebuild-" + threadId + "-" + count);
                        // reBuild 期间可能返回旧节点或新节点，但不应返回 null
                        assertNotNull(result, "即使在 reBuild 期间也不应返回 null");
                        count++;
                    } catch (Exception e) {
                        selectErrors.incrementAndGet();
                    }
                }
                selectDuringRebuild.addAndGet(count);
                selectDoneLatch.countDown();
            }, "full-rebuild-selector-" + t).start();
        }

        // 等所有 select 线程就绪
        selectStartLatch.await();

        // ── 执行全量重建 ──
        long beforeRebuild = System.nanoTime();
        lb.reBuild(newNodes);
        long rebuildTimeNs = System.nanoTime() - beforeRebuild;

        selectDoneLatch.await();

        // ── 验证结果 ──
        assertEquals(nodeCount, lb.getAllNodes().size(), "重建后应有 50 个物理节点");
        // 新节点应该能被选中
        Set<String> hitNodes = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            hitNodes.add(lb.selectNode("after-rebuild-" + i));
        }
        // 至少应该有一些新节点被命中
        boolean anyNewNodeHit = false;
        for (String node : hitNodes) {
            if (node.startsWith("10.0.0.")) {
                anyNewNodeHit = true;
                break;
            }
        }
        assertTrue(anyNewNodeHit, "重建后新节点应可被命中");

        // ── 输出结果 ──
        printResult("场景2: 全量重建 (50→50 完全不同)",
                selectDuringRebuild.get(), rebuildTimeNs, selectErrors.get(),
                "旧节点数", String.valueOf(nodeCount),
                "新节点数", String.valueOf(nodeCount),
                "reBuild 耗时(ms)", String.format("%.2f", rebuildTimeNs / 1_000_000.0),
                "reBuild 期间 select 数", String.valueOf(selectDuringRebuild.get()));

        System.out.println("[关键判定] reBuild 是否在可接受时间完成: "
                + (rebuildTimeNs / 1_000_000 < 500 ? "✓ (<500ms)" : "✗ 偏慢"));
    }

    // ================================================================
    // 场景 3：冷启动并发创建
    // ================================================================

    /**
     * <h2>场景 3：冷启动 — 多线程同时首次访问</h2>
     *
     * <h3>线上对应场景</h3>
     * <p>Consumer 刚启动，第一批请求涌进来。
     * 200 个线程同时调 doSelect("OrderService", ...)，
     * 发现 selector == null，全部进入创建流程。
     * SingletonFactory 的 DCL 保证只创建一个实例，
     * 但 200 个线程在等待期间全部在自旋。</p>
     *
     * <h3>关键观察点</h3>
     * <ul>
     *   <li>SingletonFactory 是否真正只创建了一个实例</li>
     *   <li>第一个线程建环时（160×N 次 MD5），其他 199 个线程被阻塞等待</li>
     *   <li>注意：本测试用 CountDownLatch 阻塞而非自旋。若用自旋，200 线程
     *       同时 while(==null){} 会占满 CPU 导致建环线程饿死。</li>
     *   <li>哈希环内部已改用 ReentrantReadWriteLock，selectNode 拿读锁，
     *       reBuild 拿写锁，不会出现自旋烧 CPU 的问题。</li>
     * </ul>
     */
    @Test
    @DisplayName("场景3: 冷启动 — 200线程同时首次访问同一个服务")
    void scenario03_coldStart() throws InterruptedException {
        int threadCount = 200;
        int nodeCount = 20;
        List<String> addresses = generateAddresses(nodeCount, "192.168.1", 8080);

        // ── 为每个线程准备独立的哈希环实例（模拟 SingletonFactory 创建阶段） ──
        // 注意：这里测试的是哈希环构造 + 并发 select 的配合，SingletonFactory 的
        // DCL 正确性在单元测试里已覆盖，此处聚焦于 "构造耗时长 + 其他线程自旋等" 的场景。
        final ConsistentHashLoadBalance.ConsistentHashingLoadBalancer[] sharedLb =
                new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer[1];

        CountDownLatch startLatch = new CountDownLatch(1);   // 同时起跑
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        CountDownLatch createdLatch = new CountDownLatch(1); // 环创建完成的信号
        AtomicInteger errors = new AtomicInteger(0);
        AtomicLong totalRequests = new AtomicLong(0);
        AtomicLong maxSpinMs = new AtomicLong(0);

        // ── 线程 0：负责"创建"哈希环（模拟 SingletonFactory 中第一个进入的线程） ──
        // ── 其余线程：在环创建完成前尝试 select（模拟并发等待） ──
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await(); // 等所有线程就绪，同时开始
                } catch (InterruptedException ignored) {}

                if (threadId == 0) {
                    // 线程 0：负责创建哈希环
                    // 模拟 SingletonFactory 中第一个拿到锁的线程
                    sharedLb[0] = new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(
                            addresses, 160,
                            new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer.MD5HashFunction());
                    createdLatch.countDown(); // 通知所有等待线程：环已建好
                } else {
                    // 其他线程：等待环创建完成，然后 select
                    // 这里用 CountDownLatch 阻塞等待，而不是自旋 while(==null){}
                    // 原因：200 个线程同时自旋会把 CPU 占满，建环线程饿死
                    long spinStart = System.nanoTime();
                    try {
                        createdLatch.await(); // 阻塞等待，不消耗 CPU
                    } catch (InterruptedException ignored) {}

                    long spinNs = System.nanoTime() - spinStart;
                    maxSpinMs.accumulateAndGet(spinNs / 1_000_000, Math::max);

                    // 拿到引用后立即 select
                    int count = 0;
                    for (int i = 0; i < 100; i++) {
                        try {
                            String result = sharedLb[0].selectNode("cold-start-" + threadId + "-" + i);
                            assertNotNull(result);
                            count++;
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                    totalRequests.addAndGet(count);
                }
                doneLatch.countDown();
            }, "cold-start-" + t).start();
        }

        // ── 发令枪：所有线程同时起跑 ──
        long wallClockStart = System.nanoTime();
        startLatch.countDown();
        doneLatch.await();
        long wallClockNs = System.nanoTime() - wallClockStart; // 墙钟时间（从发令到最后一个线程完成）

        // ── 验证 ──
        assertNotNull(sharedLb[0], "哈希环应被创建");
        assertEquals(nodeCount, sharedLb[0].getAllNodes().size());
        assertEquals(0, errors.get(), "冷启动期间不应有异常");

        // ── 输出 ──
        printResult("场景3: 冷启动 (200线程)",
                totalRequests.get(), wallClockNs, errors.get(),
                "总线程数", String.valueOf(threadCount),
                "节点数", String.valueOf(nodeCount),
                "最大等待时间(ms)", String.valueOf(maxSpinMs.get()),
                "总 select 次数", String.valueOf(totalRequests.get()));

        System.out.println("[注意] 已改用 ReentrantReadWriteLock。selectNode 拿读锁不阻塞 CPU，"
                + "reBuild 拿写锁时新的 selectNode 在 AQS 队列中阻塞等待。");
    }

    // ================================================================
    // 场景 4：热 Key 倾斜
    // ================================================================

    /**
     * <h2>场景 4：热 Key 倾斜检测</h2>
     *
     * <h3>线上对应场景</h3>
     * <p>定时任务批量调用同一个服务，requestId 前缀高度相似：</p>
     * <pre>
     *   "batch-job-20240101-000001"
     *   "batch-job-20240101-000002"
     *   "batch-job-20240101-000003"
     *   ...
     * </pre>
     *
     * <p>如果这些 key 的 MD5 hash 全部落在同一个虚拟节点区间，
     * 全部请求都打到同一个 Provider，该节点被打爆，其他节点空闲。
     * 这就是"热 Key 倾斜"。</p>
     *
     * <h3>要检测什么</h3>
     * <ol>
     *   <li>相似 key 是否真的倾斜到少数节点</li>
     *   <li>key 加上什么后缀可以打散分布（如加 UUID）</li>
     *   <li>不同前缀的倾斜程度有没有区别</li>
     * </ol>
     */
    @Test
    @DisplayName("场景4: 热Key倾斜 — 相似前缀key是否集中在少数节点")
    void scenario04_hotKeySkew() {
        int nodeCount = 10;
        List<String> nodes = generateAddresses(nodeCount, "192.168.1", 8080);
        ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb =
                new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(
                        nodes, 160,
                        new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer.MD5HashFunction());

        // ── 测试三种 Key 模式 ──
        System.out.println();
        System.out.println("┌──────────────────────────────────────────────────");
        System.out.println("│ 场景4: 热 Key 倾斜分析 (10 节点, 每种模式 10000 key)");
        System.out.println("├──────────────────────────────────────────────────");

        // 模式 A：高相似度前缀（定时任务场景）
        analyzeKeyDistribution(lb, nodes, "高相似前缀 (batch-job-20240101-)",
                "batch-job-20240101-", 10000);

        // 模式 B：完全随机的 requestId（正常业务场景）
        analyzeKeyDistribution(lb, nodes, "随机 UUID 前缀 (正常业务)",
                UUID.randomUUID().toString().substring(0, 8), 10000);

        // 模式 C：serviceName 相同但 requestId 不同
        analyzeKeyDistribution(lb, nodes, "serviceName+递增序号 (订单查询)",
                "com.example.OrderService+v1+default+", 10000);

        System.out.println("└──────────────────────────────────────────────────");
        System.out.println("[说明] 标准差 / 平均值 = 变异系数。越小越均匀。");
        System.out.println("[建议] 如果高相似前缀的变异系数 > 0.5，建议在 key 中拼接 UUID。");
    }

    /**
     * 分析一种 Key 生成模式在各节点间的分布均匀程度。
     *
     * @param lb       哈希环
     * @param nodes    物理节点列表
     * @param label    模式描述（用于输出）
     * @param prefix   Key 前缀
     * @param count    生成的 Key 数量
     */
    private void analyzeKeyDistribution(
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb,
            List<String> nodes,
            String label,
            String prefix,
            int count) {

        // 统计每个节点被命中的次数
        Map<String, Integer> hitCount = new LinkedHashMap<>();
        for (String n : nodes) hitCount.put(n, 0);

        for (int i = 0; i < count; i++) {
            String key = prefix + String.format("%06d", i); // 补齐 6 位，模拟序号
            String node = lb.selectNode(key);
            hitCount.merge(node, 1, Integer::sum);
        }

        // 计算统计数据
        double avg = (double) count / nodes.size();       // 理想均值
        double variance = 0;                               // 方差
        int min = Integer.MAX_VALUE, max = 0;
        for (int v : hitCount.values()) {
            variance += Math.pow(v - avg, 2);
            if (v < min) min = v;
            if (v > max) max = v;
        }
        variance /= nodes.size();
        double stdDev = Math.sqrt(variance);               // 标准差
        double cv = stdDev / avg;                           // 变异系数

        // 输出
        System.out.printf("│ %s%n", label);
        System.out.printf("│   均值: %.0f, 最小: %d, 最大: %d, 标准差: %.0f, 变异系数: %.3f%n",
                avg, min, max, stdDev, cv);

        // 详细分布（可选，仅在倾斜严重时输出）
        if (cv > 0.5) {
            System.out.print("│   ⚠ 倾斜严重! 详细分布: ");
            for (Map.Entry<String, Integer> e : hitCount.entrySet()) {
                System.out.print(e.getKey().substring(e.getKey().lastIndexOf('.') + 1)
                        + "=" + e.getValue() + " ");
            }
            System.out.println();
        }
    }

    // ================================================================
    // 场景 5：大集群稳态吞吐
    // ================================================================

    /**
     * <h2>场景 5：大集群稳态吞吐测试</h2>
     *
     * <h3>线上对应场景</h3>
     * <p>大型微服务集群：一个服务可能有 200~1000 个 Provider 实例。
     * 每个实例 160 个虚拟节点 → 最多 160,000 个 TreeMap 条目。
     * selectNode 中的 tailMap 操作是 O(log N)，但日志因子很小。
     * 真正耗时的是每次 selectNode 都要做一次 MD5 哈希。</p>
     *
     * <h3>逐步扩大节点数，观察 selectNode 延迟变化</h3>
     */
    @Test
    @DisplayName("场景5: 大集群 — 10→50→200→500→1000节点，测selectNode延迟")
    void scenario05_largeClusterThroughput() {
        System.out.println();
        System.out.println("┌──────────────────────────────────────────────────");
        System.out.println("│ 场景5: 大集群稳态吞吐");
        System.out.println("│ 每个节点 160 虚拟节点, 每个规模测 20 万次 selectNode");
        System.out.println("├──────────────────────────────────────────────────");
        System.out.printf("│ %-12s %10s %12s %10s%n", "节点数", "虚拟节点", "QPS", "平均延迟");
        System.out.println("├──────────────────────────────────────────────────");

        int iterations = 200_000;   // 每个规模测 20 万次
        int[] nodeCounts = {10, 50, 200, 500, 1000};

        for (int nodeCount : nodeCounts) {
            List<String> nodes = generateAddresses(nodeCount, "192.168.1", 8080);

            // 预热：先跑一轮让 JIT 编译优化
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb =
                    new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(
                            nodes, 160,
                            new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer.MD5HashFunction());
            for (int i = 0; i < 10_000; i++) {
                lb.selectNode("warmup-" + i);
            }

            // 正式测试
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                lb.selectNode("bench-" + i);
            }
            long elapsedNs = System.nanoTime() - start;

            int virtualCount = nodeCount * 160;
            double avgNs = (double) elapsedNs / iterations;
            double avgUs = avgNs / 1000.0;
            double qps = iterations * 1_000_000_000.0 / elapsedNs;

            System.out.printf("│ %-12d %10d %10.0f/s %8.2f μs%n",
                    nodeCount, virtualCount, qps, avgUs);
        }
        System.out.println("└──────────────────────────────────────────────────");
        System.out.println("[参考] 一般 RPC 框架的负载均衡环节延迟应 < 10μs。");
        System.out.println("[注意] 如果延迟随节点数线性增长，瓶颈可能在 TreeMap.tailMap。");
        System.out.println("[注意] 如果延迟基本稳定，瓶颈在 MD5 哈希（每次都要做，与节点数无关）。");
    }

    // ================================================================
    // 场景 6：内存占用（TODO — 需要借助 JVM 工具）
    // ================================================================

    /**
     * <h2>场景 6：内存占用估算</h2>
     *
     * <h3>线上对应场景</h3>
     * <p>Consumer 连接了 500 个不同的 RPC 服务，每个服务平均 30 个 Provider。
     * 每个 Provider 160 个虚拟节点。</p>
     *
     * <h3>粗略估算</h3>
     * <pre>
     *   单个 ConsistentHashingLoadBalancer:
     *     physicalNodes:     HashSet<String>, 30 个元素
     *     virtualNodes:      TreeMap<Long, String>, 30×160 = 4800 个条目
     *     TreeMap Entry:     ≈ 64 字节/条目
     *     String 对象:       每个地址 ≈ 40 字节, 每个虚拟节点名 ≈ 48 字节
     *
     *     每个环 ≈ 4800 × 64 (TreeMap) + 30 × 40 (addresses) + 4800 × 48 (虚拟节点名)
     *           ≈ 307KB + 1.2KB + 230KB ≈ 538KB
     *
     *   500 个服务 ≈ 500 × 538KB ≈ 269MB
     *   加上 ConcurrentHashMap 开销 ≈ 300MB+
     * </pre>
     *
     * <h3>TODO — 无法在单元测试中完成</h3>
     * <ul>
     *   <li>需要借助 JProfiler / VisualVM / jmap 查看堆内存</li>
     *   <li>需要运行一个长时间 Consumer 进程，逐个订阅 500 个服务</li>
     *   <li>观察 GC 频率和 Full GC 耗时</li>
     *   <li>验证 selectors ConcurrentHashMap 是否随服务数线性增长</li>
     * </ul>
     *
     * <h3>可能的优化方向（供后续参考）</h3>
     * <ul>
     *   <li>虚拟节点 String 可以复用（同一个物理节点名出现在 160 个虚拟节点中）</li>
     *   <li>闲置超过 N 分钟的服务可以移除其哈希环（LRU 淘汰）</li>
     *   <li>虚拟节点数可以按节点数动态调整（节点少时多、节点多时少）</li>
     * </ul>
     */
    @Test
    @DisplayName("场景6: 内存占用 — 先看估算，再上工具实测")
    // 第一步：直接跑看估算数据。第二步：用 jmap/jstat 看真实内存（方法见输出中的 TODO）
    void scenario06_memoryFootprint() {
        System.out.println();
        System.out.println("┌──────────────────────────────────────────────────");
        System.out.println("│ 场景6: 内存占用估算 (TODO)");
        System.out.println("├──────────────────────────────────────────────────");

        int serviceCount = 100;     // 模拟 100 个不同服务
        int nodesPer = 30;          // 每个服务 30 个 Provider
        int vnodePer = 160;         // 每个 Provider 160 个虚拟节点

        // 创建一个 ConcurrentHashMap 模拟 ConsistentHashLoadBalance.selectors
        ConcurrentHashMap<String, ConsistentHashLoadBalance.ConsistentHashingLoadBalancer> selectors
                = new ConcurrentHashMap<>();

        // 逐个创建并放入
        for (int s = 0; s < serviceCount; s++) {
            String serviceName = "com.example.Service" + s + ":v1:default";
            List<String> addrs = generateAddresses(nodesPer, "192.168." + (s % 255), 8080);
            ConsistentHashLoadBalance.ConsistentHashingLoadBalancer lb =
                    new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer(
                            addrs, vnodePer,
                            new ConsistentHashLoadBalance.ConsistentHashingLoadBalancer.MD5HashFunction());
            selectors.put(serviceName, lb);
        }

        // 粗略统计
        long totalVirtualNodes = (long) serviceCount * nodesPer * vnodePer;
        long estimatedBytes = totalVirtualNodes * 64L; // TreeMap entry 约 64B
        double estimatedMB = estimatedBytes / (1024.0 * 1024.0);

        System.out.printf("│ 服务数:         %d%n", serviceCount);
        System.out.printf("│ 总虚拟节点数:   %d (每个服务 %d×%d)%n",
                totalVirtualNodes, nodesPer, vnodePer);
        System.out.printf("│ TreeMap 估算:   %.1f MB (仅节点，不含 String)%n", estimatedMB);
        System.out.printf("│ selectors 数:   %d%n", selectors.size());
        System.out.println("├──────────────────────────────────────────────────");
        System.out.println("│ 【接下来需要手动操作】");
        System.out.println("├──────────────────────────────────────────────────");
        System.out.println("│");
        System.out.println("│ 第1步：保持此测试不退出（加断点或 Thread.sleep 在最后）");
        System.out.println("│        在 ConsistentHashLoadBalanceStressTest 末尾加:");
        System.out.println("│        Thread.sleep(3600_000); // 暂停1小时");
        System.out.println("│");
        System.out.println("│ 第2步：另开终端，找到 JVM 进程 PID");
        System.out.println("│        jps -l | findstr \"surefire\"");
        System.out.println("│        或: jcmd");
        System.out.println("│");
        System.out.println("│ 第3步：查看内存整体状况");
        System.out.println("│        jmap -heap <pid>");
        System.out.println("│        → 关注 Heap Usage 中的 Used 值");
        System.out.println("│");
        System.out.println("│ 第4步：查看类的实例数和占用");
        System.out.println("│        jmap -histo <pid> | findstr -i \"Consistent\\|TreeMap\\|TreeMap\\\\$Entry\"");
        System.out.println("│        → 关注 ConsistentHashingLoadBalancer 实例数");
        System.out.println("│        → 关注 TreeMap$Entry 实例数（应 ≈ 服务数×节点数×160）");
        System.out.println("│");
        System.out.println("│ 第5步：观察 GC 情况");
        System.out.println("│        jstat -gc <pid> 1s");
        System.out.println("│        → 关注 FGC (Full GC 次数) 和 FGCT (Full GC 总耗时)");
        System.out.println("│        → 如果 FGC 频繁增长，说明内存压力大");
        System.out.println("│");
        System.out.println("│ 第6步（可选）：dump 堆内存离线分析");
        System.out.println("│        jmap -dump:format=b,file=heap.hprof <pid>");
        System.out.println("│        用 VisualVM 或 Eclipse MAT 打开 heap.hprof");
        System.out.println("│        → 查看 TreeMap$Entry 的 Retained Size");
        System.out.println("│        → 查看 String 对象中有多少重复的虚拟节点名");
        System.out.println("│");
        System.out.println("│ 第7步（也可用 IDEA 自带 Profiler，更直观）：");
        System.out.println("│        Run → Run with Profiler → 选 CPU + Memory");
        System.out.println("│        直接看内存中对象的数量和大小");
        System.out.println("└──────────────────────────────────────────────────");

        // 验证没有因为创建大量环而崩溃
        assertEquals(serviceCount, selectors.size(), "所有服务的环都应创建成功");
    }
}
