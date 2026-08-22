package com.github.hgdcoder.compress;


import com.github.hgdcoder.extension.SPI;

/**
 * 消息体压缩算法的统一扩展接口。
 *
 * <p>编码顺序固定为：Java 对象 -> 序列化字节 -> 压缩字节；
 * 解码顺序必须完全相反：压缩字节 -> 解压字节 -> Java 对象。</p>
 */
@SPI
public interface Compress {
    /**
     * 压缩序列化后的消息体。
     *
     * @param bytes 序列化器产生的原始字节
     * @return 可以写入网络的压缩字节
     */
    byte[] compress(byte[] bytes);

    /**
     * 解压从网络读取的消息体。
     *
     * @param bytes 网络帧中的压缩字节
     * @return 可以交给序列化器反序列化的原始字节
     */
    byte[] decompress(byte[] bytes);
}
