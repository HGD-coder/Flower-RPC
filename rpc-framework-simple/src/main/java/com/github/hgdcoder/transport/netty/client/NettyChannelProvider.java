package com.github.hgdcoder.transport.netty.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;



import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 每个服务地址只保留一条供所有调用线程共享的 Channel。
 * 位于客户端发送链中服务发现之后、写请求之前，负责连接缓存、并发建连去重和连接关闭。
 */
final class NettyChannelProvider {
    // 由 NettyRpcClient 在创建时配置，所有异步连接都从同一个 Bootstrap 发起。
    private final Bootstrap bootstrap;
    // 等待连接占位 Future 的最长时间，比底层 connect 超时略长以覆盖回调调度
    private final int connectTimeoutMillis;
    // 地址到连接占位 Future 的并发缓存。同一地址的并发调用共享一个 Future，避免重复建连。
    private final ConcurrentHashMap<String, CompletableFuture<Channel>> channels = new ConcurrentHashMap<>();

    // LongAdder 适合并发累加；统计仅用于观测，可在客户端仍运行时重置。
    private final LongAdder createdConnections = new LongAdder();
    private final LongAdder reusedConnections = new LongAdder();
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private volatile boolean closed;

    /**
     * 创建连接缓存。Bootstrap 的 Pipeline 已由客户端预先配置完成。
     */
    NettyChannelProvider(final Bootstrap bootstrap, final int connectTimeoutMillis) {
        this.bootstrap = bootstrap;
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    Channel getChannel(InetSocketAddress address) {
        lifecycleLock.readLock().lock();
        try{
            if(closed){
                throw new IllegalStateException("Netty RPC client is closed");
            }
            String key = buildKey(address);
            for(;;) {
                CompletableFuture<Channel> slot = channels.get(key);
                boolean creator = false;
                if(slot == null) {
                    CompletableFuture<Channel> newSlot = new CompletableFuture<>();
                    slot = channels.putIfAbsent(key, newSlot);
                    if(slot == null) {
                        slot = newSlot;
                        creator = true;
                        connect(address,key,slot);
                    }
                }

                Channel channel = awaitChannel(key, slot);
                if(channel.isActive()) {
                    if(!creator) {
                        reusedConnections.increment();
                    }
                    return channel;
                }
                channels.remove(key,slot);
                channel.close();
            }
        }finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * 由抢到缓存槽位的线程发起异步连接，并把最终结果写回共享占位 Future。
     * 连接回调可能晚于等待超时或客户端关闭，此时关闭迟到 Channel，避免它脱离缓存而泄漏。
     */
    private void connect(InetSocketAddress address,
                         String key,
                         CompletableFuture<Channel> slot) {
        ChannelFuture connectFuture;
        try{
            connectFuture = bootstrap.connect(address);
        }catch(RuntimeException e){
            channels.remove(key,slot);
            slot.completeExceptionally(e);
            throw e;
        }
        connectFuture.addListener(future->{
            if(future.isSuccess()){
                Channel channel = connectFuture.channel();
                // 等待方可能已超时或客户端可能已关闭，迟到连接不能成为泄漏连接。
                if(closed || channels.get(key) != slot){
                    channel.close();
                    slot.completeExceptionally(
                            new IllegalStateException("RPC channel is no longer needed: " + key)
                    );
                    return;
                }
                createdConnections.increment();
                slot.complete(channel);
                channel.closeFuture().addListener(ignored -> channels.remove(key, slot));
            }else{
                channels.remove(key, slot);
                slot.completeExceptionally(future.cause());
            }
        });
    }

    /**
     * 等待连接槽位完成。失败或超时时会移除槽位，使下一次请求可以重新尝试建连。
     */
    private Channel awaitChannel(String key,CompletableFuture<Channel> slot) {
        try{
            return slot.get(connectTimeoutMillis + 1000L , TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channels.remove(key,slot);
            slot.completeExceptionally(e);
            throw new RuntimeException("Interrupted while creating RPC channel", e);
        } catch (ExecutionException | TimeoutException e){
            channels.remove(key,slot);
            slot.completeExceptionally(e);
            throw new RuntimeException("Create RPC channel failed: " + key, e);
        }
    }

    /**
     * 关闭当前缓存的连接但不标记提供者永久关闭；后续请求仍可按需创建新连接。
     */
    void closeConnections() {
        lifecycleLock.writeLock().lock();
        try {
            closeChannels();
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    /**
     * 永久关闭提供者并关闭缓存连接。写锁会等待正在获取连接的调用结束。
     */
    void close() {
        lifecycleLock.writeLock().lock();
        try {
            closed = true;
            closeChannels();
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    /**
     * 移除每个缓存槽位后再关闭已完成的 Channel；未完成的槽位会失去归属，迟到回调负责关闭它。
     */
    private void closeChannels() {
        for (Map.Entry<String, CompletableFuture<Channel>> entry : channels.entrySet()) {
            CompletableFuture<Channel> slot = entry.getValue();
            if (channels.remove(entry.getKey(), slot)) {
                Channel channel = slot.getNow(null);
                if (channel != null) {
                    channel.close().syncUninterruptibly();
                }
            }
        }
    }

    /**
     * 返回当前累计值的不可变快照，供客户端对外展示连接复用情况。
     */
    ConnectionStatistics statistics() {
        return new ConnectionStatistics(
                createdConnections.sum(),
                reusedConnections.sum()
        );
    }

    /**
     * 清零观测计数，不影响缓存连接和正在进行的 RPC。
     */
    void resetStatistics() {
        createdConnections.reset();
        reusedConnections.reset();
    }

    private String buildKey(InetSocketAddress address) {
        return "[" + address.getHostString() + "]:" + address.getPort();
    }
}
