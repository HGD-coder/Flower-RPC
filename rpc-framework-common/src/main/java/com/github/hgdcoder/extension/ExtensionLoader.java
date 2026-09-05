package com.github.hgdcoder.extension;

import com.github.hgdcoder.factory.SingletonFactory;
import com.github.hgdcoder.utils.StringUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 加载 Flower-RPC 扩展映射，并按扩展名创建对应实现。
 *
 * <p>getExtension() 获取并缓存无参扩展，适合序列化器、
 * 压缩器和负载均衡器等可以安全复用的对象。</p>
 *
 * <p>newExtension() 每次创建一个新对象，适合 NettyRpcClient
 * 等包含连接、线程和关闭状态的对象。</p>
 */
@Slf4j
public final class ExtensionLoader<T> {
    /**
     * SPI 配置文件所在目录。
     *
     * 完整路径示例：
     * META-INF/extensions/com.github.hgdcoder.serialize.Serializer
     */
    private static final String SERVICE_DIRECTORY = "META-INF/extensions/";

    /**
     * 缓存每个接口对应的 ExtensionLoader。
     *
     * 例如：
     * Serializer.class -> ExtensionLoader<Serializer>
     * ServiceDiscovery.class -> ExtensionLoader<ServiceDiscovery>
     */
    private static final Map<Class<?>,ExtensionLoader<?>>
            EXTENSION_LOADERS = new ConcurrentHashMap<>();

    /**
     * 当前加载器负责的 SPI 接口类型。
     */
    private final Class<T> type;

    /**
     * getExtension() 使用的实例缓存。
     *
     * key：扩展名称，例如 kryo
     * value：保存已经创建的 KryoSerializer的 Holder(Holder保证可见性)
     *
     * newExtension() 创建的对象不会放入这里。
     */
    private final Map<String,Holder<Object>>
            cachedInstances =new ConcurrentHashMap<>();

    /**
     * 扩展名到实现类的映射缓存。
     *
     * 例如：
     * kryo -> KryoSerializer.class
     * jdk -> JdkSerializer.class
     */
    private final Holder<Map<String,Class<? extends T>>>
            cachedClasses=new Holder<>();

    private ExtensionLoader(Class<T> type) {
        this.type = type;
    }

    /**
     * 获取指定 SPI 接口对应的 ExtensionLoader。
     *
     * <p>每种接口在当前 JVM 中只创建一个加载器。</p>
     */
    @SuppressWarnings("unchecked")
    public static <S> ExtensionLoader<S> getExtensionLoader(
            Class<S> type
    ) {
        if(type==null){
            throw new IllegalArgumentException("Extension type should not be null.");
        }
        if(!type.isInterface()){
            //需要是接口
            throw new IllegalArgumentException("Extension type should be an interface.");
        }
        if(type.getAnnotation(SPI.class)==null){
            //类上需要包含SPI注解
            throw new IllegalArgumentException("Extension type must be annotated by @SPI.");
        }

        /*
         * computeIfAbsent 保证同一种接口只创建一个加载器。
         *
         * map 中保存了不同泛型的 ExtensionLoader，
         * 因此取出时需要进行一次受控的类型转换。
         */
        return (ExtensionLoader<S>)
                EXTENSION_LOADERS.computeIfAbsent(
                        type,
                        ignored -> new ExtensionLoader<>(type)
                );
    }

    /**
     * 获取并缓存一个无参扩展实例。
     *
     * <p>同一个扩展名多次调用时返回同一个对象。</p>
     *
     * <p>适合无连接、无线程、没有关闭状态的扩展。</p>
     */
    public T getExtension(String name) {
        String extensionName = requireExtensionName(name);

        /*
         * 每个扩展名对应一个 Holder。
         * Holder 内部保存最终创建出来的扩展对象。
         */
        Holder<Object> holder = cachedInstances.computeIfAbsent(
                extensionName,
                ignored -> new Holder<>()
        );
        //单例模式创建对象，双检测锁。没有只是使用ConcurrentHashMap
        Object instance=holder.getValue();
        if(instance==null){
            /*
             * 只锁当前扩展名对应的 Holder。
             *
             * kryo 的创建不会阻塞 jdk 扩展的创建。
             */
            synchronized(holder){
                instance=holder.getValue();
                if(instance==null){
                    instance=createCachedExtension(name);
                    holder.setValue(instance);
                }
            }
        }
        /*
         * type.cast() 会在这里进行运行时类型检查。
         */
        return type.cast(instance);
    }

    /**
     * 根据指定的 public 构造器创建一个全新扩展实例。
     *
     * <p>这个方法不会读取或写入 cachedInstances，
     * 因此每次调用都会得到不同的对象。</p>
     *
     * @param name 扩展名称，例如 netty
     * @param parameterTypes 构造器的参数类型
     * @param arguments 构造器的实际参数
     * @return 新创建的扩展实例
     */
    public T newExtension(
            String name,
            Class<?>[] parameterTypes,
            Object... arguments
    ) {
        String extensionName = requireExtensionName(name);

        /*
         * 在执行反射前先检查参数数量和类型。
         * 这样可以给出比反射异常更容易理解的信息。
         */
        validateConstructorArguments(
                parameterTypes,
                arguments
        );

        /*
         * 根据 SPI 配置中的扩展名找到实现类。
         */
        Class<? extends T> implementation =
                getExtensionClass(extensionName);

        final Constructor<? extends T> constructor;

        try{
            /*
             * getConstructor() 只查找 public 构造器。
             *
             * parameterTypes 必须与构造器声明的类型完全对应。
             */
            constructor = implementation.getConstructor(parameterTypes);
        }catch (NoSuchMethodException e){
            throw new IllegalArgumentException(
                    "Public constructor "
                            + constructorSignature(parameterTypes)
                            + " not found for extension '"
                            + extensionName
                            + "' ("
                            + implementation.getName()
                            + ")",
                    e
            );
        }

        try{
            /*
             * 使用找到的构造器创建新对象。
             */
            return type.cast(
                    constructor.newInstance(arguments)
            );
        }catch (InvocationTargetException e) {
            /*
             * InvocationTargetException 表示构造器已经被调用，
             * 但是构造器内部自己抛出了异常。
             *
             * 使用 getCause() 保留真正的构造失败原因。
             */
            throw new IllegalStateException(
                    "Constructor failed for extension '"
                            + extensionName
                            + "' ("
                            + implementation.getName()
                            + ")",
                    e.getCause()
            );
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Could not create extension '"
                            + extensionName
                            + "' ("
                            + implementation.getName()
                            + ")",
                    e
            );
        }
    }

    /**
     * 创建并缓存无参扩展。
     *
     * <p>继续使用原来的 SingletonFactory，
     * 保持已有扩展的单例行为。</p>
     */
    private T createCachedExtension(String name) {
        Class<? extends T> implementation =
                getExtensionClass(name);

        return type.cast(
                SingletonFactory.getInstance(implementation)
        );
    }


    /**
     * 根据扩展名获取实现类。
     */
    private Class<? extends T> getExtensionClass(
            String name
    ) {
        Class<? extends T> implementation =
                getExtensionClasses().get(name);

        if (implementation == null) {
            throw new IllegalArgumentException(
                    "Extension class '"
                            + name
                            + "' not found for "
                            + type.getName()
            );
        }

        return implementation;
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
    private Map<String,Class<? extends T>> getExtensionClasses(){
        //1.从缓存中获取所有的类（获得name → Class 的映射表）
        Map<String,Class<? extends T>> classes=cachedClasses.getValue();
        //2.缓存中没有，进行双锁检测
        if(classes==null){
            synchronized (cachedClasses){
                classes=cachedClasses.getValue();

                if(classes==null){
                    classes=new HashMap<>();

                    // 3.加载 META-INF/extensions/ 目录下的配置
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
    private void loadDiretory(Map<String,Class<? extends T>> extensionClasses){
        //    构建配置文件路径：META-INF/extensions/ + 接口全限定名
        //    例如：META-INF/extensions/com.github.hgdcoder.serialize.Serializer
        String fileName=ExtensionLoader.SERVICE_DIRECTORY+type.getName();

        /*
         * 优先使用线程上下文类加载器。
         *
         * 这样应用程序和第三方依赖中的扩展资源
         * 才可以被 Flower-RPC 发现。
         */
        ClassLoader classLoader =
                Thread.currentThread()
                        .getContextClassLoader();


        /*
         * 某些环境可能没有线程上下文类加载器，
         * 此时退回到 ExtensionLoader 自己的类加载器。
         */
        if (classLoader == null) {
            classLoader = ExtensionLoader.class.getClassLoader();
        }

        try{
            /*
             * 同一个接口可能在多个 JAR 中存在扩展配置文件。
             *
             * getResources() 会返回全部同名资源，
             * 而不是只返回第一个。
             */
            Enumeration<URL> urls =
                    classLoader.getResources(fileName);

            while(urls.hasMoreElements()){
                URL resourceUrl = urls.nextElement();

                loadResource(
                        extensionClasses,
                        classLoader,
                        resourceUrl
                );
            }
        }catch (IOException e){
            throw new IllegalStateException(
                    "Failed to load extension resources for "
                            + type.getName(),
                    e
            );
        }

    }

    private void loadResource(
            Map<String,Class<? extends T>> extensionClasses,
            ClassLoader classLoader,
            URL resourceUrl
    ){
        try(BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    resourceUrl.openStream(),
                                    UTF_8
                            )
                    )){
            String line;
            int lineNumber = 0;

            //读取配置文件的每一行
            while((line = reader.readLine()) != null){
                lineNumber++;

                //过滤注释(#后面的)
                int commentIndex = line.indexOf('#');

                if (commentIndex >= 0) {
                    line = line.substring(
                            0,
                            commentIndex
                    );
                }

                //2.去掉空格
                line=line.trim();


                // 跳过空行和只有注释的行
                if (line.isEmpty()) {
                    continue;
                }
                loadMapping(
                        extensionClasses,
                        classLoader,
                        resourceUrl,
                        lineNumber,
                        line
                );
            }
        }catch (IOException e){
            throw mappingError(
                    resourceUrl,
                    0,
                    "could not read resource",
                    e
            );
        }
    }

    /**
     * 解析一条 name=implementationClass 映射。
     */
    private void loadMapping(
            Map<String,Class<? extends T>> extensionClasses,
            ClassLoader classLoader,
            URL resourceUrl,
            int lineNumber,
            String line
    ) {
        int separatorIndex = line.indexOf('=');

        //必须有且只能有一个等号，并且等号不能位于首尾。
        if(separatorIndex <= 0
        || separatorIndex == line.length()-1
        ||line.indexOf('=',separatorIndex+1) >= 0){
            throw mappingError(
                    resourceUrl,
                    lineNumber,
                    "expected exactly "
                            + "'name=implementationClass'",
                    null
            );
        }

        String name = line.substring(0, separatorIndex).trim();

        String className = line.substring(separatorIndex+1).trim();

        if (name.isEmpty() || className.isEmpty()) {
            throw mappingError(
                    resourceUrl,
                    lineNumber,
                    "extension name and implementation "
                            + "class must not be empty",
                    null
            );
        }

        final Class<?> loadedClass;

        try{
            /*
             * 根据全限定类名加载 Class 对象，
             * 此时还没有创建实现类实例。
             */
            loadedClass = classLoader.loadClass(className);
        } catch (ClassNotFoundException | LinkageError e) {
            throw mappingError(
                    resourceUrl,
                    lineNumber,
                    "implementation class not found:"+className,
                    e
            );
        }

        /*
         * 检查配置的实现类是否真的实现了当前 SPI 接口。
         *
         * 例如 Serializer 配置文件中不能放 String。
         */
        if(!type.isAssignableFrom(loadedClass)){
            throw mappingError(
                    resourceUrl,
                    lineNumber,
                    className + "is not assignable to" + type.getName(),
                    null
            );
        }

        Class<? extends T> implementation
                = loadedClass.asSubclass(type);

        Class<? extends T> existing =
                extensionClasses.get(name);

        /*
         * 多个 JAR 可以重复声明相同的 name=class。
         *
         * 但同一个名称指向两个不同的类时，
         * 无法确定应该使用谁，因此直接启动失败。
         */
        if(existing != null && !existing.equals(implementation)){
            throw mappingError(
                    resourceUrl,
                    lineNumber,
                    "conflicting extension name '"
                            + name
                            + "': "
                            + existing.getName()
                            + " and "
                            + implementation.getName(),
                    null
            );
        }

        extensionClasses.put(
                name,
                implementation
        );
    }

    /**
     * 检查扩展名不能为空。
     */
    private String requireExtensionName(String name) {
        if (StringUtil.isBlank(name)) {
            throw new IllegalArgumentException(
                    "Extension name should not be "
                            + "null or empty."
            );
        }

        return name;
    }

    /**
     * 在执行反射前检查构造器参数。
     */
    private void validateConstructorArguments(
            Class<?>[] parameterTypes,
            Object[] arguments
    ) {
        if(parameterTypes == null) {
            throw new IllegalArgumentException(
                    "Constructor parameter types "
                            + "must not be null."
            );
        }

        if(arguments == null) {
            throw new IllegalArgumentException(
                    "Constructor arguments "
                            + "must not be null."
            );
        }

        if(parameterTypes.length != arguments.length) {
            throw new IllegalArgumentException(
                    "Constructor parameter type count "
                            + "must match argument count."
            );
        }

        for(int index=0;index<parameterTypes.length;index++){
            Class<?> parameterType =
                    parameterTypes[index];

            if(parameterType == null) {
                throw new IllegalArgumentException(
                        "Constructor parameter type at index "
                                + index
                                + " must not be null."
                );
            }

            Object argument = arguments[index];

            /*
             * 引用类型参数允许传 null，
             * 基本类型参数不能接收 null。
             */
            if (argument == null) {
                if (parameterType.isPrimitive()) {
                    throw new IllegalArgumentException(
                            "Constructor argument at index "
                                    + index
                                    + " must not be null "
                                    + "for primitive "
                                    + parameterType.getName()
                                    + "."
                    );
                }
            }

            /*
             * isInstance() 不能直接判断基本类型，
             * 因此先通过 boxedType() 转成包装类型。
             */
            if(!boxedType(parameterType).isInstance(argument)) {
                throw new IllegalArgumentException(
                        "Constructor argument at index "
                                + index
                                + " has type "
                                + argument.getClass().getName()
                                + ", expected "
                                + parameterType.getName()
                                + "."
                );
            }
        }
    }

    /**
     * 把参数类型数组转换成便于阅读的构造器签名。
     */
    private String constructorSignature(
            Class<?>[] parameterTypes
    ) {
        StringBuilder signature =
                new StringBuilder("(");

        for (int index = 0;
             index < parameterTypes.length;
             index++) {
            if (index > 0) {
                signature.append(", ");
            }

            signature.append(
                    parameterTypes[index].getName()
            );
        }

        return signature.append(')').toString();
    }

    /**
     * 把基本类型转换成对应包装类型。
     */
    private Class<?> boxedType(Class<?> valueType) {
        if (!valueType.isPrimitive()) {
            return valueType;
        }

        if (valueType == boolean.class) {
            return Boolean.class;
        }
        if (valueType == byte.class) {
            return Byte.class;
        }
        if (valueType == short.class) {
            return Short.class;
        }
        if (valueType == int.class) {
            return Integer.class;
        }
        if (valueType == long.class) {
            return Long.class;
        }
        if (valueType == float.class) {
            return Float.class;
        }
        if (valueType == double.class) {
            return Double.class;
        }
        if (valueType == char.class) {
            return Character.class;
        }

        /*
         * void.class 不可能作为有效构造器参数，
         * 返回 Void.class 让类型检查自然失败。
         */
        return Void.class;
    }

    /**
     * 统一构造 SPI 配置错误。
     */
    private IllegalStateException mappingError(
            URL resourceUrl,
            int lineNumber,
            String reason,
            Throwable cause
    ) {
        String location = lineNumber > 0
                ? resourceUrl + ":" + lineNumber
                : resourceUrl.toString();

        String message =
                "Invalid extension mapping for "
                        + type.getName()
                        + " at "
                        + location
                        + ": "
                        + reason;

        return cause == null
                ? new IllegalStateException(message)
                : new IllegalStateException(
                message,
                cause
        );
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