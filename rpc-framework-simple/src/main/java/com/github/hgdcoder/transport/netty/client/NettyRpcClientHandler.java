package com.github.hgdcoder.transport.netty.client;


import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.remoting.dto.RpcMessage;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * 接收响应，并唤醒对应的同步调用线程。
 * 位于客户端 Pipeline 的最后一个入站处理器，前面的帧解码和消息解码已把网络字节转换为 RpcMessage。
 */
public class NettyRpcClientHandler extends SimpleChannelInboundHandler<RpcMessage> {
    // 所有连接共享的 pending 表；其并发容器负责协调 Netty I/O 线程与调用线程。
    private final UnprocessedRequests unprocessedRequests;

    /**
     * 为每条客户端连接处理响应，但共用同一 pending 表，以支持连接缓存下的并发调用。
     */
    NettyRpcClientHandler(final UnprocessedRequests unprocessedRequests) {
        this.unprocessedRequests = unprocessedRequests;
    }

    /**
     * 接收一条已解码响应，并按协议请求号完成调用线程正在等待的 Future。
     * 不匹配的消息说明 Pipeline 或对端协议异常，交给 Netty 异常路径关闭连接。
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcMessage message) {
        if (message.getMessageType() != RpcConstants.RESPONSE_TYPE
                || !((message.getData()) instanceof RpcResponse)) {
            throw new IllegalArgumentException("Invalid RPC response message");
        }
        unprocessedRequests.complete(
                message.getRequestId(),
                (RpcResponse<?>) message.getData()
        );
    }

    /**
     * 连接变为非活跃时，失败该连接上的全部 pending 请求，避免调用线程无限等待。
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        unprocessedRequests.failChannel(
                ctx.channel(),
                new IllegalStateException("RPC channel closed: " + ctx.channel().remoteAddress())
        );
        super.channelInactive(ctx);
    }

    /**
     * 入站异常先传播给关联请求，再关闭连接；随后 channelInactive 会再次检查，但已清理的条目不会重复完成。
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        unprocessedRequests.failChannel(ctx.channel(), cause);
        ctx.close();
    }
}
