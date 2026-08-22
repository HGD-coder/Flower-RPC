package com.github.hgdcoder.remoting.codec;

import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.remoting.dto.RpcMessage;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在内存 Pipeline 中验证 V12 的“序列化 -> GZIP -> 解压 -> 反序列化”闭环。
 */
class NettyRpcCompressionTest {

    @Test
    void shouldRoundTripGzipBusinessFrame() {
        String payload = repeatedText('F', 32 * 1024);
        RpcMessage gzipMessage = requestMessage(
                RpcConstants.GZIP_COMPRESS,
                payload
        );
        RpcMessage plainMessage = requestMessage(
                RpcConstants.NO_COMPRESS,
                payload
        );

        byte[] gzipFrame = encode(gzipMessage);
        byte[] plainFrame = encode(plainMessage);

        // 协议头下标 11 保存压缩算法编号。
        assertEquals(RpcConstants.GZIP_COMPRESS, gzipFrame[11]);
        // 大量重复内容启用 GZIP 后，网络帧应显著变小。
        assertTrue(gzipFrame.length < plainFrame.length);

        EmbeddedChannel decoder = decoderChannel();
        try {
            assertTrue(decoder.writeInbound(Unpooled.wrappedBuffer(gzipFrame)));
            RpcMessage decoded = decoder.readInbound();
            RpcRequest request = (RpcRequest) decoded.getData();

            assertEquals(RpcConstants.GZIP_COMPRESS, decoded.getCompress());
            assertEquals(payload, request.getParameters()[0]);
        } finally {
            decoder.finishAndReleaseAll();
        }
    }

    @Test
    void shouldRejectUnknownCompressTypeBeforeBusinessHandler() {
        byte[] frame = encode(requestMessage(
                RpcConstants.NO_COMPRESS,
                "Flower"
        ));
        // 模拟对端发送当前协议不认识的压缩编号。
        frame[11] = 99;

        EmbeddedChannel decoder = decoderChannel();
        try {
            assertThrows(
                    DecoderException.class,
                    () -> decoder.writeInbound(Unpooled.wrappedBuffer(frame))
            );
        } finally {
            decoder.finishAndReleaseAll();
        }
    }

    private EmbeddedChannel decoderChannel() {
        return new EmbeddedChannel(
                new NettyRpcFrameDecoder(),
                new NettyRpcMessageDecoder()
        );
    }

    private byte[] encode(RpcMessage message) {
        EmbeddedChannel encoder =
                new EmbeddedChannel(new NettyRpcMessageEncoder());
        try {
            assertTrue(encoder.writeOutbound(message));
            ByteBuf frame = encoder.readOutbound();
            try {
                byte[] bytes = new byte[frame.readableBytes()];
                frame.readBytes(bytes);
                return bytes;
            } finally {
                frame.release();
            }
        } finally {
            encoder.finishAndReleaseAll();
        }
    }

    private RpcMessage requestMessage(byte compress, String payload) {
        RpcRequest request = RpcRequest.builder()
                .requestId("v12-business-request")
                .interfaceName("com.example.HelloService")
                .methodName("hello")
                .parameters(new Object[]{payload})
                .paramTypes(new Class<?>[]{String.class})
                .group("test")
                .version("1.0")
                .build();

        return RpcMessage.builder()
                .messageType(RpcConstants.REQUEST_TYPE)
                .codec(RpcConstants.KRYO_CODEC)
                .compress(compress)
                .requestId(1201)
                .data(request)
                .build();
    }

    private String repeatedText(char value, int length) {
        char[] chars = new char[length];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
