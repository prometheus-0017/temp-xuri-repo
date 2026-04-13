package com.xuri.rpc;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个hostId对应的消息接收配置
 * 对应TS中: export interface MessageReceiverOptions
 */
public class MessageReceiverOptions {
    final ObjectOfProxyManager objectOfProxyManager;
    final RemoteProxyManager runnableProxyManager;
    String hostId;
    final ConcurrentHashMap<String, PendingRequest> requestPendingDict;

    MessageReceiverOptions(String hostId) {
        this.objectOfProxyManager = new ObjectOfProxyManager();
        this.runnableProxyManager = new RemoteProxyManager();
        this.hostId = hostId;
        this.requestPendingDict = new ConcurrentHashMap<>();
    }
}
