package com.github.hgdcoder.extension;

import lombok.Data;

@Data
public class Holder<T>{
    private volatile T value;
}
/**
 * 这句话涉及一个 Java 内存模型里很容易踩的坑，先从问题根源说起。
 *
 * ---
 *
 * **如果不用 Holder，直接存 Object 会发生什么？**
 *
 * 假设 map 里直接存实例：
 *
 * ```java
 * Map<String, Object> map = new HashMap<>();
 *
 * // 线程 A 创建并写入
 * Object instance = new KryoSerializer();
 * map.put("kryo", instance);
 *
 * // 线程 B 读取
 * Object obj = map.get("kryo"); // 可能拿到一个"半初始化"的对象
 * ```
 *
 * 问题出在 `new KryoSerializer()` 这一行。JVM 实际做了三件事：
 *
 * ```
 * 1. 分配内存
 * 2. 初始化对象（执行构造器，给字段赋值）
 * 3. 把引用指向这块内存
 * ```
 *
 * JVM 和 CPU 为了优化性能，允许把步骤 **3 重排到 2 前面**。结果就是：线程 A 还没执行完构造器，线程 B 就已经从 map 里拿到了这个引用——对象存在，但字段都是默认值（null、0），是个"半成品"。
 *
 * ---
 *
 * **Holder 怎么解决这个问题？**
 *
 * `Holder` 内部持有一个 `volatile` 字段：
 *
 * ```java
 * public class Holder<T> {
 *     private volatile T value;  // 关键在这里
 *
 *     public T get() { return value; }
 *     public void set(T value) { this.value = value; }
 * }
 * ```
 *
 * `volatile` 有一个关键保证：**写操作 happens-before 后续的读操作**。
 *
 * 具体到这段代码：
 *
 * ```java
 * // 线程 A（锁内）
 * T instance = constructor.newInstance(); // 构造器执行完毕
 * holder.set(instance);                   // volatile 写
 *
 * // 线程 B（无锁路径）
 * holder.get()  // volatile 读，happens-before 保证：
 *               // 能看到线程 A 在 volatile 写之前做的所有事
 *               // 包括构造器里的所有字段赋值
 * ```
 *
 * `volatile` 写会禁止指令重排，保证**构造器执行完 → 才写 volatile**，绝不会反过来。线程 B 读到 `holder.get()` 非 null，就一定能看到一个完整初始化的对象。
 *
 * ---
 *
 * **为什么不直接把 map 的 value 声明为 volatile？**
 *
 * `HashMap` 做不到这一点，它的 value 不是 `volatile` 的。`ConcurrentHashMap` 的 value 虽然是 `volatile`，但旧版代码里有 `containsKey` + `get` 两步操作，两步之间不是原子的，还是有窗口期。
 *
 * 用 `Holder` 把 `volatile` 语义"包"进去，map 里存的是 `Holder` 引用（这个引用本身不需要 volatile），真正需要 volatile 保护的"实例是否初始化完成"这件事，交给 `Holder.value` 来保证。这样拆分，逻辑更清晰，保护粒度也更精准。
 */