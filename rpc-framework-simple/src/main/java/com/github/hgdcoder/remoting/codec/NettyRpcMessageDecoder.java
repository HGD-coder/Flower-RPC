package com.github.hgdcoder.remoting.codec;

import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.remoting.dto.RpcMessage;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.serialize.Serializer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.Arrays;
import java.util.List;

/**
 * 校验完整协议头，并把网络字节还原为 RpcMessage。
 *
 * 普通 RPC 帧通过 Serializer SPI 反序列化消息体；
 * V11 心跳帧没有消息体，解码器根据 messageType 直接还原为 PING 或 PONG。
 *
 * 位于 Pipeline 的入站方向，接收 NettyRpcFrameDecoder 已切出的完整帧；
 * 成功后将 RpcMessage 交给客户端或服务端处理器，绝不处理半包和粘包。
 */
public class NettyRpcMessageDecoder extends MessageToMessageDecoder<ByteBuf> {
    /**
     * 读取一帧协议数据，验证元数据后产出一个 RpcMessage。
     *
     * @param ctx 当前连接的上下文，本方法只使用其所在的入站调用时机
     * @param frame 上一入站处理器保证完整的单帧 ByteBuf
     * @param out 解码成功后加入一个 RpcMessage，供 Pipeline 的下一个入站处理器消费
     * 协议头或消息体不合法时抛出 CorruptedFrameException，由 Netty 的异常路径处理。
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out){
        int actualLength = frame.readableBytes();
        if (actualLength < RpcConstants.HEADER_LENGTH) {
            throw new CorruptedFrameException("RPC frame is shorter than header: " + actualLength);
        }

        byte[] magic = new byte[RpcConstants.MAGIC_NUMBER.length];
        frame.readBytes(magic);
        if (!Arrays.equals(magic, RpcConstants.MAGIC_NUMBER)) {
            throw new CorruptedFrameException("Unknown magic number: " + Arrays.toString(magic));
        }

        byte version = frame.readByte();
        if (version != RpcConstants.VERSION) {
            throw new CorruptedFrameException("Unsupported protocol version: " + version);
        }

        int fullLength = frame.readInt();
        if(fullLength < RpcConstants.HEADER_LENGTH
                || fullLength > RpcConstants.MAX_FRAME_LENGTH
                || fullLength != actualLength) {
            throw new CorruptedFrameException("Invalid RPC frame length: declared="
                    + fullLength + ", actual=" + actualLength);
        }

        byte messageType = frame.readByte();
        if (!isSupportedMessageType(messageType)) {
            throw new CorruptedFrameException("Unsupported message type: " + messageType);
        }

        byte codec = frame.readByte();
        byte compress = frame.readByte();
        int requestId = frame.readInt();

        /*
         * 心跳帧走一条独立的轻量路径。
         * 它不会查找序列化器，也不会创建消息体 byte[]，因此固定只占 16 字节。
         */
        if(isHeartbeat(messageType)){
            validateHeartbeatFrame(
                    fullLength,
                    codec,
                    compress,
                    requestId
            );
            Object heartbeatData =  messageType == RpcConstants.HEARTBEAT_REQUEST_TYPE
                    ? RpcConstants.PING
                    : RpcConstants.PONG;
            out.add(RpcMessage.builder()
                    .messageType(messageType)
                    .codec(codec)
                    .compress(compress)
                    .requestId(requestId)
                    .data(heartbeatData)
                    .build());
            return;
        }

        /*
         * 从这里开始一定是普通请求或普通响应。
         * 先校验压缩算法编号，未知值不能继续进入消息体处理。
         */
        Serializer serializer;
        try {
            serializer = SerializerResolver.resolve(codec);
            CompressResolver.validate(compress);
        } catch (IllegalArgumentException e) {
            throw new CorruptedFrameException(e.getMessage(), e);
        }

        int bodyLength = fullLength - RpcConstants.HEADER_LENGTH;
        if (bodyLength == 0) {
            throw new CorruptedFrameException("RPC business frame body must not be empty");
        }

        byte[] compressedBody = new byte[bodyLength];
        frame.readBytes(compressedBody);
        /*
         * 编码端是“序列化 -> 压缩”，所以解码端必须严格反过来：
         * “解压 -> 反序列化”。NO_COMPRESS 会直接返回原字节。
         */
        byte[] serializedBody;
        try{
            serializedBody = CompressResolver.decompress(
                    compress,
                    compressedBody
            );
        }catch (RuntimeException e){
            throw new CorruptedFrameException("RPC body decompress failed", e);
        }

        if(serializedBody.length ==0 ) {
            throw new CorruptedFrameException("RPC business frame body must not be empty");
        }

        Class<?> bodyType = messageType == RpcConstants.REQUEST_TYPE
                ? RpcRequest.class
                : RpcResponse.class;

        Object data;
        try{
            data = serializer.deserialize(serializedBody, bodyType);
        }catch (RuntimeException e){
            throw new CorruptedFrameException("RPC body deserialize failed", e);
        }
        out.add(RpcMessage.builder()
                .messageType(messageType)
                .codec(codec)
                .compress(compress)
                .requestId(requestId)
                .data(data)
                .build());
    }

    /**
     * 心跳协议元数据必须与编码器的约定完全一致。
     * 任一字段不符合约定，都说明对端实现版本不一致或数据已损坏。
     */
    private void validateHeartbeatFrame(int fullLength,
                                        byte codec,
                                        byte compress,
                                        int requestId) {
        if (fullLength != RpcConstants.HEADER_LENGTH) {
            throw new CorruptedFrameException(
                    "Heartbeat frame must contain header only: " + fullLength
            );
        }
        if (codec != RpcConstants.NO_CODEC) {
            throw new CorruptedFrameException("Heartbeat frame must use NO_CODEC");
        }
        if (compress != RpcConstants.NO_COMPRESS) {
            throw new CorruptedFrameException("Heartbeat frame must use NO_COMPRESS");
        }
        if (requestId != RpcConstants.HEARTBEAT_REQUEST_ID) {
            throw new CorruptedFrameException("Heartbeat requestId must be 0");
        }
    }

    private boolean isSupportedMessageType(byte messageType) {
        return messageType == RpcConstants.REQUEST_TYPE
                || messageType == RpcConstants.RESPONSE_TYPE
                || messageType == RpcConstants.HEARTBEAT_REQUEST_TYPE
                || messageType == RpcConstants.HEARTBEAT_RESPONSE_TYPE;
    }

    private boolean isHeartbeat(byte messageType) {
        return messageType == RpcConstants.HEARTBEAT_REQUEST_TYPE
                || messageType == RpcConstants.HEARTBEAT_RESPONSE_TYPE;
    }
}
