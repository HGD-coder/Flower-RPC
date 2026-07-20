package com.github.hgdcoder.serialize.jdk;

import com.github.hgdcoder.serialize.Serializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * 把 Java 对象转换为字节数组的过渡实现。
 *
 * 注意：V8 虽然不再直接向 Socket 写 Object，但消息体仍采用 JDK 序列化，
 * 因此当前版本依然只适合 Java 与 Java 通信。V9 再替换为其他序列化器。
 */
public class JdkSerializer implements Serializer {
    @Override
    public byte[] serialize(Object object) {
        try(ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        ObjectOutputStream objectOut = new ObjectOutputStream(byteOut)) {
            objectOut.writeObject(object);
            objectOut.flush();
            return byteOut.toByteArray();
        }catch (Exception e) {
            throw new RuntimeException("JDK serialize failed",e);
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> targetClass) {
        try(ByteArrayInputStream byteIn = new ByteArrayInputStream(bytes);
            ObjectInputStream objectIn = new ObjectInputStream(byteIn)){
            Object object = objectIn.readObject();
            return targetClass.cast(object);
        }catch (Exception e){
            throw new RuntimeException("JDK deserialize failed: "+targetClass.getName(),e);
        }
    }
}
