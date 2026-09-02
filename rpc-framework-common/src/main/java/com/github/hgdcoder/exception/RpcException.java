package com.github.hgdcoder.exception;

import com.github.hgdcoder.enums.RpcErrorMessageEnum;

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
        super(buildMessage(errorType, detail), cause);
        if (errorType == null) {
            throw new IllegalArgumentException("errorType must not be null");
        }
        this.errorType = errorType;
    }

    public RpcErrorMessageEnum getErrorType() {
        return errorType;
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
}
