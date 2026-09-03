package com.github.hgdcoder.transport.netty.server;

import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.remoting.dto.RpcMessage;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.remoting.handler.RpcRequestHandler;
import com.github.hgdcoder.remoting.handler.RpcResponseFactory;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * 在独立业务线程组中执行服务调用，并回写同一协议请求号。
 * 它是服务端 Pipeline 的最后一个入站处理器；由 NettyRpcServer 指定到 businessGroup，
 * 所以耗时的反射业务不会阻塞 worker I/O 线程处理其他连接。
 */
@ChannelHandler.Sharable
public class NettyRpcServerHandler extends SimpleChannelInboundHandler<RpcMessage> {
    // 无状态请求执行器。Handler 被多个 Channel 共享，因此必须不保存单次请求数据。
    private final RpcRequestHandler requestHandler;

    /**
     * 注入服务调用器。实例随后会由多个连接共享，因此不保存连接或请求状态。
     */
    NettyRpcServerHandler(RpcRequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }

    /**
     * 执行一条已经解码的 RPC 请求并异步写回响应。
     * 请求业务号写入 RpcResponse 供业务关联，协议请求号保留在 RpcMessage 中供客户端匹配 pending。
     * 调用时机是消息已通过入站帧和协议解码后，被转交到独立业务线程组。
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcMessage message) {
        if(message.getMessageType() != RpcConstants.REQUEST_TYPE || !(message.getData() instanceof RpcRequest)) {
            throw new IllegalArgumentException("Invalid RPC request message");
        }

        RpcRequest request = (RpcRequest) message.getData();
        RpcResponse<?> response;
        try{
            response = RpcResponse.success(
                    requestHandler.handle(request),
                    request.getRequestId()
            );
        }catch(RuntimeException e){
            response = RpcResponseFactory.failure(
                    request.getRequestId(),
                    e
            );
        }

        RpcMessage responseMessage = RpcMessage.builder()
                .messageType(RpcConstants.RESPONSE_TYPE)
                //响应沿用请求协商出的协议元数据
                .codec(message.getCodec())
                .compress(message.getCompress())
                .requestId(message.getRequestId())
                .data(response)
                .build();
        ctx.writeAndFlush(responseMessage)
                .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    @Override
    /**
     * Pipeline 出现无法处理的异常时关闭当前连接，避免继续在协议状态不可信的 Channel 上通信。
     */
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
