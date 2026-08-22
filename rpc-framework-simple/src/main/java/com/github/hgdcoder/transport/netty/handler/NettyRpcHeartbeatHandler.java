package com.github.hgdcoder.transport.netty.handler;

import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.remoting.dto.RpcMessage;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

/**
 * 客户端和服务端共用的 Netty 心跳处理器。
 *
 * <p>它只负责“连接是否还活着”，不执行 RPC 业务方法，也不操作
 * UnprocessedRequests。连接被它关闭后，原有的 NettyRpcClientHandler
 * 会收到 channelInactive 事件，并负责让该连接上的 pending 请求失败。</p>
 *
 * <p>该 Handler 不保存任何会变化的连接状态，只保存当前实例扮演客户端
 * 还是服务端，因此可以通过 {@link ChannelHandler.Sharable} 被多个 Channel 共享。</p>
 */
@ChannelHandler.Sharable
public class NettyRpcHeartbeatHandler extends ChannelInboundHandlerAdapter {

    /**
     * 同一个类服务两种端点，但每个实例的角色创建后不再改变。
     * true 表示客户端：发 PING、收 PONG；
     * false 表示服务端：收 PING、回 PONG。
     */
    private final boolean clientSide;

    private NettyRpcHeartbeatHandler(boolean clientSide) {
        this.clientSide = clientSide;
    }

    /**
     * 创建客户端心跳处理器。
     */
    public static NettyRpcHeartbeatHandler forClient() {
        return new NettyRpcHeartbeatHandler(true);
    }

    /**
     * 创建服务端心跳处理器。
     */
    public static NettyRpcHeartbeatHandler forServer() {
        return new NettyRpcHeartbeatHandler(false);
    }

    /**
     * 处理已经由 NettyRpcMessageDecoder 解码完成的 RpcMessage。
     *
     * <p>心跳消息在这里被消费，不会进入后面的业务 Handler；
     * 普通请求或响应则通过 ctx.fireChannelRead(msg) 原样向后传播。</p>
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if(!(msg instanceof RpcMessage)) {
            ctx.fireChannelRead(msg);
            return;
        }

        RpcMessage message = (RpcMessage) msg;
        byte messageType = message.getMessageType();

        if(clientSide && messageType == RpcConstants.HEARTBEAT_RESPONSE_TYPE) {
            if(!RpcConstants.PONG.equals(message.getData())){
                throw new IllegalArgumentException("Invalid heartbeat response");
            }
            // 收到 PONG 已经刷新 IdleStateHandler 的读时间，无需再保存任何状态。
            return;
        }

        if(!clientSide && messageType == RpcConstants.HEARTBEAT_REQUEST_TYPE) {
            if(!RpcConstants.PING.equals(message.getData())){
                throw new IllegalArgumentException("Invalid heartbeat request");
            }
            writeHeartbeatResponse(ctx);
            return;
        }

        if (messageType == RpcConstants.HEARTBEAT_REQUEST_TYPE
                || messageType == RpcConstants.HEARTBEAT_RESPONSE_TYPE) {
            /*
             * 客户端不应该收到 PING，服务端也不应该收到 PONG。
             * 角色不匹配说明对端协议实现错误，关闭连接比继续猜测更安全。
             */
            ctx.close();
            return;
        }

        // 普通 RPC 消息继续进入原来的客户端或服务端业务 Handler。
        ctx.fireChannelRead(msg);
    }

    /**
     * 接收 IdleStateHandler 产生的空闲事件。
     *
     * <p>客户端：</p>
     * <ul>
     *     <li>WRITER_IDLE：5 秒没有写数据，主动发送 PING。</li>
     *     <li>READER_IDLE：15 秒没有读到数据，关闭失效连接。</li>
     * </ul>
     *
     * <p>服务端：</p>
     * <ul>
     *     <li>READER_IDLE：15 秒没有收到请求或 PING，关闭沉默连接。</li>
     * </ul>
     *
     * ctx 表示当前 Handler 所在的上下文，可以获取 Channel、传播事件或关闭连接。
     *
     * evt 是收到的事件对象，可能是 IdleStateEvent，也可能是其他自定义事件。
     *
     * IdleState.READER_IDLE  长时间没有读取数据
     * IdleState.WRITER_IDLE  长时间没有写出数据
     * IdleState.ALL_IDLE     长时间既没有读，也没有写
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // 判断当前事件是不是 IdleStateHandler 产生的空闲事件。
        if(!(evt instanceof IdleStateEvent)) {
            // 不是空闲事件，当前心跳 Handler 不负责处理，交给后面的 Handler。
            super.userEventTriggered(ctx, evt);
            return;
        }

        // 将 Object 类型的 evt 转换为 IdleStateEvent，并获取具体的空闲类型。
        IdleState state = ((IdleStateEvent) evt).state();
        // 只有客户端遇到 WRITER_IDLE 时，才需要主动发送 PING。
        // 服务端不主动发 PING，所以还要判断 clientSide。
        if(clientSide && state == IdleState.WRITER_IDLE) {
            // 判断当前 TCP Channel 是否仍然处于可用状态。
            if(ctx.channel().isActive()) {
                // Channel 可用，构造并发送一个心跳请求 PING。
                writeHeartbeatRequest(ctx);
            }else{
                // 请求 Netty 关闭当前 Channel，释放连接相关资源。
                ctx.close();
            }
            // WRITER_IDLE 已经处理完，不再把它交给后面的 Handler。
            return;
        }

        // 检查是否长时间没有收到对端数据。
        // 这个判断同时适用于客户端和服务端。
        if(state == IdleState.READER_IDLE) {
            /*
             * 关闭 Channel 后会完成该 Channel 的 closeFuture。
             *
             * 客户端的 NettyChannelProvider 注册了 closeFuture 监听器，
             * 它会从连接缓存中删除已经关闭的旧 Channel。
             *
             * 下一次 RPC 调用发现缓存中没有可用 Channel 时，
             * 会按照原有流程重新建立连接。
             */

            // 关闭长时间没有收到数据的连接。
            ctx.close();

            // READER_IDLE 已经处理完，不继续传播。
            return;
        }

        // 到这里说明事件虽然是 IdleStateEvent，
        // 但不是当前代码处理的 WRITER_IDLE 或 READER_IDLE。
        // 例如将来启用 ALL_IDLE，就会走到这里。
        super.userEventTriggered(ctx,evt);
    }

    /**
     * 构造客户端 PING。
     *
     * <p>心跳没有消息体，因此 codec=0、compress=0、requestId=0。
     * writeAndFlush 从当前 Handler 向 Pipeline 前方传播，会先经过编码器，
     * 然后才写入 Socket。</p>
     */
    private void writeHeartbeatRequest(ChannelHandlerContext ctx) {
        RpcMessage heartbeatRequest = RpcMessage.builder()
                .messageType(RpcConstants.HEARTBEAT_REQUEST_TYPE)
                .codec(RpcConstants.NO_CODEC)
                .compress(RpcConstants.NO_COMPRESS)
                .requestId(RpcConstants.HEARTBEAT_REQUEST_ID)
                .data(RpcConstants.PING)
                .build();

        /**
         * ChannelFuture writeFuture =
         *         ctx.writeAndFlush(heartbeatRequest);
         *
         * writeFuture.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
         *
         * CLOSE_ON_FAILURE 是 Netty 已经定义好的监听器常量，可以近似理解为：
         * public void operationComplete(ChannelFuture future) {
         *     if (!future.isSuccess()) {
         *         future.channel().close();
         *     }
         *
         * 这里的“失败”只表示本地 Netty 写出失败，例如：
         * Channel 在写出前已经关闭；
         * TCP 连接被对端重置；
         * 出现 Broken pipe；
         * 编码器抛出异常；
         * EventLoop 已经关闭；
         * Socket 写入发生 I/O 异常；
         * 出站任务被取消或拒绝。
         */
        ctx.writeAndFlush(heartbeatRequest)
                .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    /**
     * 构造服务端 PONG，并沿用心跳保留字段。
     */
    private void writeHeartbeatResponse(ChannelHandlerContext ctx) {
        RpcMessage heartbeatResponse = RpcMessage.builder()
                .messageType(RpcConstants.HEARTBEAT_RESPONSE_TYPE)
                .codec(RpcConstants.NO_CODEC)
                .compress(RpcConstants.NO_COMPRESS)
                .requestId(RpcConstants.HEARTBEAT_REQUEST_ID)
                .data(RpcConstants.PONG)
                .build();

        ctx.writeAndFlush(heartbeatResponse)
                .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    /**
     * 心跳处理阶段发生异常时关闭当前 Channel。
     * 其他连接以及服务端监听 Channel 不受影响。
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }

}
