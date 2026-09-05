package com.github.hgdcoder.registry.file;

import com.github.hgdcoder.config.RpcFrameworkConfig;
import com.github.hgdcoder.registry.ServiceRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class FileServiceRegistry implements ServiceRegistry {

    /**
     * 保留统一装配传入的配置快照。
     *
     * <p>当前文件注册中心没有专属配置项，
     * 但后续增加注册文件路径等配置时，可以直接使用它。</p>
     */
    private final RpcFrameworkConfig config;

    /**
     * 兼容原有无参创建方式。
     */
    public FileServiceRegistry() {
        this(RpcFrameworkConfig.defaults());
    }

    /**
     * RpcExtensionFactory 使用的统一构造器。
     *
     * <p>所有 ServiceRegistry 实现都提供接收
     * RpcFrameworkConfig 的 public 构造器。</p>
     */
    public FileServiceRegistry(
            RpcFrameworkConfig config
    ) {
        if (config == null) {
            throw new IllegalArgumentException(
                    "config must not be null"
            );
        }

        this.config = config;
    }

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

    /**
     * 删除指定服务下的一个地址，不影响其他提供者。
     * 例如：9998,9999 -> 注销 9998 -> 只保留 9999。
     */
    @Override
    public synchronized void unregisterService(
            String rpcServiceName,
            InetSocketAddress address
    ) {
        try {
            Path file = FileRegistryConfig.registryFile();
            // 文件不存在，无须注销，也不创建新文件。
            if (!Files.exists(file)) {
                return;
            }

            Properties properties = load(file);
            String oldValue = properties.getProperty(rpcServiceName);
            if (oldValue == null || oldValue.trim().isEmpty()) {
                return;
            }

            String removedAddress = address.getHostString()
                    + ":" + address.getPort();
            List<String> remaining = new ArrayList<>();

            // 只移除完全匹配的地址，保留其他服务器。
            for (String item : oldValue.split(",")) {
                String candidate = item.trim();
                if (!candidate.isEmpty()
                        && !removedAddress.equals(candidate)) {
                    remaining.add(candidate);
                }
            }

            if (remaining.isEmpty()) {
                // 最后一个提供者下线，删除整个服务条目。
                properties.remove(rpcServiceName);
            } else {
                properties.setProperty(
                        rpcServiceName, String.join(",", remaining)
                );
            }

            // 将更新后的完整注册表写回文件。
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "Flower RPC file registry");
            }
        } catch (Exception e) {
            throw new RuntimeException(
                    "Unregister service failed: " + rpcServiceName, e
            );
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
