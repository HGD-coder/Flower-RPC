package com.github.hgdcoder.transport.netty.server;

import com.github.hgdcoder.enums.RpcStatusCode;
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

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Predicate;

/**
 * Netty EventLoop 只负责请求校验和任务提交。
 * 业务调用根据请求类型进入独立的快、慢业务线程池。
 */
@ChannelHandler.Sharable
public class NettyRpcServerHandler extends SimpleChannelInboundHandler<RpcMessage> {
    // 无状态请求执行器。Handler 被多个 Channel 共享，因此必须不保存单次请求数据。
    private final RpcRequestHandler requestHandler;

    private final ThreadPoolExecutor fastExecutor;
    private final ThreadPoolExecutor slowExecutor;

    /**
     * 返回 true 表示慢请求，false 表示快请求。
     *
     * 分类器由服务端提供，必须是快速、非阻塞、线程安全的实现。
     */
    private final Predicate<RpcRequest> slowRequestPredicate;

    /**
     * 注入服务调用器。实例随后会由多个连接共享，因此不保存连接或请求状态。
     */
    NettyRpcServerHandler(
            RpcRequestHandler requestHandler,
            ThreadPoolExecutor fastExecutor,
            ThreadPoolExecutor slowExecutor,
            Predicate<RpcRequest> slowRequestPredicate
    ) {
        this.requestHandler = Objects.requireNonNull(
                requestHandler,
                "requestHandler must not be null"
        );
        this.fastExecutor = Objects.requireNonNull(
                fastExecutor,
                "fastExecutor must not be null"
        );
        this.slowExecutor = Objects.requireNonNull(
                slowExecutor,
                "slowExecutor must not be null"
        );
        this.slowRequestPredicate = Objects.requireNonNull(
                slowRequestPredicate,
                "slowRequestPredicate must not be null"
        );
    }

    /**
     * 执行一条已经解码的 RPC 请求并异步写回响应。
     * 请求业务号写入 RpcResponse 供业务关联，协议请求号保留在 RpcMessage 中供客户端匹配 pending。
     * 调用时机是消息已通过入站帧和协议解码后，被转交到独立业务线程组。
     */
    @Override
    protected void channelRead0(
            ChannelHandlerContext ctx,
            RpcMessage message
    ) {
        if(message.getMessageType() != RpcConstants.REQUEST_TYPE || !(message.getData() instanceof RpcRequest)) {
            throw new IllegalArgumentException("Invalid RPC request message");
        }

        RpcRequest request = (RpcRequest) message.getData();

        /*
         * 不把整个 RpcMessage 带进异步任务。
         * 只保存构造响应所需的协议元数据。
         */
        int wireRequestId = message.getRequestId();
        byte codec = message.getCodec();
        byte compress = message.getCompress();

        boolean slowRequest;
        try{
            /*
             * 这段代码运行在 Netty worker EventLoop 中，
             * 分类器必须只做内存查询，不能进行任何阻塞操作。
             */
            slowRequest = slowRequestPredicate.test(request);
        }catch (RuntimeException classificationFailure){
            RpcResponse<?> response = RpcResponseFactory.failure(
                    request.getRequestId(),
                    classificationFailure
            );
            writeResponse(
                    ctx,
                    wireRequestId,
                    codec,
                    compress,
                    response
            );
            return;
        }

        ThreadPoolExecutor executor = slowRequest ? slowExecutor : fastExecutor;
        try{
            /*
             * 每个请求独立提交。
             * 同一个 Channel 的两个请求可以在不同业务线程上执行。
             */
            executor.execute(() -> invokeAndRespond(
                    ctx,
                    wireRequestId,
                    codec,
                    compress,
                    request
            ));
        }catch(RejectedExecutionException rejected) {
            /**
             * 队列已满时立即返回结构化过载响应
             * 不能使用 CallerRunsPolicy，否则 EventLoop 会执行业务代码。
             */
            RpcResponse<?> response = RpcResponse.fail(
                    RpcStatusCode.RESOURCE_EXHAUSTED,
                    request.getRequestId(),
                    slowRequest
                            ? "RPC slow executor is overloaded"
                            : "RPC fast executor is overloaded"
            );

            writeResponse(
                    ctx,
                    wireRequestId,
                    codec,
                    compress,
                    response
            );
        }
    }

    /**
     * 该方法运行在快或慢业务线程池中。
     */
    private void invokeAndRespond(
            ChannelHandlerContext ctx,
            int wireRequestId,
            byte codec,
            byte compress,
            RpcRequest request
    ) {
        RpcResponse<?> response;

        try{
            Object result = requestHandler.handle(request);
            response = RpcResponse.success(
                    result,
                    request.getRequestId()
            );
        }catch(RuntimeException invocationFailure) {
            response =  RpcResponseFactory.failure(
                    request.getRequestId(),
                    invocationFailure
            );
        }

        writeResponse(
                ctx,
                wireRequestId,
                codec,
                compress,
                response
        );
    }

    private void writeResponse(
            ChannelHandlerContext ctx,
            int wireRequestId,
            byte codec,
            byte compress,
            RpcResponse<?> response
    ) {
        RpcMessage responseMessage = RpcMessage.builder()
                .messageType(RpcConstants.RESPONSE_TYPE)
                .codec(codec)
                .compress(compress)
                .requestId(wireRequestId)
                .data(response)
                .build();

        /*
         * Netty 允许其他线程调用 writeAndFlush。
         * 实际出站编码和 Socket 写入会被转交给 Channel EventLoop。
         */
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
