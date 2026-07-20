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

    /** V8 暂时只支持 JDK 序列化。 */
    public static final byte JDK_CODEC = 1;

    /** V8 暂时不压缩消息体。 */
    public static final byte NO_COMPRESS = 0;

    private RpcConstants(){

    }
}
