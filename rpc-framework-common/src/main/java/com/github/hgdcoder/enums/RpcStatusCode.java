package com.github.hgdcoder.enums;

/**
 * 写入每个 RPC 响应的稳定状态码。
 *
 * <p>数值采用通用 RPC 状态模型。某个数值一旦发布就不能改变含义，
 * 否则新客户端会错误理解旧服务端返回的响应。</p>
 */
public enum RpcStatusCode {
    OK(0, "OK"),
    CANCELLED(1, "Request cancelled"),
    UNKNOWN(2, "Unknown error"),
    INVALID_ARGUMENT(3, "Invalid argument"),
    DEADLINE_EXCEEDED(4, "Deadline exceeded"),
    NOT_FOUND(5, "Service or resource not found"),
    ALREADY_EXISTS(6, "Resource already exists"),
    PERMISSION_DENIED(7, "Permission denied"),
    RESOURCE_EXHAUSTED(8, "Resource exhausted"),
    FAILED_PRECONDITION(9, "Failed precondition"),
    ABORTED(10, "Operation aborted"),
    OUT_OF_RANGE(11, "Value out of range"),
    UNIMPLEMENTED(12, "Operation is not implemented"),
    INTERNAL(13, "Internal server error"),
    UNAVAILABLE(14, "Service unavailable"),
    DATA_LOSS(15, "Unrecoverable data loss"),
    UNAUTHENTICATED(16, "Unauthenticated");

    private final int code;
    private final String message;

    RpcStatusCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    /**
     * 将响应中的整数还原成状态枚举。
     * 未知数字表示协议不兼容或响应数据损坏。
     */
    public static RpcStatusCode fromCode(int code) {
        for (RpcStatusCode statusCode : values()) {
            if (statusCode.code == code) {
                return statusCode;
            }
        }

        throw new IllegalArgumentException(
                "Unknown RPC status code: " + code
        );
    }
}
