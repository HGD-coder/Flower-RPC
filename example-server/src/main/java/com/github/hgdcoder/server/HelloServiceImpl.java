package com.github.hgdcoder.server;

import com.github.hgdcoder.Hello;
import com.github.hgdcoder.HelloService;

public class HelloServiceImpl implements HelloService{
    @Override
    public String hello(Hello hello) {
        return "Hello"+hello.getMessage()+","+hello.getDescription();
    }
}
