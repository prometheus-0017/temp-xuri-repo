package com.xuri.rpc

/**
 * 远程代理对象，对应TS中 createRemoteProxy 动态创建的对象
 *
 * TS中远程代理是一个拥有若干async函数属性的plain object:
 *   { methodA: async (...args) => ..., methodB: async (...args) => ... }
 *
 * Kotlin中使用 RemoteObject 包装，通过 suspend invoke() 调用远程方法
 * 所有远程方法都是 suspend 函数，天然支持协程挂起
 */
class RemoteObject {

    /** 远程方法: suspend lambda，挂起直到远程调用返回 */
    private val methods = mutableMapOf<String, suspend (Array<out Any?>) -> Any?>()

    /** 是否可直接调用 (对应TS中 __call__ 模式) */
    var isCallable: Boolean = false
        private set

    fun addMethod(name: String, method: suspend (Array<out Any?>) -> Any?) {
        methods[name] = method
        if (name == "__call__") {
            isCallable = true
        }
    }

    /**
     * 调用远程方法 (suspend, 协程挂起等待结果)
     * 对应TS中: result['methodName'](...args)，每个方法都是async函数
     */
    suspend fun invoke(method: String, vararg args: Any?): Any? {
        val m = methods[method] ?: throw RuntimeException("no such function: $method")
        return m(args)
    }

    /**
     * 直接调用对象 (对应TS中 __call__ 模式)
     * TS中: const func = async (...args) => result['__call__'](...args)
     */
    suspend fun call(vararg args: Any?): Any? {
        check(isCallable) { "object is not callable" }
        return invoke("__call__", *args)
    }

    fun hasMethod(name: String): Boolean = name in methods
}
