package com.github.hgdcoder.loadbalance.loadbalancer;

import com.github.hgdcoder.loadbalance.LoadBalance;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares RandomLoadBalance and ConsistentHashLoadBalance by observable metrics.
 *
 * The test still uses the public LoadBalance interface, so it matches the V5
 * module boundary:
 *
 * service address list -> LoadBalance.selectServiceAddress(...) -> one address
 */
@DisplayName("V5 load-balance metric comparison")
class ConsistentHashLoadBalanceStressTest {
    private static final List<String> SERVICE_ADDRESSES = Arrays.asList(
            "127.0.0.1:9998",
            "127.0.0.1:9999",
            "127.0.0.1:10000"
    );

    /**
     * Metric 1: same request stability.
     *
     * Random load balance does not keep request affinity.
     * Consistent hash should keep the same requestId on the same node.
     */
    @Test
    @DisplayName("metric 1: same request stability")
    void metric01SameRequestStability() {
        int repeatCount = 200;

        StabilityMetric randomMetric = stability(
                "RandomLoadBalance",
                new RandomLoadBalance(),
                SERVICE_ADDRESSES,
                "same-request-id",
                repeatCount
        );

        StabilityMetric consistentHashMetric = stability(
                "ConsistentHashLoadBalance",
                new ConsistentHashLoadBalance(),
                SERVICE_ADDRESSES,
                "same-request-id",
                repeatCount
        );

        printHeader("Metric 1 - Same Request Stability");
        printStability(randomMetric);
        printStability(consistentHashMetric);

        assertTrue(randomMetric.uniqueNodeCount > 1,
                "RandomLoadBalance should usually select more than one node for the same request");
        assertEquals(1, consistentHashMetric.uniqueNodeCount,
                "ConsistentHashLoadBalance should keep the same request on one node");
    }

    /**
     * Metric 2: distribution uniformity.
     *
     * This test sends many different requestIds and prints:
     * hit count, hit ratio, min, max, spread, standard deviation and CV.
     */
    @Test
    @DisplayName("metric 2: distribution uniformity")
    void metric02DistributionUniformity() {
        int requestCount = 10_000;

        Distribution randomDistribution = distribution(
                "RandomLoadBalance",
                new RandomLoadBalance(),
                SERVICE_ADDRESSES,
                requestCount
        );

        Distribution consistentHashDistribution = distribution(
                "ConsistentHashLoadBalance",
                new ConsistentHashLoadBalance(),
                SERVICE_ADDRESSES,
                requestCount
        );

        printHeader("Metric 2 - Distribution Uniformity");
        printDistribution(randomDistribution);
        printDistribution(consistentHashDistribution);

        assertEquals(SERVICE_ADDRESSES.size(), randomDistribution.hitNodeCount(),
                "RandomLoadBalance should hit every node with enough requests");
        assertEquals(SERVICE_ADDRESSES.size(), consistentHashDistribution.hitNodeCount(),
                "ConsistentHashLoadBalance should hit every node with enough requests");
    }

    /**
     * Metric 3: remap ratio after adding one node.
     *
     * Consistent hash should move only part of existing request mappings.
     * Random load balance has no stable mapping, so its remap ratio is usually high.
     */
    @Test
    @DisplayName("metric 3: remap ratio after adding one node")
    void metric03RemapRatioAfterAddingOneNode() {
        List<String> oldAddresses = Arrays.asList(
                "127.0.0.1:9998",
                "127.0.0.1:9999",
                "127.0.0.1:10000",
                "127.0.0.1:10001"
        );
        List<String> newAddresses = Arrays.asList(
                "127.0.0.1:9998",
                "127.0.0.1:9999",
                "127.0.0.1:10000",
                "127.0.0.1:10001",
                "127.0.0.1:10002"
        );
        int requestCount = 10_000;

        RemapMetric randomRemapMetric = remapMetric(
                "RandomLoadBalance",
                new RandomLoadBalance(),
                oldAddresses,
                newAddresses,
                requestCount
        );

        RemapMetric consistentHashRemapMetric = remapMetric(
                "ConsistentHashLoadBalance",
                new ConsistentHashLoadBalance(),
                oldAddresses,
                newAddresses,
                requestCount
        );

        printHeader("Metric 3 - Remap Ratio After Adding One Node");
        printRemap(randomRemapMetric);
        printRemap(consistentHashRemapMetric);

        assertTrue(consistentHashRemapMetric.remapRatio() < 0.35,
                "Consistent hash should not remap most requests, actual ratio: "
                        + formatPercent(consistentHashRemapMetric.remapRatio()));
    }

    /**
     * Metric 4: local algorithm performance.
     *
     * This only measures selectServiceAddress(...), not the whole RPC network
     * call. Random is usually faster; consistent hash trades some CPU cost for
     * stable mapping and lower remap ratio.
     */
    @Test
    @DisplayName("metric 4: local algorithm performance")
    void metric04LocalAlgorithmPerformance() {
        int warmupCount = 10_000;
        int requestCount = 200_000;

        PerformanceMetric randomPerformance = performance(
                "RandomLoadBalance",
                new RandomLoadBalance(),
                SERVICE_ADDRESSES,
                warmupCount,
                requestCount
        );

        PerformanceMetric consistentHashPerformance = performance(
                "ConsistentHashLoadBalance",
                new ConsistentHashLoadBalance(),
                SERVICE_ADDRESSES,
                warmupCount,
                requestCount
        );

        printHeader("Metric 4 - Local Algorithm Performance");
        printPerformance(randomPerformance);
        printPerformance(consistentHashPerformance);

        assertTrue(randomPerformance.qps() > 0, "RandomLoadBalance QPS should be positive");
        assertTrue(consistentHashPerformance.qps() > 0,
                "ConsistentHashLoadBalance QPS should be positive");
    }

    /**
     * Extra guard: the cached consistent-hash selector should treat the same
     * address set as unchanged even if the incoming list order changes.
     */
    @Test
    @DisplayName("extra: consistent hash cache ignores address order changes")
    void consistentHashShouldIgnoreAddressOrderChange() {
        LoadBalance loadBalance = new ConsistentHashLoadBalance();
        RpcRequest request = request("order-independent-request");

        String selectedBefore = loadBalance.selectServiceAddress(SERVICE_ADDRESSES, request);

        List<String> reversed = new ArrayList<>(SERVICE_ADDRESSES);
        Collections.reverse(reversed);
        String selectedAfter = loadBalance.selectServiceAddress(reversed, request);

        assertEquals(selectedBefore, selectedAfter,
                "Same address set should reuse the cached hash ring even when order changes");
    }

    private static StabilityMetric stability(String name,
                                             LoadBalance loadBalance,
                                             List<String> addresses,
                                             String requestId,
                                             int repeatCount) {
        Set<String> selectedNodes = new HashSet<>();
        String firstSelected = null;

        for (int i = 0; i < repeatCount; i++) {
            String selected = loadBalance.selectServiceAddress(addresses, request(requestId));
            if (firstSelected == null) {
                firstSelected = selected;
            }
            selectedNodes.add(selected);
        }

        return new StabilityMetric(name, repeatCount, firstSelected, selectedNodes.size());
    }

    private static Distribution distribution(String name,
                                             LoadBalance loadBalance,
                                             List<String> addresses,
                                             int requestCount) {
        Map<String, Integer> hitCount = new LinkedHashMap<>();
        for (String address : addresses) {
            hitCount.put(address, 0);
        }

        for (int i = 0; i < requestCount; i++) {
            String selected = loadBalance.selectServiceAddress(addresses, request("request-" + i));
            hitCount.put(selected, hitCount.getOrDefault(selected, 0) + 1);
        }

        return new Distribution(name, requestCount, hitCount);
    }

    private static RemapMetric remapMetric(String name,
                                           LoadBalance loadBalance,
                                           List<String> oldAddresses,
                                           List<String> newAddresses,
                                           int requestCount) {
        Map<String, String> before = selectedNodes(loadBalance, oldAddresses, requestCount);
        Map<String, String> after = selectedNodes(loadBalance, newAddresses, requestCount);

        int unchanged = 0;
        int remapped = 0;
        for (String requestId : before.keySet()) {
            if (before.get(requestId).equals(after.get(requestId))) {
                unchanged++;
            } else {
                remapped++;
            }
        }

        return new RemapMetric(name, requestCount, unchanged, remapped);
    }

    private static PerformanceMetric performance(String name,
                                                 LoadBalance loadBalance,
                                                 List<String> addresses,
                                                 int warmupCount,
                                                 int requestCount) {
        for (int i = 0; i < warmupCount; i++) {
            loadBalance.selectServiceAddress(addresses, request("warmup-" + i));
        }

        long checksum = 0;
        long startNs = System.nanoTime();
        for (int i = 0; i < requestCount; i++) {
            String selected = loadBalance.selectServiceAddress(addresses, request("request-" + i));
            checksum += selected.length();
        }
        long elapsedNs = System.nanoTime() - startNs;

        return new PerformanceMetric(name, warmupCount, requestCount, elapsedNs, checksum);
    }

    private static Map<String, String> selectedNodes(LoadBalance loadBalance,
                                                     List<String> addresses,
                                                     int requestCount) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < requestCount; i++) {
            String requestId = "request-" + i;
            String selected = loadBalance.selectServiceAddress(addresses, request(requestId));
            result.put(requestId, selected);
        }
        return result;
    }

    private static RpcRequest request(String requestId) {
        return RpcRequest.builder()
                .requestId(requestId)
                .interfaceName("com.github.hgdcoder.HelloService")
                .methodName("hello")
                .group("test")
                .version("1.0")
                .build();
    }

    private static void printHeader(String title) {
        System.out.println();
        System.out.println("============================================================");
        System.out.println(title);
        System.out.println("============================================================");
    }

    private static void printStability(StabilityMetric metric) {
        System.out.println(metric.name);
        System.out.println("  repeatCount=" + metric.repeatCount);
        System.out.println("  firstSelected=" + metric.firstSelected);
        System.out.println("  uniqueNodeCount=" + metric.uniqueNodeCount);
        System.out.println("  stable=" + (metric.uniqueNodeCount == 1));
    }

    private static void printDistribution(Distribution distribution) {
        System.out.println(distribution.name);
        for (Map.Entry<String, Integer> entry : distribution.hitCount.entrySet()) {
            double ratio = entry.getValue() / (double) distribution.total;
            System.out.println("  " + entry.getKey() + "=" + entry.getValue()
                    + " (" + formatPercent(ratio) + ")");
        }
        System.out.println("  total=" + distribution.total);
        System.out.println("  hitNodeCount=" + distribution.hitNodeCount());
        System.out.println("  minHit=" + distribution.minHit());
        System.out.println("  maxHit=" + distribution.maxHit());
        System.out.println("  spread=" + distribution.spread());
        System.out.println("  avgHit=" + formatDouble(distribution.avgHit()));
        System.out.println("  stdDev=" + formatDouble(distribution.stdDev()));
        System.out.println("  coefficientOfVariation=" + formatPercent(distribution.coefficientOfVariation()));
    }

    private static void printRemap(RemapMetric metric) {
        System.out.println(metric.name);
        System.out.println("  total=" + metric.total);
        System.out.println("  unchanged=" + metric.unchanged);
        System.out.println("  remapped=" + metric.remapped);
        System.out.println("  unchangedRatio=" + formatPercent(metric.unchangedRatio()));
        System.out.println("  remapRatio=" + formatPercent(metric.remapRatio()));
    }

    private static void printPerformance(PerformanceMetric metric) {
        System.out.println(metric.name);
        System.out.println("  warmupCount=" + metric.warmupCount);
        System.out.println("  requestCount=" + metric.requestCount);
        System.out.println("  totalMs=" + formatDouble(metric.totalMs()));
        System.out.println("  avgNs=" + formatDouble(metric.avgNs()));
        System.out.println("  avgUs=" + formatDouble(metric.avgUs()));
        System.out.println("  qps=" + formatDouble(metric.qps()));
        System.out.println("  checksum=" + metric.checksum);
    }

    private static String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100);
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static class StabilityMetric {
        private final String name;
        private final int repeatCount;
        private final String firstSelected;
        private final int uniqueNodeCount;

        private StabilityMetric(String name, int repeatCount, String firstSelected, int uniqueNodeCount) {
            this.name = name;
            this.repeatCount = repeatCount;
            this.firstSelected = firstSelected;
            this.uniqueNodeCount = uniqueNodeCount;
        }
    }

    private static class Distribution {
        private final String name;
        private final int total;
        private final Map<String, Integer> hitCount;

        private Distribution(String name, int total, Map<String, Integer> hitCount) {
            this.name = name;
            this.total = total;
            this.hitCount = hitCount;
        }

        private int hitNodeCount() {
            int count = 0;
            for (Integer value : hitCount.values()) {
                if (value > 0) {
                    count++;
                }
            }
            return count;
        }

        private int minHit() {
            int min = Integer.MAX_VALUE;
            for (Integer value : hitCount.values()) {
                min = Math.min(min, value);
            }
            return min;
        }

        private int maxHit() {
            int max = Integer.MIN_VALUE;
            for (Integer value : hitCount.values()) {
                max = Math.max(max, value);
            }
            return max;
        }

        private int spread() {
            return maxHit() - minHit();
        }

        private double avgHit() {
            return total / (double) hitCount.size();
        }

        private double stdDev() {
            double avg = avgHit();
            double sum = 0;
            for (Integer value : hitCount.values()) {
                double diff = value - avg;
                sum += diff * diff;
            }
            return Math.sqrt(sum / hitCount.size());
        }

        private double coefficientOfVariation() {
            return stdDev() / avgHit();
        }
    }

    private static class RemapMetric {
        private final String name;
        private final int total;
        private final int unchanged;
        private final int remapped;

        private RemapMetric(String name, int total, int unchanged, int remapped) {
            this.name = name;
            this.total = total;
            this.unchanged = unchanged;
            this.remapped = remapped;
        }

        private double unchangedRatio() {
            return unchanged / (double) total;
        }

        private double remapRatio() {
            return remapped / (double) total;
        }
    }

    private static class PerformanceMetric {
        private final String name;
        private final int warmupCount;
        private final int requestCount;
        private final long elapsedNs;
        private final long checksum;

        private PerformanceMetric(String name,
                                  int warmupCount,
                                  int requestCount,
                                  long elapsedNs,
                                  long checksum) {
            this.name = name;
            this.warmupCount = warmupCount;
            this.requestCount = requestCount;
            this.elapsedNs = elapsedNs;
            this.checksum = checksum;
        }

        private double totalMs() {
            return elapsedNs / 1_000_000.0;
        }

        private double avgNs() {
            return elapsedNs / (double) requestCount;
        }

        private double avgUs() {
            return avgNs() / 1_000.0;
        }

        private double qps() {
            return requestCount * 1_000_000_000.0 / elapsedNs;
        }
    }
}
