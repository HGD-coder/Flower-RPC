package com.github.hgdcoder.registry.zk;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.curator.framework.recipes.nodes.PersistentNode;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ZooKeeper 工具类，统一封装服务注册和服务发现需要的公共操作。
 *
 * ZooKeeper 不参与 RpcRequest 和 RpcResponse 的 Socket 传输，
 * 它只负责保存服务地址，并在服务地址发生变化时通知客户端。
 */
public final class CuratorUtils {
    public static final String ZK_REGISTER_ROOT_PATH = "/flower-rpc";

    private static final String DEFAULT_ZK_ADDRESS = "127.0.0.1:2181";
    private static final String ZK_ADDRESS_PROPERTY = "flower.rpc.zk.address";
    private static final int BASE_SLEEP_TIME_MILLIS = 1000;
    private static final int MAX_RETRIES = 3;
    private static final int CONNECTION_TIMEOUT_SECONDS = 10;

    /**
     * 服务端JVM
     * ├── REGISTERED_NODE_CACHE：有数据
     * ├── CURATOR_CACHE_MAP：通常为空
     * └── SERVICE_ADDRESS_CACHE：通常为空
     *
     * 客户端JVM
     * ├── REGISTERED_NODE_CACHE：通常为空
     * ├── CURATOR_CACHE_MAP：有数据
     * └── SERVICE_ADDRESS_CACHE：有数据
     */


    /**
     * 服务地址的本地缓存。
     *
     * key：完整 RPC 服务名，例如 com.github.hgdcoder.HelloServicetest1.0
     * value：该服务当前可用的提供者地址，例如 127.0.0.1:9998
     *
     * 例如：
     * key:
     * com.github.hgdcoder.HelloServicetest1.0
     *
     * value:
     * [
     *   127.0.0.1:9998,
     *   127.0.0.1:9999
     * ]
     */
    private static final Map<String, List<String>> SERVICE_ADDRESS_CACHE =
            new ConcurrentHashMap<>();

    /**
     * 保存 CuratorCache，防止监听器对象被回收或重复创建。
     *
     * CuratorCache 保存 ZooKeeper 节点的本地视图；SERVICE_ADDRESS_CACHE
     * 则把这个视图转换成负载均衡可以直接使用的字符串地址列表。
     *
     * 例如：
     * key:
     * com.github.hgdcoder.HelloServicetest1.0
     *
     * value:
     * 负责监听 /flower-rpc/com.github.hgdcoder.HelloServicetest1.0
     * 的 CuratorCache(其内部保存内容接近
     * /flower-rpc/服务名
     * /flower-rpc/服务名/127.0.0.1:9998
     * /flower-rpc/服务名/127.0.0.1:9999
     * )
     *
     * CuratorCache作用是监听 ZooKeeper
     * → 接收节点创建/修改/删除事件
     * → 调用 refreshAddressCache()
     * → 更新 SERVICE_ADDRESS_CACHE
     */
    private static final Map<String, CuratorCache> CURATOR_CACHE_MAP =
            new ConcurrentHashMap<>();

    /**
     * 服务端使用的，保存本进程已经注册的服务节点。
     *
     * key:
     * 完整ZooKeeper节点路径
     *
     * value:
     * 负责维护该节点的PersistentNode
     *
     * 例如：
     * key:
     * /flower-rpc/com.github.hgdcoder.HelloServicetest1.0/127.0.0.1:9998
     *
     * value:
     * PersistentNode对象
     * （作用：
     * 防止同一路径重复注册
     * 保持PersistentNode对象存活
     * ZooKeeper会话恢复后自动重新创建临时节点）
     *
     * 它不是监视 ZooKeeper 的所有变化，只关心：
     * 自己维护的节点是否被删除
     * 自己与 ZooKeeper 的连接是否重连
     * 异步创建节点是否成功
     *
     */
    private static final Map<String, PersistentNode> REGISTERED_NODE_CACHE =
            new ConcurrentHashMap<>();

    private static volatile CuratorFramework zkClient;
    private static volatile boolean shutdownHookRegistered;

    private CuratorUtils() {
    }

    /**
     * 获取当前 JVM 共享的 Curator 客户端。
     *
     * 使用单例客户端是因为建立 ZooKeeper 会话的成本较高，没有必要每次注册或查询都创建连接。
     * 默认连接 compose.yaml 映射到宿主机的 127.0.0.1:2181。
     * 也可以使用 -Dflower.rpc.zk.address=host:port 覆盖默认地址。
     */
    public static CuratorFramework getZkClient() {
        CuratorFramework currentClient = zkClient;

        // 第一次检查不加锁，客户端已经启动时可以直接返回。
        if (currentClient != null && currentClient.getState() == CuratorFrameworkState.STARTED) {
            return currentClient;
        }

        synchronized (CuratorUtils.class) {
            // 获取锁之后再次检查，防止多个线程同时创建 Curator 客户端。
            currentClient = zkClient;
            if (currentClient != null && currentClient.getState() == CuratorFrameworkState.STARTED) {
                return currentClient;
            }

            String zkAddress = System.getProperty(ZK_ADDRESS_PROPERTY, DEFAULT_ZK_ADDRESS);
            CuratorFramework newClient = CuratorFrameworkFactory.builder()
                    .connectString(zkAddress)
                    // 连接失败时最多重试 3 次，并逐渐增加每次重试前的等待时间。
                    .retryPolicy(new ExponentialBackoffRetry(BASE_SLEEP_TIME_MILLIS, MAX_RETRIES))
                    .build();
            newClient.start();

            try {
                boolean connected = newClient.blockUntilConnected(
                        CONNECTION_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                );
                if (!connected) {
                    newClient.close();
                    throw new RuntimeException("Timed out connecting to ZooKeeper: " + zkAddress);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                newClient.close();
                throw new RuntimeException("Interrupted while connecting to ZooKeeper", e);
            }

            zkClient = newClient;
            registerShutdownHook();
            return newClient;
        }
    }

    /**
     * 把一个服务提供者注册为 ZooKeeper 临时节点。
     *
     * 节点示例：
     * /flower-rpc/com.github.hgdcoder.HelloServicetest1.0/127.0.0.1:9998
     *
     * 使用临时节点的原因：服务端进程退出或长时间断开后，节点会自动消失，
     * 客户端就不会继续调用已经下线的地址。
     */
    public static synchronized void createEphemeralNode(
            CuratorFramework client,
            String path
    ) {
        // 同一个服务地址在一个 JVM 中只注册一次。
        if (REGISTERED_NODE_CACHE.containsKey(path)) {
            return;
        }

        // PersistentNode 是 Curator 提供的“自动维护节点”方案。
        // 节点类型仍然是 EPHEMERAL，因此服务端真正下线后节点会被删除。
        PersistentNode node = new PersistentNode(
                client,
                CreateMode.EPHEMERAL,
                false,
                path,
                new byte[0]
        );

        try {
            node.start();
            boolean created = node.waitForInitialCreate(
                    CONNECTION_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
            if (!created) {
                node.close();
                throw new RuntimeException("Timed out creating ZooKeeper node: " + path);
            }
            REGISTERED_NODE_CACHE.put(path, node);
        } catch (Exception e) {
            closeQuietly(node);
            throw new RuntimeException("Create ZooKeeper node failed: " + path, e);
        }
    }

    /**
     * 从本地缓存中获取某个服务的全部提供者地址。
     *
     * 第一次查询时会读取 ZooKeeper 并创建监听器；后续 RPC 调用直接读取本地缓存，
     * 不会让每个业务请求都访问 ZooKeeper。子节点变化时，监听器会自动刷新缓存。
     */
    public static List<String> getChildrenNodes(
            CuratorFramework client,
            String rpcServiceName
    ) {
        List<String> cachedAddresses = SERVICE_ADDRESS_CACHE.get(rpcServiceName);
        if (cachedAddresses != null) {
            return cachedAddresses;
        }

        synchronized (CuratorUtils.class) {
            cachedAddresses = SERVICE_ADDRESS_CACHE.get(rpcServiceName);
            if (cachedAddresses != null) {
                return cachedAddresses;
            }

            registerWatcher(client, rpcServiceName);
            return SERVICE_ADDRESS_CACHE.getOrDefault(
                    rpcServiceName,
                    Collections.emptyList()
            );
        }
    }

    /**
     * 关闭监听器、服务注册节点维护器和共享 Curator 客户端。
     * JVM 正常退出时，关闭钩子会自动调用该方法。
     */
    public static synchronized void closeZkClient() {
        for (CuratorCache cache : CURATOR_CACHE_MAP.values()) {
            closeQuietly(cache);
        }
        CURATOR_CACHE_MAP.clear();

        for (PersistentNode node : REGISTERED_NODE_CACHE.values()) {
            closeQuietly(node);
        }
        REGISTERED_NODE_CACHE.clear();
        SERVICE_ADDRESS_CACHE.clear();

        if (zkClient != null) {
            zkClient.close();
            zkClient = null;
        }
    }

    private static void registerWatcher(
            CuratorFramework client,
            String rpcServiceName
    ) {
        // 已经为该服务创建过 CuratorCache 时直接复用，避免重复监听同一路径。
        if (CURATOR_CACHE_MAP.containsKey(rpcServiceName)) {
            return;
        }

        String servicePath = buildServicePath(rpcServiceName);
        CuratorCache cache = null;

        try {
            if (client.checkExists().forPath(servicePath) == null) {
                return;
            }

            // CuratorCache 使用 ZooKeeper 3.6+ 的持久 Watch，统一监听节点的创建、修改和删除。
            /**
             * 匿名内部类只能使用：
             * final 局部变量
             * 实际上没有被重新赋值的局部变量，也就是 effectively finl
             * 所以匿名内部类要使用这个startedCache，因为cache有两次赋值
             */
            cache = CuratorCache.build(client, servicePath);
            CuratorCache startedCache = cache;
            CountDownLatch initialized = new CountDownLatch(1);

            // 这里注册的监听器就是节点变化时执行的钩子函数。
            // event() 处理后续变化，initialized() 表示首次节点数据已经加载完成。
            CuratorCacheListener listener = new CuratorCacheListener() {
                @Override
                public void event(Type type, ChildData oldData, ChildData data) {
                    refreshAddressCache(rpcServiceName, startedCache);
                }

                @Override
                public void initialized() {
                    refreshAddressCache(rpcServiceName, startedCache);
                    initialized.countDown();
                }
            };

            // 必须先注册监听器再启动，否则可能错过初始化完成事件。
            cache.listenable().addListener(listener);
            cache.start();

            // 第一次服务发现要等待初始数据加载完成，避免返回尚未初始化的空缓存。
            boolean initialLoadCompleted = initialized.await(
                    CONNECTION_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
            if (!initialLoadCompleted) {
                throw new RuntimeException("Timed out initializing CuratorCache: " + servicePath);
            }

            CURATOR_CACHE_MAP.put(rpcServiceName, cache);
        } catch (Exception e) {
            closeQuietly(cache);
            throw new RuntimeException("Watch ZooKeeper service path failed: " + servicePath, e);
        }
    }

    private static void refreshAddressCache(
            String rpcServiceName,
            CuratorCache cache
    ) {
        List<String> addresses = new ArrayList<>();
        String directChildPrefix = buildServicePath(rpcServiceName) + "/";

        cache.stream().forEach(child -> {
            String path = child.getPath();

            // CuratorCache 中还包含被监听的服务根节点，这里只提取它的直接子节点。
            if (!path.startsWith(directChildPrefix)) {
                return;
            }

            String childName = path.substring(directChildPrefix.length());
            if (!childName.isEmpty() && childName.indexOf('/') < 0) {
                addresses.add(childName);
            }
        });

        // 固定地址顺序，避免仅仅因为 ZooKeeper 返回顺序变化就重建一致性哈希环。
        Collections.sort(addresses);
        SERVICE_ADDRESS_CACHE.put(
                rpcServiceName,
                Collections.unmodifiableList(addresses)
        );
    }

    private static String buildServicePath(String rpcServiceName) {
        return ZK_REGISTER_ROOT_PATH + "/" + rpcServiceName;
    }

    private static void registerShutdownHook() {
        if (shutdownHookRegistered) {
            return;
        }

        // 正常停止服务端时主动释放 ZooKeeper 会话，使临时服务节点尽快消失。
        Runtime.getRuntime().addShutdownHook(
                new Thread(CuratorUtils::closeZkClient, "flower-rpc-zk-shutdown")
        );
        shutdownHookRegistered = true;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }

        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
