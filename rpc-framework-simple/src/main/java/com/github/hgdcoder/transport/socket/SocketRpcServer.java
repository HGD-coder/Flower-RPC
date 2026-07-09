package com.github.hgdcoder.transport.socket;

import com.github.hgdcoder.enums.RpcResponseCodeEnum;
import com.github.hgdcoder.provider.ServiceProvider;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.remoting.handler.RpcRequestHandler;

import java.io.Closeable;
import java.io.EOFException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class SocketRpcServer {
    private static final int SERVER_BACKLOG = 1024;
    private static final int SOCKET_IDLE_TIMEOUT_MILLIS = 60000;

    private final int port;
    private final ServiceProvider serviceProvider;

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
        ObjectOutputStream out = null;
        ObjectInputStream in = null;

        try (Socket ignored = socket) {
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in = new ObjectInputStream(socket.getInputStream());

            while (!socket.isClosed()) {
                RpcRequest request;

                try {
                    Object object = in.readObject();
                    if (!(object instanceof RpcRequest)) {
                        continue;
                    }
                    request = (RpcRequest) object;
                } catch (EOFException | SocketException | SocketTimeoutException e) {
                    break;
                }

                RpcResponse<?> response = handleRequest(handler, request);
                out.writeObject(response);
                out.flush();
                out.reset();
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
        }
    }
}
