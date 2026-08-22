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
    private static final byte[] EMPTY_BODY = new byte[0];
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

        /*
         * 心跳没有业务对象，不应该为了传输 "ping"/"pong" 再启动一次序列化。
         * 普通消息才根据协议头中的 codec 查找 JDK/Kryo 序列化器。
         */
        byte[] body = EMPTY_BODY;
        if(!isHeartbeat(message.getMessageType())){
            Serializer serializer = SerializerResolver.resolve(message.getCodec());
            byte[] serializedBody = serializer.serialize(message.getData());

            /*
             * 同时限制压缩前长度，避免客户端用一个很小的压缩包表达超大的对象。
             * 该限制也和解压端的最大输出长度保持一致。
             */
            int maxBodyLength = RpcConstants.MAX_FRAME_LENGTH - RpcConstants.HEADER_LENGTH;
            if(serializedBody.length > maxBodyLength){
                throw new TooLongFrameException("RPC serialized body too large: " + serializedBody.length);
            }
            body = CompressResolver.compress(
                    message.getCompress(),
                    serializedBody
            );
        }

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
            validateBusinessMetadata(message);
        } else if (message.getMessageType() == RpcConstants.RESPONSE_TYPE) {
            if (!(message.getData() instanceof RpcResponse)) {
                throw new IllegalArgumentException("RPC response body type is invalid");
            }
            validateBusinessMetadata(message);
        } else if (message.getMessageType() == RpcConstants.HEARTBEAT_REQUEST_TYPE) {
            if (!RpcConstants.PING.equals(message.getData())) {
                throw new IllegalArgumentException("Heartbeat request data must be ping");
            }
            validateHeartbeatMetadata(message);
        } else if (message.getMessageType() == RpcConstants.HEARTBEAT_RESPONSE_TYPE) {
            if (!RpcConstants.PONG.equals(message.getData())) {
                throw new IllegalArgumentException("Heartbeat response data must be pong");
            }
            validateHeartbeatMetadata(message);
        } else {
            throw new IllegalArgumentException("Unsupported message type: "
                    + message.getMessageType());
        }
    }

    /**
     * 普通业务消息必须明确选择一种可用序列化器。
     * V12 才会引入压缩，所以当前仍只接受 NO_COMPRESS。
     */
    private void validateBusinessMetadata(RpcMessage message) {
        SerializerResolver.resolve(message.getCodec());
        // V12 同时允许 NO_COMPRESS 和 GZIP_COMPRESS。
        // 未知编号仍然会在这里被拒绝。
        CompressResolver.validate(message.getCompress());
    }

    /**
     * 心跳帧必须使用保留元数据：无序列化、无压缩、请求号为 0。
     * 这样可以保证心跳始终是固定 16 字节，也不会占用业务请求号。
     */
    private void validateHeartbeatMetadata(RpcMessage message) {
        if (message.getCodec() != RpcConstants.NO_CODEC) {
            throw new IllegalArgumentException("Heartbeat frame must use NO_CODEC");
        }
        if (message.getCompress() != RpcConstants.NO_COMPRESS) {
            throw new IllegalArgumentException("Heartbeat frame must use NO_COMPRESS");
        }
        if (message.getRequestId() != RpcConstants.HEARTBEAT_REQUEST_ID) {
            throw new IllegalArgumentException("Heartbeat requestId must be 0");
        }
    }

    private boolean isHeartbeat(byte messageType) {
        return messageType == RpcConstants.HEARTBEAT_REQUEST_TYPE
                || messageType == RpcConstants.HEARTBEAT_RESPONSE_TYPE;
    }
}
