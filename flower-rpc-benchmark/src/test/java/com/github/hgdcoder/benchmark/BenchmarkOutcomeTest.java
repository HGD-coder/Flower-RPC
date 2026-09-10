package com.github.hgdcoder.benchmark;

import com.github.hgdcoder.enums.RpcStatusCode;
import com.github.hgdcoder.exception.RpcException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BenchmarkOutcomeTest {
    @Test
    void classifiesNestedRpcFailures() {
        assertEquals(
                BenchmarkOutcome.RESOURCE_EXHAUSTED,
                BenchmarkOutcome.from(new CompletionException(new RpcException(
                        RpcStatusCode.RESOURCE_EXHAUSTED,
                        "queue full"
                )))
        );
        assertEquals(
                BenchmarkOutcome.DEADLINE_EXCEEDED,
                BenchmarkOutcome.from(new CompletionException(new TimeoutException()))
        );
        assertEquals(
                BenchmarkOutcome.OTHER_RPC_ERROR,
                BenchmarkOutcome.from(new RpcException(RpcStatusCode.INTERNAL, "failed"))
        );
        assertEquals(
                BenchmarkOutcome.CLIENT_ERROR,
                BenchmarkOutcome.from(new IllegalStateException("local bug"))
        );
    }
}
