package com.github.hgdcoder.remoting.codec;

import com.github.hgdcoder.remoting.constants.RpcConstants;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * 按协议 fullLength 字段切分 TCP 字节流，处理半包和粘包。
 *
 * Flower-RPC 的协议头布局如下：
 * magic(4) + version(1) + fullLength(4) + 其他头字段(7) = 16 字节。
 * fullLength 保存的是“协议头 + 消息体”的总长度，而不是消息体长度。
 * 这是 Pipeline 的入站第一环: 它只负责把 TCP 字节流整理成完整 ByteBuf，
 * 不校验字段语义；语义校验和反序列化由后续 NettyRpcMessageDecoder 完成。
 */
public class NettyRpcFrameDecoder extends LengthFieldBasedFrameDecoder {
    public NettyRpcFrameDecoder() {
        super(
                // 单个 RPC 数据包允许的最大长度，超过后直接拒绝，避免异常数据占用过多内存。
                RpcConstants.MAX_FRAME_LENGTH,

                // 长度字段的起始下标。
                // fullLength 前面有 magic(4) + version(1)，所以从下标 5 开始。
                5,

                // fullLength 使用 int 表示，因此长度字段占 4 字节。
                4,

                // Netty 默认会在长度字段的值后面，加上“长度字段结束位置”：5 + 4 = 9。
                // 但 fullLength 本身已经表示整帧总长度，所以要用 -9 抵消 Netty 加上的 9 字节：
                // 最终帧长度 = fullLength + (-9) + 5 + 4 = fullLength。
                -9,

                // 拆出完整帧后不丢弃任何字节。
                // 后面的 NettyRpcMessageDecoder 还需要读取 magic、version 等完整协议头。
                0
        );
    }
}
