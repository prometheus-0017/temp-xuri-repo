@file:Suppress("UNCHECKED_CAST")

package com.xuri.rpc

import kotlinx.coroutines.CompletableDeferred

/**
 * RPC客户端，负责发送请求和管理远程代理
 * 对应TS中: export class Client
 *
 * 核心async方法均使用Kotlin suspend实现:
 * - waitForRequest: 使用 CompletableDeferred.await() 挂起等待响应
 * - getObject / getMain: suspend调用 waitForRequest
 * - createRemoteProxy 中的远程方法: suspend lambda
 */
class Client(private val hostId: String? = null) {

    private var sender: ISender = NotImplementSender()
    private val argTranslator = ArgTranslator()
    var argsAutoWrapper: AutoWrapper = Rpc.shallowAutoWrapper

    fun setSender(sender: ISender) {
        if (this.sender !is NotImplementSender) {
            throw RuntimeException("sender already set")
        }
        this.sender = sender
    }

    fun getSender(): ISender = sender

    internal fun getReqPending() = Rpc.getOrCreateOption(hostId).requestPendingDict

    private fun putAwait(id: String, deferred: CompletableDeferred<Any?>, request: Message) {
        Rpc.getOrCreateOption(hostId).requestPendingDict[id] =
            PendingRequest(deferred, request, System.currentTimeMillis())
    }

    /**
     * 发送请求并挂起等待响应
     * 对应TS中: async waitForRequest(request: Request): Promise<{}>
     *
     * 使用 CompletableDeferred 实现:
     * 1. 创建 CompletableDeferred
     * 2. 注册到 requestPendingDict
     * 3. 通过 sender 发送请求
     * 4. await() 挂起当前协程直到响应到达
     */
    suspend fun waitForRequest(request: Request): Any? { // 对应TS async
        if (Rpc.debugFlag) {
            Rpc.assertRequest(request)
            Rpc.assertArgJSON(request)
            println("${getHostId()} is waiting for ${request.id}, $request")
        }

        val deferred = CompletableDeferred<Any?>()
        putAwait(request.id, deferred, request)

        // 对应TS: let senderPromise = async () => { try { await sender!.send(request) } catch(e) { reject(e) } }
        try {
            sender.send(request)
        } catch (e: Exception) {
            deferred.completeExceptionally(e)
        }

        return deferred.await() // suspend: 协程挂起, 不阻塞线程
    }

    /**
     * 将对象转换为RPC传输格式
     * 对应TS中: toArgObj(obj: any): ArgObj
     */
    fun toArgObj(obj: Any?): Any? =
        argTranslator.toArgObj(obj) { Rpc.asProxy(it, getHostId()) }

    fun getHostId(): String? =
        hostId ?: Rpc.getOrCreateOption(null).hostId

    fun getProxyManager(): ObjectOfProxyManager =
        Rpc.getOrCreateOption(hostId).objectOfProxyManager

    fun getRunnableProxyManager(): RemoteProxyManager =
        Rpc.getOrCreateOption(hostId).runnableProxyManager

    /**
     * 为远程代理描述创建本地代理对象
     * 对应TS中: createRemoteProxy(data: ProxyDescriber)
     *
     * 返回 RemoteObject，其中每个方法都是 suspend lambda:
     * - 调用 argsAutoWrapper 包装参数
     * - 调用 toArgObj 转换为传输格式
     * - 调用 suspend waitForRequest 挂起等待远程响应
     *
     * 对于 __call__ 类型的代理(远程函数)，通过 RemoteObject.call() 调用
     */
    fun createRemoteProxy(data: ProxyDescriber): Any {
        // 如果hostId相同, 是本地对象, 直接返回
        if (data.hostId == hostId) {
            return getProxyManager().getById(data.id)!!
        }

        // 检查缓存
        getRunnableProxyManager().get(data.id)?.let { return it }

        val result = RemoteObject()
        for (member in data.members) {
            when (member.type) {
                "property" -> System.err.println("not implemented: property type member")
                "function" -> {
                    val methodName = member.name
                    result.addMethod(methodName) { args -> // suspend lambda
                        // 对应TS: async (...args) => { ... }

                        // 转换参数: autoWrapper → toArgObj
                        val argsTransformed = args.map { argsAutoWrapper(it) }.map { toArgObj(it) }

                        val request = Request(
                            objectId = data.id,
                            meta = mutableMapOf(),
                            id = Rpc.getId(),
                            method = methodName,
                            args = argsTransformed.toMutableList()
                        )

                        waitForRequest(request) // suspend: 挂起等待远程响应
                    }
                }
                else -> throw RuntimeException("no such function type: ${member.type}")
            }
        }

        // __call__ 补丁已内置在 RemoteObject 中: isCallable / call()
        return result
    }

    /**
     * 将从远程接收到的参数转换为本地对象
     * 对应TS中: transformArg(argObj: ArgObj, clazz: any)
     *
     * @param clazz 在Kotlin中可用于创建类型化代理 (TS中未使用)
     */
    fun transformArg(argObj: Any?, clazz: Class<*>? = null): Any? {
        if (Rpc.isSimpleObject(argObj)) return argObj

        if (argObj is Map<*, *>) {
            val map = argObj as Map<String, Any?>
            val type = map["type"]

            if (type == "data") return map["data"]

            // 视为代理描述
            val data: ProxyDescriber = when {
                map["data"] is ProxyDescriber -> map["data"] as ProxyDescriber
                map["data"] is Map<*, *> -> ProxyDescriber.fromMap(map["data"] as Map<String, Any?>)
                "id" in map && "hostId" in map -> ProxyDescriber.fromMap(map)
                else -> return argObj // 无法识别的格式
            }
            val result = createRemoteProxy(data)
            getRunnableProxyManager().set(data.id, result)

            // clazz: 在Java/Kotlin中可使用 java.lang.reflect.Proxy 创建类型化代理 (扩展点)
            return result
        }

        return argObj
    }

    fun reverseToArgObj(argObj: Any?): Any? = argTranslator.reverseToArgObj(argObj, this)

    /**
     * 获取远程对象
     * 对应TS中: async getObject(objectId: string)
     */
    suspend fun getObject(objectId: String): Any? { // 对应TS async
        val request = Request(
            meta = mutableMapOf(),
            id = Rpc.getId(),
            objectId = "main0",
            method = "getMain",
            args = mutableListOf(toArgObj(objectId))
        )
        return waitForRequest(request)
    }

    /**
     * 获取远程主对象
     * 对应TS中: async getMain()
     */
    suspend fun getMain(): Any? = getObject("main") // 对应TS async

    /** 未实现的Sender占位 */
    private class NotImplementSender : ISender {
        override fun send(message: Message) = throw RuntimeException("Not implement")
    }
}
