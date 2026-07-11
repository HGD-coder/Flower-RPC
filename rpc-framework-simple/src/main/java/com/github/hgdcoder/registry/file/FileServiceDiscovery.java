package com.github.hgdcoder.registry.file;

import com.github.hgdcoder.registry.ServiceDiscovery;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class FileServiceDiscovery implements ServiceDiscovery {
    @Override
    public InetSocketAddress lookupService(String rpcServiceName) {
        try {
            Path file = FileRegistryConfig.registryFile();

            if (!Files.exists(file)) {
                throw new RuntimeException("Registry file does not exist: " + file);
            }

            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            }

            String address = properties.getProperty(rpcServiceName);
            if (address == null) {
                throw new RuntimeException("No service address found for: " + rpcServiceName);
            }

            int splitIndex = address.lastIndexOf(":");
            String host = address.substring(0, splitIndex);
            int port = Integer.parseInt(address.substring(splitIndex + 1));

            return new InetSocketAddress(host, port);
        } catch (Exception e) {
            throw new RuntimeException("Lookup service failed: " + rpcServiceName, e);
        }
    }
}
