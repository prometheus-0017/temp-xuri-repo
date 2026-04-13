package com.xuri.rpc;

import java.util.Map;

/**
 * RPC拦截器函数接口
 * 对应TS中: type Interceptor = (context: RpcContext, message: Request, client: Client, nextGenerator: NextFunction) => Promise<void>
 *
 * 注意: 原TS中Interceptor返回Promise&lt;void&gt;，此处已转换为同步调用
 */
@FunctionalInterface
public interface Interceptor {
    /* @async 原TS中interceptor返回Promise<void>，此处已转换为同步 */
    void intercept(Map<String, Object> context, Request message, Client client, Runnable next);
}
