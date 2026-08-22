package com.github.hgdcoder.remoting.codec;

import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.remoting.dto.RpcMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 V11 心跳在网络协议层的表示。
 *
 * 这里使用 EmbeddedChannel 在内存中执行真实 Netty 编解码器，
 * 不启动 Socket，也能检查心跳是否确实只有 16 字节协议头。
 */
class NettyHeartbeatCodecTest {

    @Test
    void shouldEncodeAndDecodeHeaderOnlyHeartbeatFrames() {
        assertHeartbeatRoundTrip(
                RpcConstants.HEARTBEAT_REQUEST_TYPE,
                RpcConstants.PING
        );
        assertHeartbeatRoundTrip(
                RpcConstants.HEARTBEAT_RESPONSE_TYPE,
                RpcConstants.PONG
        );
    }

    private void assertHeartbeatRoundTrip(byte messageType, String expectedData) {
        RpcMessage heartbeat = RpcMessage.builder()
                .messageType(messageType)
                .codec(RpcConstants.NO_CODEC)
                .compress(RpcConstants.NO_COMPRESS)
                .requestId(RpcConstants.HEARTBEAT_REQUEST_ID)
                .data(expectedData)
                .build();

        EmbeddedChannel encoderChannel =
                new EmbeddedChannel(new NettyRpcMessageEncoder());
        EmbeddedChannel decoderChannel = new EmbeddedChannel(
                new NettyRpcFrameDecoder(),
                new NettyRpcMessageDecoder()
        );

        try {
            assertTrue(encoderChannel.writeOutbound(heartbeat));
            ByteBuf encoded = encoderChannel.readOutbound();

            /*
             * 心跳没有消息体，所以 fullLength 必须正好等于 HEADER_LENGTH。
             * 下标 5 是 fullLength 字段的起点，下标 9 是 messageType。
             */
            assertEquals(RpcConstants.HEADER_LENGTH, encoded.readableBytes());
            assertEquals(RpcConstants.HEADER_LENGTH, encoded.getInt(5));
            assertEquals(messageType, encoded.getByte(9));
            assertEquals(RpcConstants.NO_CODEC, encoded.getByte(10));
            assertEquals(RpcConstants.NO_COMPRESS, encoded.getByte(11));
            assertEquals(RpcConstants.HEARTBEAT_REQUEST_ID, encoded.getInt(12));

            /*
             * 把编码结果交给真实入站 Pipeline。
             * writeInbound 接管 ByteBuf 的生命周期，因此这里不要再手动 release。
             */
            assertTrue(decoderChannel.writeInbound(encoded));
            RpcMessage decoded = decoderChannel.readInbound();
            assertEquals(messageType, decoded.getMessageType());
            assertEquals(expectedData, decoded.getData());
            assertEquals(RpcConstants.NO_CODEC, decoded.getCodec());
            assertEquals(
                    RpcConstants.HEARTBEAT_REQUEST_ID,
                    decoded.getRequestId()
            );
        } finally {
            encoderChannel.finishAndReleaseAll();
            decoderChannel.finishAndReleaseAll();
        }
    }
}
