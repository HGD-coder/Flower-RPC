package com.github.hgdcoder.transport.netty.client;

import com.github.hgdcoder.config.RpcFrameworkConfig;
import com.github.hgdcoder.config.RpcServiceConfig;
import com.github.hgdcoder.provider.impl.DefaultServiceProvider;
import com.github.hgdcoder.registry.ServiceDiscovery;
import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.transport.netty.server.NettyRpcServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端、服务端和编解码器的同进程端到端测试。
 * 这里使用真实本地端口而非 EmbeddedChannel，以验证多线程共用连接、超时清理和关闭顺序的整体协作。
 */
class NettyRpcLocalEndToEndTest {
    @Test
    void shouldRoundTripWithJdkSerializerAndGzipConfig() {
        RpcFrameworkConfig config = RpcFrameworkConfig.defaults().toBuilder()
                .serverPort(0)
                .serializer("jdk")
                .compress("gzip")
                .build();

        DefaultServiceProvider provider = new DefaultServiceProvider();
        provider.addService(RpcServiceConfig.builder()
                .service(new EchoServiceImpl())
                .group("test")
                .version("1.0")
                .build());

        NettyRpcServer server = new NettyRpcServer(config, provider);
        server.start();
        ServiceDiscovery discovery = request -> new InetSocketAddress(
                config.getServerHost(),
                server.getPort()
        );
        NettyRpcClient client = new NettyRpcClient(discovery, config);
        try {
            // 配置名称最终映射成协议头中的 byte，并完成一次真实 Socket 往返。
            assertEquals(RpcConstants.JDK_CODEC, config.getCodec());
            assertEquals(RpcConstants.GZIP_COMPRESS, config.getCompressType());
            assertEquals("echo:configured", call(client, "configured", 0));
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void shouldCallLocallyAndShareOneChannelAcrossThreads() throws Exception {
        // 同时放行多个调用，验证连接占位 Future 让它们共用同一条物理连接。
        TestContext context = startContext(3000);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                final int value = i;
                results.add(pool.submit(() -> {
                    startGate.await();
                    return call(context.client, "value-" + value, 0);
                }));
            }
            startGate.countDown();
            for (int i = 0; i < results.size(); i++) {
                assertEquals("echo:value-" + i, results.get(i).get(5, TimeUnit.SECONDS));
            }

            assertEquals(1, context.client.getCreatedConnectionCount());
            assertEquals(11, context.client.getReusedConnectionCount());

            context.client.closeConnections();
            context.client.resetConnectionStatistics();
            assertEquals("echo:again", call(context.client, "again", 0));
            assertEquals(1, context.client.getCreatedConnectionCount());
            assertEquals(0, context.client.getReusedConnectionCount());
        } finally {
            pool.shutdownNow();
            context.close();
        }
    }

    @Test
    void shouldClearPendingOnTimeoutAndClientClose() throws Exception {
        // 分别覆盖超时和显式关闭两条 pending 清理路径，确保等待线程不会泄漏。
        TestContext timeoutContext = startContext(50);
        try {
            assertThrows(RuntimeException.class,
                    () -> call(timeoutContext.client, "timeout", 300));
            assertEquals(0, timeoutContext.client.getPendingRequestCount());
            // 超时只结束客户端等待，不中断服务端业务；等待业务自然完成再关同进程服务端。
            Thread.sleep(320L);
        } finally {
            timeoutContext.close();
        }

        TestContext closeContext = startContext(3000);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> call = pool.submit(
                    () -> call(closeContext.client, "close", 500));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (closeContext.client.getPendingRequestCount() == 0
                    && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(1, closeContext.client.getPendingRequestCount());
            closeContext.client.close();
            assertThrows(ExecutionException.class,
                    () -> call.get(2, TimeUnit.SECONDS));
            assertEquals(0, closeContext.client.getPendingRequestCount());
            // 等业务任务自然结束后再关闭同进程服务端，避免测试制造无关的关停竞态日志。
            Thread.sleep(550L);
        } finally {
            pool.shutdownNow();
            closeContext.close();
        }
    }

    private TestContext startContext(int requestTimeoutMillis) {
        // 端口 0 让操作系统分配空闲端口，避免测试机器上的固定端口冲突。
        DefaultServiceProvider provider = new DefaultServiceProvider();
        provider.addService(RpcServiceConfig.builder()
                .service(new EchoServiceImpl())
                .group("test")
                .version("1.0")
                .build());

        NettyRpcServer server = new NettyRpcServer(0, provider);
        server.start();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", server.getPort());
        ServiceDiscovery discovery = request -> address;
        NettyRpcClient client = new NettyRpcClient(discovery, 1000, requestTimeoutMillis);
        return new TestContext(server, client);
    }

    private String call(NettyRpcClient client, String value, long delayMillis) {
        // 通过真实客户端调用回声服务；delayMillis 用于让服务端业务晚于客户端超时或关闭。
        RpcRequest request = RpcRequest.builder()
                .requestId(UUID.randomUUID().toString())
                .interfaceName(EchoService.class.getName())
                .methodName("echo")
                .parameters(new Object[]{value, delayMillis})
                .paramTypes(new Class<?>[]{String.class, long.class})
                .group("test")
                .version("1.0")
                .build();
        RpcResponse<?> response = (RpcResponse<?>) client.sendRpcRequest(request);
        assertEquals(200, response.getCode());
        return (String) response.getData();
    }

    public interface EchoService {
        String echo(String value, long delayMillis);
    }

    public static class EchoServiceImpl implements EchoService {
        @Override
        public String echo(String value, long delayMillis) {
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("service interrupted", e);
                }
            }
            return "echo:" + value;
        }
    }

    private static final class TestContext implements AutoCloseable {
        private final NettyRpcServer server;
        private final NettyRpcClient client;

        private TestContext(NettyRpcServer server, NettyRpcClient client) {
            this.server = server;
            this.client = client;
        }

        @Override
        public void close() {
            // 先结束客户端等待和网络线程，再停止同进程服务端，避免客户端仍向已关闭服务端写入。
            client.close();
            server.close();
        }
    }
}
