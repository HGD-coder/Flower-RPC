package com.github.hgdcoder.transport;

import com.github.hgdcoder.extension.SPI;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;

import java.util.concurrent.CompletableFuture;

@SPI
public interface RpcRequestTransport {
    /**
     * send rpc request to server and get result
     *
     * @param rpcRequest message body
     * @return 响应 Future。发送成功、收到响应或发生异常时，由传输层完成它
     */
    CompletableFuture<RpcResponse<Object>> sendRpcRequest(RpcRequest rpcRequest);
}
