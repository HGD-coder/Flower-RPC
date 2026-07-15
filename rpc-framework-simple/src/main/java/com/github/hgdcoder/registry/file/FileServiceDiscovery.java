package com.github.hgdcoder.registry.file;

import com.github.hgdcoder.loadbalance.LoadBalance;
import com.github.hgdcoder.loadbalance.loadbalancer.RandomLoadBalance;
import com.github.hgdcoder.registry.ServiceDiscovery;
import com.github.hgdcoder.remoting.dto.RpcRequest;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

public class FileServiceDiscovery implements ServiceDiscovery {
    private final LoadBalance loadBalance;

    public FileServiceDiscovery() {
        this(new RandomLoadBalance());
    }

    public FileServiceDiscovery(LoadBalance loadBalance) {
        this.loadBalance = loadBalance;
    }

    @Override
    public InetSocketAddress lookupService(RpcRequest rpcRequest) {
        try {
            Path file = FileRegistryConfig.registryFile();

            if (!Files.exists(file)) {
                throw new RuntimeException("Registry file does not exist: " + file);
            }

            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            }

            // The value may be one address or multiple comma-separated addresses.
            // Example: 127.0.0.1:9998,127.0.0.1:9999
            String value = properties.getProperty(rpcRequest.getRpcServiceName());
            if (value == null || value.trim().isEmpty()) {
                throw new RuntimeException("No service address found for: " + rpcRequest.getRpcServiceName());
            }

            List<String> addressList = Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(address -> !address.isEmpty())
                    .collect(Collectors.toList());

            // Let the configured load balancer choose one provider address.
            String selectedAddress = loadBalance.selectServiceAddress(addressList, rpcRequest);
            return parseAddress(selectedAddress);
        } catch (Exception e) {
            throw new RuntimeException("Lookup service failed: " + rpcRequest.getRpcServiceName(), e);
        }
    }

    private InetSocketAddress parseAddress(String address) {
        int splitIndex = address.lastIndexOf(":");
        String host = address.substring(0, splitIndex);
        int port = Integer.parseInt(address.substring(splitIndex + 1));
        return new InetSocketAddress(host, port);
    }
}
