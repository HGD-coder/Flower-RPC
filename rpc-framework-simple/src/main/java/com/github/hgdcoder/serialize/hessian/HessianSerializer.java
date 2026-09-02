package com.github.hgdcoder.serialize.hessian;

import com.github.hgdcoder.enums.RpcErrorMessageEnum;
import com.github.hgdcoder.exception.SerializeException;
import com.github.hgdcoder.serialize.Serializer;
import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Hessian 序列化器。
 *
 * <p>Hessian 是一种二进制对象序列化格式。
 * 相比 JDK 序列化，它不要求业务类实现 Serializable。</p>
 *
 * <p>Serializer 会被 SPI 缓存成单例，但本实现没有共享可变状态，
 * 每次调用都创建独立输入输出对象，因此可以安全地并发调用。</p>
 */
public class HessianSerializer implements Serializer {
    @Override
    public byte[] serialize(Object object) {
        if (object == null) {
            throw new IllegalArgumentException("object must not be null");
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            HessianOutput hessianOutput = new HessianOutput(outputStream);

            hessianOutput.writeObject(object);
            hessianOutput.flush();

            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new SerializeException(
                    RpcErrorMessageEnum.SERIALIZATION_FAILURE,
                    "hessian",
                    e
            );
        }
    }

    @Override
    public <T> T deserialize(
            byte[] data,
            Class<T> targetClass
    ) {
        if (data == null) {
            throw new IllegalArgumentException(
                    "data must not be null"
            );
        }
        if (targetClass == null) {
            throw new IllegalArgumentException(
                    "targetClass must not be null"
            );
        }

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(data)) {

            HessianInput hessianInput = new HessianInput(inputStream);

            Object value = hessianInput.readObject();
            return targetClass.cast(value);
        } catch (Exception e) {
            throw new SerializeException(
                    RpcErrorMessageEnum.DESERIALIZATION_FAILURE,
                    "hessian -> " + targetClass.getName(),
                    e
            );
        }
    }
}
