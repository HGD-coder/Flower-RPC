package com.github.hgdcoder.transport.socket;

import com.github.hgdcoder.registry.ServiceDiscovery;
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

    /**
     * Finds the remote service address by rpcServiceName.
     * V4 keeps only the discovery-based client flow.
     */
    private final ServiceDiscovery serviceDiscovery;

    /**
     * One connection per caller thread.
     * ObjectInputStream/ObjectOutputStream are not safe to share for concurrent
     * request-response reads, so each benchmark worker keeps its own socket.
     */
    private final ThreadLocal<SocketConnection> connectionHolder = new ThreadLocal<>();

    /**
     * V4 usage:
     * new SocketRpcClient(new FileServiceDiscovery())
     */
    public SocketRpcClient(ServiceDiscovery serviceDiscovery) {
        this.serviceDiscovery = serviceDiscovery;
    }

    /**
     * Sends one RPC request.
     *
     * Call chain:
     * RpcClientProxy.invoke(...)
     * -> SocketRpcClient.sendRpcRequest(...)
     * -> serviceDiscovery.lookupService(...)
     * -> SocketConnection.send(...)
     */
    @Override
    public Object sendRpcRequest(RpcRequest rpcRequest) {
        InetSocketAddress address = serviceDiscovery.lookupService(rpcRequest);
        SocketConnection connection = getOrCreateConnection(address);

        try {
            return connection.send(rpcRequest);
        } catch (Exception e) {
            // Drop the current thread's broken connection. The next request will
            // create a fresh one.
            closeCurrentConnection();
            throw new RuntimeException("Send rpc request failed", e);
        }
    }

    /**
     * Reuses the current thread's connection when it still points to the same
     * service address; otherwise creates a new socket connection.
     */
    private SocketConnection getOrCreateConnection(InetSocketAddress address) {
        SocketConnection connection = connectionHolder.get();

        if (connection == null || !connection.isAvailable() || !connection.isSameAddress(address)) {
            closeCurrentConnection();
            connection = new SocketConnection(
                    address.getHostString(),
                    address.getPort(),
                    DEFAULT_CONNECT_TIMEOUT_MILLIS,
                    DEFAULT_READ_TIMEOUT_MILLIS
            );
            connectionHolder.set(connection);
        }

        return connection;
    }

    /**
     * Closes the connection owned by the current thread.
     */
    public void closeCurrentConnection() {
        SocketConnection connection = connectionHolder.get();

        if (connection != null) {
            connection.close();
            connectionHolder.remove();
        }
    }

    /**
     * A real socket connection that owns its input/output object streams.
     */
    private static class SocketConnection implements Closeable {
        private final String host;
        private final int port;
        private final Socket socket;

        private ObjectOutputStream out;
        private ObjectInputStream in;

        SocketConnection(String host, int port, int connectTimeoutMillis, int readTimeoutMillis) {
            this.host = host;
            this.port = port;

            try {
                socket = new Socket();
                // Reduce latency for small request-response messages.
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
                socket.setSoTimeout(readTimeoutMillis);

                /*
                 * ObjectOutputStream writes a stream header when it is created,
                 * while ObjectInputStream waits for the peer's header. Creating
                 * output first on both sides avoids a stream-header deadlock.
                 */
                out = new ObjectOutputStream(socket.getOutputStream());
                out.flush();
                in = new ObjectInputStream(socket.getInputStream());
            } catch (Exception e) {
                close();
                throw new RuntimeException("Create socket connection failed", e);
            }
        }

        /**
         * Sends one RpcRequest and waits for the matching RpcResponse.
         */
        Object send(RpcRequest rpcRequest) throws Exception {
            out.writeObject(rpcRequest);
            out.flush();
            // Clear the object handle table for long-lived streams.
            out.reset();
            return in.readObject();
        }

        /**
         * Checks whether this socket can still be reused.
         */
        boolean isAvailable() {
            return socket != null
                    && socket.isConnected()
                    && !socket.isClosed()
                    && !socket.isInputShutdown()
                    && !socket.isOutputShutdown();
        }

        /**
         * A discovered service may move to another address, so the cached
         * connection is reused only when the address still matches.
         */
        boolean isSameAddress(InetSocketAddress address) {
            return host.equals(address.getHostString()) && port == address.getPort();
        }

        /**
         * Closes connection resources quietly.
         */
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
