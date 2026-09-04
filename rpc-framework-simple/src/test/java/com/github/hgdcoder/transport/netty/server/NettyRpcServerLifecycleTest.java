package com.github.hgdcoder.transport.netty.server;

import com.github.hgdcoder.config.RpcFrameworkConfig;
import com.github.hgdcoder.config.RpcServiceConfig;
import com.github.hgdcoder.provider.ServiceProvider;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Netty 监听地址与服务发布、注销生命周期保持一致。 */
class NettyRpcServerLifecycleTest {

    @Test
    void shouldPublishActualAddressAndUnpublishItOnClose() {
        RpcFrameworkConfig config = RpcFrameworkConfig.defaults()
                .toBuilder()
                .serverHost("127.0.0.1")
                .serverPort(0)
                .build();
        RecordingServiceProvider provider = new RecordingServiceProvider();
        NettyRpcServer server = new NettyRpcServer(config, provider);

        try {
            server.start();

            assertNotNull(provider.publishedAddress);
            assertEquals("127.0.0.1", provider.publishedAddress.getHostString());
            assertEquals(server.getPort(), provider.publishedAddress.getPort());
            assertTrue(provider.publishedAddress.getPort() > 0);
        } finally {
            server.close();
        }

        assertEquals(provider.publishedAddress, provider.unpublishedAddress);
    }

    private static final class RecordingServiceProvider implements ServiceProvider {
        private InetSocketAddress publishedAddress;
        private InetSocketAddress unpublishedAddress;

        @Override
        public void addService(RpcServiceConfig rpcServiceConfig) {
        }

        @Override
        public Object getService(String rpcServiceName) {
            return null;
        }

        @Override
        public void publishService(RpcServiceConfig rpcServiceConfig) {
        }

        @Override
        public void publishAllServices(InetSocketAddress serverAddress) {
            this.publishedAddress = serverAddress;
        }

        @Override
        public void unpublishAllServices(InetSocketAddress serverAddress) {
            this.unpublishedAddress = serverAddress;
        }
    }
}
