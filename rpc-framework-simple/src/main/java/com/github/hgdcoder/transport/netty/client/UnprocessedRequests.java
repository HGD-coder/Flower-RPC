package com.github.hgdcoder.transport.netty.client;


import com.github.hgdcoder.remoting.dto.RpcResponse;
import io.netty.channel.Channel;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 用协议层 int requestId 匹配并发请求与响应。
 * 位于客户端写请求和接收响应之间: 写前登记 pending，响应、写失败、连接关闭、超时或客户端关闭时清理。
 */
final class UnprocessedRequests {
    // 并发安全的 pending 表。条目只存活到首次终态，remove 后不会保留请求或定时任务引用。
    private final ConcurrentHashMap<Integer,PendingRequest> requests = new ConcurrentHashMap<>();

    /**
     * 登记一次已选定 Channel 的调用，并在该 Channel 的 EventLoop 上安排超时清理。
     *
     * @param requestId 协议层请求号，必须在当前 pending 表中唯一
     * @param channel 承载本次请求的连接，用于连接关闭时定向失败
     * @param timeoutMillis 等待响应的最长时间
     * @return 调用线程等待的 Future；请求号重复时返回 null，调用方应换号后重试
     */
    CompletableFuture<RpcResponse<?>> register(int requestId,Channel channel,long timeoutMillis) {
        PendingRequest pending = new PendingRequest(channel);
        if(requests.putIfAbsent(requestId,pending) != null) {
            return null;
        }

        try {
            ScheduledFuture<?> timeoutFuture = channel.eventLoop().schedule(
                    () -> fail(requestId,new TimeoutException(
                            "RPC request timed out: requestId=" + requestId
                    )),
                    timeoutMillis,
                    TimeUnit.MILLISECONDS
            );
            pending.setTimeoutFuture(timeoutFuture);
        }catch (RuntimeException e) {
            requests.remove(requestId, pending);
            pending.future.completeExceptionally(e);
            throw e;
        }
        return pending.future;
    }

    /**
     * 收到响应时完成对应 Future，并取消仍未执行的超时任务。
     * 返回 false 表示该请求已超时或已被其他失败路径清理，迟到响应会被安全忽略。
     */
    boolean complete(int requestId,RpcResponse<?> response) {
        PendingRequest pending = requests.remove(requestId);
        if(pending == null) {
            return false;
        }
        pending.cancelTimeout();
        pending.future.complete(response);
        return true;
    }

    /**
     * 让单个 pending 请求异常完成，用于写失败、超时和调用线程中断等路径。
     */
    void fail(int requestId, Throwable cause) {
        PendingRequest pending = requests.remove(requestId);
        if(pending != null) {
            pending.cancelTimeout();
            pending.future.completeExceptionally(cause);
        }
    }

    /**
     * 连接不可用时仅清理绑定到该连接的请求，其他地址的请求可继续等待。
     */
    void failChannel(Channel channel, Throwable cause) {
        for(Map.Entry<Integer,PendingRequest> entry : requests.entrySet()) {
            PendingRequest pending = entry.getValue();
            if (pending.channel == channel && requests.remove(entry.getKey(),pending)) {
                pending.cancelTimeout();
                pending.future.completeExceptionally(cause);
            }
        }
    }

    /**
     * 客户端关闭或主动关闭全部连接前清空 pending，确保所有等待线程都能被唤醒。
     */
    void failAll(Throwable cause) {
        for(Map.Entry<Integer, PendingRequest> entry :requests.entrySet()) {
            PendingRequest pending = entry.getValue();
            if (requests.remove(entry.getKey(),pending)){
                pending.cancelTimeout();
                pending.future.completeExceptionally(cause);
            }
        }
    }

    int size() {
        return requests.size();
    }


    private static final class PendingRequest {
        // 用引用比较关联 Channel，防止同地址的新连接误伤旧连接上的请求。
        private final Channel channel;
        // 只会由响应或某条失败路径完成一次，调用线程在客户端中等待它。
        private final CompletableFuture<RpcResponse<?>> future = new CompletableFuture<>();
        // 超时任务与响应到达存在竞态，原子引用保证两者可安全交接并取消任务。
        private final AtomicReference<ScheduledFuture<?>> timeoutFuture = new AtomicReference<>();

        /**
         * 创建尚未安排超时任务的 pending 条目，并记录它归属的连接。
         */
        private PendingRequest(Channel channel) {
            this.channel = channel;
        }

        /**
         * 保存刚创建的超时任务。若响应恰好已先到达，立即取消这个迟到登记的任务。
         */
        private void setTimeoutFuture(ScheduledFuture<?> future){
            timeoutFuture.set(future);
            if(this.future.isDone()){
                future.cancel(false);
            }
        }

        /**
         * 请求进入任一终态时取消定时器，避免无效任务继续占用 EventLoop 队列。
         */
        private void cancelTimeout(){
            ScheduledFuture<?> future = timeoutFuture.get();
            if(future != null){
                future.cancel(false);
            }
        }
    }
}
