package com.github.hgdcoder.remoting.handler;

import com.github.hgdcoder.enums.RpcErrorMessageEnum;
import com.github.hgdcoder.exception.RpcException;
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
        }catch (RpcException e){
            // 已经是明确分类的 RPC 异常，不再重复包装。
            throw e;
        } catch (Exception e) {
            throw new RpcException(
                    RpcErrorMessageEnum.SERVICE_INVOCATION_FAILURE,
                    rpcRequest.getRpcServiceName() + "#" + rpcRequest.getMethodName(),
                    e
            );
        }
    }
}
