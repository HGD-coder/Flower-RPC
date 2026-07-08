package com.github.hgdcoder.transport.socket;

import com.github.hgdcoder.provider.ServiceProvider;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.remoting.handler.RpcRequestHandler;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SocketRpcServer {
    private final int port;
    private final ServiceProvider serviceProvider;
    private final ExecutorService threadPool = Executors.newFixedThreadPool(8);

    public SocketRpcServer(int port, ServiceProvider serviceProvider) {
        this.port = port;
        this.serviceProvider = serviceProvider;
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            RpcRequestHandler handler = new RpcRequestHandler(serviceProvider);
            while (true) {
                Socket socket = serverSocket.accept();
                threadPool.execute(() -> handle(socket, handler));
            }
        } catch (Exception e) {
            throw new RuntimeException("Socket rpc server start failed", e);
        }
    }

    private void handle(Socket socket, RpcRequestHandler handler) {
        try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())
        ) {
            RpcRequest request = (RpcRequest) in.readObject();
            Object result = handler.handle(request);
            out.writeObject(RpcResponse.success(result, request.getRequestId()));
            out.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
