@file:Suppress("UNCHECKED_CAST")

package com.xuri.rpc

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * RPC框架核心工具类 (Kotlin object单例)
 * 对应TS中 rpc.ts 模块级别的变量和函数
 *
 * 定时任务使用 CoroutineScope + delay 实现，天然支持协程
 */
object Rpc {

    // ==================== 全局状态 ====================

    /** 调试标志, 对应TS: let debugFlag = false */
    @Volatile
    var debugFlag: Boolean = false

    /** 本地主机ID, 对应TS: let hostId: string | null = null */
    @Volatile
    var hostId: String? = null
        private set

    /** 全局消息接收器, 对应TS: let messageReceiver */
    @Volatile
    private var messageReceiver: MessageReceiver? = null

    /** ID计数器, 对应TS: let idCount = 0 */
    private val idCount = AtomicLong(0)

    /** 增强类型标志, 对应TS: let enchanceType = true */
    var enhanceType: Boolean = true

    /** GC超时限制(毫秒), 对应TS: const gcTimeLimit = 30 * 1000 */
    const val GC_TIME_LIMIT = 30_000L

    /** 连接超时限制(毫秒), 对应TS: const connectionLimit = 30 * 1000 */
    const val CONNECTION_LIMIT = 30_000L

    /**
     * 每个hostId的选项映射
     * 对应TS: let options: Record<string|symbol, MessageReceiverOptions> = {}
     */
    private val options = ConcurrentHashMap<String, MessageReceiverOptions>()

    /** 默认host的key, 对应TS: let defaultHost = Symbol('defaultHost') */
    private const val DEFAULT_HOST_KEY = "__DEFAULT_HOST__"

    // ==================== 浅自动包装器 ====================

    /**
     * 浅层自动包装器
     * 对应TS: const shallowAutoWrapper: AutoWrapper
     *
     * 规则:
     * - null → 原样
     * - RpcFunction → 包装为代理 (对应TS typeof == 'function')
     * - List中含RpcFunction → 整个List包装为代理
     * - Map中含RpcFunction值 → 整个Map包装为代理
     * - 其他 → 原样
     */
    val shallowAutoWrapper: AutoWrapper = { obj ->
        when {
            obj == null -> null
            obj is RpcFunction -> asProxy(obj)
            obj is List<*> -> {
                if (obj.any { it is RpcFunction }) asProxy(obj) else obj
            }
            obj is Map<*, *> -> {
                if (obj.values.any { it is RpcFunction }) asProxy(obj) else obj
            }
            else -> obj
        }
    }

    // ==================== 公共方法 ====================

    /**
     * 设置主机ID
     * 对应TS: export function setHostId(id: string)
     */
    fun setHostId(id: String) {
        hostId = id
        getOrCreateOption(null).hostId = id
    }

    /**
     * 按ID删除代理
     * 对应TS: export function _deleteProxyById(id, hostId?)
     */
    fun deleteProxyById(id: String, hostIdParam: String? = null) {
        getOrCreateOption(hostIdParam).objectOfProxyManager.deleteById(id)
    }

    /**
     * 按对象删除代理
     * 对应TS: export function _deleteProxy(obj, hostId?)
     */
    fun deleteProxy(obj: Any, hostIdParam: String? = null) {
        getOrCreateOption(hostIdParam).objectOfProxyManager.delete(obj)
    }

    /**
     * 生成唯一ID
     * 对应TS: function getId() { return hostId + '' + (idCount++) }
     */
    fun getId(): String = "${hostId ?: ""}${idCount.getAndIncrement()}"

    /**
     * 获取或创建全局消息接收器
     * 对应TS: export function getMessageReceiver()
     */
    fun getMessageReceiver(): MessageReceiver {
        return messageReceiver ?: synchronized(this) {
            messageReceiver ?: MessageReceiver().also { messageReceiver = it }
        }
    }

    /**
     * 获取或创建指定hostId的选项
     * 对应TS: function getOrCreateOption(id?)
     */
    fun getOrCreateOption(id: String?): MessageReceiverOptions {
        val key = when {
            id == null -> DEFAULT_HOST_KEY
            id == hostId -> DEFAULT_HOST_KEY
            else -> id
        }
        return options.getOrPut(key) {
            MessageReceiverOptions(if (key == DEFAULT_HOST_KEY) null else key)
        }
    }

    // ==================== 代理相关 ====================

    /**
     * 获取或生成对象的代理ID
     * 对应TS: function getOrGenerateObjectId(obj, hostIdFrom)
     */
    private fun getOrGenerateObjectId(obj: Any, hostIdFrom: String?): String {
        val proxyManager = getOrCreateOption(hostIdFrom).objectOfProxyManager
        checkNotNull(hostId) { "hostId is null" }

        if (!proxyManager.has(obj)) {
            proxyManager.set(obj, getId())
        }
        return proxyManager.get(obj)!!
    }

    /**
     * 为对象创建代理描述
     * 对应TS: function createProxyForObject(proxyId, obj, hostId)
     */
    private fun createProxyForObject(proxyId: String, obj: Any?, hostIdParam: String?): ProxyDescriber? {
        if (obj == null) return null

        val members = mutableListOf<ProxyDescriber.MemberDescriber>()

        when (obj) {
            // 对应TS: typeof obj == 'function'
            is RpcFunction -> {
                members.add(ProxyDescriber.MemberDescriber("function", "__call__"))
            }
            // 对应TS: Object.keys(obj).filter(k => typeof obj[k] == 'function').filter(k => !k.startsWith('__'))
            is Map<*, *> -> {
                (obj as Map<String, Any?>).forEach { (key, value) ->
                    if (value is RpcFunction && !key.startsWith("__")) {
                        members.add(ProxyDescriber.MemberDescriber("function", key))
                    }
                }
            }
            // 非Map非RpcFunction的对象: 可通过反射获取方法 (扩展点)
        }

        return ProxyDescriber(id = proxyId, hostId = hostIdParam ?: "", members = members)
    }

    /**
     * 将对象包装为代理PreArgObj
     * 对应TS: export function asProxy(obj, hostIdFrom?)
     */
    fun asProxy(obj: Any?, hostIdFrom: String? = null): PreArgObj {
        val resolvedHostId = getOrCreateOption(hostIdFrom).hostId
        val id = getOrGenerateObjectId(obj!!, resolvedHostId)
        val proxy = createProxyForObject(id, obj, resolvedHostId)
        return PreArgObj("proxy", proxy)
    }

    /**
     * 生成错误响应
     * 对应TS: export function generateErrorReply(message, errorText, status = 500)
     */
    fun generateErrorReply(message: Request, errorText: String, status: Int = 500): Response =
        Response(id = getId(), idFor = message.id, trace = errorText, status = status)

    // ==================== 定时任务 (协程版) ====================

    /**
     * 启动自动检查定时任务
     * 对应TS: export function autoCheck()
     *
     * 使用 CoroutineScope + delay 替代 setInterval:
     * - killTimeoutConnection: 清理超时连接
     * - autoReRegister: 自动重新注册 (suspend)
     * - removeOutdatedProxyObject: 移除过期代理
     */
    fun autoCheck(scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())) {
        scope.launch {
            while (isActive) {
                delay(3000)
                killTimeoutConnection()
            }
        }
        scope.launch {
            while (isActive) {
                delay(3000)
                try {
                    autoReRegister() // suspend: 内部可能调用远程方法
                } catch (e: Exception) {
                    System.err.println("autoReRegister failed: ${e.message}")
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(3000)
                removeOutdatedProxyObject()
            }
        }
    }

    /**
     * 清理超时连接
     * 对应TS: function killTimeoutConnection(client?, millSec?)
     */
    fun killTimeoutConnection(client: Client? = null, millSec: Long = -1) {
        val timeout = if (millSec == -1L) CONNECTION_LIMIT else millSec

        if (client != null) {
            processTimeoutForOption(getOrCreateOption(client.getHostId()), timeout)
        } else {
            options.values.forEach { processTimeoutForOption(it, timeout) }
        }
    }

    private fun processTimeoutForOption(option: MessageReceiverOptions, timeout: Long) {
        val reqPending = option.requestPendingDict
        val toRemove = mutableListOf<String>()
        for ((key, pending) in reqPending) {
            if (System.currentTimeMillis() - pending.sendTime > timeout) {
                pending.deferred.completeExceptionally(RuntimeException("timeout"))
                toRemove.add(key)
            }
        }
        toRemove.forEach { reqPending.remove(it) }
    }

    /**
     * 自动重新注册远程代理
     * 对应TS: export function autoReRegister()
     *
     * 此方法是 suspend 的，因为内部调用 client.getObject() (suspend)
     */
    suspend fun autoReRegister() { // 对应TS中的隐式async (调用了async getObject)
        for (option in options.values) {
            val manager = option.runnableProxyManager

            for ((client, ids) in manager.clientMap) {
                if (ids.isNullOrEmpty()) continue

                val toReRegister = mutableListOf<List<Any?>>()
                val toRemove = mutableSetOf<String>()

                for (id in ids) {
                    val obj = manager.get(id)
                    if (obj == null) {
                        toRemove.add(id)
                    } else {
                        toReRegister.add(listOf(id))
                    }
                }

                // 清理已失效的id
                ids.removeAll(toRemove)
                if (ids.isEmpty()) {
                    manager.removeFromClientMap(client)
                }

                // 调用远程 reRegister
                if (toReRegister.isNotEmpty()) {
                    try {
                        val main0 = client.getObject("main0") // suspend
                        if (main0 is RemoteObject) {
                            main0.invoke("reRegister", toReRegister) // suspend
                        }
                    } catch (e: Exception) {
                        System.err.println("autoReRegister call failed: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * 移除过期代理对象
     * 对应TS: export function removeOutdatedProxyObject(timeout = -1)
     * (已修复bug: 正确遍历 reverseProxyMap)
     */
    fun removeOutdatedProxyObject(timeout: Long = -1) {
        val effectiveTimeout = if (timeout <= 0) GC_TIME_LIMIT else timeout
        for ((key, option) in options) {
            val manager = option.objectOfProxyManager
            val before = manager.size
            val count = manager.removeOutdated(effectiveTimeout)
            if (count > 0 && debugFlag) {
                println("$key removed $count proxies, before $before after ${manager.size}")
            }
        }
    }

    // ==================== 类型检查工具 ====================

    /**
     * 判断是否为简单/原始类型
     * 对应TS: function isSimpleObject(obj)
     */
    fun isSimpleObject(obj: Any?): Boolean =
        obj == null || obj is String || obj is Number || obj is Boolean || obj is ByteArray

    /**
     * 判断消息是否为响应
     * 对应TS: function isResponse(message)
     */
    fun isResponse(message: Message): Boolean =
        message is Response && message.idFor != null

    /**
     * 深度检查对象是否可JSON序列化
     * 对应TS: function isSerializableDeep(obj, seen?)
     */
    fun isSerializableDeep(obj: Any?, seen: MutableSet<Any> = mutableSetOf()): Boolean {
        if (obj == null) return true
        if (obj is String || obj is Number || obj is Boolean) return true

        // 增强类型
        if (obj is ByteArray || obj is java.util.Date || obj is java.math.BigInteger) {
            return enhanceType
        }

        // List
        if (obj is List<*>) {
            if (obj in seen) return false
            seen.add(obj)
            return obj.all { isSerializableDeep(it, seen) }
        }

        // Map (对应JS plain object)
        if (obj is Map<*, *>) {
            if (obj in seen) return false
            seen.add(obj)
            return obj.all { (k, v) -> k is String && isSerializableDeep(v, seen) }
        }

        return false
    }

    // ==================== 断言/校验 ====================

    fun assertRequest(request: Request) {
        requireNotNull(request.id) { "request.id: expected string" }
        requireNotNull(request.meta) { "request.meta: expected object" }
        requireNotNull(request.method) { "request.method: expected string" }
        requireNotNull(request.objectId) { "request.objectId: expected string" }
        requireNotNull(request.args) { "request.args: expected array" }
    }

    fun assertArgJSON(request: Request) {
        request.args.forEachIndexed { i, arg ->
            if (!isSerializableDeep(arg)) {
                System.err.println("Non-serializable arg: $arg")
                throw RuntimeException("${request.method}.args[$i] is not serializable")
            }
        }
    }

    fun assertResponse(response: Response) {
        requireNotNull(response.id) { "response.id: expected string" }
        requireNotNull(response.idFor) { "response.idFor: expected string" }
    }
}
