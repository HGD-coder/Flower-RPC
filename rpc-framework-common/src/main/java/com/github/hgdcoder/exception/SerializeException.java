package com.github.hgdcoder.exception;

import com.github.hgdcoder.enums.RpcErrorMessageEnum;

public class SerializeException extends RpcException {
    private static final long serialVersionUID = 1L;

    public SerializeException(
            RpcErrorMessageEnum errorType,
            String serializerName,
            Throwable cause
    ) {
        super(errorType, serializerName, cause);
        if (errorType != RpcErrorMessageEnum.SERIALIZATION_FAILURE
                && errorType != RpcErrorMessageEnum.DESERIALIZATION_FAILURE) {
            throw new IllegalArgumentException(
                    "SerializeException only accepts serialization error types"
            );
        }
    }
}
