package com.github.hgdcoder.transport;

import com.github.hgdcoder.extension.SPI;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;

import java.util.concurrent.CompletableFuture;

/**
 * 客户端请求传输扩展点。
 *
 * <p>NettyRpcClient 和 SocketRpcClient 都实现该接口。</p>
 */
@SPI
public interface RpcRequestTransport extends AutoCloseable {
    /**
     * send rpc request to server and get result
     *
     * @param rpcRequest message body
     * @return 响应 Future。发送成功、收到响应或发生异常时，由传输层完成它
     */
    CompletableFuture<RpcResponse<Object>> sendRpcRequest(RpcRequest rpcRequest);

    /**
     * 统一所有传输实现的生命周期接口。
     *
     * <p>没有线程或连接资源的实现可以使用默认空方法；
     * NettyRpcClient 和 SocketRpcClient 会覆盖它，
     * 关闭自己的连接及线程。</p>
     */
    @Override
    default void close() {
        // 默认实现没有资源，因此不需要清理。
    }
}
