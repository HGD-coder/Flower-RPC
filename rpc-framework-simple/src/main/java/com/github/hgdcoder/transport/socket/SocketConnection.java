package com.github.hgdcoder.transport.socket;

import com.github.hgdcoder.remoting.codec.RpcMessageCodec;
import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.remoting.dto.RpcMessage;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.serialize.jdk.JdkSerializer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一条由当前线程独占的 Socket 长连接。
 *
 * V8 不再直接使用 ObjectInputStream/ObjectOutputStream 传对象，
 * 而是通过 RpcMessageCodec 收发具有明确边界的二进制数据包。
 */
public class SocketConnection implements Closeable {
    private static final AtomicInteger PROTOCOL_REQUEST_ID = new AtomicInteger();

    private final Socket socket;
    private final RpcMessageCodec messageCodec = new RpcMessageCodec(new JdkSerializer());

    private DataOutputStream outputStream;
    private DataInputStream inputStream;

    SocketConnection(InetSocketAddress address,
                     int connectTimeoutMillis,
                     int readTimeoutMillis)  {
        this.socket = new Socket();

        try{
            // RPC 消息通常较小，关闭 Nagle 算法可以减少小包等待时间。
            socket.setTcpNoDelay(true);

            // 建立到指定服务提供者的 TCP 连接。
            socket.connect(address, connectTimeoutMillis);

            // 防止服务端一直不返回结果，导致客户端线程永久阻塞。
            socket.setSoTimeout(readTimeoutMillis);

            outputStream = new DataOutputStream(socket.getOutputStream());
            inputStream = new DataInputStream(socket.getInputStream());
        }catch (Exception e){
            close();
            throw new RuntimeException("Create socket connection failed: " + address,e);
        }
    }

    /**
     * 发送一个请求并同步等待同一协议 requestId 的响应。
     *
     * RpcRequest.requestId 是业务调用 UUID；这里的 int requestId 属于协议层，
     * 将来改成 Netty 异步请求时，它可以用来匹配响应与 CompletableFuture。
     */
    Object send(RpcRequest rpcRequest)throws Exception{
        int requestId = PROTOCOL_REQUEST_ID.incrementAndGet();
        RpcMessage requestMessage = RpcMessage.builder()
                        .messageType(RpcConstants.REQUEST_TYPE)
                        .codec(RpcConstants.JDK_CODEC)
                        .compress(RpcConstants.NO_COMPRESS)
                        .requestId(requestId)
                        .data(rpcRequest)
                        .build();

        messageCodec.encode(outputStream, requestMessage);
        RpcMessage responseMessage = messageCodec.decode(inputStream);

        if (responseMessage.getMessageType() != RpcConstants.RESPONSE_TYPE) {
            throw new IOException("Expected RPC response, but type was: "
                    + responseMessage.getMessageType());
        }
        if (responseMessage.getRequestId() != requestId) {
            throw new IOException("Protocol request id does not match: expected="
                    + requestId + ", actual=" + responseMessage.getRequestId());
        }
        if (!(responseMessage.getData() instanceof RpcResponse)) {
            throw new IOException("RPC response body type is invalid");
        }
        return responseMessage.getData();
    }

    /**
     * 判断连接在本地状态上是否仍然可用。
     *
     * 注意：该判断无法百分之百发现网络另一端已经崩溃。
     * 真正的半开连接通常要在读写失败时才能发现。
     */
    boolean isAvailable() {
        return socket.isConnected()
                && !socket.isClosed()
                && !socket.isInputShutdown()
                && !socket.isOutputShutdown();
    }

    @Override
    public void close(){
        // 依次尝试关闭所有底层资源；某一个资源关闭失败时，仍继续释放其余资源。
        closeQuietly(outputStream);
        closeQuietly(inputStream);
        closeQuietly(socket);
    }

    private void closeQuietly(Closeable closeable){
        if(closeable == null){
            return;
        }

        try{
            closeable.close();
        }catch(Exception e){
            // 关闭资源失败不能覆盖原来的 RPC 异常
            /**
             * 不是“不抛出错误”，而是同时发生两个异常时，优先保留原来的 RPC 异常。
             * 假设：
             * RPC 调用失败，抛出 RpcException
             * 清理连接时 close() 也失败，抛出 IOException
             * 普通 finally 可能导致关闭异常覆盖 RPC 异常：
             * try {
             *     sendRpcRequest(); // 抛出 RpcException
             * } finally {
             *     connection.close(); // 又抛出 IOException
             * }
             * 调用方最后可能只看到：
             * IOException: socket close failed
             * 真正重要的：
             * RpcException: rpc request failed
             */
        }
    }
}
