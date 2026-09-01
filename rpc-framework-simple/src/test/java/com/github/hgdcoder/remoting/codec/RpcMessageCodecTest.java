package com.github.hgdcoder.remoting.codec;

import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.remoting.dto.RpcMessage;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RpcMessageCodecTest {
    private final RpcMessageCodec codec = new RpcMessageCodec();

    @Test
    void shouldRoundTripJdkFrame() throws Exception {
        assertRoundTrip(RpcConstants.JDK_CODEC);
    }

    @Test
    void shouldRoundTripKryoFrame() throws Exception {
        assertRoundTrip(RpcConstants.KRYO_CODEC);
    }

    @Test
    void shouldRoundTripHessianFrame() throws Exception {
        assertRoundTrip(RpcConstants.HESSIAN_CODEC);
    }

    @Test
    void shouldRoundTripProtostuffFrame() throws Exception {
        assertRoundTrip(RpcConstants.PROTOSTUFF_CODEC);
    }

    @Test
    void shouldRejectOversizedFrameBeforeAllocatingBody() throws Exception {
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        DataOutputStream dataOut = new DataOutputStream(byteOut);
        dataOut.write(RpcConstants.MAGIC_NUMBER);
        dataOut.writeByte(RpcConstants.VERSION);
        dataOut.writeInt(RpcConstants.MAX_FRAME_LENGTH + 1);

        DataInputStream dataIn = new DataInputStream(
                new ByteArrayInputStream(byteOut.toByteArray())
        );
        assertThrows(IllegalArgumentException.class, () -> codec.decode(dataIn));
    }

    private void assertRoundTrip(byte codecType) throws Exception {
        RpcRequest request = RpcRequest.builder()
                .requestId("business-request-id")
                .interfaceName("com.example.HelloService")
                .methodName("hello")
                .parameters(new Object[]{"Flower", 9})
                .paramTypes(new Class<?>[]{String.class, int.class})
                .group("test")
                .version("1.0")
                .build();
        RpcMessage message = RpcMessage.builder()
                .messageType(RpcConstants.REQUEST_TYPE)
                .codec(codecType)
                .compress(RpcConstants.NO_COMPRESS)
                .requestId(1001)
                .data(request)
                .build();

        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        codec.encode(new DataOutputStream(byteOut), message);

        RpcMessage decoded = codec.decode(new DataInputStream(
                new ByteArrayInputStream(byteOut.toByteArray())
        ));
        RpcRequest decodedRequest = (RpcRequest) decoded.getData();

        assertEquals(codecType, decoded.getCodec());
        assertEquals(1001, decoded.getRequestId());
        assertEquals("hello", decodedRequest.getMethodName());
        assertEquals("Flower", decodedRequest.getParameters()[0]);
        assertEquals(9, decodedRequest.getParameters()[1]);
    }
}