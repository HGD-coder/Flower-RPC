package com.github.hgdcoder.remoting.codec;

import com.github.hgdcoder.extension.ExtensionLoader;
import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.serialize.Serializer;

/**
 * 把协议头中的 codec 编号解析为 SPI 序列化器。
 */
public class SerializerResolver {
    private SerializerResolver() {
    }

    static Serializer resolve(byte codec){
        String extensionName;
        if(codec == RpcConstants.JDK_CODEC){
            extensionName = "jdk";
        }else if(codec == RpcConstants.KRYO_CODEC){
            extensionName = "kryo";
        }else{
            throw new IllegalArgumentException("Unknown codec: " + codec);
        }
        return ExtensionLoader.getExtensionLoader(Serializer.class)
                .getExtension(extensionName);
    }
}
