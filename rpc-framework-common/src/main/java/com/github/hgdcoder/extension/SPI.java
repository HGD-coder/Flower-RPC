package com.github.hgdcoder.extension;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SPI {
}

/**
 * SPI 是为了实现微内核和插件化，那在实际操作中，如果我（作为第三方开发者）想要为这个 RPC
 * 框架新增一种自定义的负载均衡策略，我具体需要写哪几步代码和配置才能让框架自动加载它?
 *
 *非常好的问题，分四步：
 *
 * ---
 *
 * **第一步：实现接口**
 *
 * ```java
 * // 新建你的实现类
 * public class WeightedLoadBalance implements LoadBalance {
 *     @Override
 *     public String selectServiceAddress(List<String> addresses, RpcRequest request) {
 *         // 你的加权轮询逻辑
 *         return addresses.get(0);
 *     }
 * }
 * ```
 *
 * ---
 *
 * **第二步：在配置文件里注册**
 *
 * 在你的 jar 包或项目的 `resources` 目录下，新建文件：
 *
 * ```
 * META-INF/extensions/com.github.hgdcoder.loadbalance.LoadBalance
 * ```
 *
 * 文件内容写一行：
 *
 * ```
 * weighted=com.yourpackage.WeightedLoadBalance
 * ```
 *
 * ---
 *
 * **第三步：框架调用时按名字取**
 *
 * 框架内部（或你的配置）指定用哪个实现：
 *
 * ```java
 * LoadBalance loadBalance = ExtensionLoader
 *     .getExtensionLoader(LoadBalance.class)
 *     .getExtension("weighted");  // 和配置文件里的 key 对应
 * ```
 *
 * ---
 *
 * **第四步：什么都不用改**
 *
 * 框架核心代码、接口定义、其他实现类，一行都不用动。`getResources()` 会自动扫描所有 jar 包里的同名配置文件，你的 jar 加进 classpath，框架就能找到它。
 *
 * ---
 *
 * **整个过程你只做了两件事**：写实现类 + 加一行配置。这就是 SPI 插件化的意义——框架和扩展完全解耦，扩展方不需要接触框架源码。
 */

/**
 *实现的接口是谁提供的？可以自定义吗
 *
 * **接口是框架提供的**，你作为第三方开发者只能实现它，不能随便自定义。
 *
 * ---
 *
 * **为什么接口必须由框架提供**
 *
 * `ExtensionLoader` 有一个强制校验：
 *
 * ```java
 * if (type.getAnnotation(SPI.class) == null) {
 *     throw new IllegalArgumentException("Extension type must be annotated by @SPI");
 * }
 * ```
 *
 * 接口必须带 `@SPI` 注解，而 `@SPI` 是框架定义的注解。所以流程是：
 *
 * ```
 * 框架定义接口 + 标注 @SPI
 *        ↓
 * 第三方实现这个接口
 *        ↓
 * 写配置文件注册
 *        ↓
 * 框架按名字加载
 * ```
 *
 * ---
 *
 * **可以自定义接口吗**
 *
 * 可以，但有前提——**你得能改框架代码**，或者框架本身开放了这个能力。具体两种情况：
 *
 * **情况一：你就是框架开发者**
 *
 * 自己加一个新的扩展点接口，标注 `@SPI`，框架内部调 `getExtensionLoader` 时传这个接口进去，完全没问题：
 *
 * ```java
 * @SPI
 * public interface MyCustomExtension {
 *     void doSomething();
 * }
 * ```
 *
 * **情况二：你是纯第三方，不能改框架**
 *
 * 没办法新增扩展点接口，只能实现框架已经定义好的那些接口（`LoadBalance`、`Serializer`、`Registry` 等）。这是所有插件化框架的共同限制——**扩展点的边界由框架划定，第三方只能在边界内发挥**。
 *
 * ---
 *
 * **一句话**
 *
 * 接口是框架和第三方之间的契约，框架划定契约，第三方遵守契约。想新增契约就得有修改框架的权限。
 */
