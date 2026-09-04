package com.github.hgdcoder.transport.socket;

import com.github.hgdcoder.enums.RpcStatusCode;
import com.github.hgdcoder.exception.RpcException;
import com.github.hgdcoder.registry.ServiceDiscovery;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.utils.concurrent.threadpool.CustomThreadPoolConfig;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 BIO 客户端饱和时快速失败，而不是无限堆积请求。 */
class SocketRpcClientBackpressureTest {

    @Test
    void shouldRejectRequestWhenWorkerAndQueueAreFull() throws Exception {
        CountDownLatch firstTaskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        ServiceDiscovery blockingDiscovery = request -> {
            firstTaskStarted.countDown();
            try {
                releaseTask.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("stop test request before real connection");
        };

        CustomThreadPoolConfig tinyPool = CustomThreadPoolConfig.builder()
                .corePoolSize(1)
                .maximumPoolSize(1)
                .queueCapacity(1)
                .build();
        SocketRpcClient client = new SocketRpcClient(blockingDiscovery, tinyPool);

        try {
            CompletableFuture<RpcResponse<Object>> running =
                    client.sendRpcRequest(request());
            assertTrue(firstTaskStarted.await(1, TimeUnit.SECONDS));

            // 一个任务占用工作线程，第二个任务占用唯一队列槽位。
            CompletableFuture<RpcResponse<Object>> queued =
                    client.sendRpcRequest(request());
            CompletableFuture<RpcResponse<Object>> rejected =
                    client.sendRpcRequest(request());

            ExecutionException exception = assertThrows(
                    ExecutionException.class,
                    () -> rejected.get(1, TimeUnit.SECONDS)
            );
            RpcException cause = (RpcException) exception.getCause();
            assertEquals(RpcStatusCode.RESOURCE_EXHAUSTED, cause.getStatusCode());

            releaseTask.countDown();
            assertThrows(ExecutionException.class, running::get);
            assertThrows(ExecutionException.class, queued::get);
        } finally {
            releaseTask.countDown();
            client.close();
        }
    }

    private RpcRequest request() {
        return RpcRequest.builder()
                .requestId(UUID.randomUUID().toString())
                .interfaceName("test.Service")
                .methodName("call")
                .parameters(new Object[0])
                .paramTypes(new Class<?>[0])
                .group("test")
                .version("1.0")
                .build();
    }
}
