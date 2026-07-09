package com.github.hgdcoder.transport.socket;

import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.transport.RpcRequestTransport;

import java.io.Closeable;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class SocketRpcClient implements RpcRequestTransport {
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 3000;
    private static final int DEFAULT_READ_TIMEOUT_MILLIS = 5000;

    private final String host;
    private final int port;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    // One connection per caller thread. This avoids multiple threads reading and
    // writing the same ObjectInputStream/ObjectOutputStream at the same time.
    private final ThreadLocal<SocketConnection> connectionHolder = new ThreadLocal<>();

    public SocketRpcClient(String host, int port) {
        this(host, port, DEFAULT_CONNECT_TIMEOUT_MILLIS, DEFAULT_READ_TIMEOUT_MILLIS);
    }

    public SocketRpcClient(String host, int port, int connectTimeoutMillis, int readTimeoutMillis) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public Object sendRpcRequest(RpcRequest rpcRequest) {
        SocketConnection connection = getOrCreateConnection();

        try {
            return connection.send(rpcRequest);
        } catch (Exception e) {
            closeCurrentConnection();
            throw new RuntimeException("Send rpc request failed", e);
        }
    }

    private SocketConnection getOrCreateConnection() {
        SocketConnection connection = connectionHolder.get();
        if (connection == null || !connection.isAvailable()) {
            connection = new SocketConnection(host, port, connectTimeoutMillis, readTimeoutMillis);
            connectionHolder.set(connection);
        }
        return connection;
    }

    public void closeCurrentConnection() {
        SocketConnection connection = connectionHolder.get();
        if (connection != null) {
            connection.close();
            connectionHolder.remove();
        }
    }

    private static class SocketConnection implements Closeable {
        private final Socket socket;
        private ObjectOutputStream out;
        private ObjectInputStream in;

        SocketConnection(String host, int port, int connectTimeoutMillis, int readTimeoutMillis) {
            try {
                socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
                socket.setSoTimeout(readTimeoutMillis);

                // Both client and server create ObjectOutputStream first and flush
                // the stream header, then create ObjectInputStream. This avoids
                // both sides blocking while waiting for the other side's header.
                out = new ObjectOutputStream(socket.getOutputStream());
                out.flush();
                in = new ObjectInputStream(socket.getInputStream());
            } catch (Exception e) {
                close();
                throw new RuntimeException("Create socket connection failed", e);
            }
        }

        Object send(RpcRequest rpcRequest) throws Exception {
            out.writeObject(rpcRequest);
            out.flush();
            // Clear ObjectOutputStream's handle table so long-lived streams do not
            // keep growing and later writes are serialized as fresh objects.
            out.reset();
            return in.readObject();
        }

        boolean isAvailable() {
            return socket != null
                    && socket.isConnected()
                    && !socket.isClosed()
                    && !socket.isInputShutdown()
                    && !socket.isOutputShutdown();
        }

        @Override
        public void close() {
            closeQuietly(in);
            closeQuietly(out);
            closeQuietly(socket);
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
}
