package com.github.hgdcoder.registry.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FileServiceRegistryLifecycleTest {
    private static final String FILE_PROPERTY = "flower.rpc.registry.file";

    // JUnit 创建临时目录，并在测试结束后自动清理。
    @TempDir
    Path tempDir;

    @Test
    void shouldRemoveOnlyTheSpecifiedAddress() throws Exception {
        // 保存原配置，防止这个测试影响后续测试。
        String previous = System.getProperty(FILE_PROPERTY);
        Path registryFile = tempDir.resolve("registry.properties");
        System.setProperty(FILE_PROPERTY, registryFile.toString());

        try {
            FileServiceRegistry registry = new FileServiceRegistry();
            InetSocketAddress first = new InetSocketAddress("127.0.0.1", 9998);
            InetSocketAddress second = new InetSocketAddress("127.0.0.1", 9999);

            // 同一个服务拥有两个提供者。
            registry.registerService("test.Service", first);
            registry.registerService("test.Service", second);

            // 注销第一个地址，不应该影响第二个地址。
            registry.unregisterService("test.Service", first);
            assertEquals(
                    "127.0.0.1:9999",
                    load(registryFile).getProperty("test.Service")
            );

            // 最后一个地址注销后，整个服务条目应该被删除。
            registry.unregisterService("test.Service", second);
            assertNull(load(registryFile).getProperty("test.Service"));
        } finally {
            // 即使断言失败，也必须恢复 JVM 原来的配置。
            if (previous == null) {
                System.clearProperty(FILE_PROPERTY);
            } else {
                System.setProperty(FILE_PROPERTY, previous);
            }
        }
    }

    /** 直接读取文件，检查注册中心实际持久化的结果。 */
    private Properties load(Path file) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return properties;
    }
}