package com.github.hgdcoder.enums;

import java.util.Locale;

/**
 * RPC 协议中的序列化器类型。
 *
 * <p>name 用于配置文件和 SPI 扩展名称；
 * code 写入 RPC 消息头，占用一个 byte。</p>
 *
 * <p>已经发布的 code 不能随意修改，否则新旧客户端和服务端
 * 会对同一个数字产生不同理解。</p>
 */
public enum SerializationTypeEnum {
    JDK(((byte) 1),"jdk"),
    KRYO((byte) 2,"kryo"),
    HESSIAN((byte) 3,"hessian"),
    PROTOSTUFF((byte) 4,"protostuff");

    private final byte code;
    private final String name;

    SerializationTypeEnum(byte code, String name) {
        this.code = code;
        this.name = name;
    }

    public byte getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    /**
     * 把配置文件中的名称转换成枚举。
     *
     * 例如：
     * " Kryo " -> KRYO
     */
    public static SerializationTypeEnum fromName(String rawName) {
        if(rawName == null || rawName.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "serializer name must not be empty"
            );
        }

        String normalized = rawName.toLowerCase(Locale.ROOT);

        for (SerializationTypeEnum type : values()) {
            if (type.name.equals(normalized)) {
                return type;
            }
        }

        throw new IllegalArgumentException(
                "Unsupported serializer name: " + rawName
        );
    }

    /**
     * 把协议头中的 byte 转换成序列化类型。
     */
    public static SerializationTypeEnum fromCode(byte code) {
        for (SerializationTypeEnum type : values()) {
            if (type.code == code) {
                return type;
            }
        }

        throw new IllegalArgumentException(
                "Unknown serializer codec: " + code
        );
    }
}
