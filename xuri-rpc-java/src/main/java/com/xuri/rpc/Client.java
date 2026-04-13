package com.xuri.rpc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * RPC客户端，负责发送请求和管理远程代理
 * 对应TS中: export class Client
 */
public class Client {
    private ISender sender;
    private final String hostId;
    private final ArgTranslator argTranslator = new ArgTranslator();
    private AutoWrapper argsAutoWrapper = Rpc.SHALLOW_AUTO_WRAPPER;

    public Client(String hostId) {
        this.hostId = hostId;
        this.sender = new NotImplementSender();
    }

    public Client() {
        this(null);
    }

    public void setArgsAutoWrapper(AutoWrapper autoWrapper) {
        this.argsAutoWrapper = autoWrapper;
    }

    public void setSender(ISender sender) {
        if (this.sender != null && !(this.sender instanceof NotImplementSender)) {
            throw new RuntimeException("sender already set");
        }
        this.sender = sender;
    }

    public ISender getSender() {
        return sender;
    }

    ConcurrentHashMap<String, PendingRequest> getReqPending() {
        return Rpc.getOrCreateOption(hostId).requestPendingDict;
    }

    void putAwait(String id, CompletableFuture<Object> future, Message request) {
        Rpc.getOrCreateOption(hostId).requestPendingDict.put(id,
                new PendingRequest(future, request, System.currentTimeMillis()));
    }

    /**
     * 发送请求并同步等待响应
     * 对应TS中: async waitForRequest(request: Request): Promise<{}>
     *
     * 内部使用CompletableFuture实现异步→同步转换:
     * 1. 创建CompletableFuture
     * 2. 将future的complete/completeExceptionally注册为回调
     * 3. 通过sender发送请求
     * 4. 调用future.get()阻塞等待响应
     */
    /* @async 原TS中为async方法，此处已转换为同步阻塞 */
    public Object waitForRequest(Request request) {
        if (Rpc.isDebug()) {
            Rpc.assertRequest(request);
            Rpc.assertArgJSON(request);
            System.out.println(getHostId() + " is waiting for " + request.getId() + ", " + request);
        }
        if (sender == null) {
            throw new RuntimeException("sender not set");
        }

        CompletableFuture<Object> future = new CompletableFuture<>();
        putAwait(request.getId(), future, request);

        // 对应TS中: let senderPromise = async () => { try { await sender!.send(request) } catch(e) { reject(e) } }
        try {
            sender.send(request);
        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        try {
            return future.get(); // 同步阻塞等待响应
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * 将对象转换为RPC传输格式
     * 对应TS中: toArgObj(obj: any): ArgObj
     *
     * 注意: 返回类型在TS中标注为ArgObj，但实际可能返回原始值、List、Map或ProxyDescriber
     */
    public Object toArgObj(Object obj) {
        return argTranslator.toArgObj(obj, (o) -> Rpc.asProxy(o, getHostId()));
    }

    public String getHostId() {
        if (hostId == null) {
            return Rpc.getOrCreateOption(null).hostId;
        }
        return hostId;
    }

    public ObjectOfProxyManager getProxyManager() {
        return Rpc.getOrCreateOption(hostId).objectOfProxyManager;
    }

    public RemoteProxyManager getRunnableProxyManager() {
        return Rpc.getOrCreateOption(hostId).runnableProxyManager;
    }

    /**
     * 为远程代理描述创建本地代理对象
     * 对应TS中: createRemoteProxy(data: ProxyDescriber)
     *
     * 在TS中返回一个带有函数属性的plain object;
     * Java中返回RemoteObject，通过invoke(method, args)调用远程方法
     *
     * 对于__call__类型的代理(即远程函数)，TS中会将对象替换为可调用函数，
     * Java中通过RemoteObject.call(args)实现
     *
     * @param data 代理描述信息
     * @return 本地对象(如果是同host) 或 RemoteObject
     */
    public Object createRemoteProxy(ProxyDescriber data) {
        // 如果hostId相同，说明是本地对象，直接返回
        if (data.getHostId() != null && data.getHostId().equals(hostId)) {
            return getProxyManager().getById(data.getId());
        }

        // 检查是否已有缓存的远程代理
        Object existing = getRunnableProxyManager().get(data.getId());
        if (existing != null) {
            return existing;
        }

        RemoteObject result = new RemoteObject();
        for (ProxyDescriber.MemberDescriber member : data.getMembers()) {
            String key = member.getType();
            if ("property".equals(key)) {
                System.err.println("not implemented: property type member");
            } else if ("function".equals(key)) {
                final String methodName = member.getName();
                result.addMethod(methodName, (Object... args) -> {
                    /* @async 原TS中为async箭头函数，此处已转换为同步阻塞 */

                    // 转换参数: 先通过autoWrapper包装，再转换为ArgObj格式
                    List<Object> argsTransformed = new ArrayList<>();
                    for (Object arg : args) {
                        Object wrapped = argsAutoWrapper.wrap(arg);
                        argsTransformed.add(toArgObj(wrapped));
                    }

                    Request request = new Request();
                    request.setObjectId(data.getId());
                    request.setMeta(new HashMap<>());
                    request.setId(Rpc.getId());
                    request.setMethod(methodName);
                    request.setArgs(argsTransformed);

                    return waitForRequest(request);
                });
            } else {
                throw new RuntimeException("no such function type: " + key);
            }
        }

        // 对应TS中的__call__补丁: 如果对象有__call__方法，TS中将对象替换为可调用函数
        // Java中: RemoteObject.isCallable() / RemoteObject.call(...) 已内置支持
        return result;
    }

    /**
     * 将从远程接收到的参数转换为本地对象
     * 对应TS中: transformArg(argObj: ArgObj, clazz: any)
     *
     * @param argObj 接收到的参数 (可能是Map形式的ArgObj, 也可能是原始值)
     * @param clazz  在Java中可用于创建类型化代理接口 (TS中未使用)
     * @return 转换后的本地对象
     */
    @SuppressWarnings("unchecked")
    public Object transformArg(Object argObj, Class<?> clazz) {
        // 原始值直接返回
        if (Rpc.isSimpleObject(argObj)) {
            return argObj;
        }

        if (argObj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) argObj;
            Object type = map.get("type");

            if ("data".equals(type)) {
                return map.get("data");
            }

            // 视为代理描述 (type == "proxy" 或其他)
            Object rawData = map.get("data");
            ProxyDescriber data;
            if (rawData instanceof ProxyDescriber) {
                data = (ProxyDescriber) rawData;
            } else if (rawData instanceof Map) {
                data = ProxyDescriber.fromMap((Map<String, Object>) rawData);
            } else if (map.containsKey("id") && map.containsKey("hostId")) {
                // map本身可能就是ProxyDescriber格式
                data = ProxyDescriber.fromMap(map);
            } else {
                // 无法识别的格式，直接返回
                return argObj;
            }

            Object result = createRemoteProxy(data);
            getRunnableProxyManager().set(data.getId(), result);

            // clazz参数: 在Java中可使用java.lang.reflect.Proxy创建类型化代理
            // 例如: Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz}, handler)
            // 当前未实现，留作扩展点

            return result;
        }

        return argObj;
    }

    /**
     * 反向转换ArgObj
     * 对应TS中: reverseToArgObj(argObj: ArgObj): any
     */
    public Object reverseToArgObj(Object argObj) {
        return argTranslator.reverseToArgObj(argObj, this);
    }

    /**
     * 获取远程对象
     * 对应TS中: async getObject(objectId: string)
     */
    /* @async 原TS中为async方法，此处已转换为同步阻塞 */
    public Object getObject(String objectId) {
        Request request = new Request();
        request.setMeta(new HashMap<>());
        request.setId(Rpc.getId());
        request.setObjectId("main0");
        request.setMethod("getMain");
        request.setArgs(Collections.singletonList(toArgObj(objectId)));

        return waitForRequest(request);
    }

    /**
     * 获取远程主对象
     * 对应TS中: async getMain()
     */
    /* @async 原TS中为async方法，此处已转换为同步阻塞 */
    public Object getMain() {
        return getObject("main");
    }

    /**
     * 未实现的Sender，构造Client时的默认占位
     * 对应TS中: class NotImplementSender implements ISender
     */
    private static class NotImplementSender implements ISender {
        @Override
        public void send(Message message) {
            throw new RuntimeException("Not implement");
        }
    }
}
