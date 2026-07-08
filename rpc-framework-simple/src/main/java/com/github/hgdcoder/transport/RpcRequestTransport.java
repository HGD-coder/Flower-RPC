package com.github.hgdcoder.transport;

import com.github.hgdcoder.extension.SPI;
import com.github.hgdcoder.remoting.dto.RpcRequest;

@SPI
public interface RpcRequestTransport {
    /**
     * send rpc request to server and get result
     *
     * @param rpcRequest message body
     * @return data from server
     */
    Object sendRpcRequest(RpcRequest rpcRequest);
}
