package com.xuri.rpc;

/**
 * 自动包装器函数接口
 * 对应TS中: type AutoWrapper = (x: any) => any
 */
@FunctionalInterface
public interface AutoWrapper {
    Object wrap(Object obj);
}
