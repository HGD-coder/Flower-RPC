package com.github.hgdcoder.constants;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class RpcConstants {
    //magic number . Verify RpcMessage
    public static final byte[] MAGIC_NUMBER = new byte[] {(byte)'g',(byte)'r',(byte)'p',(byte)'c'};
    public static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    //version information
    public static final byte VERSION=1;
    public static final byte TOTAL_LENGTh=16;

    //ping

    //pong
}
