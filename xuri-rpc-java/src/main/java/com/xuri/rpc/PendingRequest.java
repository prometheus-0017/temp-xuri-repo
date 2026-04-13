package com.xuri.rpc;

import java.util.concurrent.CompletableFuture;

/**
 * 待处理的请求，用于存储发出请求后等待响应的上下文
 * 对应TS中: {resolve, reject, request, sendTime}
 *
 * Java中使用CompletableFuture替代resolve/reject回调
 */
class PendingRequest {
    final CompletableFuture<Object> future;
    final Message request;
    final long sendTime;

    PendingRequest(CompletableFuture<Object> future, Message request, long sendTime) {
        this.future = future;
        this.request = request;
        this.sendTime = sendTime;
    }
}
