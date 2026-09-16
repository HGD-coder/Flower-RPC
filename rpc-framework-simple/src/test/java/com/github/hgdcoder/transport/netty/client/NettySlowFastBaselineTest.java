package com.github.hgdcoder.transport.netty.client;

import com.github.hgdcoder.annotation.RpcSlow;
import com.github.hgdcoder.config.RpcFrameworkConfig;
import com.github.hgdcoder.config.RpcServiceConfig;
import com.github.hgdcoder.provider.impl.DefaultServiceProvider;
import com.github.hgdcoder.registry.ServiceDiscovery;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.transport.netty.server.NettyRpcServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证同一条 Netty Channel 上，标记了 {@link RpcSlow} 的慢请求
 * 不会阻塞后到达的快请求，并且二者进入不同的业务线程池。
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class NettySlowFastBaselineTest {

    @Test
    void shouldIsolateAnnotatedSlowRequestFromFastRequestOnOneChannel()
            throws Exception {
        RpcFrameworkConfig config = RpcFrameworkConfig.defaults().toBuilder()
                .serverPort(0)
                .serializer("jdk")
                .compress("none")
                .connectTimeoutMillis(1000)
                .requestTimeoutMillis(3000)
                .build();

        SlowFastServiceImpl service = new SlowFastServiceImpl();
        DefaultServiceProvider provider = new DefaultServiceProvider();
        provider.addService(RpcServiceConfig.builder()
                .service(service)
                .group("baseline")
                .version("1.0")
                .build());

        NettyRpcServer server = new NettyRpcServer(config, provider);
        NettyRpcClient client = null;
        try {
            server.start();
            InetSocketAddress address = new InetSocketAddress(
                    "127.0.0.1",
                    server.getPort()
            );
            ServiceDiscovery discovery = request -> address;
            client = new NettyRpcClient(discovery, config);

            // 预热并建立唯一的长连接，正式断言不混入首次建连开销。
            assertEquals("fast", responseData(send(client, "fast")));

            CompletableFuture<RpcResponse<Object>> slowFuture =
                    send(client, "slow");
            assertTrue(
                    service.slowEntered.await(2, TimeUnit.SECONDS),
                    "慢请求没有在规定时间内进入服务端"
            );

            CompletableFuture<RpcResponse<Object>> fastFuture =
                    send(client, "fast");

            /*
             * slow 尚未释放时 fast 必须已经返回。
             * 线程名前缀继续证明请求确实进入不同线程池，避免两个请求
             * 都进入多线程 fast 池时出现假通过。
             */
            assertEquals("fast", responseData(fastFuture));
            assertFalse(
                    slowFuture.isDone(),
                    "快请求完成时，慢请求应该仍然阻塞"
            );
            assertTrue(
                    service.maxActive.get() >= 2,
                    "快慢请求没有并发执行"
            );
            assertEquals(
                    1,
                    client.getPendingRequestCount(),
                    "此时应该只剩慢请求等待响应"
            );
            assertTrue(
                    service.slowThreadName.get()
                            .startsWith("flower-rpc-slow-"),
                    () -> "慢请求进入了错误的线程池: "
                            + service.slowThreadName.get()
            );
            assertTrue(
                    service.fastThreadName.get()
                            .startsWith("flower-rpc-fast-"),
                    () -> "快请求进入了错误的线程池: "
                            + service.fastThreadName.get()
            );
            assertEquals(1L, client.getCreatedConnectionCount());

            service.releaseSlow.countDown();
            assertEquals("slow", responseData(slowFuture));
            assertEquals(0, client.getPendingRequestCount());

            System.out.printf(
                    "SLOW_FAST_ISOLATION maxServiceConcurrency=%d "
                            + "slowThread=%s fastThread=%s "
                            + "createdConnections=%d reusedConnections=%d%n",
                    service.maxActive.get(),
                    service.slowThreadName.get(),
                    service.fastThreadName.get(),
                    client.getCreatedConnectionCount(),
                    client.getReusedConnectionCount()
            );
        } finally {
            service.releaseSlow.countDown();
            if (client != null) {
                client.close();
            }
            server.close();
        }
    }

    private static CompletableFuture<RpcResponse<Object>> send(
            NettyRpcClient client,
            String methodName
    ) {
        RpcRequest request = RpcRequest.builder()
                .requestId(UUID.randomUUID().toString())
                .interfaceName(SlowFastService.class.getName())
                .methodName(methodName)
                .parameters(new Object[0])
                .paramTypes(new Class<?>[0])
                .group("baseline")
                .version("1.0")
                .build();
        return client.sendRpcRequest(request);
    }

    private static Object responseData(
            CompletableFuture<RpcResponse<Object>> future
    ) throws Exception {
        RpcResponse<Object> response = future.get(2, TimeUnit.SECONDS);
        assertTrue(
                response.isSuccess(),
                () -> "RPC 调用失败：" + response.getMessage()
        );
        return response.getData();
    }

    public interface SlowFastService {
        String slow();

        String fast();
    }

    public static final class SlowFastServiceImpl
            implements SlowFastService {
        private final CountDownLatch slowEntered = new CountDownLatch(1);
        private final CountDownLatch releaseSlow = new CountDownLatch(1);
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final AtomicReference<String> slowThreadName =
                new AtomicReference<>();
        private final AtomicReference<String> fastThreadName =
                new AtomicReference<>();

        @RpcSlow
        @Override
        public String slow() {
            slowThreadName.set(Thread.currentThread().getName());
            enterBusiness();
            slowEntered.countDown();
            try {
                if (!releaseSlow.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("等待释放慢请求超时");
                }
                return "slow";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("慢请求被中断", e);
            } finally {
                active.decrementAndGet();
            }
        }

        @Override
        public String fast() {
            fastThreadName.set(Thread.currentThread().getName());
            enterBusiness();
            try {
                return "fast";
            } finally {
                active.decrementAndGet();
            }
        }

        private void enterBusiness() {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
        }
    }
}
