package com.github.hgdcoder.exception;

import com.github.hgdcoder.enums.RpcErrorMessageEnum;
import com.github.hgdcoder.enums.RpcStatusCode;

/**
 * Flower-RPC 的统一运行时异常。
 *
 * <p>它继承 RuntimeException，因此业务接口不需要在每个方法上声明
 * {@code throws RpcException}。errorType 用来判断失败阶段，cause 用来
 * 保留最底层异常，例如 IOException 或 InvocationTargetException。</p>
 */
public class RpcException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final RpcErrorMessageEnum errorType;
    private final RpcStatusCode statusCode;
    private final String requestId;

    public RpcException(RpcErrorMessageEnum errorType) {
        this(errorType, null, null);
    }

    public RpcException(RpcErrorMessageEnum errorType, String detail) {
        this(errorType, detail, null);
    }

    public RpcException(
            RpcErrorMessageEnum errorType,
            String detail,
            Throwable cause
    ) {
        this(requireErrorType(errorType).getStatusCode(), null,
                buildMessage(errorType, detail), cause, errorType);
    }

    public RpcException(RpcStatusCode statusCode, String detail) {
        this(statusCode, null, detail, null, null);
    }

    public RpcException(
            RpcStatusCode statusCode,
            String requestId,
            String detail,
            Throwable cause
    ) {
        this(statusCode, requestId, detail, cause, null);
    }

    private RpcException(
            RpcStatusCode statusCode,
            String requestId,
            String detail,
            Throwable cause,
            RpcErrorMessageEnum errorType
    ) {
        super(buildStatusMessage(statusCode, detail), cause);
        this.statusCode = requireStatusCode(statusCode);
        this.requestId = requestId;
        this.errorType = errorType;
    }

    public RpcErrorMessageEnum getErrorType() {
        return errorType;
    }

    public RpcStatusCode getStatusCode() {
        return statusCode;
    }

    public String getRequestId() {
        return requestId;
    }

    private static String buildMessage(
            RpcErrorMessageEnum errorType,
            String detail
    ) {
        if (errorType == null) {
            throw new IllegalArgumentException("errorType must not be null");
        }
        if (detail == null || detail.trim().isEmpty()) {
            return errorType.getMessage();
        }
        return errorType.getMessage() + ": " + detail;
    }

    private static String buildStatusMessage(
            RpcStatusCode statusCode,
            String detail
    ) {
        RpcStatusCode required = requireStatusCode(statusCode);
        if (detail == null || detail.trim().isEmpty()) {
            return required.getMessage();
        }
        return detail;
    }

    private static RpcErrorMessageEnum requireErrorType(
            RpcErrorMessageEnum errorType
    ) {
        if (errorType == null) {
            throw new IllegalArgumentException("errorType must not be null");
        }
        return errorType;
    }

    private static RpcStatusCode requireStatusCode(RpcStatusCode statusCode) {
        if (statusCode == null || statusCode == RpcStatusCode.OK) {
            throw new IllegalArgumentException(
                    "exception statusCode must be a non-OK value"
            );
        }
        return statusCode;
    }
}
