package com.github.hgdcoder.remoting.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 自定义协议中的完整消息。
 *
 * RpcRequest/RpcResponse 是业务消息体；RpcMessage 是网络传输信封，
 * 额外携带消息类型、序列化方式、压缩方式和协议请求编号。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpcMessage {
    private byte messageType;
    private byte codec;
    private byte compress;
    private int requestId;
    private Object data;
}