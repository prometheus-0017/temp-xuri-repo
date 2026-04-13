@file:Suppress("UNCHECKED_CAST")

package com.xuri.rpc

/**
 * 参数转换器，负责将对象转换为RPC传输格式，以及反向转换
 * 对应TS中: class ArgTranslator
 */
class ArgTranslator {

    /**
     * 自定义参数转换器接口
     * 对应TS中: interface CustomArgTranslatorFunction
     */
    interface CustomArgTranslatorFunction {
        fun match(obj: Any?): Boolean
        fun translate(obj: Any?): Any?
        fun reverseTranslate(obj: Any?): Any?
    }

    var typeIndicator: String = "__type"
    val customTranslators = mutableListOf<CustomArgTranslatorFunction>()

    /**
     * 将对象转换为RPC传输格式
     * 对应TS中: toArgObj(target: any, asProxyLocal: (obj: any) => any): any
     *
     * 转换规则:
     * 1. PreArgObj → 直接返回其data
     * 2. 简单类型 (String/Number/Boolean/null/ByteArray) → 原样返回
     * 3. List → 递归转换每一项
     * 4. Map (plain object) → 递归转换每个属性 (对应TS isDict，已修复bug)
     * 5. 自定义转换器匹配 → 使用自定义转换
     * 6. 其他复杂对象 → 调用 asProxyLocal 包装为代理
     */
    fun toArgObj(target: Any?, asProxyLocal: (Any?) -> Any?): Any? {
        // 1. PreArgObj
        if (target is PreArgObj) {
            return when (target.type) {
                "proxy" -> target.data
                "data" -> target.data
                else -> throw UnsupportedOperationException("not implemented for type: ${target.type}")
            }
        }

        // 2. 简单类型
        if (Rpc.isSimpleObject(target)) {
            return target
        }

        // 3. List (Array)
        if (target is List<*>) {
            return target.map { toArgObj(it, asProxyLocal) }
        }

        // 4. Map (plain object, 对应TS isDict)
        if (target is Map<*, *>) {
            val map = target as Map<String, Any?>
            return map.mapValues { (_, v) -> toArgObj(v, asProxyLocal) }
        }

        // 5. 自定义转换器
        for (translator in customTranslators) {
            if (translator.match(target)) {
                return translator.translate(target)
            }
        }

        // 6. 其他复杂对象 → 包装为代理
        return asProxyLocal(target)
    }

    /**
     * 将RPC传输格式反向转换为对象
     * 对应TS中: reverseToArgObj(target: any, client: Client): any
     */
    fun reverseToArgObj(target: Any?, client: Client): Any? {
        // 检查是否是代理描述 (包含typeIndicator字段)
        if (target is Map<*, *>) {
            val map = target as Map<String, Any?>
            if (typeIndicator in map) {
                val rawData = map["data"]
                val data: ProxyDescriber = when (rawData) {
                    is ProxyDescriber -> rawData
                    is Map<*, *> -> ProxyDescriber.fromMap(rawData as Map<String, Any?>)
                    else -> throw RuntimeException("unexpected proxy data type: $rawData")
                }
                val result = client.createRemoteProxy(data)
                client.getRunnableProxyManager().set(data.id, result)
                return result
            }

            // 普通字典 - 递归处理
            return map.mapValues { (_, v) -> reverseToArgObj(v, client) }
        }

        // List - 递归处理
        if (target is List<*>) {
            return target.map { reverseToArgObj(it, client) }
        }

        // 自定义反向转换器
        for (translator in customTranslators) {
            if (translator.match(target)) {
                return translator.reverseTranslate(target)
            }
        }

        return target
    }
}
