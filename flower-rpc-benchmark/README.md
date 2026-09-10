# Flower-RPC Benchmark

这个模块使用同一套测量逻辑比较 RPC 修改前后的表现。运行前应启动 ZooKeeper
和一个或多个 `example-server`，客户端连接方式由
`src/main/resources/flower-rpc.properties` 决定。

## 两种模式

- `concurrency` 是闭环模式：每个工作线程收到响应后才发下一次请求，适合测固定并发下的吞吐和延迟。
- `rate` 是开环模式：按计划速率产生请求，适合观察过载、排队、拒绝和尾延迟。

## 单场运行

固定并发：

```text
BenchmarkClientMain --mode=concurrency --threads=8 --duration=30 --warmup=5 --payload-bytes=1024
```

固定速率：

```text
BenchmarkClientMain --mode=rate --rate=10000 --max-inflight=1024 --duration=30 --drain-timeout=10
```

可使用的公共参数：

```text
--mode=concurrency|rate
--threads=4
--rate=1000
--max-inflight=1024
--duration=30
--warmup=5
--drain-timeout=10
--payload-bytes=9
--payload-type=repeat|deterministic-random
--seed=20260909
--output=benchmark-results.csv
```

## 批量运行

固定并发矩阵：

```text
BenchmarkBatchMain --mode=concurrency --payloads=9,1024,65536 --threads-list=1,2,4,8 --repeats=3
```

固定速率矩阵：

```text
BenchmarkBatchMain --mode=rate --payloads=9,65536 --rates=1000,5000,10000 --repeats=3
```

每完成一个场次就会向 CSV 追加一行。比较 A/B/C 版本时必须保持命令、配置、
JDK、机器负载和压测工具版本一致，只改变被验证的 RPC 实现。

## 关键指标

- `planned`：按场景计划产生的请求数。
- `started`：真正进入 RPC 调用的请求数。
- `dropped`：发压端错过时刻或达到 `max-inflight` 后放弃的请求数。
- `completed`：已经进入互斥终态的请求数。
- `inflight`：排空结束后仍未完成的请求数，正常情况下应为 0。
- `terminalP99Ms`：所有已结束请求的调用耗时，包括失败。
- `plannedToTerminalP99Ms`：从计划发送时刻到终态的耗时，用于暴露调度和排队延迟。
- `schedulingDelayP99Ms`：发压端从计划时刻到实际调用的延迟。
- `conserved`：两个计数守恒关系是否成立；为 `false` 时本场结果不应参与性能结论。
- `averageProcessCpuCores`：压测客户端 JVM 在测量阶段平均占用的逻辑 CPU 核数。
- `heapDeltaBytes`、`gcCount`、`gcTimeMillis`：客户端堆变化和 GC 开销，用于判断发压端是否成为瓶颈。

延迟由 HdrHistogram 记录，量程为 1 微秒到 60 秒、三位有效数字。超过量程的
样本会被夹到最高量程并单独计数，不会静默丢失。
