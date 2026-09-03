package com.github.hgdcoder.remoting.handler;

import com.github.hgdcoder.enums.RpcErrorMessageEnum;
import com.github.hgdcoder.enums.RpcStatusCode;
import com.github.hgdcoder.exception.RpcException;
import com.github.hgdcoder.provider.ServiceProvider;
import com.github.hgdcoder.remoting.dto.RpcRequest;

import java.lang.reflect.InvocationTargetException;
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
        } catch (InvocationTargetException e) {
            /*
             * Method.invoke 会把业务方法抛出的异常包进 InvocationTargetException。
             * 明确的 RpcServiceException 也属于 RpcException，应该原样保留；
             * 未知业务异常则统一标记为 INTERNAL，并由响应工厂隐藏内部信息。
             */
            Throwable targetException = e.getTargetException();
            if (targetException instanceof RpcException) {
                throw (RpcException) targetException;
            }
            throw new RpcException(
                    RpcStatusCode.INTERNAL,
                    rpcRequest.getRequestId(),
                    "服务方法执行失败",
                    targetException
            );
        } catch (Exception e) {
            throw new RpcException(
                    RpcErrorMessageEnum.SERVICE_INVOCATION_FAILURE,
                    rpcRequest.getRpcServiceName() + "#" + rpcRequest.getMethodName(),
                    e
            );
        }
    }
}
