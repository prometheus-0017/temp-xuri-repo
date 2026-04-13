package com.xuri.rpc

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * 远程代理管理器，管理从远程获取的代理对象
 * 对应TS中: export class RemoteProxyManager
 *
 * 使用 WeakReference (对应TS WeakRef)，允许GC回收不再使用的远程代理
 */
class RemoteProxyManager {

    // id → WeakReference<远程代理对象>
    private val map = ConcurrentHashMap<String, WeakReference<Any>>()

    // Client → 该Client相关的代理ID集合
    val clientMap = ConcurrentHashMap<Client, MutableSet<String>>()

    fun set(id: String, proxy: Any, client: Client) {
        map[id] = WeakReference(proxy)
        clientMap.getOrPut(client) { ConcurrentHashMap.newKeySet() }.add(id)
    }

    /**
     * 不指定Client的set
     * TS中 transformArg/createRemoteProxy 调用时未传client参数
     */
    fun set(id: String, proxy: Any) {
        map[id] = WeakReference(proxy)
    }

    fun get(id: String): Any? {
        val ref = map[id] ?: return null
        val result = ref.get()
        if (result == null) {
            map.remove(id)
            return null
        }
        return result
    }

    fun removeFromClientMap(client: Client) {
        clientMap.remove(client)
    }
}
