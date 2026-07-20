package com.github.hgdcoder.transport.socket;

import com.github.hgdcoder.enums.RpcResponseCodeEnum;
import com.github.hgdcoder.provider.ServiceProvider;
import com.github.hgdcoder.remoting.codec.RpcMessageCodec;
import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.remoting.dto.RpcMessage;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.remoting.handler.RpcRequestHandler;
import com.github.hgdcoder.serialize.jdk.JdkSerializer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 基于 BIO Socket 的 RPC 服务端。
 *
 * 网络模型仍与 V7 相同：一条连接由一个服务端任务循环处理；
 * V8 只替换这条连接上的消息格式，不改变注册、发现和业务处理流程。
 */
public class SocketRpcServer {
    private static final int SERVER_BACKLOG = 1024;
    private static final int SOCKET_IDLE_TIMEOUT_MILLIS = 60000;

    private final int port;
    private final ServiceProvider serviceProvider;
    private final RpcMessageCodec messageCodec = new RpcMessageCodec(new JdkSerializer());

    private final ExecutorService threadPool = new ThreadPoolExecutor(
            8,
            32,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1024),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public SocketRpcServer(int port, ServiceProvider serviceProvider) {
        this.port = port;
        this.serviceProvider = serviceProvider;
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.bind(new InetSocketAddress(port), SERVER_BACKLOG);

            RpcRequestHandler handler = new RpcRequestHandler(serviceProvider);
            while (true) {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_IDLE_TIMEOUT_MILLIS);
                threadPool.execute(() -> handle(socket, handler));
            }
        } catch (Exception e) {
            throw new RuntimeException("Socket rpc server start failed", e);
        }
    }

    private void handle(Socket socket, RpcRequestHandler handler) {
        DataOutputStream out = null;
        DataInputStream in = null;

        try (Socket ignored = socket) {
            out = new DataOutputStream(socket.getOutputStream());
            in = new DataInputStream(socket.getInputStream());

            while (!socket.isClosed()) {
                RpcMessage requestMessage;
                try {
                    requestMessage = messageCodec.decode(in);
                } catch (EOFException | SocketException | SocketTimeoutException e) {
                    // 客户端正常关闭、连接断开或长时间空闲时，结束当前连接任务。
                    break;
                }

                if (requestMessage.getMessageType() != RpcConstants.REQUEST_TYPE
                        || !(requestMessage.getData() instanceof RpcRequest)) {
                    throw new IOException("Invalid RPC request message");
                }

                RpcRequest request = (RpcRequest) requestMessage.getData();
                RpcResponse<?> response = handleRequest(handler, request);

                RpcMessage responseMessage = RpcMessage.builder()
                        .messageType(RpcConstants.RESPONSE_TYPE)
                        .codec(requestMessage.getCodec())
                        .compress(requestMessage.getCompress())
                        // 响应必须原样带回协议请求编号。
                        .requestId(requestMessage.getRequestId())
                        .data(response)
                        .build();
                messageCodec.encode(out, responseMessage);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private RpcResponse<?> handleRequest(RpcRequestHandler handler, RpcRequest request) {
        try {
            Object result = handler.handle(request);
            return RpcResponse.success(result, request.getRequestId());
        } catch (Exception e) {
            return RpcResponse.fail(
                    RpcResponseCodeEnum.FAIL,
                    request.getRequestId(),
                    e.getMessage()
            );
        }
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }

        try {
            closeable.close();
        } catch (Exception ignored) {
            // 连接退出时尽力释放资源即可。
        }
    }
}
