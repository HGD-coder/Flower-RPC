package com.github.hgdcoder.exception;

import com.github.hgdcoder.enums.RpcErrorMessageEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 common 模块中的统一 RPC 异常能够保留分类、细节和根因。 */
class RpcExceptionTest {

    @Test
    void shouldKeepErrorTypeDetailAndCause() {
        IllegalStateException cause =
                new IllegalStateException("root cause");

        RpcException exception = new RpcException(
                RpcErrorMessageEnum.SERVICE_INVOCATION_FAILURE,
                "HelloService#hello",
                cause
        );

        assertEquals(
                RpcErrorMessageEnum.SERVICE_INVOCATION_FAILURE,
                exception.getErrorType()
        );
        assertTrue(exception.getMessage().contains("HelloService#hello"));
        assertSame(cause, exception.getCause());
    }
}
