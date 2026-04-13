package com.xuri.rpc

import java.util.concurrent.ConcurrentHashMap

/**
 * 每个hostId对应的消息接收配置
 * 对应TS中: export interface MessageReceiverOptions
 */
class MessageReceiverOptions(
    var hostId: String?
) {
    val objectOfProxyManager = ObjectOfProxyManager()
    val runnableProxyManager = RemoteProxyManager()
    val requestPendingDict = ConcurrentHashMap<String, PendingRequest>()
}
