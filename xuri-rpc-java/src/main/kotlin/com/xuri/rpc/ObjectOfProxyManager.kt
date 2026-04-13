package com.xuri.rpc

import java.util.IdentityHashMap

/**
 * 本地对象代理管理器，管理本地对象与代理ID的双向映射
 * 对应TS中: export class ObjectOfProxyManager
 *
 * proxyMap 使用 IdentityHashMap，等价于TS中以对象引用为key的Map
 */
class ObjectOfProxyManager {

    /**
     * 代理对象处理器
     * 对应TS中: class ProxyObjectHandlerForManager
     */
    private class ProxyObjectHandler(
        val id: String,
        val target: Any,
        var lastRegistered: Long = System.currentTimeMillis()
    )

    // 对象 → ID (引用相等)
    private val proxyMap = IdentityHashMap<Any, String>()

    // ID → Handler
    private val reverseProxyMap = mutableMapOf<String, ProxyObjectHandler>()

    fun set(obj: Any, id: String) {
        proxyMap[obj] = id
        reverseProxyMap[id] = ProxyObjectHandler(id, obj)
    }

    fun reRegister(id: String) {
        reverseProxyMap[id]?.let { it.lastRegistered = System.currentTimeMillis() }
    }

    fun getById(id: String): Any? = reverseProxyMap[id]?.target

    fun get(obj: Any): String? = proxyMap[obj]

    fun has(obj: Any): Boolean = obj in proxyMap

    fun deleteById(id: String) {
        reverseProxyMap.remove(id)?.let { proxyMap.remove(it.target) }
    }

    fun delete(obj: Any) {
        proxyMap.remove(obj)?.let { reverseProxyMap.remove(it) }
    }

    val size: Int get() = proxyMap.size

    /**
     * 移除超时的代理对象
     * 对应TS中 removeOutdatedProxyObject 对 reverseProxyMap 的遍历 (已修复bug)
     *
     * @param timeoutMs 超时阈值(毫秒)，实际比较用 timeout*3
     * @return 被移除的数量
     */
    fun removeOutdated(timeoutMs: Long): Int {
        var count = 0
        val now = System.currentTimeMillis()
        val iter = reverseProxyMap.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (now - entry.value.lastRegistered > timeoutMs * 3) {
                proxyMap.remove(entry.value.target)
                iter.remove()
                count++
            }
        }
        return count
    }
}
