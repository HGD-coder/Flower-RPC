package com.github.hgdcoder.transport.netty.handler;

import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.remoting.dto.RpcMessage;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在内存 Channel 中验证客户端和服务端两种心跳角色。
 *
 * 测试直接触发 IdleStateEvent，不需要真的等待 5 秒或 15 秒，
 * 因而执行速度快，也不会受机器调度抖动影响。
 */
class NettyRpcHeartbeatHandlerTest {

    @Test
    void clientShouldSendPingWhenWriterIsIdle() {
        EmbeddedChannel channel = new EmbeddedChannel(
                NettyRpcHeartbeatHandler.forClient()
        );
        try {
            channel.pipeline().fireUserEventTriggered(
                    IdleStateEvent.WRITER_IDLE_STATE_EVENT
            );

            RpcMessage ping = channel.readOutbound();
            assertEquals(
                    RpcConstants.HEARTBEAT_REQUEST_TYPE,
                    ping.getMessageType()
            );
            assertEquals(RpcConstants.PING, ping.getData());
            assertEquals(RpcConstants.NO_CODEC, ping.getCodec());
            assertEquals(
                    RpcConstants.HEARTBEAT_REQUEST_ID,
                    ping.getRequestId()
            );
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void clientShouldConsumePong() {
        EmbeddedChannel channel = new EmbeddedChannel(
                NettyRpcHeartbeatHandler.forClient()
        );
        try {
            /*
             * PONG 在心跳 Handler 内被消费，不会继续进入后面的业务响应处理器。
             * 因此 writeInbound 返回 false。
             */
            assertFalse(channel.writeInbound(heartbeatResponse()));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void serverShouldReplyPongForPing() {
        EmbeddedChannel channel = new EmbeddedChannel(
                NettyRpcHeartbeatHandler.forServer()
        );
        try {
            // PING 被消费，所以入站队列中没有消息，writeInbound 返回 false。
            assertFalse(channel.writeInbound(heartbeatRequest()));

            RpcMessage pong = channel.readOutbound();
            assertEquals(
                    RpcConstants.HEARTBEAT_RESPONSE_TYPE,
                    pong.getMessageType()
            );
            assertEquals(RpcConstants.PONG, pong.getData());
            assertEquals(RpcConstants.NO_CODEC, pong.getCodec());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldPassBusinessMessageToNextHandler() {
        EmbeddedChannel channel = new EmbeddedChannel(
                NettyRpcHeartbeatHandler.forServer()
        );
        try {
            RpcMessage businessMessage = RpcMessage.builder()
                    .messageType(RpcConstants.REQUEST_TYPE)
                    .codec(RpcConstants.KRYO_CODEC)
                    .compress(RpcConstants.NO_COMPRESS)
                    .requestId(1)
                    .data("business-message-placeholder")
                    .build();

            /*
             * 心跳 Handler 不处理普通 RPC 消息，所以消息必须原样向后传播。
             * 测试 Pipeline 没有再放业务 Handler，因此能从入站队列读回它。
             */
            assertTrue(channel.writeInbound(businessMessage));
            assertSame(businessMessage, channel.readInbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void bothSidesShouldCloseOnReaderTimeout() {
        assertReaderTimeoutCloses(
                NettyRpcHeartbeatHandler.forClient()
        );
        assertReaderTimeoutCloses(
                NettyRpcHeartbeatHandler.forServer()
        );
    }

    private void assertReaderTimeoutCloses(
            NettyRpcHeartbeatHandler heartbeatHandler) {
        EmbeddedChannel channel = new EmbeddedChannel(heartbeatHandler);
        try {
            channel.pipeline().fireUserEventTriggered(
                    IdleStateEvent.READER_IDLE_STATE_EVENT
            );
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private RpcMessage heartbeatRequest() {
        return RpcMessage.builder()
                .messageType(RpcConstants.HEARTBEAT_REQUEST_TYPE)
                .codec(RpcConstants.NO_CODEC)
                .compress(RpcConstants.NO_COMPRESS)
                .requestId(RpcConstants.HEARTBEAT_REQUEST_ID)
                .data(RpcConstants.PING)
                .build();
    }

    private RpcMessage heartbeatResponse() {
        return RpcMessage.builder()
                .messageType(RpcConstants.HEARTBEAT_RESPONSE_TYPE)
                .codec(RpcConstants.NO_CODEC)
                .compress(RpcConstants.NO_COMPRESS)
                .requestId(RpcConstants.HEARTBEAT_REQUEST_ID)
                .data(RpcConstants.PONG)
                .build();
    }
}
