package com.github.hgdcoder.registry.file;

import com.github.hgdcoder.registry.ServiceRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class FileServiceRegistry implements ServiceRegistry {
    @Override
    public synchronized void registerService(String rpcServiceName, InetSocketAddress address) {
        try {
            Path file = FileRegistryConfig.registryFile();
            Properties properties = load(file);

            //com.github.hgdcoder.HelloServicetest1.0=127.0.0.1:9998,127.0.0.1:9999 多个地址
            String value = address.getHostString() + ":" + address.getPort();
            String oldValue = properties.getProperty(rpcServiceName);
            if (oldValue == null || oldValue.trim().isEmpty()) {
                properties.setProperty(rpcServiceName, value);
            } else if (!containsAddress(oldValue, value)) {
                properties.setProperty(rpcServiceName, oldValue + "," + value);
            }

            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "Flower RPC file registry");
            }
        } catch (Exception e) {
            throw new RuntimeException("Register service failed: " + rpcServiceName, e);
        }
    }

    private Properties load(Path file) throws IOException {
        Properties properties = new Properties();

        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            }
        }

        return properties;
    }

    private boolean containsAddress(String oldValue, String address) {
        String[] addresses = oldValue.split(",");

        for (String item : addresses) {
            if (address.equals(item.trim())) {
                return true;
            }
        }

        return false;
    }
}
