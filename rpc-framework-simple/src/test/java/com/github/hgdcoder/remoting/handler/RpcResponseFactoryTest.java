package com.github.hgdcoder.remoting.handler;

import com.github.hgdcoder.enums.RpcStatusCode;
import com.github.hgdcoder.exception.RpcServiceException;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RpcResponseFactoryTest {

    @Test
    void shouldReturnSafeBusinessMessage() {
        RpcResponse<Object> response = RpcResponseFactory.failure(
                "request-1",
                new InvocationTargetException(
                        new RpcServiceException(
                                RpcStatusCode.NOT_FOUND,
                                "订单不存在"
                        )
                )
        );

        assertEquals(RpcStatusCode.NOT_FOUND.getCode(), response.getCode());
        assertEquals("request-1", response.getRequestId());
        assertEquals("订单不存在", response.getMessage());
        assertFalse(response.isSuccess());
    }

    @Test
    void shouldHideUnexpectedInternalMessage() {
        RpcResponse<Object> response = RpcResponseFactory.failure(
                "request-2",
                new IllegalStateException("database password=secret")
        );

        assertEquals(RpcStatusCode.INTERNAL.getCode(), response.getCode());
        assertEquals(RpcStatusCode.INTERNAL.getMessage(), response.getMessage());
    }

    @Test
    void shouldMapTimeoutToDeadlineExceeded() {
        RpcResponse<Object> response = RpcResponseFactory.failure(
                "request-3",
                new TimeoutException("too slow")
        );

        assertEquals(
                RpcStatusCode.DEADLINE_EXCEEDED.getCode(),
                response.getCode()
        );
    }
}
