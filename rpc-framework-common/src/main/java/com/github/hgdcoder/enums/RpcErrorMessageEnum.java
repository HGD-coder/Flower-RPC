package com.github.hgdcoder.enums;

/**
 * RPC 调用链中的错误类型。
 *
 * <p>枚举负责给错误分类，异常对象负责保存本次失败的细节和原始原因。
 * 以后记录日志、统计失败次数时，可以根据枚举稳定地聚合错误，
 * 不需要解析可能变化的异常文字。</p>
 */
public enum RpcErrorMessageEnum {
    CLIENT_CONNECT_SERVER_FAILURE("客户端连接服务端失败",RpcStatusCode.UNAVAILABLE),
    SERVICE_INVOCATION_FAILURE("远程服务调用失败",RpcStatusCode.INTERNAL),
    SERVICE_CAN_NOT_BE_FOUND("没有找到指定的 RPC 服务",RpcStatusCode.NOT_FOUND),
    REQUEST_NOT_MATCH_RESPONSE("RPC 请求与响应不匹配",RpcStatusCode.DATA_LOSS),
    SERIALIZATION_FAILURE("RPC 消息序列化失败",RpcStatusCode.DATA_LOSS),
    DESERIALIZATION_FAILURE("RPC 消息反序列化失败", RpcStatusCode.DATA_LOSS);

    /** 枚举创建后错误说明不应再变化。 */
    private final String message;

    private final RpcStatusCode statusCode;

    RpcErrorMessageEnum(String message,RpcStatusCode statusCode) {
        this.message = message;
        this.statusCode = statusCode;
    }

    public String getMessage() {
        return message;
    }

    public RpcStatusCode getStatusCode() {
        return statusCode;
    }
}
