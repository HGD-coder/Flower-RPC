package com.github.hgdcoder.remoting.handler;

import com.github.hgdcoder.enums.RpcStatusCode;
import com.github.hgdcoder.exception.RpcException;
import com.github.hgdcoder.exception.RpcServiceException;
import com.github.hgdcoder.remoting.dto.RpcResponse;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** 将服务端异常统一转换成稳定且不会泄露内部细节的 RPC 响应。 */
public class RpcResponseFactory {
    private RpcResponseFactory() {}

    public static RpcResponse<Object> failure(
            String requestId,
            Throwable throwable
    ) {
        Throwable cause = unwrap(throwable);
        RpcStatusCode statusCode = statusOf(cause);
        return RpcResponse.fail(
                statusCode,
                requestId,
                safeMessage(cause, statusCode)
        );
    }

    public static RpcStatusCode statusOf(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof RpcException) {
            return ((RpcException) cause).getStatusCode();
        }
        if (cause instanceof CancellationException) {
            return RpcStatusCode.CANCELLED;
        }
        if (cause instanceof TimeoutException) {
            return RpcStatusCode.DEADLINE_EXCEEDED;
        }
        if (cause instanceof IllegalArgumentException) {
            return RpcStatusCode.INVALID_ARGUMENT;
        }
        return RpcStatusCode.INTERNAL;
    }


    /** 去掉 Future 和反射附加的包装异常，找到真正失败原因。 */
    public static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException
                || current instanceof InvocationTargetException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeMessage(
            Throwable cause,
            RpcStatusCode statusCode
    ) {
        if (cause instanceof RpcServiceException && hasMessage(cause)) {
            return cause.getMessage();
        }
        if (cause instanceof RpcException
                && statusCode != RpcStatusCode.INTERNAL
                && statusCode != RpcStatusCode.UNKNOWN
                && statusCode != RpcStatusCode.DATA_LOSS
                && hasMessage(cause)) {
            return cause.getMessage();
        }
        return statusCode.getMessage();
    }

    private static boolean hasMessage(Throwable throwable) {
        return throwable.getMessage() != null
                && !throwable.getMessage().trim().isEmpty();
    }
}
