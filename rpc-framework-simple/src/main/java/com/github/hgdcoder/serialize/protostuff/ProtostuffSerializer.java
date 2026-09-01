package com.github.hgdcoder.serialize.protostuff;

import com.github.hgdcoder.serialize.Serializer;
import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;

/**
 * Protostuff 序列化器。
 *
 * <p>RuntimeSchema 会根据 Java 类在运行时生成 Schema，
 * 因此当前版本不需要手写 .proto 文件。</p>
 *
 * <p>LinkedBuffer 不是线程安全的，而 Serializer 会被 SPI
 * 缓存成单例，所以使用 ThreadLocal 为每个线程保存独立缓冲区。</p>
 */
public class ProtostuffSerializer implements Serializer {
    private static final ThreadLocal<LinkedBuffer> BUFFER_HOLDER =
            ThreadLocal.withInitial(
                    () -> LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE)
            );

    @Override
    public byte[] serialize(Object object) {
        if (object == null) {
            throw new IllegalArgumentException("object must not be null");
        }

        @SuppressWarnings("unchecked")
        Schema<Object> schema =
                (Schema<Object>) RuntimeSchema.getSchema(
                        object.getClass()
                );

        LinkedBuffer buffer = BUFFER_HOLDER.get();

        try {
            return ProtostuffIOUtil.toByteArray(
                    object,
                    schema,
                    buffer
            );
        } catch (Exception e) {
            throw new RuntimeException(
                    "Protostuff serialize failed: "
                            + object.getClass().getName(),
                    e
            );
        } finally {
            /*
             * 只清空内容，不删除 ThreadLocal。
             * 下一次序列化继续复用缓冲区，减少对象创建。
             */
            buffer.clear();
        }
    }

    @Override
    public <T> T deserialize(byte[] data, Class<T> targetClass) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (targetClass == null) {
            throw new IllegalArgumentException("targetClass must not be null");
        }

        try {
            Schema<T> schema = RuntimeSchema.getSchema(targetClass);

            /*
             * RpcRequest 和 RpcResponse 都有无参构造函数，
             * RuntimeSchema 可以创建空对象并把字段合并进去。
             */
            T object = schema.newMessage();

            ProtostuffIOUtil.mergeFrom(
                    data,
                    object,
                    schema
            );

            return object;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Protostuff deserialize failed: "
                            + targetClass.getName(),
                    e
            );
        }
    }
}
