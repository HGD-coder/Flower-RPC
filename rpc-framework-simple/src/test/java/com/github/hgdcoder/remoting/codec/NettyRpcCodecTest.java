package com.github.hgdcoder.remoting.codec;

import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.remoting.dto.RpcMessage;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Netty 编解码 Pipeline 的单元测试。
 * 使用 EmbeddedChannel 在内存中驱动入站和出站事件，既能直接断言协议字节，
 * 又无需真实 Socket，因此能稳定覆盖半包、粘包和非法帧等网络边界。
 */
class NettyRpcCodecTest {
    @Test
    void shouldRoundTripJdkAndKryoFrames() {
        // 同一协议格式应能按两种已注册序列化器往返，还原请求号和业务字段。
        assertRoundTrip(RpcConstants.JDK_CODEC, 101);
        assertRoundTrip(RpcConstants.KRYO_CODEC, 102);
    }

    @Test
    void shouldKeepProtocolRequestIdWrittenByCaller() {
        // 协议请求号用于客户端匹配 pending，编码器不得替换为业务 requestId。
        byte[] bytes = encode(requestMessage(RpcConstants.JDK_CODEC, 987654321));
        ByteBuf frame = Unpooled.wrappedBuffer(bytes);
        try {
            assertEquals(987654321, frame.getInt(12));
        } finally {
            frame.release();
        }
    }

    @Test
    void shouldHandleHalfPacket() {
        // 第一次入站不足一帧时不应产出消息，补齐剩余字节后才解码。
        byte[] bytes = encode(requestMessage(RpcConstants.KRYO_CODEC, 201));
        EmbeddedChannel channel = decoderChannel();
        try {
            assertFalse(channel.writeInbound(
                    Unpooled.wrappedBuffer(Arrays.copyOfRange(bytes, 0, 7))));
            assertNull(channel.readInbound());
            assertTrue(channel.writeInbound(
                    Unpooled.wrappedBuffer(Arrays.copyOfRange(bytes, 7, bytes.length))));
            RpcMessage decoded = channel.readInbound();
            assertEquals(201, decoded.getRequestId());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldSplitStickyPackets() {
        // 一次读取包含两帧时，长度字段解码器必须依次交付两条独立消息。
        byte[] first = encode(requestMessage(RpcConstants.JDK_CODEC, 301));
        byte[] second = encode(requestMessage(RpcConstants.KRYO_CODEC, 302));
        byte[] sticky = new byte[first.length + second.length];
        System.arraycopy(first, 0, sticky, 0, first.length);
        System.arraycopy(second, 0, sticky, first.length, second.length);

        EmbeddedChannel channel = decoderChannel();
        try {
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(sticky)));
            assertEquals(301, ((RpcMessage) channel.readInbound()).getRequestId());
            assertEquals(302, ((RpcMessage) channel.readInbound()).getRequestId());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldRejectIllegalMetadataAndOversizedFrame() {
        // 在业务处理器之前拒绝协议头篡改和超过最大帧长的数据，保护后续解码逻辑。
        byte[] illegalMagic = encode(requestMessage(RpcConstants.JDK_CODEC, 401));
        illegalMagic[0] = 0;
        EmbeddedChannel illegalChannel = decoderChannel();
        try {
            assertThrows(DecoderException.class,
                    () -> illegalChannel.writeInbound(Unpooled.wrappedBuffer(illegalMagic)));
        } finally {
            illegalChannel.finishAndReleaseAll();
        }

        ByteBuf oversized = Unpooled.buffer(9);
        oversized.writeBytes(RpcConstants.MAGIC_NUMBER);
        oversized.writeByte(RpcConstants.VERSION);
        oversized.writeInt(RpcConstants.MAX_FRAME_LENGTH + 1);
        EmbeddedChannel oversizedChannel = decoderChannel();
        try {
            assertThrows(DecoderException.class,
                    () -> oversizedChannel.writeInbound(oversized));
        } finally {
            oversizedChannel.finishAndReleaseAll();
        }
    }

    private void assertRoundTrip(byte codec, int requestId) {
        // 复用断言，覆盖协议头字节和经完整入站 Pipeline 还原的业务对象。
        RpcMessage original = requestMessage(codec, requestId);
        byte[] bytes = encode(original);
        assertArrayEquals(RpcConstants.MAGIC_NUMBER, Arrays.copyOf(bytes, 4));
        assertEquals(bytes.length, ByteBuffer.wrap(bytes, 5, 4).getInt());

        EmbeddedChannel channel = decoderChannel();
        try {
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(bytes)));
            RpcMessage decoded = channel.readInbound();
            RpcRequest request = (RpcRequest) decoded.getData();
            assertEquals(codec, decoded.getCodec());
            assertEquals(requestId, decoded.getRequestId());
            assertEquals("hello", request.getMethodName());
            assertEquals("Flower", request.getParameters()[0]);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private EmbeddedChannel decoderChannel() {
        // 按真实入站顺序组装 Pipeline，验证先切帧、后解码的协作关系。
        return new EmbeddedChannel(
                new NettyRpcFrameDecoder(),
                new NettyRpcMessageDecoder()
        );
    }

    private byte[] encode(RpcMessage message) {
        // 单独驱动出站编码器，方便检查写出的原始协议头和字节长度。
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRpcMessageEncoder());
        try {
            assertTrue(channel.writeOutbound(message));
            ByteBuf encoded = channel.readOutbound();
            try {
                byte[] bytes = new byte[encoded.readableBytes()];
                encoded.readBytes(bytes);
                return bytes;
            } finally {
                encoded.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private RpcMessage requestMessage(byte codec, int requestId) {
        // 构造最小的合法请求帧，requestId 故意同时用于验证协议层字段传递。
        RpcRequest request = RpcRequest.builder()
                .requestId("business-" + requestId)
                .interfaceName("com.example.HelloService")
                .methodName("hello")
                .parameters(new Object[]{"Flower"})
                .paramTypes(new Class<?>[]{String.class})
                .group("test")
                .version("1.0")
                .build();
        return RpcMessage.builder()
                .messageType(RpcConstants.REQUEST_TYPE)
                .codec(codec)
                .compress(RpcConstants.NO_COMPRESS)
                .requestId(requestId)
                .data(request)
                .build();
    }
}
