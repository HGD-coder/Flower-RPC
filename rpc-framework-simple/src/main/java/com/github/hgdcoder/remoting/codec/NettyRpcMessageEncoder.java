package com.github.hgdcoder.remoting.codec;

import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.remoting.dto.RpcMessage;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.serialize.Serializer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.TooLongFrameException;

/**
 * 把 RpcMessage 编码为 V9 保持不变的 16 字节头协议。
 * 位于 Channel Pipeline 的出站方向: 业务端写入 RpcMessage 后，先在这里得到字节帧，
 * 再由 Netty 写入网络；入站方向的 NettyRpcMessageDecoder 与它相反。
 */
public class NettyRpcMessageEncoder extends MessageToByteEncoder<RpcMessage> {
    /**
     * 校验消息并按“协议头 + 序列化消息体”的顺序写入出站缓冲区。
     *
     * @param ctx 当前 Channel 的上下文；编码本身不保存连接状态
     * @param message 上层构造的协议消息，包含请求号和协议元数据
     * @param out Netty 提供的出站 ByteBuf，方法返回后交给后续出站处理器发送
     * 调用时机是业务代码执行 writeAndFlush 后，且在消息真正写入网络前。
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, RpcMessage message, ByteBuf out){
        validate(message);
        Serializer serializer = SerializerResolver.resolve(message.getCodec());
        byte[] body = serializer.serialize(message.getData());
        if(body.length > RpcConstants.MAX_FRAME_LENGTH - RpcConstants.HEADER_LENGTH){
            throw new TooLongFrameException("RPC frame too large:"+ (RpcConstants.HEADER_LENGTH + (long) body.length));
        }

        int fullLength = RpcConstants.HEADER_LENGTH + body.length;
        out.writeBytes(RpcConstants.MAGIC_NUMBER);
        out.writeByte(RpcConstants.VERSION);
        out.writeInt(fullLength);
        out.writeByte(message.getMessageType());
        out.writeByte(message.getCodec());
        out.writeByte(message.getCompress());
        // 协议请求号由调用方生成；编码器只原样写入，绝不自行改号。
        out.writeInt(message.getRequestId());
        out.writeBytes(body);
    }

    /**
     * 在序列化前拒绝当前版本无法表达的消息，避免写出对端无法解析的半有效帧。
     *
     * @param message 待编码的协议消息
     */
    private void validate(RpcMessage message) {
        if (message == null || message.getData() == null) {
            throw new IllegalArgumentException("rpc message and data must not be null");
        }
        if (message.getMessageType() == RpcConstants.REQUEST_TYPE) {
            if (!(message.getData() instanceof RpcRequest)) {
                throw new IllegalArgumentException("RPC request body type is invalid");
            }
        } else if (message.getMessageType() == RpcConstants.RESPONSE_TYPE) {
            if (!(message.getData() instanceof RpcResponse)) {
                throw new IllegalArgumentException("RPC response body type is invalid");
            }
        } else {
            throw new IllegalArgumentException("Unsupported message type: "
                    + message.getMessageType());
        }
        SerializerResolver.resolve(message.getCodec());
        if (message.getCompress() != RpcConstants.NO_COMPRESS) {
            throw new IllegalArgumentException("Unsupported compress type: "
                    + message.getCompress());
        }
    }
}
