package com.github.hgdcoder.compress.gzip;

import com.github.hgdcoder.compress.Compress;
import com.github.hgdcoder.remoting.constants.RpcConstants;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 基于 JDK GZIP 流实现的压缩算法，不需要增加第三方依赖。
 */
public class GzipCompress implements Compress {
    private static final int BUFFER_SIZE = 4 * 1024;
    private static final int MAX_DECOMPRESSED_BODY_LENGTH =
            RpcConstants.MAX_FRAME_LENGTH - RpcConstants.HEADER_LENGTH;

    @Override
    public byte[] compress(byte[] bytes) {
        requireBytes(bytes);

        /*
         * GZIPOutputStream 把压缩结果写入内存中的 ByteArrayOutputStream。
         * finish() 会写完 GZIP 尾部校验信息；在它之前不能读取最终结果。
         */
        try(ByteArrayOutputStream output = new ByteArrayOutputStream();
            GZIPOutputStream gzip=new GZIPOutputStream(output)){
            gzip.write(bytes);
            gzip.finish();
            return output.toByteArray();
        }catch (IOException e) {
            throw new IllegalStateException("GZIP compress failed",e);
        }
    }

    @Override
    public byte[] decompress(byte[] bytes) {
        requireBytes(bytes);

        try(ByteArrayInputStream input = new ByteArrayInputStream(bytes);
            GZIPInputStream gzip=new GZIPInputStream(input);
            ByteArrayOutputStream output = new ByteArrayOutputStream()){
            byte[] buffer = new byte[BUFFER_SIZE];
            int totalLength = 0;
            int readLength;
            while((readLength = gzip.read(buffer))!=-1) {
                totalLength += readLength;
                /*
                 * 压缩包可能很小，但解压后极大。边读边限制长度，
                 * 可以避免恶意 GZIP 数据耗尽 JVM 内存。
                 */
                if(totalLength > MAX_DECOMPRESSED_BODY_LENGTH) {
                    throw new IllegalArgumentException(
                            "GZIP body exceeds maximum decompressed length: "
                                    + totalLength
                    );
                }
                output.write(buffer,0,readLength);
            }
            return output.toByteArray();
        }catch(IOException e) {
            throw new IllegalStateException("GZIP decompress failed", e);
        }
    }

    private void requireBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes must not be null");
    }
}
