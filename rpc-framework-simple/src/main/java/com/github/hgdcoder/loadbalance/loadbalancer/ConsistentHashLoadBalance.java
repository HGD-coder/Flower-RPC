package com.github.hgdcoder.loadbalance.loadbalancer;

import com.github.hgdcoder.factory.SingletonFactory;
import com.github.hgdcoder.loadbalance.AbstractLoadBalance;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import lombok.extern.slf4j.Slf4j;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
public class ConsistentHashLoadBalance extends AbstractLoadBalance {
    private final ConcurrentHashMap<String,ConsistentHashingLoadBalancer> selectors=new ConcurrentHashMap<>();

    //重构次数，测试使用
    public static AtomicInteger count=new AtomicInteger();

    //创建次数，测试使用
    public static AtomicInteger createCount=new AtomicInteger();

    @Override
    protected String doSelect(List<String> serviceAddresses, RpcRequest rpcRequest){
        String rpcServiceName=rpcRequest.getRpcServiceName();
        //1.获得hash选择器
        ConsistentHashingLoadBalancer selector=selectors.get(rpcServiceName);
        if(selector==null){
            //2.如果没有，就新建hash环，使用单例工厂模式进行创建
            selector = SingletonFactory.getInstance(
                    ()->new ConsistentHashingLoadBalancer(
                            serviceAddresses,
                            160,
                            new ConsistentHashingLoadBalancer.MD5HashFunction()),
                            ConsistentHashingLoadBalancer.class);
            selectors.put(rpcServiceName,selector);
        }else if(selector.hasChanged(serviceAddresses)){
            //3.如果地址变了
            selector=selectors.get(rpcServiceName);
            selector.reBuild(serviceAddresses);
        }
        //使用请求的uuid进行hash
        return selector.selectNode(rpcServiceName+rpcRequest.getRequestId());
    }


    static class ConsistentHashingLoadBalancer{
        /**
         * 哈希环：使用TreeMap存储虚拟节点的哈希值到物理节点的映射
         * 1.虚拟节点
         * 2.hash函数
         * 3.TreeMap存储节点
         * 4.物理节点列表
         */
        private final TreeMap<Long,String> virtualNodes = new TreeMap<>();
        private final Set<String> physicalNodes=new HashSet<>();
        private final int virtualNodeCount;
        private final HashFunction hashFunction;

        /**
         * 读写锁（公平模式）：selectNode 拿读锁，reBuild 拿写锁。
         * 公平模式下先到先得，避免密集读请求导致写锁（reBuild）饿死。
         * 代价是吞吐略降，但负载均衡对正确性要求高于极致性能。
         */
        private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(true);

        //构造函数，在初始化时候，就需要进行hash环的构建了
        public ConsistentHashingLoadBalancer(List<String> invokers,
                                             int virtualNodeCount,
                                             HashFunction hashFunction){
            log.info("创建服务的服务器");
            this.virtualNodeCount=virtualNodeCount;
            this.hashFunction=hashFunction;
            //1.构建hash环
            for(String addr:invokers){
                this.addNode(addr);
            }
            //2.初始化完成
            createCount.getAndIncrement();
        }

        /**
         * 添加物理节点
         * @param node
         */
        private void addNode(String node){
            if(physicalNodes.contains(node)){
                return;
            }
            physicalNodes.add(node);

            //为每一个物理节点创建虚拟节点
            for(int i=0;i<virtualNodeCount;i++){
                String virtualNodeName = node +"#"+i;
                long hash=hashFunction.hash(virtualNodeName);
                virtualNodes.put(hash,node);
            }
        }

        private void removeNode(String node){
            if(!physicalNodes.contains(node)){
                return;
            }
            physicalNodes.remove(node);

            //移除该物理节点对应的所有虚拟节点
            for(int i=0;i<virtualNodeCount;i++){
                String virtualNodeName = node +"#"+i;
                long hash=hashFunction.hash(virtualNodeName);
                virtualNodes.remove(hash);
            }
        }

        /**
         * 判断地址是否已经发生了变化，不用加上锁
         * @param address
         * @return
         */
        public boolean hasChanged(List<String> address) {
            if (address.size() != this.physicalNodes.size()) {
                return true;
            }
            for (String addr: address) {
                if (!this.physicalNodes.contains(addr)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 根据请求的key选择节点
         */
        public String selectNode(String key) {
            rwLock.readLock().lock();
            try {
                if(virtualNodes.isEmpty()){
                    return null;
                }
                long keyHash = hashFunction.hash(key);
                //顺时针找到第一个大于等于keyHash的虚拟节点，获取键值对
                SortedMap<Long,String> tailMap=virtualNodes.tailMap(keyHash);
                Long nodeHash=tailMap.isEmpty()?virtualNodes.firstKey():tailMap.firstKey();
                return virtualNodes.get(nodeHash);
            } finally {
                rwLock.readLock().unlock();
            }
        }


        public void reBuild(List<String> address) {
            rwLock.writeLock().lock();
            try {
                // DCL：再次检查是否真的变了（可能已被其他线程重建过）
                if(!this.hasChanged(address)){
                    return;
                }
                log.info("重构服务的选择器");
                count.getAndIncrement();

                // 1. 找出新增和删除的节点
                Set<String> currentAddress = new HashSet<>(address);
                Set<String> preAddress = new HashSet<>(this.physicalNodes);

                List<String> readyToRemove = new ArrayList<>();
                List<String> readyToAdd = new ArrayList<>();
                for (String addr : address) {
                    if (!preAddress.contains(addr)) {
                        readyToAdd.add(addr);
                    }
                }
                for (String addr : this.physicalNodes) {
                    if (!currentAddress.contains(addr)) {
                        readyToRemove.add(addr);
                    }
                }

                // 2. 增量增删
                for (String r : readyToRemove) {
                    this.removeNode(r);
                }
                for (String a : readyToAdd) {
                    this.addNode(a);
                }
                log.info("重新构建的列表大小:{}", this.physicalNodes.size());
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        public List<String> getAllNodes() {
            rwLock.readLock().lock();
            try {
                return Collections.unmodifiableList(new ArrayList<>(physicalNodes));
            } finally {
                rwLock.readLock().unlock();
            }
        }



        /**
         * 哈希函数接口
         */
        public interface HashFunction{
            long hash(String key);
        }

        public static class MD5HashFunction implements HashFunction{
            @Override
            public long hash(String key) {
                try{
                    //MD5为16个字节
                    MessageDigest md5 = MessageDigest.getInstance("MD5");
                    byte[] digest=md5.digest(key.getBytes());

                    //取前8个字节作为long类型的哈希值（按大端序拼成64位整数）
                    return  ((long) (digest[0] & 0xFF) << 56) |
                            ((long) (digest[1] & 0xFF) << 48) |
                            ((long) (digest[2] & 0xFF) << 40) |
                            ((long) (digest[3] & 0xFF) << 32) |
                            ((long) (digest[4] & 0xFF) << 24) |
                            ((long) (digest[5] & 0xFF) << 16) |
                            ((long) (digest[6] & 0xFF) << 8) |
                            (digest[7] & 0xFF);
                }catch (NoSuchAlgorithmException e){
                    throw new RuntimeException(e);
                }
            }
        }
        /**
         * 这要从 Java 的 `byte` 类型和位运算的类型提升说起。
         *
         * ## 根源：Java 的 byte 是有符号的
         *
         * Java 没有无符号 byte，`byte` 的范围是 **-128 ~ 127**。MD5 产生的字节值在 0~255，落到 128~255 时 Java 就当负数了。
         *
         * ```
         * MD5 字节:   0xbc  →  二进制 10111100
         *
         * Java byte 解读:
         *   最高位是 1 → 负数 → 值是 -68
         * ```
         *
         * ---
         *
         * ## 关键：`&` 操作会触发"二进制数字提升"
         *
         * Java 在做 `&`、`|`、`<<` 等位运算前，会先把 byte **自动提升为 int**（这叫 *binary numeric promotion*）。
         *
         * 提升规则：**符号扩展**——高位全部用原来的符号位填充。
         *
         * ```
         * 原始 byte:    10111100  (-68, 一共8位)
         * 提升为 int:   11111111 11111111 11111111 10111100  (还是 -68, 但现在是32位)
         *               ^^^^^^^^ ^^^^^^^^ ^^^^^^^^
         *               高位全被符号位 1 填满了
         * ```
         *
         * ---
         *
         * ## `& 0xFF` 做了两件事
         *
         * `0xFF` 是 `int` 字面量：
         *
         * ```
         * 0xFF  =  00000000 00000000 00000000 11111111  (255)
         * ```
         *
         * 做 `&` 运算：
         *
         * ```
         * 提升后的 -68:   11111111 11111111 11111111 10111100
         * 0xFF:           00000000 00000000 00000000 11111111
         *                 ─────────────────────────────────
         * & 结果:          00000000 00000000 00000000 10111100  = 188
         * ```
         *
         * 高 24 位全被清零了，只保留低 8 位的原始比特。结果就是 **188**，一个正 int。
         *
         * ---
         *
         * ## 直观总结
         *
         * ```
         * byte 0xbc = -68
         *               │
         *               ▼  提升为 int（符号扩展，高位补1）
         *     11111111 11111111 11111111 10111100
         *               │
         *               ▼  & 0xFF（高位清零，只留低8位）
         *     00000000 00000000 00000000 10111100  = 188 ✅
         * ```
         *
         * **一句话**：`& 0xFF` 就是把符号扩展产生的那些多余的 `1` 全部砍掉，只保留原始 8 位，这样就恢复成了 0~255 的无符号值。
         */
    }
}

