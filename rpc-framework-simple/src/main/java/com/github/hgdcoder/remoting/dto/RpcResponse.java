package com.github.hgdcoder.remoting.dto;

import com.github.hgdcoder.enums.RpcResponseCodeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@ToString
public class RpcResponse<T> implements Serializable {
    private static final long serialVersionUID = 1L;

    private String requestId;
    private Integer code;
    private String message;
    private T data;

    public static <T> RpcResponse<T> success(T data, String requestId) {
        RpcResponse<T> response = new RpcResponse<>();
        response.setCode(RpcResponseCodeEnum.SUCCESS.getCode());
        response.setMessage(RpcResponseCodeEnum.SUCCESS.getMessage());
        response.setRequestId(requestId);
        response.setData(data);
        return response;
    }

    public static <T> RpcResponse<T> fail(RpcResponseCodeEnum rpcResponseCodeEnum) {
        return fail(rpcResponseCodeEnum, null, rpcResponseCodeEnum.getMessage());
    }

    public static <T> RpcResponse<T> fail(
            RpcResponseCodeEnum rpcResponseCodeEnum,
            String requestId,
            String message
    ) {
        RpcResponse<T> response = new RpcResponse<>();
        response.setCode(rpcResponseCodeEnum.getCode());
        response.setMessage(message == null ? rpcResponseCodeEnum.getMessage() : message);
        response.setRequestId(requestId);
        return response;
    }
}
