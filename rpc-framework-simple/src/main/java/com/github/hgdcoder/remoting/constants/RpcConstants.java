package com.github.hgdcoder.remoting.constants;

/**
 * Flower-RPC 自定义协议使用的固定常量。
 */
public final class RpcConstants {
    /** 4 字节魔数，用于快速识别 Flower-RPC 数据包。 */
    public static final byte[] MAGIC_NUMBER = {
            (byte) 'f', (byte) 'r', (byte) 'p', (byte) 'c'
    };

    /** 当前协议版本。 */
    public static final byte VERSION = 1;

    /**
     * 固定协议头长度：
     * magic(4) + version(1) + fullLength(4) + messageType(1)
     * + codec(1) + compress(1) + requestId(4) = 16 字节。
     */
    public static final int HEADER_LENGTH = 16;

    /** 单个数据包最大为 8 MiB，防止异常长度导致客户端分配超大数组。 */
    public static final int MAX_FRAME_LENGTH = 8*1024*1024;

    public static final byte REQUEST_TYPE = 1;
    public static final byte RESPONSE_TYPE = 2;

    /** V11 新增：客户端发给服务端的心跳请求。 */
    public static final byte HEARTBEAT_REQUEST_TYPE = 3;

    /** V11 新增：服务端回复给客户端的心跳响应。 */
    public static final byte HEARTBEAT_RESPONSE_TYPE = 4;

    /**
     * 心跳帧没有消息体，不需要序列化器。
     * 使用 0 明确表达“本帧不使用 codec”，避免随便填写 JDK 或 Kryo 造成误解。
     */
    public static final byte NO_CODEC = 0;

    /** V8 暂时只支持 JDK 序列化。 */
    public static final byte JDK_CODEC = 1;

    /** Kryo 序列化（V9 正式推荐）。 */
    public static final byte KRYO_CODEC = 2;

    /** 兼容旧代码；V14 运行时由 RpcFrameworkConfig 选择 codec。 */
    public static final byte DEFAULT_CODEC = KRYO_CODEC;

    /** V8 暂时不压缩消息体。 */
    public static final byte NO_COMPRESS = 0;

    /** V12 新增：使用 JDK 自带的 GZIP 压缩消息体。 */
    public static final byte GZIP_COMPRESS = 1;

    /**
     * V12 主链路默认启用 GZIP。
     * 想做不压缩与 GZIP 的 A/B 压测时，只需把它临时改为 NO_COMPRESS。
     */
    public static final byte DEFAULT_COMPRESS = NO_COMPRESS;

    /**
     * 普通 RPC 的协议请求号从 1 开始，因此把 0 留给心跳。
     * 心跳不进入 UnprocessedRequests，也不需要通过请求号匹配 Future。
     */
    public static final int HEARTBEAT_REQUEST_ID = 0;

    /**
     * RpcMessage 在 JVM 内部仍携带一段可读数据，便于 Handler 判断消息语义。
     * 这两个字符串不会写入网络；心跳在网络中仍然只有 16 字节协议头。
     */
    public static final String PING = "ping";
    public static final String PONG = "pong";

    /**
     * 客户端连续 5 秒没有写任何数据时发送一次 PING。
     * 正常业务请求本身也算一次写操作，因此繁忙连接不会额外发送无意义心跳。
     * 兼容旧代码；V14 客户端读取配置快照。
     */
    public static final int HEARTBEAT_INTERVAL_SECONDS = 5;

    /**
     * 连续 15 秒没有读到对端任何数据，就认为连接已经不可用。
     * 15 秒等于允许错过 3 个心跳周期，可以容忍短暂的 GC 或线程调度抖动。
     *
     *  兼容旧代码；V14 客户端和服务端读取配置快照。
     */
    public static final int HEARTBEAT_TIMEOUT_SECONDS = 15;

    private RpcConstants(){

    }
}
