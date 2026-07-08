package com.github.hgdcoder.remoting.dto;

import com.github.hgdcoder.enums.RpcResponseCodeEnum;
import lombok.*;

import java.io.Serializable;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@ToString
public class RpcResponse<T> implements Serializable {
    private static final long serialVersionUID = 1L;
    private String requestId;
    //response code
    private Integer code;
    //response message
    private String message;
    //response body
    private T data;


    public static <T> RpcResponse<T> success(T data, String requestId) {
        RpcResponse<T> response=new RpcResponse<>();
        response.setCode(RpcResponseCodeEnum.SUCCESS.getCode());
        response.setMessage(RpcResponseCodeEnum.SUCCESS.getMessage());
        response.setRequestId(requestId);
        if(null!=data){
            response.setData(data);
        }
        return response;
    }


    //Todo 搞清楚fail时候是否要传入参数requestId并返回
    public static <T> RpcResponse<T> fail(RpcResponseCodeEnum rpcResponseCodeEnum) {
        RpcResponse<T> response =new RpcResponse<>();
        response.setCode(rpcResponseCodeEnum.getCode());
        response.setMessage(rpcResponseCodeEnum.getMessage());
        return response;
    }
}
