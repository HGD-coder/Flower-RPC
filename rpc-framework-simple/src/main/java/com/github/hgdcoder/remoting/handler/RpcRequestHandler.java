package com.github.hgdcoder.remoting.handler;

import com.github.hgdcoder.provider.ServiceProvider;
import com.github.hgdcoder.remoting.dto.RpcRequest;

import java.lang.reflect.Method;

public class RpcRequestHandler {
    private final ServiceProvider serviceProvider;

    public RpcRequestHandler(ServiceProvider serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    public Object handle(RpcRequest rpcRequest) {
        try{
            Object service = serviceProvider.getService(rpcRequest.getRpcServiceName());
            Method method = service.getClass().getMethod(
                    rpcRequest.getMethodName(),
                    rpcRequest.getParamTypes()
            );
            return method.invoke(service,rpcRequest.getParameters());
        }catch (Exception e){
            throw new RuntimeException("Rpc request handle failed",e);
        }
    }
}
