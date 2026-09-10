package com.github.hgdcoder.benchmark;

import com.github.hgdcoder.enums.RpcStatusCode;
import com.github.hgdcoder.exception.RpcException;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** 一次已结束调用的互斥终态。 */
enum BenchmarkOutcome {
    SUCCESS,
    CANCELLED,
    DEADLINE_EXCEEDED,
    RESOURCE_EXHAUSTED,
    UNAVAILABLE,
    OTHER_RPC_ERROR,
    CLIENT_ERROR;

    static BenchmarkOutcome from(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof CancellationException) {
            return CANCELLED;
        }
        if (cause instanceof TimeoutException) {
            return DEADLINE_EXCEEDED;
        }
        if (cause instanceof RpcException) {
            RpcStatusCode status = ((RpcException) cause).getStatusCode();
            if (status == RpcStatusCode.CANCELLED) {
                return CANCELLED;
            }
            if (status == RpcStatusCode.DEADLINE_EXCEEDED) {
                return DEADLINE_EXCEEDED;
            }
            if (status == RpcStatusCode.RESOURCE_EXHAUSTED) {
                return RESOURCE_EXHAUSTED;
            }
            if (status == RpcStatusCode.UNAVAILABLE) {
                return UNAVAILABLE;
            }
            return OTHER_RPC_ERROR;
        }
        return CLIENT_ERROR;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
