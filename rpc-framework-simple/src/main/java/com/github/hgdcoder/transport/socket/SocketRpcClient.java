package com.github.hgdcoder.transport.socket;

import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.transport.RpcRequestTransport;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;

public class SocketRpcClient implements RpcRequestTransport {
    private final String host;
    private final int port;

    public SocketRpcClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public Object sendRpcRequest(RpcRequest rpcRequest) {
        try(Socket socket = new Socket(host,port);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream())
            ) {
            out.writeObject(rpcRequest);
            out.flush();
            return in.readObject();
        }catch (Exception e) {
            throw new RuntimeException("Send rpc request failed", e);
        }
    }
}
