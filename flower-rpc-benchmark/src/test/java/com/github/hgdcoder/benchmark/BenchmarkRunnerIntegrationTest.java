package com.github.hgdcoder.benchmark;

import com.github.hgdcoder.Hello;
import com.github.hgdcoder.HelloService;
import com.github.hgdcoder.config.RpcFrameworkConfig;
import com.github.hgdcoder.config.RpcServiceConfig;
import com.github.hgdcoder.provider.impl.DefaultServiceProvider;
import com.github.hgdcoder.registry.ServiceDiscovery;
import com.github.hgdcoder.transport.netty.server.NettyRpcServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkRunnerIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void runsBothLoadModelsThroughRealLocalNettyTransport() throws Exception {
        RpcFrameworkConfig config = RpcFrameworkConfig.defaults()
                .toBuilder()
                .serverHost("127.0.0.1")
                .bindHost("127.0.0.1")
                .serverPort(0)
                .requestTimeoutMillis(2000)
                .build();
        DefaultServiceProvider provider = new DefaultServiceProvider();
        provider.addService(RpcServiceConfig.builder()
                .service(new LocalHelloService())
                .group("test")
                .version("1.0")
                .build());

        Path output = temporaryDirectory.resolve("integration.csv");
        try (NettyRpcServer server = new NettyRpcServer(config, provider)) {
            server.start();
            ServiceDiscovery discovery = request ->
                    new InetSocketAddress("127.0.0.1", server.getPort());
            BenchmarkRunner runner = new BenchmarkRunner(config, discovery);

            BenchmarkResult concurrency = runner.run(BenchmarkOptions.parse(new String[]{
                    "--mode=concurrency", "--threads=2", "--duration=1", "--warmup=0",
                    "--output=" + output
            }));
            BenchmarkResult rate = runner.run(BenchmarkOptions.parse(new String[]{
                    "--mode=rate", "--rate=100", "--max-inflight=16", "--duration=1",
                    "--warmup=0", "--drain-timeout=2", "--output=" + output
            }));

            assertSuccessfulAndConserved(concurrency);
            assertSuccessfulAndConserved(rate);
            assertEquals(3, Files.readAllLines(output).size());
        }
    }

    private static void assertSuccessfulAndConserved(BenchmarkResult result) {
        List<String> values = result.csvValues();
        int successColumn = BenchmarkResult.CSV_HEADER.indexOf("success");
        int conservedColumn = BenchmarkResult.CSV_HEADER.indexOf("conserved");
        assertTrue(
                Long.parseLong(values.get(successColumn)) > 0,
                () -> "没有成功请求，完整结果：" + values
        );
        assertEquals(
                "true",
                values.get(conservedColumn),
                () -> "请求计数不守恒，完整结果：" + values
        );
    }

    public static final class LocalHelloService implements HelloService {
        @Override
        public String hello(Hello hello) {
            return "Hello " + hello.getDescription();
        }
    }
}
