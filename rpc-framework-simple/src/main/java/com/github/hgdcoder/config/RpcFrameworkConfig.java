package com.github.hgdcoder.config;

import com.github.hgdcoder.enums.RpcRequestTransportEnum;
import com.github.hgdcoder.enums.SerializationTypeEnum;
import com.github.hgdcoder.enums.ServiceDiscoveryEnum;
import com.github.hgdcoder.enums.ServiceRegistryEnum;
import com.github.hgdcoder.remoting.constants.RpcConstants;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;

/**
 * Flower-RPC 的全局配置快照。
 *
 * <p>程序启动时读取 flower-rpc.properties、JVM 参数和默认值，
 * 然后生成一个不可修改的配置对象。</p>
 *
 * <p>客户端、服务端、注册中心等组件共享同一个配置对象，
 * 避免不同组件重复读取配置后得到不一致的结果。</p>
 */
@Getter
public final class RpcFrameworkConfig {
    /*
     * 这些字符串是 flower-rpc.properties 中允许填写的配置值。
     */
    public static final String LOAD_BALANCE_RANDOM = "random";
    public static final String LOAD_BALANCE_CONSISTENT_HASH = "consistent-hash";

    public static final String SERIALIZER_JDK =
            SerializationTypeEnum.JDK.getName();

    public static final String SERIALIZER_KRYO =
            SerializationTypeEnum.KRYO.getName();

    public static final String SERIALIZER_HESSIAN =
            SerializationTypeEnum.HESSIAN.getName();

    public static final String SERIALIZER_PROTOSTUFF =
            SerializationTypeEnum.PROTOSTUFF.getName();

    public static final String COMPRESS_NONE = "none";
    public static final String COMPRESS_GZIP = "gzip";

    /**
     * 三个内置 SPI 实现的默认名称。
     *
     * <p>这里使用枚举保存框架内置名称，但配置仍然允许填写
     * 第三方扩展名，例如 nacos、http、quic。</p>
     */
    public static final String REGISTRY_ZK =
            ServiceRegistryEnum.ZK.getName();

    public static final String DISCOVERY_ZK =
            ServiceDiscoveryEnum.ZK.getName();

    public static final String TRANSPORT_NETTY =
            RpcRequestTransportEnum.NETTY.getName();

    /**
     * ZooKeeper 地址，例如 127.0.0.1:2181。
     */
    private final String zkAddress;

    /**
     * RPC 服务端监听和注册到 ZooKeeper 的主机地址。
     */
    private final String serverHost;

    /**
     * RPC 服务端监听端口。
     */
    private final int serverPort;

    /**
     * 客户端使用的负载均衡策略。
     */
    private final String loadBalance;

    /**
     * 便于人阅读和配置的序列化器名称。
     */
    private final String serializer;

    /**
     * 便于人阅读和配置的压缩算法名称。
     */
    private final String compress;

    /**
     * 服务注册实现的 SPI 名称。
     *
     * <p>例如：zk、file。</p>
     */
    private final String registry;

    /**
     * 服务发现实现的 SPI 名称。
     *
     * <p>例如：zk、file。</p>
     */
    private final String discovery;

    /**
     * 客户端传输实现的 SPI 名称。
     *
     * <p>例如：netty、socket。</p>
     */
    private final String transport;

    /**
     * 写入 RPC 协议头的序列化器编号。
     */
    private final byte codec;

    /**
     * 写入 RPC 协议头的压缩算法编号。
     */
    private final byte compressType;

    /**
     * 建立 Netty TCP 连接的超时时间。
     */
    private final int connectTimeoutMillis;

    /**
     * 一次 RPC 请求等待响应的超时时间。
     */
    private final int requestTimeoutMillis;

    /**
     * 客户端多久没有写数据后发送一次心跳。
     */
    private final int heartbeatIntervalSeconds;

    /**
     * 多久没有收到数据后认为连接失效。
     */
    private final int heartbeatTimeoutSeconds;

    /**
     * 只能通过 Builder 创建配置对象。
     *
     * <p>这里不仅给字段赋值，还会完成：</p>
     * <p>1. 配置格式校验；</p>
     * <p>2. 字符串标准化；</p>
     * <p>3. 配置名称到协议编号的转换。</p>
     */
    private RpcFrameworkConfig(Builder builder) {
        this.zkAddress = requireText(
                RpcConfigLoader.ZK_ADDRESS_KEY,
                builder.zkAddress
        );

        this.serverHost = requireText(
                RpcConfigLoader.SERVER_HOST_KEY,
                builder.serverHost
        );

        /*
         * Builder 允许端口为 0，方便测试让操作系统自动选择空闲端口。
         * RpcConfigLoader 读取正式配置文件时仍限制端口必须大于 0。
         */
        this.serverPort = requireRange(
                RpcConfigLoader.SERVER_PORT_KEY,
                builder.serverPort,
                0,
                65535
        );

        this.loadBalance = normalizeChoice(
                RpcConfigLoader.LOAD_BALANCE_KEY,
                builder.loadBalance,
                LOAD_BALANCE_RANDOM,
                LOAD_BALANCE_CONSISTENT_HASH
        );


        this.compress = normalizeChoice(
                RpcConfigLoader.COMPRESS_KEY,
                builder.compress,
                COMPRESS_NONE,
                COMPRESS_GZIP
        );

        /*
         * SPI 扩展名只做格式标准化，不限制可选值。
         *
         * 如果这里使用 normalizeChoice() 写死 zk、file，
         * 第三方新增 nacos 时就必须修改框架代码，不符合 SPI 的目的。
         */
        this.registry = normalizeExtensionName(
                RpcConfigLoader.REGISTRY_KEY,
                builder.registry
        );

        this.discovery = normalizeExtensionName(
                RpcConfigLoader.DISCOVERY_KEY,
                builder.discovery
        );

        this.transport = normalizeExtensionName(
                RpcConfigLoader.TRANSPORT_KEY,
                builder.transport
        );


        /*
         * 配置类不再手写所有序列化器选项。
         * 枚举负责完成 name -> code 的统一映射。
         */
        SerializationTypeEnum serializerType =
                normalizeSerializer(
                        RpcConfigLoader.SERIALIZER_KEY,
                        builder.serializer
                );

        this.serializer = serializerType.getName();
        this.codec = serializerType.getCode();

        this.compressType = compressToType(this.compress);

        this.connectTimeoutMillis = requirePositive(
                RpcConfigLoader.CONNECT_TIMEOUT_MILLIS_KEY,
                builder.connectTimeoutMillis
        );

        this.requestTimeoutMillis = requirePositive(
                RpcConfigLoader.REQUEST_TIMEOUT_MILLIS_KEY,
                builder.requestTimeoutMillis
        );

        this.heartbeatIntervalSeconds = requirePositive(
                RpcConfigLoader.HEARTBEAT_INTERVAL_SECONDS_KEY,
                builder.heartbeatIntervalSeconds
        );

        this.heartbeatTimeoutSeconds = requirePositive(
                RpcConfigLoader.HEARTBEAT_TIMEOUT_SECONDS_KEY,
                builder.heartbeatTimeoutSeconds
        );

        /*
         * 超时时间必须大于发送间隔。
         *
         * 例如每 5 秒发送一次心跳，可以设置 15 秒没有收到数据才断开。
         */
        if (heartbeatTimeoutSeconds <= heartbeatIntervalSeconds) {
            throw new IllegalArgumentException(
                    "Invalid configuration "
                            + RpcConfigLoader.HEARTBEAT_TIMEOUT_SECONDS_KEY
                            + "='" + heartbeatTimeoutSeconds
                            + "': must be greater than "
                            + RpcConfigLoader.HEARTBEAT_INTERVAL_SECONDS_KEY
                            + "='" + heartbeatIntervalSeconds + "'"
            );
        }
    }

    /**
     * 加载程序的正式配置。
     * <p>
     * 配置优先级：
     * JVM System property > flower-rpc.properties > 框架默认值。
     */
    public static RpcFrameworkConfig load() {
        return RpcConfigLoader.load();
    }

    /**
     * 不读取配置文件，直接返回框架默认配置。
     * <p>
     * 主要用于兼容旧构造方法以及测试。
     */
    public static RpcFrameworkConfig defaults() {
        return builder().build();
    }

    /**
     * 创建一个带有默认值的 Builder。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 复制当前配置，产生一个可以修改的 Builder。
     * <p>
     * 例如只修改序列化器：
     * <p>
     * RpcFrameworkConfig newConfig = oldConfig.toBuilder()
     * .serializer("jdk")
     * .build();
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * 校验字符串不能为空，并删除首尾空格。
     */
    private static String requireText(String key, String rawValue) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            throw invalidValue(
                    key,
                    rawValue,
                    "value must not be empty"
            );
        }
        return rawValue.trim();
    }

    /**
     * 校验一个字符串是否属于支持的两个选项。
     * <p>
     * 同时把大小写统一为小写：
     * " Kryo " -> "kryo"
     */
    private static String normalizeChoice(
            String key,
            String rawValue,
            String... supportedValues
    ) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            throw invalidValue(
                    key,
                    rawValue,
                    "value must not be empty"
            );
        }

        String normalized =
                rawValue.trim().toLowerCase(Locale.ROOT);

        for (String supportedValue : supportedValues) {
            if (supportedValue.equals(normalized)) {
                return normalized;
            }
        }

        throw invalidValue(
                key,
                rawValue,
                "supported values are "
                        + Arrays.toString(supportedValues)
        );
    }

    /**
     * 标准化 SPI 扩展名称。
     *
     * <p>只要求名称非空，并转换成小写，不设置实现白名单。</p>
     *
     * <p>例如：</p>
     * <pre>
     * " ZK "    -> "zk"
     * " Netty " -> "netty"
     * " Nacos " -> "nacos"
     * </pre>
     */
    private static String normalizeExtensionName(
            String key,
            String rawValue
    ) {
        return requireText(key, rawValue)
                .toLowerCase(Locale.ROOT);
    }

    /**
     * 校验整数必须大于 0。
     */
    private static int requirePositive(String key, int value) {
        return requireRange(
                key,
                value,
                1,
                Integer.MAX_VALUE
        );
    }

    /**
     * 校验整数必须位于指定范围内。
     */
    private static int requireRange(
            String key,
            int value,
            int min,
            int max
    ) {
        if (value < min || value > max) {
            throw invalidValue(
                    key,
                    String.valueOf(value),
                    "value must be in ["
                            + min
                            + ","
                            + max
                            + "]"
            );
        }

        return value;
    }


    /**
     * 校验序列化器名称并转换成枚举。
     *
     * <p>支持的实现由 SerializationTypeEnum 统一维护，
     * 不再受 normalizeChoice 只能列两个参数的限制。</p>
     */
    private static SerializationTypeEnum normalizeSerializer(
            String key,
            String rawValue
    ) {
        String normalized = requireText(
                key,
                rawValue
        ).toLowerCase(Locale.ROOT);

        try {
            return SerializationTypeEnum.fromName(normalized);
        } catch (IllegalArgumentException e) {
            throw invalidValue(
                    key,
                    rawValue,
                    e.getMessage()
            );
        }
    }

    /**
     * 将压缩算法名称转换成协议编号。
     */
    private static byte compressToType(String compress) {
        return COMPRESS_NONE.equals(compress)
                ? RpcConstants.NO_COMPRESS
                : RpcConstants.GZIP_COMPRESS;
    }

    /**
     * 将协议常量中的默认压缩编号转换成配置名称。
     */
    private static String defaultCompressName() {
        if (RpcConstants.DEFAULT_COMPRESS
                == RpcConstants.NO_COMPRESS) {
            return COMPRESS_NONE;
        }

        if (RpcConstants.DEFAULT_COMPRESS
                == RpcConstants.GZIP_COMPRESS) {
            return COMPRESS_GZIP;
        }

        throw new IllegalStateException(
                "Unsupported RpcConstants.DEFAULT_COMPRESS: "
                        + RpcConstants.DEFAULT_COMPRESS
        );
    }

    /**
     * 统一生成配置错误异常。
     */
    private static IllegalArgumentException invalidValue(
            String key,
            Object value,
            String reason
    ) {
        return new IllegalArgumentException(
                "Invalid configuration "
                        + key
                        + "='"
                        + String.valueOf(value)
                        + "': "
                        + reason
        );
    }



    /**
     * 配置对象的建造器。
     * <p>
     * Builder 是可修改的，RpcFrameworkConfig 是不可修改的。
     * 调用 build() 后才会执行完整校验并生成最终配置快照。
     */
    public static final class Builder {

        /**
         * 以下字段都是 Flower-RPC 的默认配置。
         */
        private String zkAddress = "127.0.0.1:2181";
        private String serverHost = "127.0.0.1";
        private int serverPort = 9998;
        private String loadBalance = LOAD_BALANCE_CONSISTENT_HASH;
        private String serializer = SERIALIZER_KRYO;
        private String compress = defaultCompressName();

        /*
         * 默认使用 ZooKeeper 注册、ZooKeeper 发现和 Netty 传输。
         */
        private String registry = REGISTRY_ZK;
        private String discovery = DISCOVERY_ZK;
        private String transport = TRANSPORT_NETTY;

        private int connectTimeoutMillis = 3000;
        private int requestTimeoutMillis = 5000;
        private int heartbeatIntervalSeconds = 5;
        private int heartbeatTimeoutSeconds = 15;

        private Builder() {
        }

        /**
         * 把已有配置复制到新的 Builder 中。
         */
        private Builder(RpcFrameworkConfig config) {
            this.zkAddress = config.zkAddress;
            this.serverHost = config.serverHost;
            this.serverPort = config.serverPort;
            this.loadBalance = config.loadBalance;
            this.serializer = config.serializer;
            this.compress = config.compress;

            /*
             * toBuilder() 也必须复制三个 SPI 选择，
             * 否则复制配置后会意外恢复成默认实现。
             */
            this.registry = config.registry;
            this.discovery = config.discovery;
            this.transport = config.transport;

            this.connectTimeoutMillis =
                    config.connectTimeoutMillis;
            this.requestTimeoutMillis =
                    config.requestTimeoutMillis;
            this.heartbeatIntervalSeconds =
                    config.heartbeatIntervalSeconds;
            this.heartbeatTimeoutSeconds =
                    config.heartbeatTimeoutSeconds;
        }

        public Builder zkAddress(String zkAddress) {
            this.zkAddress = zkAddress;
            return this;
        }

        public Builder serverHost(String serverHost) {
            this.serverHost = serverHost;
            return this;
        }

        public Builder serverPort(int serverPort) {
            this.serverPort = serverPort;
            return this;
        }

        public Builder loadBalance(String loadBalance) {
            this.loadBalance = loadBalance;
            return this;
        }

        public Builder serializer(String serializer) {
            this.serializer = serializer;
            return this;
        }

        public Builder compress(String compress) {
            this.compress = compress;
            return this;
        }

        /**
         * 选择服务注册 SPI 实现。
         */
        public Builder registry(String registry) {
            this.registry = registry;
            return this;
        }

        /**
         * 选择服务发现 SPI 实现。
         */
        public Builder discovery(String discovery) {
            this.discovery = discovery;
            return this;
        }

        /**
         * 选择客户端传输 SPI 实现。
         */
        public Builder transport(String transport) {
            this.transport = transport;
            return this;
        }

        public Builder connectTimeoutMillis(
                int connectTimeoutMillis
        ) {
            this.connectTimeoutMillis = connectTimeoutMillis;
            return this;
        }

        public Builder requestTimeoutMillis(
                int requestTimeoutMillis
        ) {
            this.requestTimeoutMillis = requestTimeoutMillis;
            return this;
        }

        public Builder heartbeatIntervalSeconds(
                int heartbeatIntervalSeconds
        ) {
            this.heartbeatIntervalSeconds =
                    heartbeatIntervalSeconds;
            return this;
        }

        public Builder heartbeatTimeoutSeconds(
                int heartbeatTimeoutSeconds
        ) {
            this.heartbeatTimeoutSeconds =
                    heartbeatTimeoutSeconds;
            return this;
        }

        /**
         * 创建最终配置对象。
         * <p>
         * RpcFrameworkConfig 构造函数会完成所有校验。
         */
        public RpcFrameworkConfig build() {
            return new RpcFrameworkConfig(this);
        }
    }
}
