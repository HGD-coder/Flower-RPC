package com.github.hgdcoder.compress.gzip;

import com.github.hgdcoder.compress.Compress;
import com.github.hgdcoder.extension.ExtensionLoader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 GZIP 算法本身和 Compress SPI 注册。 */
public class GzipCompressTest {
    @Test
    void shouldCompressAndRestoreBytes() {
        byte[] original = repeatedBytes(
                "Flower-RPC-GZIP-",
                2_000
        );
        GzipCompress gzip = new GzipCompress();

        byte[] compressed = gzip.compress(original);
        byte[] restored = gzip.decompress(compressed);

        // 重复文本适合压缩，压缩结果应明显小于原始数据。
        assertTrue(compressed.length < original.length);
        // 解压后必须逐字节恢复，任何一个字节不同都会导致反序列化失败。
        assertArrayEquals(original, restored);
    }

    @Test
    void shouldLoadGzipAsSingletonFromSpi() {
        ExtensionLoader<Compress> loader =
                ExtensionLoader.getExtensionLoader(Compress.class);

        Compress first = loader.getExtension("gzip");
        Compress second = loader.getExtension("gzip");

        assertTrue(first instanceof GzipCompress);
        // ExtensionLoader 会缓存扩展实例，同一名字不应反复创建对象。
        assertSame(first, second);
    }

    @Test
    void shouldRejectInvalidGzipBytes() {
        GzipCompress gzip = new GzipCompress();
        assertThrows(
                IllegalStateException.class,
                () -> gzip.decompress(new byte[]{1, 2, 3, 4})
        );
    }

    private byte[] repeatedBytes(String text, int count) {
        byte[] block = text.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[block.length * count];
        for (int i = 0; i < count; i++) {
            System.arraycopy(block, 0, result, i * block.length, block.length);
        }
        return result;
    }
}
