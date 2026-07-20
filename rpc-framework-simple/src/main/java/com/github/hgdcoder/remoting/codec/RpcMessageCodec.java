package com.github.hgdcoder.remoting.codec;

import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.remoting.dto.RpcMessage;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.serialize.Serializer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * 自定义协议编解码器。
 *
 * TCP 只提供连续字节流，没有“一个 read 对应一个消息”的边界。
 * 编码器在头部写入 fullLength，解码器先读固定 16 字节头，
 * 再按 bodyLength 精确读取消息体，从而明确每个 RPC 数据包的边界。
 */
public final class RpcMessageCodec {
    private final Serializer serializer;

    public RpcMessageCodec(Serializer serialier){
        if(serialier == null){
            throw new NullPointerException("serializer must not be null");
        }
        this.serializer = serialier;
    }

    public void encode(DataOutputStream out, RpcMessage message) throws IOException {
        checkMessageMetadata(message);

        byte[] body = serializer.serialize(message.getData());
        int fullLength = RpcConstants.HEADER_LENGTH + body.length;
        if(fullLength > RpcConstants.MAX_FRAME_LENGTH){
            throw new IOException("RPC frame is too large:"+fullLength);
        }

        out.write(RpcConstants.MAGIC_NUMBER);
        out.writeByte(RpcConstants.VERSION);
        out.writeInt(fullLength);
        out.writeByte(message.getMessageType());
        out.writeByte(message.getCodec());
        out.writeByte(message.getCompress());
        out.writeInt(message.getRequestId());
        out.write(body);
        out.flush();
    }

    public RpcMessage decode(DataInputStream in) throws IOException {
        byte[] magic = new byte[RpcConstants.MAGIC_NUMBER.length];
        in.readFully(magic);
        if(!Arrays.equals(magic,RpcConstants.MAGIC_NUMBER)){
            throw new IllegalArgumentException("Unknown magic number: " + Arrays.toString(magic));
        }

        byte version = in.readByte();
        if(version != RpcConstants.VERSION){
            throw new IllegalArgumentException("Unsupported protocol version: " + version);
        }

        int fullLength = in.readInt();
        if(fullLength < RpcConstants.HEADER_LENGTH){
            throw new IllegalArgumentException("Invalid RPC frame length: " + fullLength);
        }

        byte messageType = in.readByte();
        byte codec = in.readByte();
        byte compress = in.readByte();
        int requestId = in.readInt();

        RpcMessage message = RpcMessage.builder()
                .messageType(messageType)
                .codec(codec)
                .compress(compress)
                .requestId(requestId)
                .build();
        checkMessageMetadata(message);

        int bodyLength = fullLength - RpcConstants.HEADER_LENGTH;
        byte[] body = new byte[bodyLength];
        in.readFully(body);

        Class<?> bodyType = messageType == RpcConstants.REQUEST_TYPE ? RpcRequest.class : RpcResponse.class;
        message.setData(serializer.deserialize(body, bodyType));
        return message;
    }



    private void checkMessageMetadata(RpcMessage message){
        if(message == null){
            throw new IllegalArgumentException("rpc message must not be null");
        }
        if(message.getMessageType()!= RpcConstants.REQUEST_TYPE &&
         message.getMessageType()!= RpcConstants.RESPONSE_TYPE){
            throw new IllegalArgumentException("Unsupported message type:"+message.getMessageType());
        }
        if(message.getCodec()!=RpcConstants.JDK_CODEC){
            throw new IllegalArgumentException("Unsupported codec:"+message.getCodec());
        }
        if(message.getCompress()!=RpcConstants.NO_COMPRESS){
            throw new IllegalArgumentException("Unsupported compress type:"+message.getCompress());
        }
    }
}
