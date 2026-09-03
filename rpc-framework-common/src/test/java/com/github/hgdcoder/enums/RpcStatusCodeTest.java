package com.github.hgdcoder.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RpcStatusCodeTest {

    @Test
    void shouldResolvePublishedCode() {
        assertEquals(RpcStatusCode.OK, RpcStatusCode.fromCode(0));
        assertEquals(RpcStatusCode.NOT_FOUND, RpcStatusCode.fromCode(5));
        assertEquals(RpcStatusCode.INTERNAL, RpcStatusCode.fromCode(13));
    }

    @Test
    void shouldRejectUnknownCode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RpcStatusCode.fromCode(999)
        );
    }
}
