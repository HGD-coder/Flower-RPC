package com.github.hgdcoder.loadbalance;

import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.utils.CollectionUtil;

import java.util.List;

public abstract class AbstractLoadBalance implements LoadBalance {
    @Override
    public String selectServiceAddress(List<String> serviceAddresses, RpcRequest rpcRequest){
        //1.判空
        if(CollectionUtil.isEmpty(serviceAddresses)){
            return null;
        }
        //2.如果只有一个的情况
        if(serviceAddresses.size() == 1){
            return serviceAddresses.get(0);
        }
        //3.使用钩子函数，进行选择
        return doSelect(serviceAddresses,rpcRequest);
    }

    protected abstract String doSelect(List<String> serviceAddress,RpcRequest rpcRequest);
}

/**
 * 为什么使用抽象类实现接口，然后再继承抽象类？
 *
 * 这是**模板方法模式**的经典写法。看起来多了一层，实际上解决了一个问题：**代码复用和扩展点的分离**。
 *
 * ## 直接实现接口 vs 中间插一层抽象类
 *
 * 假设没有 `AbstractLoadBalance`，每种负载均衡都要自己写：
 *
 * ```java
 * // 一致性哈希
 * public class ConsistentHashLoadBalance implements LoadBalance {
 *     @Override
 *     public String selectServiceAddress(List<String> addresses, RpcRequest request) {
 *         if (addresses == null || addresses.isEmpty()) return null;  // 重复
 *         if (addresses.size() == 1) return addresses.get(0);         // 重复
 *         // 真正不一样的逻辑 ↓
 *         return doSelect(addresses, request);
 *     }
 * }
 *
 * // 随机
 * public class RandomLoadBalance implements LoadBalance {
 *     @Override
 *     public String selectServiceAddress(List<String> addresses, RpcRequest request) {
 *         if (addresses == null || addresses.isEmpty()) return null;  // 重复
 *         if (addresses.size() == 1) return addresses.get(0);         // 重复
 *         // 真正不一样的逻辑 ↓
 *         return doSelect(addresses, request);
 *     }
 * }
 *
 * // 轮询
 * public class RoundRobinLoadBalance implements LoadBalance {
 *     @Override
 *     public String selectServiceAddress(List<String> addresses, RpcRequest request) {
 *         if (addresses == null || addresses.isEmpty()) return null;  // 重复
 *         if (addresses.size() == 1) return addresses.get(0);         // 重复
 *         // 真正不一样的逻辑 ↓
 *         return doSelect(addresses, request);
 *     }
 * }
 * ```
 *
 * 每个实现类都复制粘贴一样的判空、单节点短路逻辑。**加一个抽象类**，这些代码只写一次：
 *
 * ```java
 * public abstract class AbstractLoadBalance implements LoadBalance {
 *
 *     @Override
 *     public String selectServiceAddress(List<String> addresses, RpcRequest request) {
 *         // 公共逻辑只写一次
 *         if (addresses == null || addresses.isEmpty()) return null;
 *         if (addresses.size() == 1) return addresses.get(0);
 *         return doSelect(addresses, request);
 *     }
 *
 *     // 子类只关心这个
 *     protected abstract String doSelect(List<String> addresses, RpcRequest request);
 * }
 * ```
 *
 * 子类就变简洁了：
 *
 * ```java
 * public class ConsistentHashLoadBalance extends AbstractLoadBalance {
 *     @Override
 *     protected String doSelect(List<String> addresses, RpcRequest request) {
 *         // 只写核心算法，不用管判空
 *     }
 * }
 * ```
 *
 * ---
 *
 * ## 那为什么还要保留接口？
 *
 * 直接只用抽象类不行吗——让子类全部 `extends AbstractLoadBalance`？
 *
 * **不行，接口是 SPI 扩展点的"契约"**。回到你这个项目的 `@SPI` 机制：
 *
 * ```java
 * ExtensionLoader.getExtensionLoader(LoadBalance.class)  // 按接口加载
 * ```
 *
 * `ExtensionLoader` 只认接口上标注的 `@SPI`。如果只有抽象类，第三方的实现类就必须继承你的抽象类，耦合度更高。保留接口，第三方可以：
 *
 * ```java
 * // 完全独立实现，不继承任何东西
 * @SPI
 * public class MyCustomLoadBalance implements LoadBalance {
 *     ...
 * }
 * ```
 *
 * ---
 *
 * ## 总结这个三层结构
 *
 * ```
 * LoadBalance (interface)        ← @SPI 标注，扩展点的"合同"
 *     ↑
 * AbstractLoadBalance (abstract) ← 公共逻辑写一次，给"标准实现"复用
 *     ↑
 * ConsistentHashLoadBalance      ← 只写核心算法
 * RandomLoadBalance
 * RoundRobinLoadBalance
 * ```
 *
 * | 层级 | 作用 |
 * |------|------|
 * | 接口 | 定义扩展点，SPI 加载的入口，**对外契约** |
 * | 抽象类 | 提取公共代码，**对内复用**，减少重复 |
 * | 具体类 | 只写差异化的核心逻辑 |
 *
 * **接口管"是什么"（what），抽象类管"怎么做公共部分"（how-common），实现类管"怎么做自己那部分"（how-specific）。**
 *
 * JDK 自己也是这么干的——`List`(接口) → `AbstractList`(抽象类) → `ArrayList`(实现类)。
 */


/**
 * 模板方法模式的标准写法是什么？
 *
 * ## 核心结构
 *
 * ```
 * 抽象父类（模板）
 *     │
 *     ├── 模板方法(final)      ← 定义骨架，不允许子类改
 *     │       │
 *     │      调用 ──→ 具体步骤1(抽象/protected)  ← 子类实现
 *     │       │
 *     │      调用 ──→ 具体步骤2(抽象/protected)  ← 子类实现
 *     │       │
 *     │      调用 ──→ 钩子方法(可选、有默认值)    ← 子类可覆盖也可不覆盖
 *     │
 *     └── 具体步骤1 / 步骤2    ← 子类只负责这部分的差异化逻辑
 * ```
 *
 * ---
 *
 * ## 标准代码
 *
 * ```java
 * // 抽象父类：定义骨架
 * public abstract class AbstractTemplate {
 *
 *     // ① 模板方法 — final，不允许子类修改流程
 *     public final void templateMethod() {
 *         step1();          // 子类必须实现
 *         step2();          // 子类必须实现
 *         if (hook()) {     // 钩子：子类可选覆盖
 *             step3();
 *         }
 *     }
 *
 *     // ② 抽象方法 — 子类必须实现
 *     protected abstract void step1();
 *     protected abstract void step2();
 *
 *     // ③ 钩子方法 — 有默认实现，子类可选覆盖
 *     protected boolean hook() {
 *         return true;  // 默认走 step3
 *     }
 *
 *     // ④ 私有方法 — 子类不能动
 *     private void step3() {
 *         // 固定逻辑
 *     }
 * }
 * ```
 *
 * ```java
 * // 具体子类：只写差异化部分
 * public class ConcreteClass extends AbstractTemplate {
 *
 *     @Override
 *     protected void step1() { 自己的实现  }
        *
        *@Override
 *

protected void step2() {  自己的实现  }
 *
         *@Override
 *

protected boolean hook() {
    return false;
}  // 可选：关掉 step3
 *}
         * ```
         *
         *---
         *
         * ##四个关键要素
 *
         *|要素 |修饰符 |作用 |
        *|------|--------|------|
        *|**模板方法**| `public final` |定义算法骨架，禁止子类篡改流程 |
        *|**抽象步骤**| `protected abstract` |强制子类提供差异化实现 |
        *|**钩子方法**| `protected`（有默认实现） |子类可选覆盖，用于"开关"或"微调"|
        *|**私有步骤**| `private` |固定逻辑，子类完全不能碰 |
        *
        *---
        *
        * ##对照你项目里的代码
 *
         * ```java
 *

public abstract class AbstractLoadBalance implements LoadBalance {
 *
         *     // ① 模板方法 — 虽然没加 final，但就是这个角色
         *
    @Override
 *

    public String selectServiceAddress(List<String> addresses, RpcRequest request) {
 *if (addresses == null || addresses.isEmpty()) return null;   // 固定逻辑
 *if (addresses.size() == 1) return addresses.get(0);          // 固定逻辑
 *return doSelect(addresses, request);                         // → 调用扩展点
 *}
 *
         *     // ② 抽象步骤 — 子类必须实现
         *

    protected abstract String doSelect(List<String> addresses, RpcRequest request);
 *
}
 * ```
         *
         * ```java
 *

public class ConsistentHashLoadBalance extends AbstractLoadBalance {
 *
         *     // ② 子类只写这部分的差异逻辑
         *
    @Override
 *

    protected String doSelect(List<String> addresses, RpcRequest request) {
 *         // 一致性哈希的核心算法
 *}
 *
}
 * ```
         *
         *---
         *
         * ##调用方的感受
 *
         * ```java
 *
LoadBalance lb = new ConsistentHashLoadBalance();
 *
         * // 调的是模板方法，不用关心里面怎么判空、怎么分发的
         *
String addr = lb.selectServiceAddress(addresses, request);
 * ```
         *
         *调用者只看到接口方法 `selectServiceAddress`，永远调不到 `doSelect`（因为它是 `protected`）。流程由父类牢牢控制，变化的部分交给子类。
        *
        *---
        *
        ***一句话**：父类 `final`方法写死流程，`protected abstract`方法留给子类填坑。流程你定，细节我填。
 *
 */