package com.github.hgdcoder.factory;

import com.github.hgdcoder.extension.Holder;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class SingletonFactory {
    private static final Map<String,Holder<Object>> OBJECT_MAP=new ConcurrentHashMap<>();
    private static final Object lock=new Object();

    private SingletonFactory(){
    }

    //没有无参构造器的情况下，使用Supplier提供返回 T 的 lambda
    //Supplier 有返回值没参数
    public static <T> T getInstance(Supplier<T> constructor,Class<T> clazz){
        if(clazz==null){
            throw new IllegalArgumentException("Class cannot be null");
        }
        String key=clazz.getName();

        //1.第一次检查，快速读取缓存（无锁）
        Holder<Object> holder=OBJECT_MAP.get(key);
        if(holder!=null&&holder.getValue()!=null){
            //1.1 holder保证可见性，从而不会使用没有初始化的对象
            return clazz.cast(holder.getValue());
        }

        //2.同步块：确保只有一个线程创建实例
        synchronized (lock){
            //3.二次检查：防止其他线程已创建holder
            holder=OBJECT_MAP.computeIfAbsent(key,k->new Holder<>());

            //4.创建实例 此处不需要再次检查holder.getValue()，因为锁保证了互斥性
            if(holder.getValue()==null){
                try{
                    //4.1创建对象()
                    T instance=constructor.get();
                    //4.2 放入到map里面
                    holder.setValue(instance);
                }catch(Exception e){
                    throw new RuntimeException("创建示例失败",e);
                }
            }
        }
        //cast是从Object转到clazz对应的类或者接口
        return clazz.cast(holder.getValue());
    }

    //Supplier 是"给我造一个对象"，Consumer 是"对象已经有了，你来配置它"
    //有无参构造器，框架能反射创建对象，但内部一些值还是null，还需要配置
    //Consumer 有参数没返回值
    public static <T> T getInstance(Consumer<T> initConsumer, Class<T> clazz){
        if(clazz==null){
            throw new IllegalArgumentException("Class cannot be null");
        }
        String key=clazz.getName();

        //1.第一次检查：快速读取缓存（无锁）
        Holder<Object> holder=OBJECT_MAP.get(key);
        if(holder!=null&&holder.getValue()!=null) {
            //1.1 holder保证可见性，从而不会使用没有初始化的对象
            return clazz.cast(holder.getValue());
        }

        //2.同步块：确保只有一个线程创建实例
        synchronized (lock){
            //3.第二次检查：防止其他线程已创建holder
            holder=OBJECT_MAP.computeIfAbsent(key,k->new Holder<>());

            //4.创建实例 此处不需要再次检查holder.getValue()，因为锁保证了互斥性
            if(holder.getValue()==null){
                try{
                    //4.1创建对象(找无参构造器，反射调用它)
                    T instance=clazz.getDeclaredConstructor().newInstance();
                    //4.2初始化对象
                    initConsumer.accept(instance);
                    //4.3放入map里面
                    holder.setValue(instance);
                }catch(Exception e){
                    throw new RuntimeException("创建示例失败",e);
                }
            }
        }
        return clazz.cast(holder.getValue());
    }

    public static <T> T getInstance(Class<T> clazz){
        if(clazz==null){
            throw new IllegalArgumentException("Class cannot be null");
        }
        String key=clazz.getName();
        //1.第一次检查：快速读取缓存（无锁）
        Holder<Object> holder=OBJECT_MAP.get(key);
        if(holder!=null&&holder.getValue()!=null) {
            //1.1 holder保证可见性，从而不会使用没有初始化的对象
            return clazz.cast(holder.getValue());
        }

        //2.同步块：确保只有一个线程创建实例
        synchronized (lock){
            // 3. 第二次检查：防止其他线程已创建holder
            holder=OBJECT_MAP.computeIfAbsent(key,k->new Holder<>());

            //4.创建实例 此处不需要再次检查holder.getValue()，因为锁保证了互斥性
            if(holder.getValue()==null){
                try{
                    //4.1 获得无参构造函数，获得实例
                    Constructor<T> constructor=clazz.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    T instance=constructor.newInstance();
                    //4.2 放入map里面
                    holder.setValue(instance);
                }catch(Exception e){
                    throw new RuntimeException("创建示例失败",e);
                }
            }
        }
        return clazz.cast(holder.getValue());
    }
}

/**
 * clazz.cast()和(T)强转
 * 场景：map 里存了一个 Integer，但你想取成 String
 * javaObject obj = 123;  // map 里存的是 Integer
 *
 * 用 (T) 强转
 * java// 第一步：强转，不报错
 * String s = (String) obj;  // 运行时什么都不发生，因为泛型擦除，T 变成 Object
 *
 * // 第二步：真正使用时才崩
 * System.out.println(s.length());  // 这里抛 ClassCastException
 * 错误发生在用的地方，不是强转那行。调用栈指向 s.length()，你看到报错完全不知道是哪里把错误的类型塞进来的，排查很痛苦。
 *
 * 用 c.cast()
 * javaClass<String> c = String.class;
 *
 * // 第一步：cast 内部检查类型
 * String s = c.cast(obj);  // 这里立刻抛 ClassCastException
 *                           // 因为 obj 是 Integer，不是 String
 * 错误发生在 cast 那行，调用栈直接指向问题根源，一眼就知道是类型不匹配。
 */



/**
 * 三个方法的不同
 * 用一个具体场景来说，假设你有一个 `RpcClient` 类，分三种情况：
 *
 * ---
 *
 * **情况一：类很简单，有无参构造器**
 *
 * ```java
 * public class RpcClient {
 *     private String host = "localhost";  // 默认值写死在这里
 *     private int port = 8080;
 *
 *     public RpcClient() {}  // 无参构造器
 * }
 * ```
 *
 * 用 `getInstance(Class)`，框架直接反射调无参构造器，什么都不用传：
 *
 * ```java
 * RpcClient client = SingletonFactory.getInstance(RpcClient.class);
 * ```
 *
 * ---
 *
 * **情况二：构造器需要参数，没有无参构造器**
 *
 * ```java
 * public class RpcClient {
 *     private String host;
 *     private int port;
 *
 *     public RpcClient(String host, int port) {  // 只有带参构造器
 *         this.host = host;
 *         this.port = port;
 *     }
 * }
 * ```
 *
 * 框架反射只会调无参构造器，这里根本没有，用 `getInstance(Class)` 直接报错。
 *
 * 用 `getInstance(Supplier, Class)`，你自己告诉框架怎么造：
 *
 * ```java
 * RpcClient client = SingletonFactory.getInstance(
 *     () -> new RpcClient("localhost", 8080),  // 你来造
 *     RpcClient.class
 * );
 * ```
 *
 * ---
 *
 * **情况三：有无参构造器，但造完之后还需要做额外的事**
 *
 * ```java
 * public class RpcClient {
 *     private String host;
 *     private int port;
 *     private Connection conn;
 *
 *     public RpcClient() {}  // 无参构造器，但字段都是 null
 *
 *     public void setHost(String host) { this.host = host; }
 *     public void setPort(int port) { this.port = port; }
 *     public void connect() { this.conn = new Connection(host, port); }
 * }
 * ```
 *
 * 框架能反射创建对象，但创建完 `host`、`port` 都是 null，还没连接。
 *
 * 用 `getInstance(Consumer, Class)`，框架造对象，你来配置：
 *
 * ```java
 * RpcClient client = SingletonFactory.getInstance(
 *     c -> {                    // 框架把造好的对象给你
 *         c.setHost("localhost");
 *         c.setPort(8080);
 *         c.connect();          // 你来做后续初始化
 *     },
 *     RpcClient.class
 * );
 * ```
 *
 * ---
 *
 * **一句话区别**
 *
 * `getInstance(Class)` — 框架全做，适合简单类。
 * `getInstance(Supplier)` — 你来造对象，适合构造器有参数。
 * `getInstance(Consumer)` — 框架造对象，你来配置，适合造完还要额外初始化的场景。
 */