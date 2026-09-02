package com.github.hgdcoder.enums;

/**
 * RPC 调用链中的错误类型。
 *
 * <p>枚举负责给错误分类，异常对象负责保存本次失败的细节和原始原因。
 * 以后记录日志、统计失败次数时，可以根据枚举稳定地聚合错误，
 * 不需要解析可能变化的异常文字。</p>
 */
public enum RpcErrorMessageEnum {
    CLIENT_CONNECT_SERVER_FAILURE("客户端连接服务端失败"),
    SERVICE_INVOCATION_FAILURE("远程服务调用失败"),
    SERVICE_CAN_NOT_BE_FOUND("没有找到指定的 RPC 服务"),
    REQUEST_NOT_MATCH_RESPONSE("RPC 请求与响应不匹配"),
    SERIALIZATION_FAILURE("RPC 消息序列化失败"),
    DESERIALIZATION_FAILURE("RPC 消息反序列化失败");

    /** 枚举创建后错误说明不应再变化。 */
    private final String message;

    RpcErrorMessageEnum(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
