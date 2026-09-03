package com.github.hgdcoder.exception;

import com.github.hgdcoder.enums.RpcStatusCode;

/**
 * 服务实现可以主动抛出的业务异常。
 *
 * <p>它的 message 被视为可以安全返回给客户端的业务提示，
 * 例如“订单不存在”；普通内部异常的 message 不会直接暴露。</p>
 */
public class RpcServiceException extends RpcException{
    private static final long serialVersionUID = 1L;

    public RpcServiceException(RpcStatusCode statusCode,String message) {
        super(statusCode,message);
    }
}
