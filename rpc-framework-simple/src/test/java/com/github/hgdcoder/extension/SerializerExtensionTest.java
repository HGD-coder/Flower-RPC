package com.github.hgdcoder.extension;

import com.github.hgdcoder.serialize.Serializer;
import com.github.hgdcoder.serialize.jdk.JdkSerializer;
import com.github.hgdcoder.serialize.kryo.KryoSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializerExtensionTest {
    @Test
    void shouldLoadBothSerializersFromExtensionFile() {
        ExtensionLoader<Serializer> loader = ExtensionLoader
                .getExtensionLoader(Serializer.class);

        assertTrue(loader.getExtension("jdk") instanceof JdkSerializer);
        assertTrue(loader.getExtension("kryo") instanceof KryoSerializer);
    }
}
