@file:Suppress("UNCHECKED_CAST")

package com.xuri.rpc

import kotlinx.coroutines.CompletableDeferred

// ====================
// RPC消息标记接口
// 对应TS中: type Message = Request | Response
// ====================
sealed interface Message

// ====================
// RPC请求
// 对应TS中: export interface Request
// ====================
data class Request(
    var id: String = "",
    var meta: MutableMap<String, Any?> = mutableMapOf(),
    var method: String = "",
    var objectId: String = "",
    var args: MutableList<Any?> = mutableListOf()
) : Message

// ====================
// RPC响应
// 对应TS中: export interface Response
// ====================
data class Response(
    var id: String = "",
    var idFor: String? = null,
    var meta: MutableMap<String, Any?> = mutableMapOf(),
    var status: Int = 0,
    var trace: String? = null,
    var data: Any? = null
) : Message

// ====================
// 预参数对象
// 对应TS中: export class PreArgObj
// type可选值: "proxy", "data", "datetime", null
// ====================
data class PreArgObj(
    var type: String?, // "proxy" | "data" | "datetime" | null
    var data: Any?
)

// ====================
// 代理描述器
// 对应TS中: interface ProxyDescriber
// ====================
data class ProxyDescriber(
    var id: String = "",
    var hostId: String = "",
    var members: MutableList<MemberDescriber> = mutableListOf()
) {
    /**
     * 代理成员描述
     * 对应TS中: {type: 'function' | 'property', name: string}
     */
    data class MemberDescriber(
        var type: String = "", // "function" 或 "property"
        var name: String = ""
    )

    companion object {
        /** 从Map(JSON反序列化结果)构建 */
        fun fromMap(map: Map<String, Any?>): ProxyDescriber {
            val members = (map["members"] as? List<Map<String, Any?>>)
                ?.map { MemberDescriber(type = it["type"] as? String ?: "", name = it["name"] as? String ?: "") }
                ?.toMutableList()
                ?: mutableListOf()
            return ProxyDescriber(
                id = map["id"] as? String ?: "",
                hostId = map["hostId"] as? String ?: "",
                members = members
            )
        }
    }
}

// ====================
// 待处理请求
// 对应TS中: {resolve, reject, request, sendTime}
// Kotlin中使用 CompletableDeferred 替代 resolve/reject 回调
// ====================
class PendingRequest(
    val deferred: CompletableDeferred<Any?>,
    val request: Message,
    val sendTime: Long
)

// ====================
// 消息发送接口
// 对应TS中: export interface ISender
// ====================
interface ISender {
    fun send(message: Message)
}

// ====================
// 可通过RPC调用的函数接口
// 对应TS中 Record<string, Function> 中的 Function
//
// 使用 fun interface 支持 Kotlin SAM 转换:
//   val f = RpcFunction { args -> doSomething(args) }
// ====================
fun interface RpcFunction {
    suspend operator fun invoke(args: Array<out Any?>): Any?
}

// ====================
// 类型别名
// ====================

/** 自动包装器, 对应TS: type AutoWrapper = (x: any) => any */
typealias AutoWrapper = (Any?) -> Any?

/**
 * RPC拦截器, 使用suspend支持协程
 * 对应TS: type Interceptor = (context, message, client, next) => Promise<void>
 *
 * next 也是 suspend 函数, 调用 next() 会执行拦截器链的下一环节
 */
typealias RpcInterceptor = suspend (
    context: MutableMap<String, Any?>,
    message: Request,
    client: Client,
    next: suspend () -> Unit
) -> Unit
