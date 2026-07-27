package com.github.hgdcoder.serialize.kryo;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.serialize.Serializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
/**
 * Kryo 序列化实现。
 *
 * Kryo 实例不是线程安全的，Serializer 会被 ExtensionLoader 缓存为单例，
 * 因此必须使用 ThreadLocal，让每个业务线程拥有自己的 Kryo 实例
 */
public class KryoSerializer implements Serializer {
    /**
     * 每个线程独立持有一个Kryo实例
     */
    private static final ThreadLocal<Kryo> KRYO_HOLDER = ThreadLocal.withInitial(()->{
        Kryo kryo = new Kryo();
        // Kryo 默认要求所有类必须预注册，这里关闭强制注册以支持动态类。
        kryo.setRegistrationRequired(false);

        //预注册RPC核心类，避免序列化时写入完整类名，减少字节数
        kryo.register(RpcRequest.class);
        kryo.register(RpcResponse.class);
        return kryo;
    });

    @Override
    public byte[] serialize(Object object) {
        try(ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            Output output = new Output(byteOut)){
            Kryo kryo = KRYO_HOLDER.get();
            kryo.writeObject(output,object);
            output.flush();
            return byteOut.toByteArray();
        }catch(Exception e){
            throw new RuntimeException("Kryo serialize failed", e);
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes,Class<T> targetClass){
        try (ByteArrayInputStream byteIn = new ByteArrayInputStream(bytes);
             Input input = new Input(byteIn)) {
            Kryo kryo = KRYO_HOLDER.get();
                return kryo.readObject(input, targetClass);
        } catch (Exception e) {
            throw new RuntimeException("Kryo deserialize failed: " + targetClass.getName(), e);
        }
    }
}
