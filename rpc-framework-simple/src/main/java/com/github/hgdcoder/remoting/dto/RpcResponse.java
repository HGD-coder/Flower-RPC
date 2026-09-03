package com.github.hgdcoder.remoting.dto;

import com.github.hgdcoder.enums.RpcStatusCode;
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

    /** 业务请求 UUID，用于确认响应属于哪个请求。 */
    private String requestId;

    /** RpcStatusCode 对应的稳定整数值。 */
    private Integer code;

    /** 可以安全展示给调用方的响应信息。 */
    private String message;

    /** 成功时保存业务结果；失败时为 null。 */
    private T data;

    public static <T> RpcResponse<T> success(
            T data,
            String requestId
    ) {
        RpcResponse<T> response = new RpcResponse<>();

        response.setCode(RpcStatusCode.OK.getCode());
        response.setMessage(RpcStatusCode.OK.getMessage());
        response.setRequestId(requestId);
        response.setData(data);

        return response;
    }

    public static <T> RpcResponse<T> fail(
            RpcStatusCode statusCode,
            String requestId,
            String message
    ) {
        if (statusCode == null || statusCode == RpcStatusCode.OK) {
            throw new IllegalArgumentException(
                    "failure response requires a non-OK status"
            );
        }

        RpcResponse<T> response = new RpcResponse<>();

        response.setCode(statusCode.getCode());
        response.setMessage(
                message == null || message.trim().isEmpty()
                        ? statusCode.getMessage()
                        : message
        );
        response.setRequestId(requestId);
        response.setData(null);

        return response;
    }

    public boolean isSuccess() {
        return Integer.valueOf(
                RpcStatusCode.OK.getCode()
        ).equals(code);
    }
}