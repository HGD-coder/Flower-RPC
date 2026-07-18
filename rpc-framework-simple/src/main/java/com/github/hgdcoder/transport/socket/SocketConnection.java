package com.github.hgdcoder.transport.socket;

import com.github.hgdcoder.remoting.dto.RpcRequest;

import java.io.Closeable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 表示一条真实的 Socket 长连接。
 *
 * 一个 SocketConnection 持有：
 * 1. Socket
 * 2. ObjectOutputStream
 * 3. ObjectInputStream
 *
 * 当前连接由一个调用线程独占，因此不需要给 send() 加锁。
 * 后续切换到 Netty 后，它会被 Netty 的 Channel 替代。
 */
public class SocketConnection implements Closeable {
    private final Socket socket;

    private ObjectOutputStream outputStream;
    private ObjectInputStream inputStream;

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

            /*
             * ObjectOutputStream 创建时会立即写入流头。
             * ObjectInputStream 创建时会等待对端流头。
             *
             * 客户端和服务端都先创建 ObjectOutputStream，
             * 可以避免双方同时等待对方流头而发生死锁。
             */
            outputStream = new ObjectOutputStream(socket.getOutputStream());
            outputStream.flush();
            inputStream = new ObjectInputStream(socket.getInputStream());
        }catch (Exception e){
            close();
            throw new RuntimeException("Create socket connection failed: " + address,e);
        }
    }

    /**
     * 在当前连接上发送一次 RPC 请求，并同步等待响应。
     *
     * 当前一个连接只属于一个线程，所以同一时间只有一个请求：
     *
     * write request
     * -> read response
     * -> write next request
     */
    Object send(RpcRequest rpcRequest)throws Exception{
        outputStream.writeObject(rpcRequest);
        outputStream.flush();

        /*
         * ObjectOutputStream 会缓存已经序列化过的对象引用。
         * 长连接反复发送请求时需要清空引用表，避免内存持续增长，
         * 也避免相同对象再次发送时只写入旧引用。
         */
        outputStream.reset();

        return inputStream.readObject();
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
