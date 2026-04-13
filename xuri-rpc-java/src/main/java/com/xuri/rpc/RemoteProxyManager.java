package com.xuri.rpc;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 远程代理管理器，管理从远程获取的代理对象
 * 对应TS中: export class RemoteProxyManager
 *
 * 使用WeakReference(对应TS WeakRef)，允许GC回收不再使用的远程代理
 */
public class RemoteProxyManager {

    // id → WeakReference<远程代理对象>
    private final Map<String, WeakReference<Object>> map = new ConcurrentHashMap<>();

    // Client → 该Client相关的代理ID集合
    private final Map<Client, Set<String>> clientMap = new ConcurrentHashMap<>();

    public void set(String id, Object proxy, Client client) {
        map.put(id, new WeakReference<>(proxy));
        clientMap.computeIfAbsent(client, k -> ConcurrentHashMap.newKeySet()).add(id);
    }

    /**
     * 不指定Client的set方法
     * 在TS中 transformArg 和 createRemoteProxy 中调用时未传client参数
     */
    public void set(String id, Object proxy) {
        map.put(id, new WeakReference<>(proxy));
    }

    public Object get(String id) {
        if (!map.containsKey(id)) {
            return null;
        }
        WeakReference<Object> ref = map.get(id);
        Object result = ref != null ? ref.get() : null;
        if (result == null) {
            map.remove(id);
            return null;
        }
        return result;
    }

    public Map<Client, Set<String>> getClientMap() {
        return clientMap;
    }

    public void removeFromClientMap(Client client) {
        clientMap.remove(client);
    }
}
