package com.github.hgdcoder.remoting.codec;

import com.github.hgdcoder.compress.Compress;
import com.github.hgdcoder.extension.ExtensionLoader;
import com.github.hgdcoder.remoting.constants.RpcConstants;

/**
 * 把协议头中的 compress 编号转换为压缩算法。
 *
 * <p>协议只传一个 byte，而 Java 代码需要一个 Compress 对象，
 * 这个类就是两者之间的翻译层。</p>
 */
public class CompressResolver {
    private CompressResolver() {}

    /**
     * NO_COMPRESS 不需要创建对象，直接返回原字节；
     * 其他编号先通过 SPI 找到算法，再执行压缩。
     */
    static byte[] compress(byte compressType,byte[] bytes){
        if(compressType == RpcConstants.NO_COMPRESS){
            return bytes;
        }
        return resolve(compressType).compress(bytes);
    }

    /** 解码端必须使用协议头声明的同一种算法。 */
    static byte[] decompress(byte compressType, byte[] bytes) {
        if (compressType == RpcConstants.NO_COMPRESS) {
            return bytes;
        }
        return resolve(compressType).decompress(bytes);
    }

    /**
     * 只校验编号，不处理消息体。
     * 编码器和解码器会在分配、序列化之前调用它，尽早拒绝未知协议值。
     */
    static void validate(byte compressType) {
        if (compressType != RpcConstants.NO_COMPRESS) {
            resolve(compressType);
        }
    }

    private static Compress resolve(byte compressType) {
        final String extensionName;
        if (compressType == RpcConstants.GZIP_COMPRESS) {
            extensionName = "gzip";
        } else {
            throw new IllegalArgumentException(
                    "Unknown compress type: " + compressType
            );
        }
        return ExtensionLoader.getExtensionLoader(Compress.class)
                .getExtension(extensionName);
    }

}
