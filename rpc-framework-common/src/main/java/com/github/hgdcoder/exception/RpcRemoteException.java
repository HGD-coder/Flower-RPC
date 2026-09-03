package com.github.hgdcoder.exception;

import com.github.hgdcoder.enums.RpcStatusCode;

/**
 * 客户端收到失败响应后创建的异常。
 *
 * 它表示网络通信已经完成，但是远端没有成功处理业务请求。
 */
public class RpcRemoteException extends RpcException {
    private static final long serialVersionUID = 1L;

    public RpcRemoteException(
            RpcStatusCode statusCode,
            String requestId,
            String remoteMessage
    ) {
        super(
                statusCode,
                requestId,
                remoteMessage,
                null
        );
    }
}
