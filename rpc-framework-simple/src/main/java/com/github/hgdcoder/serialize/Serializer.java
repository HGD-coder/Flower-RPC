package com.github.hgdcoder.serialize;

/**
 * 序列化器统一接口。
 *
 * V8 先接入 JDK 实现；后续添加 Kryo、Hessian 或 Protostuff 时，
 * 编解码器不需要再关心具体序列化细节。
 */
public interface Serializer {
    byte[] serialize(Object object);

    <T> T deserialize(byte[] data, Class<T> targetClass);
}
