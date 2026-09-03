package com.github.hgdcoder.transport.netty.client;

import com.github.hgdcoder.remoting.dto.RpcResponse;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pending 请求表的生命周期测试。
 * 使用 EmbeddedChannel 提供可手动推进的 EventLoop，使超时任务无需真实网络和不稳定的定时器。
 */
class UnprocessedRequestsTest {
    @Test
    void shouldCompleteAndRemoveNormalResponse() throws Exception {
        // 正常响应必须完成调用方 Future，同时删除条目，避免 pending 表持续增长。
        EmbeddedChannel channel = new EmbeddedChannel();
        UnprocessedRequests requests = new UnprocessedRequests();
        try {
            CompletableFuture<RpcResponse<Object>> future = requests.register(1, channel, 1000);
            RpcResponse<String> response = RpcResponse.success("ok", "business-1");
            assertTrue(requests.complete(1, response));
            assertEquals("ok", future.get().getData());
            assertEquals(0, requests.size());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldClearOnWriteFailureChannelCloseAndClientClose() {
        // 三种终止路径都应清理各自负责的请求，且连接关闭不能误清理另一连接的请求。
        EmbeddedChannel firstChannel = new EmbeddedChannel();
        EmbeddedChannel secondChannel = new EmbeddedChannel();
        UnprocessedRequests requests = new UnprocessedRequests();
        try {
            CompletableFuture<RpcResponse<Object>> writeFailed =
                    requests.register(2, firstChannel, 1000);
            requests.fail(2, new IllegalStateException("write failed"));
            assertExceptional(writeFailed);
            assertEquals(0, requests.size());

            CompletableFuture<RpcResponse<Object>> channelClosed =
                    requests.register(3, firstChannel, 1000);
            CompletableFuture<RpcResponse<Object>> clientClosed =
                    requests.register(4, secondChannel, 1000);
            requests.failChannel(firstChannel, new IllegalStateException("channel closed"));
            assertExceptional(channelClosed);
            assertEquals(1, requests.size());

            requests.failAll(new IllegalStateException("client closed"));
            assertExceptional(clientClosed);
            assertEquals(0, requests.size());
        } finally {
            firstChannel.finishAndReleaseAll();
            secondChannel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldTimeoutAndRemovePendingRequest() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        UnprocessedRequests requests = new UnprocessedRequests();
        try {
            CompletableFuture<RpcResponse<Object>> future = requests.register(5, channel, 10);
            // EmbeddedChannel 不会自动推进计划任务；等待到期后显式执行，验证超时也会清理 pending。
            Thread.sleep(20L);
            channel.runScheduledPendingTasks();
            assertExceptional(future);
            assertEquals(0, requests.size());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private void assertExceptional(CompletableFuture<?> future) {
        // 统一验证 Future 的异常终态，避免测试只检查表大小而漏掉调用方是否被唤醒。
        assertTrue(future.isCompletedExceptionally());
        assertThrows(ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS));
    }
}
