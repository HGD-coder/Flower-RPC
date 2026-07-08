package com.github.hgdcoder.extension;

import com.github.hgdcoder.factory.SingletonFactory;
import com.github.hgdcoder.utils.StringUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
public final class ExtensionLoader<T> {
    private static final String SERVICE_DIRECTORY = "META-INF/extensions/";

    /**
     * 扩展类加载器的缓存，每一个类都有一个扩展类加载器。
     * 需要考虑多线程的问题
     * */
    private static final Map<Class<?>,ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<>();

    private final Class<?> type;

    /**
     * 实例缓存，根据名字进行缓存
     * 保证可见性的 Holder。
     * */
    private final Map<String,Holder<Object>> cacheInstances =new ConcurrentHashMap<>();

    /**
     * 类缓存，根据名称进行缓存，从文件中进行读取的key，value
     * */
    private final Holder<Map<String,Class<?>>> cachedClasses=new Holder<>();

    private ExtensionLoader(Class<?> type) {
        this.type = type;
    }

    /**
     * 获取扩展类加载器
     * */
    public static <S> ExtensionLoader<S> getExtensionLoader(Class<S> type) {
        if(type==null){
            throw new IllegalArgumentException("Extension type should not be null.");
        }
        if(!type.isInterface()){
            //需要是接口
            throw new IllegalArgumentException("Extension type should be an interface.");
        }
        if(type.getAnnotation(SPI.class)==null){
            //类上需要包含SPI注解
            throw new IllegalArgumentException("Extension type Extension type must be annotated by @SPI.");
        }

        //创建类加载器，直接就是使用ConcurrentHashMap进行创建的，每一个类有一个自己的类加载器
        ExtensionLoader<S> extensionLoader=(ExtensionLoader<S>)EXTENSION_LOADERS.get(type);
        if(extensionLoader==null){
            EXTENSION_LOADERS.put(type,new ExtensionLoader<>(type));
            extensionLoader=(ExtensionLoader<S>)EXTENSION_LOADERS.get(type);
        }
        return extensionLoader;
    }

    public T getExtension(String name) {
        if(StringUtil.isBlank(name)){
            throw new IllegalArgumentException("Extension name should not be null or empty.");
        }

        //从缓存获取对象，如果没有的情况下，创建一个新的
        Holder<Object> holder=cacheInstances.get(name);
        if(holder==null){
            cacheInstances.put(name,new Holder<>());
            holder=cacheInstances.get(name);
        }
        //单例模式创建对象，双检测锁。没有只是使用ConcurrentHashMap
        Object instance=holder.getValue();
        if(instance==null){
            synchronized(holder){
                instance=holder.getValue();
                if(instance==null){
                    instance=createExtension(name);
                    holder.setValue(instance);
                }
            }
        }
        return (T)instance;
    }

    /**
     * 使用SigletonFactory创建单例bean
     * */
    private T createExtension(String name){
        //1.首先获得对应的类
        Class<?> clazz=getExtensionClasses().get(name);
        if(clazz==null){
            throw new RuntimeException("Extension class "+name+" not found.");
        }
        //2.获取实例
        return (T) SingletonFactory.getInstance(clazz);
    }

    //返回name → Class 的映射表
    private Map<String,Class<?>> getExtensionClasses(){
        //1.从缓存中获取所有的类（获得name → Class 的映射表）
        Map<String,Class<?>> classes=cachedClasses.getValue();
        //2.缓存中没有，进行双锁检测
        if(classes==null){
            synchronized (cachedClasses){
                classes=cachedClasses.getValue();
                if(classes==null){
                    classes=new HashMap<>();
                    //3.从文件夹中加载所有的拓展类
                    loadDiretory(classes);
                    cachedClasses.setValue(classes);
                }
            }
        }
        return classes;
    }

    /**
     * java的SPI机制
     * */
    private void loadDiretory(Map<String,Class<?>> extensionClasses){
        //1.构建配置文件的路径
        String fileName=ExtensionLoader.SERVICE_DIRECTORY+type.getName();
        try{
            Enumeration<URL> urls;
            //2.Java的SPI，拓展类加载器，然后设置文件的URL
            ClassLoader classLoader=ExtensionLoader.class.getClassLoader();
            urls=classLoader.getResources(fileName);
            if(urls!=null){
                while(urls.hasMoreElements()){
                    URL resourceUrl=urls.nextElement();
                    //3.加载并解析
                    loadResource(extensionClasses,classLoader,resourceUrl);
                }
            }
        }catch(IOException e){
            log.error(e.getMessage());
        }
    }

    private void loadResource(Map<String,Class<?>> extensionClasses,ClassLoader classLoader,URL resourceUrl){
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(resourceUrl.openStream(),UTF_8))){
            String line;
            //读取配置文件的每一行
            while((line=reader.readLine())!=null){
                //1.过滤注释
                final int  ci=line.indexOf('#');
                if(ci>=0){
                    line=line.substring(0,ci);
                }
                //2.去掉空格
                line=line.trim();
                if(line.length()>0){
                    try{
                        //3.实现key value对解析，存入map中
                        final int ei=line.indexOf('=');
                        String name=line.substring(0,ei).trim();
                        String clazzName=line.substring(ei+1).trim();
                        if(name.length()>0&&clazzName.length()>0){
                            //4.Java的SPI的具体实现
                            Class<?> clazz=classLoader.loadClass(clazzName);
                            extensionClasses.put(name,clazz);
                        }
                    }catch(ClassNotFoundException e){
                        log.error(e.getMessage());
                    }
                }
            }
        }catch (IOException e){
            log.error(e.getMessage());
        }
    }
}

/**
 * ?和T的区别
 * T 是类型参数，代表一个具体但未知的类型
 * 在类或方法定义时声明，整个作用域内 T 指的是同一个类型：
 * javapublic class ExtensionLoader<T> {
 *     // 这里的 T 贯穿整个类
 *     // 外部用 ExtensionLoader<Serializer> 时，T 就固定是 Serializer
 *
 *     public T getExtension(String name) {
 *         return (T) instance;  // 返回值和类声明的 T 是同一个类型
 *     }
 * }
 * T 是一个占位符，实例化时填入具体类型，之后就固定了。
 *
 * ? 是通配符，代表某个不关心是什么的类型
 * javaMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS
 * 意思是：map 的 key 是"某种类的 Class"，value 是"某种类型的 ExtensionLoader"，但不关心具体是什么，也不要求 key 和 value 的类型一致。
 *
 * 最核心的区别：能不能往里写
 * javaList<T> listT = new ArrayList<>();
 * listT.add(item);  // 可以，T 是确定的类型
 *
 * List<?> listQ = new ArrayList<>();
 * listQ.add(item);  // 编译报错，? 是未知类型，不能写入
 * ? 只能读，不能写，因为编译器不知道 ? 到底是什么，写入任何东西都可能类型不匹配。
 *
 * 为什么 EXTENSION_LOADERS 要用 ? 而不是 T
 * 因为这个 map 要同时存不同类型的加载器：
 * javaEXTENSION_LOADERS.put(Serializer.class,  new ExtensionLoader<Serializer>());
 * EXTENSION_LOADERS.put(LoadBalance.class, new ExtensionLoader<LoadBalance>());
 * EXTENSION_LOADERS.put(Registry.class,    new ExtensionLoader<Registry>());
 * 如果用 T：
 * javaMap<Class<T>, ExtensionLoader<T>> EXTENSION_LOADERS
 * // T 只能是一种类型，没法同时存 Serializer 和 LoadBalance
 * 用 ? 就能放任意类型进去，代价是取出来时编译器不知道具体类型，需要强转。
 */