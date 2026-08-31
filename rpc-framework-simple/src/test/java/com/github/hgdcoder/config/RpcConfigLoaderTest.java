package com.github.hgdcoder.config;

import com.github.hgdcoder.remoting.constants.RpcConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证统一配置的优先级、规范化和启动期失败边界。 */
class RpcConfigLoaderTest {
    private static final String[] SUPPORTED_KEYS = {
            RpcConfigLoader.ZK_ADDRESS_KEY,
            RpcConfigLoader.SERVER_HOST_KEY,
            RpcConfigLoader.SERVER_PORT_KEY,
            RpcConfigLoader.LOAD_BALANCE_KEY,
            RpcConfigLoader.SERIALIZER_KEY,
            RpcConfigLoader.COMPRESS_KEY,
            RpcConfigLoader.CONNECT_TIMEOUT_MILLIS_KEY,
            RpcConfigLoader.REQUEST_TIMEOUT_MILLIS_KEY,
            RpcConfigLoader.HEARTBEAT_INTERVAL_SECONDS_KEY,
            RpcConfigLoader.HEARTBEAT_TIMEOUT_SECONDS_KEY
    };

    private final Map<String, String> originalSystemProperties = new HashMap<>();

    @BeforeEach
    void isolateSystemProperties() {
        for (String key : SUPPORTED_KEYS) {
            originalSystemProperties.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
    }

    @AfterEach
    void restoreSystemProperties() {
        for (String key : SUPPORTED_KEYS) {
            String originalValue = originalSystemProperties.get(key);
            if (originalValue == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, originalValue);
            }
        }
    }

    @Test
    void shouldUseFrameworkDefaultsWhenClasspathFileIsMissing() {
        RpcFrameworkConfig config = RpcConfigLoader.load(resourceClassLoader(null));

        assertEquals("127.0.0.1:2181", config.getZkAddress());
        assertEquals("127.0.0.1", config.getServerHost());
        assertEquals(9998, config.getServerPort());
        assertEquals("consistent-hash", config.getLoadBalance());
        assertEquals("kryo", config.getSerializer());
        assertEquals(RpcConstants.KRYO_CODEC, config.getCodec());
        assertEquals("none", config.getCompress());
        assertEquals(RpcConstants.DEFAULT_COMPRESS, config.getCompressType());
        assertEquals(3000, config.getConnectTimeoutMillis());
        assertEquals(5000, config.getRequestTimeoutMillis());
        assertEquals(5, config.getHeartbeatIntervalSeconds());
        assertEquals(15, config.getHeartbeatTimeoutSeconds());
    }

    @Test
    void shouldReadAllValuesFromClasspathRoot() {
        RpcFrameworkConfig config = RpcConfigLoader.load(resourceClassLoader(
                "flower.rpc.zk.address=10.0.0.8:2182\n"
                        + "flower.rpc.server.host=0.0.0.0\n"
                        + "flower.rpc.server.port=10001\n"
                        + "flower.rpc.load-balance=random\n"
                        + "flower.rpc.serializer=jdk\n"
                        + "flower.rpc.compress=gzip\n"
                        + "flower.rpc.connect-timeout-millis=1200\n"
                        + "flower.rpc.request-timeout-millis=6400\n"
                        + "flower.rpc.heartbeat.interval-seconds=7\n"
                        + "flower.rpc.heartbeat.timeout-seconds=21\n"
        ));

        assertEquals("10.0.0.8:2182", config.getZkAddress());
        assertEquals("0.0.0.0", config.getServerHost());
        assertEquals(10001, config.getServerPort());
        assertEquals("random", config.getLoadBalance());
        assertEquals("jdk", config.getSerializer());
        assertEquals("gzip", config.getCompress());
        assertEquals(1200, config.getConnectTimeoutMillis());
        assertEquals(6400, config.getRequestTimeoutMillis());
        assertEquals(7, config.getHeartbeatIntervalSeconds());
        assertEquals(21, config.getHeartbeatTimeoutSeconds());
    }

    @Test
    void systemPropertyShouldOverrideClasspathValue() {
        System.setProperty(RpcConfigLoader.SERIALIZER_KEY, "jdk");
        System.setProperty(RpcConfigLoader.SERVER_PORT_KEY, "10003");

        RpcFrameworkConfig config = RpcConfigLoader.load(resourceClassLoader(
                "flower.rpc.serializer=kryo\n"
                        + "flower.rpc.server.port=10002\n"
        ));

        assertEquals("jdk", config.getSerializer());
        assertEquals(10003, config.getServerPort());
    }

    @Test
    void shouldNormalizeChoiceCaseAndSurroundingWhitespace() {
        RpcFrameworkConfig config = RpcConfigLoader.load(resourceClassLoader(
                "flower.rpc.load-balance=  RaNdOm  \n"
                        + "flower.rpc.serializer=  JdK  \n"
                        + "flower.rpc.compress=  GZIP  \n"
        ));

        assertEquals("random", config.getLoadBalance());
        assertEquals("jdk", config.getSerializer());
        assertEquals("gzip", config.getCompress());
        assertEquals(RpcConstants.JDK_CODEC, config.getCodec());
        assertEquals(RpcConstants.GZIP_COMPRESS, config.getCompressType());
    }

    @Test
    void shouldRejectUnknownAndEmptyChoiceValues() {
        assertInvalid("flower.rpc.compress=brotli\n", RpcConfigLoader.COMPRESS_KEY, "brotli");

        System.setProperty(RpcConfigLoader.SERIALIZER_KEY, "  ");
        assertInvalid(null, RpcConfigLoader.SERIALIZER_KEY, "  ");
    }

    @Test
    void shouldRejectNonNumericAndOutOfRangeNumbers() {
        assertInvalid(
                "flower.rpc.request-timeout-millis=soon\n",
                RpcConfigLoader.REQUEST_TIMEOUT_MILLIS_KEY,
                "soon"
        );
        assertInvalid(
                "flower.rpc.server.port=70000\n",
                RpcConfigLoader.SERVER_PORT_KEY,
                "70000"
        );
    }

    @Test
    void heartbeatTimeoutMustBeGreaterThanInterval() {
        assertInvalid(
                "flower.rpc.heartbeat.interval-seconds=5\n"
                        + "flower.rpc.heartbeat.timeout-seconds=5\n",
                RpcConfigLoader.HEARTBEAT_TIMEOUT_SECONDS_KEY,
                "5"
        );
    }

    private void assertInvalid(String fileContent, String key, String value) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> RpcConfigLoader.load(resourceClassLoader(fileContent))
        );
        assertTrue(error.getMessage().contains(key), error.getMessage());
        assertTrue(error.getMessage().contains("='" + value + "'"), error.getMessage());
    }

    private ClassLoader resourceClassLoader(String fileContent) {
        final byte[] bytes = fileContent == null
                ? null
                : fileContent.getBytes(StandardCharsets.UTF_8);
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (bytes != null && RpcConfigLoader.RESOURCE_NAME.equals(name)) {
                    return new ByteArrayInputStream(bytes);
                }
                return null;
            }
        };
    }
}
