package com.github.hgdcoder.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RpcServiceNameBuilderTest {

    @Test
    void shouldEncodeGroupAndVersionAsUtf8Base64UrlWithoutPadding() {
        String serviceName = RpcServiceNameBuilder.build(
                "com.example.EchoService",
                "\u7814\u53d1\uffff",
                "v1.0"
        );

        assertEquals(
                "com.example.EchoService:56CU5Y-R77-_:djEuMA",
                serviceName
        );
        assertFalse(serviceName.contains("+"));
        assertFalse(serviceName.contains("/"));
        assertFalse(serviceName.contains("="));
    }

    @Test
    void shouldTreatNullAndEmptyGroupAndVersionAsEmptyFields() {
        assertEquals(
                "com.example.EchoService::",
                RpcServiceNameBuilder.build("com.example.EchoService", null, "")
        );
    }

    @Test
    void shouldKeepPreviouslyCollidingGroupAndVersionBoundariesDistinct() {
        String first = RpcServiceNameBuilder.build(
                "com.example.EchoService", "ab", "c");
        String second = RpcServiceNameBuilder.build(
                "com.example.EchoService", "a", "bc");

        assertNotEquals(first, second);
    }

    @Test
    void shouldRejectInvalidInterfaceNames() {
        assertInvalidInterfaceName(null);
        assertInvalidInterfaceName("  ");
        assertInvalidInterfaceName("com.example/Service");
        assertInvalidInterfaceName("com.example:Service");
        assertInvalidInterfaceName("com.example\0Service");
    }

    private void assertInvalidInterfaceName(String interfaceName) {
        assertThrows(
                IllegalArgumentException.class,
                () -> RpcServiceNameBuilder.build(interfaceName, "", "")
        );
    }
}
