package com.github.hgdcoder.proxy;

import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.transport.RpcRequestTransport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证同步、异步代理共享同一个远程服务名，并保持各自返回语义。 */
class RpcClientProxyAsyncTest {

    @Test
    void shouldReturnFutureWithoutBlockingCaller() {
        CompletableFuture<RpcResponse<Object>> transportFuture =
                new CompletableFuture<>();
        RpcRequest[] capturedRequest = new RpcRequest[1];
        RpcRequestTransport transport = request -> {
            capturedRequest[0] = request;
            return transportFuture;
        };

        AsyncGreetingService service = new RpcClientProxy(
                transport, "test", "1.0"
        ).getAsyncProxy(AsyncGreetingService.class, GreetingService.class);

        CompletableFuture<String> resultFuture = service.greet("Flower");

        // transportFuture 尚未完成，异步方法已经把 resultFuture 返回给调用者。
        assertFalse(resultFuture.isDone());
        assertEquals(
                GreetingService.class.getName(),
                capturedRequest[0].getInterfaceName()
        );

        transportFuture.complete(RpcResponse.success(
                "Hello, Flower",
                capturedRequest[0].getRequestId()
        ));
        assertEquals("Hello, Flower", resultFuture.join());
    }

    @Test
    void shouldKeepSynchronousProxyUsage() {
        RpcRequestTransport transport = request -> CompletableFuture.completedFuture(
                RpcResponse.success("Hello, sync", request.getRequestId())
        );

        GreetingService service = new RpcClientProxy(
                transport, "test", "1.0"
        ).getProxy(GreetingService.class);

        assertEquals("Hello, sync", service.greet("sync"));
    }

    @Test
    void shouldRejectInvalidAsyncMirror() {
        RpcRequestTransport transport = request -> new CompletableFuture<>();
        RpcClientProxy factory = new RpcClientProxy(transport, "test", "1.0");

        assertThrows(
                IllegalArgumentException.class,
                () -> factory.getAsyncProxy(BrokenAsyncService.class, GreetingService.class)
        );
    }

    private interface GreetingService {
        String greet(String name);
    }

    private interface AsyncGreetingService {
        CompletableFuture<String> greet(String name);
    }

    private interface BrokenAsyncService {
        String greet(String name);
    }
}
