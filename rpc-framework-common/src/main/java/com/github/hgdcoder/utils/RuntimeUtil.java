package com.github.hgdcoder.utils;

/** JVM 运行环境工具。 */
public final class RuntimeUtil {

    private RuntimeUtil() {
    }

    /**
     * 返回 JVM 当前可使用的处理器数量。
     * 它可能小于物理 CPU 数，例如容器为进程限制了 CPU 配额。
     */
    public static int cpus() {
        return Runtime.getRuntime().availableProcessors();
    }
}
