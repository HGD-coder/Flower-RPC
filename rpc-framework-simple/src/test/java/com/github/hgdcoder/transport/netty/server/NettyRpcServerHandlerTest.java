package com.github.hgdcoder.transport.netty.server;

import com.github.hgdcoder.enums.RpcStatusCode;
import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.remoting.dto.RpcMessage;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.remoting.handler.RpcRequestHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NettyRpcServerHandlerTest {

    @Test
    void shouldReturnResourceExhaustedWhenFastExecutorRejects() {
        assertRejectedRequest(false, "RPC fast executor is overloaded");
    }

    @Test
    void shouldReturnResourceExhaustedWhenSlowExecutorRejects() {
        assertRejectedRequest(true, "RPC slow executor is overloaded");
    }

    private static void assertRejectedRequest(
            boolean slowRequest,
            String expectedMessage
    ) {
        AtomicBoolean businessInvoked = new AtomicBoolean();
        RpcRequestHandler requestHandler = new RpcRequestHandler(null) {
            @Override
            public Object handle(RpcRequest rpcRequest) {
                businessInvoked.set(true);
                return "unexpected";
            }
        };
        ThreadPoolExecutor rejectingExecutor = rejectingExecutor();
        EmbeddedChannel channel = new EmbeddedChannel(
                new NettyRpcServerHandler(
                        requestHandler,
                        rejectingExecutor,
                        rejectingExecutor,
                        request -> slowRequest
                )
        );

        try {
            RpcRequest request = RpcRequest.builder()
                    .requestId("business-request-17")
                    .interfaceName("com.example.EchoService")
                    .methodName("echo")
                    .parameters(new Object[]{"value"})
                    .paramTypes(new Class<?>[]{String.class})
                    .group("")
                    .version("")
                    .build();
            RpcMessage message = RpcMessage.builder()
                    .messageType(RpcConstants.REQUEST_TYPE)
                    .codec(RpcConstants.KRYO_CODEC)
                    .compress(RpcConstants.NO_COMPRESS)
                    .requestId(73)
                    .data(request)
                    .build();

            assertFalse(channel.writeInbound(message));

            RpcMessage responseMessage = channel.readOutbound();
            assertNotNull(responseMessage);
            assertEquals(RpcConstants.RESPONSE_TYPE, responseMessage.getMessageType());
            assertEquals(RpcConstants.KRYO_CODEC, responseMessage.getCodec());
            assertEquals(RpcConstants.NO_COMPRESS, responseMessage.getCompress());
            assertEquals(73, responseMessage.getRequestId());

            RpcResponse<?> response = (RpcResponse<?>) responseMessage.getData();
            assertEquals("business-request-17", response.getRequestId());
            assertEquals(RpcStatusCode.RESOURCE_EXHAUSTED.getCode(), response.getCode());
            assertEquals(expectedMessage, response.getMessage());
            assertFalse(businessInvoked.get());
        } finally {
            channel.finishAndReleaseAll();
            rejectingExecutor.shutdownNow();
        }
    }

    private static ThreadPoolExecutor rejectingExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.shutdown();
        return executor;
    }
}
