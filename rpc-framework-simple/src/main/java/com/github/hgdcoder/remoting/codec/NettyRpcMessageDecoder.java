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
 * 校验完整协议头，并通过 Serializer SPI 反序列化消息体。
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
        if(messageType != RpcConstants.REQUEST_TYPE
        && messageType != RpcConstants.RESPONSE_TYPE) {
            throw new CorruptedFrameException("Unsupported message type: " + messageType);
        }

        byte codec = frame.readByte();
        Serializer serializer;
        try {
            serializer = SerializerResolver.resolve(codec);
        } catch (IllegalArgumentException e) {
            throw new CorruptedFrameException(e.getMessage(), e);
        }

        byte compress = frame.readByte();
        if (compress != RpcConstants.NO_COMPRESS) {
            throw new CorruptedFrameException("Unsupported compress type: " + compress);
        }

        int requestId = frame.readInt();
        byte[] body = new byte[fullLength-RpcConstants.HEADER_LENGTH];
        frame.readBytes(body);
        Class<?> bodyType = messageType == RpcConstants.REQUEST_TYPE ? RpcRequest.class : RpcResponse.class;

        Object data;
        try{
            data = serializer.deserialize(body, bodyType);
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
}
