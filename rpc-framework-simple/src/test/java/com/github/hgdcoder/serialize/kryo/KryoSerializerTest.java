package com.github.hgdcoder.serialize.kryo;

import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.serialize.Serializer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KryoSerializerTest {
    private final Serializer serializer = new KryoSerializer();

    @Test
    void shouldRoundTripRpcRequest() {
        RpcRequest request = createRequest("single", 7);

        byte[] bytes = serializer.serialize(request);
        RpcRequest decoded = serializer.deserialize(bytes, RpcRequest.class);

        assertEquals("single", decoded.getRequestId());
        assertEquals(7, decoded.getParameters()[0]);
    }

    @Test
    void shouldUseIndependentKryoInstanceForEachThread() throws Exception {
        int threadCount = 8;
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final int value = i;
            new Thread(() -> {
                try {
                    RpcRequest decoded = serializer.deserialize(
                            serializer.serialize(createRequest("thread-" + value, value)),
                            RpcRequest.class
                    );
                    if (decoded.getRequestId().equals("thread-" + value)
                            && ((Integer) decoded.getParameters()[0]) == value) {
                        success.incrementAndGet();
                    }
                } finally {
                    done.countDown();
                }
            }).start();
        }

        done.await();
        assertEquals(threadCount, success.get());
    }

    private RpcRequest createRequest(String requestId, int value) {
        return RpcRequest.builder()
                .requestId(requestId)
                .interfaceName("com.example.HelloService")
                .methodName("hello")
                .parameters(new Object[]{value})
                .paramTypes(new Class<?>[]{int.class})
                .group("test")
                .version("1.0")
                .build();
    }
}