package com.github.hgdcoder.registry.zk;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证静态 ZooKeeper 缓存只能绑定一个 connect string。 */
class CuratorUtilsAddressTest {
    @AfterEach
    void cleanupStaticState() {
        CuratorUtils.closeZkClient();
    }

    @Test
    void shouldRejectConflictingAddressAndReleaseGuardOnClose() throws Exception {
        CuratorFramework activeClient = startedClientProxy();
        setStaticField("activeZkAddress", "127.0.0.1:2181");
        setStaticField("zkClient", activeClient);

        assertSame(activeClient, CuratorUtils.getZkClient(" 127.0.0.1:2181 "));
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> CuratorUtils.getZkClient("10.0.0.8:2181")
        );
        assertTrue(error.getMessage().contains("127.0.0.1:2181"));
        assertTrue(error.getMessage().contains("10.0.0.8:2181"));

        CuratorUtils.closeZkClient();
        assertNull(getStaticField("zkClient"));
        assertNull(getStaticField("activeZkAddress"));
    }

    private CuratorFramework startedClientProxy() {
        return (CuratorFramework) Proxy.newProxyInstance(
                CuratorFramework.class.getClassLoader(),
                new Class<?>[]{CuratorFramework.class},
                (proxy, method, args) -> {
                    if ("getState".equals(method.getName())) {
                        return CuratorFrameworkState.STARTED;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(
                            "Unexpected Curator method in test: " + method.getName()
                    );
                }
        );
    }

    private void setStaticField(String name, Object value) throws Exception {
        Field field = CuratorUtils.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private Object getStaticField(String name) throws Exception {
        Field field = CuratorUtils.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
