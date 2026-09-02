package com.github.hgdcoder.serialize;

import com.github.hgdcoder.enums.RpcErrorMessageEnum;
import com.github.hgdcoder.exception.SerializeException;
import com.github.hgdcoder.serialize.jdk.JdkSerializer;
import org.junit.jupiter.api.Test;

import java.io.NotSerializableException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证具体序列化实现把底层失败转换为统一的序列化异常。 */
class SerializerExceptionTest {

    @Test
    void shouldWrapJdkSerializationFailure() {
        // Object 没有实现 Serializable，JDK 序列化必定失败。
        SerializeException exception = assertThrows(
                SerializeException.class,
                () -> new JdkSerializer().serialize(new Object())
        );

        assertEquals(
                RpcErrorMessageEnum.SERIALIZATION_FAILURE,
                exception.getErrorType()
        );
        assertTrue(exception.getCause() instanceof NotSerializableException);
    }
}
