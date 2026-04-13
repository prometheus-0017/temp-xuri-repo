package com.xuri.rpc;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 本地对象代理管理器，管理本地对象与代理ID的双向映射
 * 对应TS中: export class ObjectOfProxyManager
 *
 * proxyMap使用IdentityHashMap，等价于TS中以对象引用为key的Map
 */
public class ObjectOfProxyManager {

    /**
     * 代理对象处理器，记录代理ID、目标对象和最后注册时间
     * 对应TS中: class ProxyObjectHandlerForManager
     */
    static class ProxyObjectHandler {
        final String id;
        final Object target;
        long lastRegistered;

        ProxyObjectHandler(String id, Object target) {
            this.id = id;
            this.target = target;
            this.lastRegistered = System.currentTimeMillis();
        }
    }

    // 对象 → ID (使用引用相等, 等价于TS中的Map<object, string>)
    private final Map<Object, String> proxyMap = new IdentityHashMap<>();

    // ID → Handler
    private final Map<String, ProxyObjectHandler> reverseProxyMap = new HashMap<>();

    public void set(Object obj, String id) {
        proxyMap.put(obj, id);
        reverseProxyMap.put(id, new ProxyObjectHandler(id, obj));
    }

    public void reRegister(String id) {
        ProxyObjectHandler handler = reverseProxyMap.get(id);
        if (handler != null) {
            handler.lastRegistered = System.currentTimeMillis();
        }
    }

    public Object getById(String id) {
        ProxyObjectHandler handler = reverseProxyMap.get(id);
        return handler != null ? handler.target : null;
    }

    public String get(Object obj) {
        return proxyMap.get(obj);
    }

    public boolean has(Object obj) {
        return proxyMap.containsKey(obj);
    }

    public void deleteById(String id) {
        ProxyObjectHandler handler = reverseProxyMap.get(id);
        if (handler != null) {
            proxyMap.remove(handler.target);
        }
        reverseProxyMap.remove(id);
    }

    public void delete(Object obj) {
        String id = proxyMap.get(obj);
        if (id != null) {
            reverseProxyMap.remove(id);
        }
        proxyMap.remove(obj);
    }

    public int size() {
        return proxyMap.size();
    }

    /**
     * 移除超时的代理对象
     * 对应TS中 removeOutdatedProxyObject 函数对 reverseProxyMap 的遍历逻辑
     *
     * @param timeoutMs 超时阈值(毫秒)，实际使用 timeout*3
     * @return 被移除的数量
     */
    public int removeOutdated(long timeoutMs) {
        int count = 0;
        Iterator<Map.Entry<String, ProxyObjectHandler>> it = reverseProxyMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ProxyObjectHandler> entry = it.next();
            if (System.currentTimeMillis() - entry.getValue().lastRegistered > timeoutMs * 3) {
                proxyMap.remove(entry.getValue().target);
                it.remove();
                count++;
            }
        }
        return count;
    }
}
