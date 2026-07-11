package com.github.hgdcoder.registry.file;

import java.nio.file.Path;
import java.nio.file.Paths;

final class FileRegistryConfig {
    private static final String REGISTRY_FILE_PROPERTY = "flower.rpc.registry.file";

    private FileRegistryConfig() {
    }

    static Path registryFile() {
        String customPath = System.getProperty(REGISTRY_FILE_PROPERTY);

        if (customPath != null && !customPath.trim().isEmpty()) {
            return Paths.get(customPath);
        }

        // The user home path lets server/client modules share one registry file.
        return Paths.get(System.getProperty("user.home"), ".flower-rpc", "registry.properties");
    }
}
