# Flower-RPC

**简体中文** | [English](README_EN.md)

Flower-RPC 是一个用于学习 RPC 原理与 Java 网络编程的教学型实现。项目围绕一次远程调用的完整生命周期，覆盖服务发布、动态代理、注册发现、负载均衡、协议编解码、网络传输与响应回传。

> [!IMPORTANT]
> 项目仍在学习和开发中，尚未完成，也未按生产环境标准进行完整验证，请勿直接用于生产系统。

<p align="center">
  <a href="docs/architecture/flower-rpc-architecture.html">
    <img src="docs/architecture/flower-rpc-architecture.png" alt="Flower-RPC 项目架构图" width="100%">
  </a>
</p>

## 架构图

- [查看交互式项目架构图](docs/architecture/flower-rpc-architecture.html)（下载仓库后可直接在浏览器打开）
- [查看 Archify 架构规范](docs/architecture/flower-rpc-architecture.json)

默认调用链：

```text
Spring Bean
  -> @RpcReference / JDK 动态代理
  -> ZooKeeper 服务发现 / 一致性哈希
  -> Netty 客户端 / 自定义 RPC 协议
  -> Netty 服务端 / 反射调用
  -> RpcResponse / CompletableFuture
```

ZooKeeper 只负责服务注册与发现，不承载 RPC 请求和响应；业务数据通过 Netty 或可替换的 Socket 传输实现流转。

## 核心能力

- 基于 `@RpcService` 与 `@RpcReference` 的 Spring 扫描、服务发布和代理注入
- 同步接口与基于 `CompletableFuture` 的异步调用
- Netty NIO 长连接、连接复用、请求超时、心跳与优雅关闭
- 16 字节协议头、帧切分、消息编解码和请求响应匹配
- ZooKeeper/Curator 注册发现及文件注册中心替代实现
- 一致性哈希与随机负载均衡
- JDK、Kryo、Protostuff、Hessian 序列化
- GZIP 压缩及无压缩模式
- 基于 `ExtensionLoader` 和 `META-INF/extensions` 的 SPI 扩展机制
- 单元测试、Netty 本地端到端测试与基准测试模块

## 模块说明

| 模块 | 作用 |
| --- | --- |
| `rpc-framework-common` | SPI 加载、共享枚举、异常、工具与线程池基础能力 |
| `rpc-framework-simple` | 代理、配置、Spring 集成、注册发现、负载均衡、协议、序列化与传输核心实现 |
| `hello-service-api` | 客户端与服务端共享的服务接口、异步接口和 DTO |
| `example-server` | `@RpcService` 服务发布与 Netty 服务端示例 |
| `example-client` | `@RpcReference` 注入与同步/异步调用示例 |
| `flower-rpc-benchmark` | 复用真实 RPC 链路的吞吐、延迟与连接测试 |

根目录中的 `flower-rpc-framework` 当前不属于 Maven 聚合构建；根 `pom.xml` 声明的六个模块以上表为准。

## 默认技术栈

- Java 25、Maven 3.9+
- Spring Framework 7
- Netty 4.2
- Apache Curator + ZooKeeper
- Kryo（默认序列化）、一致性哈希（默认负载均衡）
- JUnit 6、HdrHistogram

示例配置位于：

- [`example-server/src/main/resources/flower-rpc.properties`](example-server/src/main/resources/flower-rpc.properties)
- [`example-client/src/main/resources/flower-rpc.properties`](example-client/src/main/resources/flower-rpc.properties)

配置优先级为：JVM `-D` 参数 > `flower-rpc.properties` > 框架默认值。

## 快速开始

### 1. 环境要求

- JDK 25+
- Maven 3.9+
- Docker 与 Docker Compose（用于运行默认 ZooKeeper）

### 2. 启动 ZooKeeper

```bash
docker compose up -d zookeeper
```

默认地址为 `127.0.0.1:2181`。查看状态：

```bash
docker compose ps
```

### 3. 构建与测试

```bash
mvn clean verify
```

### 4. 运行示例

在 IDE 中依次运行：

1. `com.github.hgdcoder.server.ServerMain`
2. `com.github.hgdcoder.client.ClientMain`

服务端默认监听 `0.0.0.0:9998`，并向 ZooKeeper 发布 `127.0.0.1:9998`；客户端发现服务后调用 `HelloService`。

### 5. 停止基础设施

```bash
docker compose down
```

## 扩展机制

框架通过 `@SPI`、`ExtensionLoader` 与 `META-INF/extensions` 装载实现。新增扩展时，实现对应接口并在资源目录注册映射即可。当前可扩展点包括：

- `RpcRequestTransport`
- `ServiceRegistry` / `ServiceDiscovery`
- `LoadBalance`
- `Serializer`
- `Compress`

## 项目定位与来源

本项目参考 [Snailclimb/guide-rpc-framework](https://github.com/Snailclimb/guide-rpc-framework)，跟随其架构、模块划分和实现思路逐步学习与复现，并非完全独立起源的 RPC 框架。目前尚未进行系统性的差异化重构或功能扩展。

本项目仅用于学习和技术交流，与上游作者无隶属或官方合作关系。上游代码及相关权利归原作者和贡献者所有。

## 许可证

本仓库保留 [木兰宽松许可证，第 1 版](LICENSE)；使用与分发时请同时遵守上游项目的许可和声明要求。
