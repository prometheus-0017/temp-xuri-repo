package com.xuri.rpc;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 远程代理对象，对应TS中createRemoteProxy动态创建的对象
 *
 * 在TS中，远程代理是一个拥有若干函数属性的普通对象:
 *   { methodA: async (...args) => ..., methodB: async (...args) => ... }
 *
 * Java中无法动态创建此类对象，使用RemoteObject包装，通过invoke方法调用远程方法
 */
public class RemoteObject {

    /**
     * 远程方法接口
     */
    @FunctionalInterface
    public interface RemoteMethod {
        /* @async 原TS中每个远程方法都是async函数，此处已转换为同步阻塞 */
        Object invoke(Object... args);
    }

    private final Map<String, RemoteMethod> methods = new HashMap<>();
    private boolean callable = false;

    public void addMethod(String name, RemoteMethod method) {
        methods.put(name, method);
        if ("__call__".equals(name)) {
            callable = true;
        }
    }

    /**
     * 调用远程方法
     *
     * @param method 方法名
     * @param args   参数列表
     * @return 远程调用结果
     */
    public Object invoke(String method, Object... args) {
        RemoteMethod m = methods.get(method);
        if (m == null) {
            throw new RuntimeException("no such function: " + method);
        }
        return m.invoke(args);
    }

    /**
     * 对应TS中 __call__ 模式：远程对象本身是可调用的函数
     * TS中的实现是: const func = async (...args) => result['__call__'](...args)
     */
    public Object call(Object... args) {
        if (!callable) {
            throw new RuntimeException("object is not callable");
        }
        return invoke("__call__", args);
    }

    public boolean isCallable() {
        return callable;
    }

    public boolean hasMethod(String name) {
        return methods.containsKey(name);
    }
}
