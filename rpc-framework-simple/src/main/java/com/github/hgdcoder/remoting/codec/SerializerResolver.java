package com.github.hgdcoder.remoting.codec;

import com.github.hgdcoder.enums.SerializationTypeEnum;
import com.github.hgdcoder.extension.ExtensionLoader;
import com.github.hgdcoder.serialize.Serializer;

/**
 * 把协议头中的 codec 编号解析为 SPI 序列化器。
 */
public class SerializerResolver {
    private SerializerResolver() {
    }

    /**
     * 根据协议头中的 codec 选择序列化器。
     *
     * 例如：
     * codec=3
     * -> HESSIAN
     * -> name="hessian"
     * -> HessianSerializer
     */
    static Serializer resolve(byte codec){
        SerializationTypeEnum type = SerializationTypeEnum.fromCode(codec);

        return ExtensionLoader.getExtensionLoader(Serializer.class)
                .getExtension(type.getName());
    }
}
