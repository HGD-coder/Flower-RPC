package com.github.hgdcoder.proxy;

import com.github.hgdcoder.enums.RpcStatusCode;
import com.github.hgdcoder.exception.RpcRemoteException;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.transport.RpcRequestTransport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RpcClientProxyErrorTest {

    @Test
    void shouldRestoreRemoteFailureAsTypedException() {
        RpcRequestTransport transport = request -> CompletableFuture.completedFuture(
                RpcResponse.fail(
                        RpcStatusCode.NOT_FOUND,
                        request.getRequestId(),
                        "用户不存在"
                )
        );

        UserService service = new RpcClientProxy(
                transport, "test", "1.0"
        ).getProxy(UserService.class);

        RpcRemoteException exception = assertThrows(
                RpcRemoteException.class,
                () -> service.findName(7L)
        );

        assertEquals(RpcStatusCode.NOT_FOUND, exception.getStatusCode());
        assertNotNull(exception.getRequestId());
        assertEquals("用户不存在", exception.getMessage());
    }

    private interface UserService {
        String findName(long userId);
    }
}
