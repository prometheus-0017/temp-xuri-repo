@file:Suppress("UNCHECKED_CAST")

package com.xuri.rpc

import kotlinx.coroutines.CompletableDeferred

/**
 * 消息接收器，负责处理收到的RPC请求和响应
 * 对应TS中: export class MessageReceiver
 *
 * 核心async方法均使用Kotlin suspend实现:
 * - onReceiveMessage: suspend, 处理请求时可能调用suspend的RpcFunction
 * - withRpcContext: suspend, 拦截器链支持suspend
 */
class MessageReceiver(private val hostId: String? = null) {

    private var rpcServer: Map<String, Any?>? = null
    val interceptors = mutableListOf<RpcInterceptor>()
    private val objectWithContext = mutableSetOf<String>()
    var resultAutoWrapper: AutoWrapper = Rpc.shallowAutoWrapper

    init {
        val hostIdToSend = getHostId()

        // 注册内置对象 main0
        val main0 = mapOf<String, Any>(
            "getMain" to RpcFunction { args ->
                val objectId = args.getOrNull(0) as? String ?: "main"
                Rpc.asProxy(getProxyManager().getById(objectId), hostIdToSend)
            },
            "reRegister" to RpcFunction { args ->
                val list = args[0] as List<List<Any?>>
                for (item in list) {
                    getProxyManager().reRegister(item[0] as String)
                }
                null
            }
        )
        getProxyManager().set(main0, "main0")
    }

    /**
     * 设置主服务对象
     * 对应TS: setMain(obj: Record<string, Function>)
     */
    fun setMain(obj: Map<String, Any?>) {
        rpcServer = obj
        setObject("main", obj, false)
    }

    fun setObject(id: String, obj: Any, withContext: Boolean) {
        getProxyManager().set(obj, id)
        if (withContext) {
            objectWithContext.add(id)
        }
    }

    fun addInterceptor(interceptor: RpcInterceptor) {
        interceptors.add(interceptor)
    }

    fun currentWaitingCount(): Int = getReqPending().size

    /**
     * 拦截器链式执行 (with context模式)
     * 对应TS中: async withContext(message, client, args, func)
     *
     * 整个拦截器链都在suspend上下文中执行:
     * interceptor[0] → interceptor[1] → ... → 实际函数调用(suspend)
     */
    private suspend fun withRpcContext( // 对应TS async withContext (重命名避免与coroutines.withContext冲突)
        message: Request,
        client: Client,
        args: Array<Any?>,
        func: Any
    ): Any? {
        var result: Any? = null

        val context = mutableMapOf<String, Any?>(
            "setContext" to RpcFunction { ctxArgs ->
                result = ctxArgs[0]
                null
            }
        )

        // 递归构建拦截器执行链
        // 对应TS: function generateInteceptorExecutor(indexOfInteceptor)
        fun generateInterceptorExecutor(index: Int): suspend () -> Unit {
            return if (index < interceptors.size) {
                suspend { // 对应TS async function executeThisInteceptor()
                    val interceptor = interceptors[index]
                    val next: suspend () -> Unit = { // 对应TS async () => { ... generateAndExecuteNext }
                        val executor = generateInterceptorExecutor(index + 1)
                        executor()
                    }
                    interceptor(context, message, client, next)
                }
            } else {
                suspend { // 最终执行实际函数
                    result = when (func) {
                        is RpcFunction -> func(arrayOf(context, *args))
                        else -> throw RuntimeException("unsupported func type: ${func::class}")
                    }
                }
            }
        }

        val firstExecutor = generateInterceptorExecutor(0)
        firstExecutor() // suspend: 执行拦截器链
        return result
    }

    fun getProxyManager(): ObjectOfProxyManager =
        Rpc.getOrCreateOption(hostId).objectOfProxyManager

    fun getRunnableProxyManager(): RemoteProxyManager =
        Rpc.getOrCreateOption(hostId).runnableProxyManager

    fun getHostId(): String? =
        Rpc.getOrCreateOption(hostId).hostId

    internal fun getReqPending() =
        Rpc.getOrCreateOption(hostId).requestPendingDict

    /**
     * 处理收到的消息（请求或响应）
     * 对应TS中: async onReceiveMessage(messageRecv: Request|Response, clientForCallBack: Client)
     *
     * 请求处理流程:
     * 1. 从代理管理器中获取目标对象
     * 2. 转换参数
     * 3. 执行方法 (可能是 suspend 的 RpcFunction，经过拦截器链)
     * 4. 包装结果并发送响应
     *
     * 响应处理流程:
     * 1. 查找对应的 PendingRequest
     * 2. 通过 CompletableDeferred.complete()/completeExceptionally() 恢复等待的协程
     */
    suspend fun onReceiveMessage(messageRecv: Message, clientForCallBack: Client) { // 对应TS async
        requireNotNull(clientForCallBack) { "clientForCallBack must not null" }

        if (Rpc.debugFlag) {
            when (messageRecv) {
                is Response -> println(
                    "${getHostId()} received a reply, which is for ${messageRecv.id}" +
                            " and it is ${messageRecv.idFor} $messageRecv"
                )
                is Request -> println(
                    "${getHostId()} received a request, which id is ${messageRecv.id} $messageRecv"
                )
            }
        }

        if (!Rpc.isResponse(messageRecv)) {
            // === 处理请求 ===
            val message = messageRecv as Request
            try {
                val obj = getProxyManager().getById(message.objectId)
                if (obj == null) {
                    clientForCallBack.getSender().send(
                        Rpc.generateErrorReply(message, "object not found", 100)
                    )
                    return
                }

                // 转换参数
                val args = message.args.map { clientForCallBack.transformArg(it) }.toTypedArray()

                var result: Any? = null
                val shouldWithContext = message.objectId in objectWithContext

                if (message.method == "__call__") {
                    // 对象本身是可调用的
                    result = if (shouldWithContext) {
                        withRpcContext(message, clientForCallBack, args, obj)
                    } else {
                        (obj as RpcFunction)(args) // suspend调用
                    }
                } else {
                    // 调用对象的指定方法
                    val objMap = obj as Map<String, Any?>
                    val method = objMap[message.method]
                        ?: throw RuntimeException("method not found: ${message.method}")

                    result = if (shouldWithContext) {
                        withRpcContext(message, clientForCallBack, args, method)
                    } else {
                        (method as RpcFunction)(args) // suspend调用
                    }
                }

                // autoWrapper包装结果
                result = resultAutoWrapper(result)
                // 对应TS: result = await result
                // Kotlin中若result是Deferred, 此处不需额外处理 (autoWrapper返回同步值)

                val wrappedResult = clientForCallBack.toArgObj(result)

                if (Rpc.debugFlag && !Rpc.isSerializableDeep(wrappedResult)) {
                    System.err.println("${message.method}: result is not serializable")
                    throw RuntimeException("${message.method}: result is not serializable")
                }

                // 发送成功响应
                clientForCallBack.getSender().send(
                    Response(
                        id = Rpc.getId(),
                        idFor = message.id,
                        meta = mutableMapOf(),
                        data = wrappedResult,
                        status = 200
                    )
                )
            } catch (e: Exception) {
                // 发送错误响应
                val trace = buildString {
                    appendLine(e.toString())
                    e.stackTrace.forEach { appendLine("  at $it") }
                }

                clientForCallBack.getSender().send(
                    Response(
                        id = Rpc.getId(),
                        idFor = message.id,
                        meta = mutableMapOf(),
                        data = null,
                        trace = trace,
                        status = -1
                    )
                )
                System.err.println("Error handling request: ${e.message}")
                e.printStackTrace()
            }
        } else {
            // === 处理响应 ===
            val message = messageRecv as Response
            val idFor = message.idFor ?: return

            val reqPending = getReqPending()
            val pending = reqPending.remove(idFor)
            if (pending == null) {
                System.err.println("[${getHostId()}] no pending request for id $idFor $message")
                return
            }

            if (message.status == 200) {
                // 通过 CompletableDeferred.complete() 恢复挂起的协程
                pending.deferred.complete(clientForCallBack.reverseToArgObj(message.data))
            } else {
                // 通过 CompletableDeferred.completeExceptionally() 让挂起的协程抛出异常
                pending.deferred.completeExceptionally(
                    RuntimeException("RPC error: status=${message.status}, trace=${message.trace}")
                )
            }
        }
    }
}
