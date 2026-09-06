package com.github.hgdcoder.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 从 classpath 根目录和 JVM System property 组装配置快照。
 *
 * <p>优先级固定为 System property &gt; flower-rpc.properties &gt; 框架默认值。
 * 使用 ClassLoader 流读取资源，应用打成 JAR 后仍可加载包内配置文件。</p>
 */
public final class RpcConfigLoader {
    public static final String RESOURCE_NAME = "flower-rpc.properties";

    public static final String ZK_ADDRESS_KEY = "flower.rpc.zk.address";
    public static final String SERVER_HOST_KEY = "flower.rpc.server.host";

    /**
     * RPC 服务端在本机真正监听的地址。
     */
    public static final String SERVER_BIND_HOST_KEY = "flower.rpc.server.bind-host";

    public static final String SERVER_PORT_KEY = "flower.rpc.server.port";
    public static final String LOAD_BALANCE_KEY = "flower.rpc.load-balance";
    public static final String SERIALIZER_KEY = "flower.rpc.serializer";
    public static final String COMPRESS_KEY = "flower.rpc.compress";

    /** 服务注册 SPI 实现名称。 */
    public static final String REGISTRY_KEY = "flower.rpc.registry";

    /** 服务发现 SPI 实现名称。 */
    public static final String DISCOVERY_KEY = "flower.rpc.discovery";

    /** 客户端传输 SPI 实现名称。 */
    public static final String TRANSPORT_KEY = "flower.rpc.transport";

    public static final String CONNECT_TIMEOUT_MILLIS_KEY =
            "flower.rpc.connect-timeout-millis";
    public static final String REQUEST_TIMEOUT_MILLIS_KEY =
            "flower.rpc.request-timeout-millis";
    public static final String HEARTBEAT_INTERVAL_SECONDS_KEY =
            "flower.rpc.heartbeat.interval-seconds";
    public static final String HEARTBEAT_TIMEOUT_SECONDS_KEY =
            "flower.rpc.heartbeat.timeout-seconds";

    private RpcConfigLoader() {
    }

    public static RpcFrameworkConfig load() {
        return load(resolveClassLoader());
    }

    /** 包内入口允许测试提供可控 ClassLoader，不扩大生产 API。 */
    static RpcFrameworkConfig load(ClassLoader classLoader) {
        Properties fileProperties = loadFileProperties(classLoader);
        RpcFrameworkConfig defaults = RpcFrameworkConfig.defaults();

        return defaults.toBuilder()
                .zkAddress(resolve(
                        ZK_ADDRESS_KEY,
                        fileProperties,
                        defaults.getZkAddress()))
                .serverHost(resolve(SERVER_HOST_KEY,
                        fileProperties,
                        defaults.getServerHost()))
                /*
                 * bindHost 与 serverHost 分开读取：
                 *
                 * serverHost：注册给客户端连接的地址；
                 * bindHost：服务端在本机监听的地址。
                 */
                .bindHost(resolve(
                        SERVER_BIND_HOST_KEY,
                        fileProperties,
                        defaults.getBindHost()
                ))
                .serverPort(parseInt(
                        SERVER_PORT_KEY,
                        resolve(SERVER_PORT_KEY,
                                fileProperties,
                                String.valueOf(defaults.getServerPort())),
                        1,
                        65535
                ))
                .loadBalance(resolve(
                        LOAD_BALANCE_KEY,
                        fileProperties,
                        defaults.getLoadBalance()
                ))
                .serializer(resolve(
                        SERIALIZER_KEY,
                        fileProperties,
                        defaults.getSerializer()
                ))
                .compress(resolve(
                        COMPRESS_KEY,
                        fileProperties,
                        defaults.getCompress()
                ))
                .registry(resolve(
                        REGISTRY_KEY,
                        fileProperties,
                        defaults.getRegistry()
                ))
                .discovery(resolve(
                        DISCOVERY_KEY,
                        fileProperties,
                        defaults.getDiscovery()
                ))
                .transport(resolve(
                        TRANSPORT_KEY,
                        fileProperties,
                        defaults.getTransport()
                ))
                .connectTimeoutMillis(parseInt(
                        CONNECT_TIMEOUT_MILLIS_KEY,
                        resolve(CONNECT_TIMEOUT_MILLIS_KEY,fileProperties,
                                String.valueOf(defaults.getConnectTimeoutMillis())),
                        1,
                        Integer.MAX_VALUE
                ))
                .requestTimeoutMillis(parseInt(
                        REQUEST_TIMEOUT_MILLIS_KEY,
                        resolve(REQUEST_TIMEOUT_MILLIS_KEY,fileProperties,
                                String.valueOf(defaults.getRequestTimeoutMillis())),
                        1,
                        Integer.MAX_VALUE
                ))
                .heartbeatIntervalSeconds(parseInt(
                        HEARTBEAT_INTERVAL_SECONDS_KEY,
                        resolve(HEARTBEAT_INTERVAL_SECONDS_KEY, fileProperties,
                                String.valueOf(defaults.getHeartbeatIntervalSeconds())),
                        1,
                        Integer.MAX_VALUE
                ))
                .heartbeatTimeoutSeconds(parseInt(
                        HEARTBEAT_TIMEOUT_SECONDS_KEY,
                        resolve(HEARTBEAT_TIMEOUT_SECONDS_KEY, fileProperties,
                                String.valueOf(defaults.getHeartbeatTimeoutSeconds())),
                        1,
                        Integer.MAX_VALUE
                ))
                .build();
    }

    private static ClassLoader resolveClassLoader() {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return contextClassLoader == null
                ?RpcConfigLoader.class.getClassLoader()
                :contextClassLoader;
    }

    private static Properties loadFileProperties(ClassLoader classLoader) {
        Properties properties = new Properties();
        if(classLoader == null) {
            return properties;
        }

        try(InputStream input = classLoader.getResourceAsStream(RESOURCE_NAME)) {
            // 配置文件缺失是正常场景，此时后续逻辑直接使用框架默认值。
            if(input == null) {
                return properties;
            }
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return properties;
        }catch(IOException e) {
            throw new IllegalStateException(
                    "Failed to load classpath configuration: "+RESOURCE_NAME,
                    e
            );
        }
    }

    private static String resolve(
            String key,
            Properties fileProperties,
            String defaultValue
    ) {
        String systemValue = System.getProperty(key);
        if(systemValue != null) {
            return systemValue;
        }

        String fileValue = fileProperties.getProperty(key);
        return fileValue == null ? defaultValue : fileValue;
    }

    private static int parseInt(String key,String rawValue,int min,int max) {
        String normalized = rawValue == null ? null : rawValue.trim();
        if(normalized == null || normalized.isEmpty()) {
            throw invalidNumber(key,rawValue,"value must not be empty");
        }

        final int value;
        try {
            value = Integer.parseInt(normalized);
        } catch (NumberFormatException e) {
            throw invalidNumber(key, rawValue, "value must be an integer");
        }
        if (value < min || value > max) {
            throw invalidNumber(
                    key,
                    rawValue,
                    "value must be in [" + min + "," + max + "]"
            );
        }
        return value;
    }

    private static IllegalArgumentException invalidNumber(
            String key,
            String value,
            String reason
    ) {
        return new IllegalArgumentException(
                "Invalid configuration " + key + "='" + String.valueOf(value) + "': " + reason
        );
    }
}
