package com.xuri.rpc;

/**
 * 消息发送接口
 * 对应TS中: export interface ISender { send(message: Request|Response): void }
 */
public interface ISender {
    void send(Message message);
}
