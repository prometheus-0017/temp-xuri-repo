package com.xuri.rpc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RPC框架核心工具类，包含全局状态和静态工具方法
 * 对应TS中 rpc.ts 模块级别的变量和函数
 */
public final class Rpc {

    private Rpc() {
    }

    // ==================== 全局状态 ====================

    /** 调试标志, 对应TS: let debugFlag = false */
    private static volatile boolean debugFlag = false;

    /** 本地主机ID, 对应TS: let hostId: string | null = null */
    private static volatile String hostId = null;

    /** 全局消息接收器单例, 对应TS: let messageReceiver: MessageReceiver | null */
    private static volatile MessageReceiver messageReceiver = null;

    /** ID计数器 (线程安全), 对应TS: let idCount = 0 */
    private static final AtomicLong idCount = new AtomicLong(0);

    /** 增强类型标志, 对应TS: let enchanceType = true */
    private static boolean enhanceType = true;

    /** GC超时限制(毫秒), 对应TS: const gcTimeLimit = 30 * 1000 */
    static final long GC_TIME_LIMIT = 30 * 1000;

    /** 连接超时限制(毫秒), 对应TS: const connectionLimit = 30 * 1000 */
    static final long CONNECTION_LIMIT = 30 * 1000;

    /**
     * 每个hostId的选项映射, 对应TS: let options: Record<string|symbol, MessageReceiverOptions> = {}
     * 注意: TS中使用Symbol('defaultHost')作为默认key，Java中使用null key由DEFAULT_HOST_KEY替代
     */
    private static final ConcurrentHashMap<String, MessageReceiverOptions> options = new ConcurrentHashMap<>();

    /** 默认host的key, 对应TS: let defaultHost = Symbol('defaultHost') */
    private static final String DEFAULT_HOST_KEY = "__DEFAULT_HOST__";

    // ==================== 浅自动包装器 ====================

    /**
     * 浅层自动包装器, 对应TS: const shallowAutoWrapper: AutoWrapper
     *
     * 规则:
     * - null → 原样返回
     * - RpcFunction → 包装为代理
     * - List中包含RpcFunction → 整个List包装为代理
     * - Map中包含RpcFunction值 → 整个Map包装为代理
     * - 其他 → 原样返回
     *
     * 注意: TS中检查 typeof obj == 'function'，Java中对应为 instanceof RpcFunction
     * (因为Java没有一等函数类型，需要通过接口标识可调用对象)
     */
    public static final AutoWrapper SHALLOW_AUTO_WRAPPER = (Object obj) -> {
        if (obj == null) {
            return null;
        }
        // 对应TS: typeof obj == 'function'
        if (obj instanceof MessageReceiver.RpcFunction) {
            return asProxy(obj);
        }
        if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            boolean notPureData = false;
            for (Object item : list) {
                if (item instanceof MessageReceiver.RpcFunction) {
                    notPureData = true;
                    break;
                }
            }
            return notPureData ? asProxy(obj) : obj;
        }
        if (obj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) obj;
            boolean notPureData = false;
            for (Object value : map.values()) {
                if (value instanceof MessageReceiver.RpcFunction) {
                    notPureData = true;
                    break;
                }
            }
            return notPureData ? asProxy(obj) : obj;
        }
        return obj;
    };

    // ==================== 公共方法 ====================

    /**
     * 设置调试标志
     * 对应TS: export function setDebugFlag(flag: boolean)
     */
    public static void setDebugFlag(boolean flag) {
        debugFlag = flag;
    }

    public static boolean isDebug() {
        return debugFlag;
    }

    /**
     * 设置主机ID
     * 对应TS: export function setHostId(id: string)
     */
    public static void setHostId(String id) {
        hostId = id;
        getOrCreateOption(null).hostId = id;
    }

    public static String getHostId() {
        return hostId;
    }

    /**
     * 按ID删除代理
     * 对应TS: export function _deleteProxyById(id: string, hostId?: string)
     */
    public static void deleteProxyById(String id, String hostIdParam) {
        getOrCreateOption(hostIdParam).objectOfProxyManager.deleteById(id);
    }

    /**
     * 按对象删除代理
     * 对应TS: export function _deleteProxy(obj: object, hostId?: string)
     */
    public static void deleteProxy(Object obj, String hostIdParam) {
        getOrCreateOption(hostIdParam).objectOfProxyManager.delete(obj);
    }

    /**
     * 生成唯一ID
     * 对应TS: function getId() { return hostId + '' + (idCount++) }
     */
    public static String getId() {
        return (hostId != null ? hostId : "") + idCount.getAndIncrement();
    }

    /**
     * 获取或创建全局消息接收器
     * 对应TS: export function getMessageReceiver(): MessageReceiver
     */
    public static MessageReceiver getMessageReceiver() {
        if (messageReceiver == null) {
            synchronized (Rpc.class) {
                if (messageReceiver == null) {
                    messageReceiver = new MessageReceiver();
                }
            }
        }
        return messageReceiver;
    }

    /**
     * 获取或创建指定hostId的选项
     * 对应TS: function getOrCreateOption(id?: string | null | symbol): MessageReceiverOptions
     */
    static MessageReceiverOptions getOrCreateOption(String id) {
        if (id == null) {
            id = DEFAULT_HOST_KEY;
        }
        if (id.equals(hostId)) {
            id = DEFAULT_HOST_KEY;
        }
        final String key = id;
        return options.computeIfAbsent(key, k -> {
            String resolvedHostId = DEFAULT_HOST_KEY.equals(k) ? null : k;
            return new MessageReceiverOptions(resolvedHostId);
        });
    }

    // ==================== 代理相关 ====================

    /**
     * 获取或生成对象的代理ID
     * 对应TS: function getOrGenerateObjectId(obj: object, hostIdFrom: string)
     */
    private static String getOrGenerateObjectId(Object obj, String hostIdFrom) {
        ObjectOfProxyManager proxyManager = getOrCreateOption(hostIdFrom).objectOfProxyManager;
        if (hostId == null) {
            throw new RuntimeException("hostId is null");
        }
        if (!proxyManager.has(obj)) {
            String id = getId();
            proxyManager.set(obj, id);
        }
        return proxyManager.get(obj);
    }

    /**
     * 为对象创建代理描述
     * 对应TS: function createProxyForObject(proxyId, obj, hostId)
     *
     * 在TS中检查typeof obj == 'function'和Object.keys过滤函数属性;
     * Java中: RpcFunction对应函数，Map中的RpcFunction值对应函数属性
     */
    private static ProxyDescriber createProxyForObject(String proxyId, Object obj, String hostIdParam) {
        if (obj == null) {
            return null;
        }

        ProxyDescriber proxy = new ProxyDescriber();
        proxy.setId(proxyId);
        proxy.setHostId(hostIdParam);

        List<ProxyDescriber.MemberDescriber> members = new ArrayList<>();

        // 对应TS: if(typeof obj == 'function')
        if (obj instanceof MessageReceiver.RpcFunction) {
            members.add(new ProxyDescriber.MemberDescriber("function", "__call__"));
        } else if (obj instanceof Map) {
            // 对应TS: Object.keys(obj).filter(k => typeof obj[k] == 'function').filter(k => !k.startsWith('__'))
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) obj;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (entry.getValue() instanceof MessageReceiver.RpcFunction
                        && !entry.getKey().startsWith("__")) {
                    members.add(new ProxyDescriber.MemberDescriber("function", entry.getKey()));
                }
            }
        }
        // 注意: 对于非Map非RpcFunction的Java对象，此处不会生成members
        // 在实际Java使用中，可通过反射获取public方法列表 (留作扩展)

        proxy.setMembers(members);
        return proxy;
    }

    /**
     * 将对象包装为代理PreArgObj
     * 对应TS: export function asProxy(obj: object, hostIdFrom?: string): PreArgObj
     */
    public static PreArgObj asProxy(Object obj, String hostIdFrom) {
        String resolvedHostId = getOrCreateOption(hostIdFrom).hostId;
        String id = getOrGenerateObjectId(obj, resolvedHostId);
        ProxyDescriber proxy = createProxyForObject(id, obj, resolvedHostId);
        return new PreArgObj("proxy", proxy);
    }

    public static PreArgObj asProxy(Object obj) {
        return asProxy(obj, null);
    }

    /**
     * 生成错误响应
     * 对应TS: export function generateErrorReply(message, errorText, status = 500)
     */
    public static Response generateErrorReply(Request message, String errorText, int status) {
        Response reply = new Response();
        reply.setId(getId());
        reply.setIdFor(message.getId());
        reply.setTrace(errorText);
        reply.setStatus(status);
        return reply;
    }

    public static Response generateErrorReply(Request message, String errorText) {
        return generateErrorReply(message, errorText, 500);
    }

    // ==================== 定时任务 ====================

    /**
     * 启动自动检查定时任务
     * 对应TS: export function autoCheck()
     *
     * 每3秒执行:
     * 1. killTimeoutConnection - 清理超时连接
     * 2. autoReRegister - 自动重新注册
     * 3. removeOutdatedProxyObject - 移除过期代理
     */
    public static void autoCheck() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rpc-auto-check");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(Rpc::killTimeoutConnection, 3, 3, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(Rpc::autoReRegister, 3, 3, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(() -> removeOutdatedProxyObject(-1), 3, 3, TimeUnit.SECONDS);
    }

    /**
     * 清理超时连接
     * 对应TS: function killTimeoutConnection(client: Client | null = null, millSec: number = -1)
     */
    public static void killTimeoutConnection() {
        killTimeoutConnection(null, -1);
    }

    public static void killTimeoutConnection(Client client, long millSec) {
        if (millSec == -1) {
            millSec = CONNECTION_LIMIT;
        }
        final long timeout = millSec;

        if (client != null) {
            processTimeoutForOption(getOrCreateOption(client.getHostId()), timeout);
        } else {
            for (MessageReceiverOptions option : options.values()) {
                processTimeoutForOption(option, timeout);
            }
        }
    }

    private static void processTimeoutForOption(MessageReceiverOptions option, long timeout) {
        ConcurrentHashMap<String, PendingRequest> reqPending = option.requestPendingDict;
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, PendingRequest> entry : reqPending.entrySet()) {
            PendingRequest pending = entry.getValue();
            if (System.currentTimeMillis() - pending.sendTime > timeout) {
                pending.future.completeExceptionally(new RuntimeException("timeout"));
                toRemove.add(entry.getKey());
            }
        }
        for (String key : toRemove) {
            reqPending.remove(key);
        }
    }

    /**
     * 自动重新注册远程代理
     * 对应TS: export function autoReRegister()
     */
    public static void autoReRegister() {
        for (MessageReceiverOptions option : options.values()) {
            RemoteProxyManager manager = option.runnableProxyManager;
            for (Map.Entry<Client, Set<String>> entry : manager.getClientMap().entrySet()) {
                Client client = entry.getKey();
                Set<String> ids = entry.getValue();
                if (ids == null) {
                    continue;
                }

                List<List<Object>> toReRegister = new ArrayList<>();
                Set<String> toRemove = new HashSet<>();

                for (String id : ids) {
                    Object obj = manager.get(id);
                    if (obj == null) {
                        toRemove.add(id);
                        continue;
                    }
                    List<Object> item = new ArrayList<>();
                    item.add(id);
                    toReRegister.add(item);
                }

                // 清理已失效的id
                for (String id : toRemove) {
                    ids.remove(id);
                }
                if (ids.isEmpty()) {
                    manager.removeFromClientMap(client);
                }

                // 调用远程reRegister方法
                if (!toReRegister.isEmpty()) {
                    /* @async 原TS中 client.getObject('main0').reRegister(toReRegister)，此处同步调用 */
                    try {
                        Object main0 = client.getObject("main0");
                        if (main0 instanceof RemoteObject) {
                            ((RemoteObject) main0).invoke("reRegister", (Object) toReRegister);
                        }
                    } catch (Exception e) {
                        System.err.println("autoReRegister failed: " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 移除过期代理对象
     * 对应TS: export function removeOutdatedProxyObject(timeout: number = -1)
     * (已修复bug: 原TS中错误遍历 Object.entries(manager)，应遍历 manager.reverseProxyMap)
     */
    public static void removeOutdatedProxyObject(long timeout) {
        if (timeout <= 0) {
            timeout = GC_TIME_LIMIT;
        }
        for (Map.Entry<String, MessageReceiverOptions> entry : options.entrySet()) {
            ObjectOfProxyManager manager = entry.getValue().objectOfProxyManager;
            int before = manager.size();
            int count = manager.removeOutdated(timeout);
            if (count > 0 && debugFlag) {
                System.out.println(entry.getKey() + " removed " + count
                        + " proxies, before " + before + " after " + manager.size());
            }
        }
    }

    // ==================== 类型检查工具 ====================

    /**
     * 判断是否为简单/原始类型对象
     * 对应TS: function isSimpleObject(obj)
     *
     * 简单类型: null, String, Number(Integer/Long/Double/Float等), Boolean, byte[]
     */
    public static boolean isSimpleObject(Object obj) {
        return obj == null
                || obj instanceof String
                || obj instanceof Number
                || obj instanceof Boolean
                || obj instanceof byte[];
    }

    /**
     * 判断消息是否为响应
     * 对应TS: function isResponse(message): message is Response
     */
    public static boolean isResponse(Message message) {
        return message instanceof Response && ((Response) message).getIdFor() != null;
    }

    /**
     * 判断消息是否为请求
     * 对应TS: function isRequest(obj): boolean
     */
    public static boolean isRequest(Message message) {
        return message instanceof Request;
    }

    /**
     * 深度检查对象是否可JSON序列化
     * 对应TS: function isSerializableDeep(obj, seen = new WeakSet())
     */
    public static boolean isSerializableDeep(Object obj) {
        return isSerializableDeep(obj, new HashSet<>());
    }

    @SuppressWarnings("unchecked")
    private static boolean isSerializableDeep(Object obj, Set<Object> seen) {
        if (obj == null) {
            return true;
        }

        // 基本类型
        if (obj instanceof String || obj instanceof Number || obj instanceof Boolean) {
            return true;
        }

        // 增强类型: byte[], java.util.Date, java.math.BigInteger
        if (obj instanceof byte[] || obj instanceof java.util.Date || obj instanceof java.math.BigInteger) {
            return enhanceType;
        }

        // 检查List
        if (obj instanceof List) {
            if (seen.contains(obj)) {
                return false; // 循环引用
            }
            seen.add(obj);
            for (Object item : (List<Object>) obj) {
                if (!isSerializableDeep(item, seen)) {
                    return false;
                }
            }
            return true;
        }

        // 检查Map (对应JS plain object)
        if (obj instanceof Map) {
            if (seen.contains(obj)) {
                return false; // 循环引用
            }
            seen.add(obj);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    return false; // JSON键必须是字符串
                }
                if (!isSerializableDeep(entry.getValue(), seen)) {
                    return false;
                }
            }
            return true;
        }

        // 其他类型 (类实例等) - 不可序列化
        return false;
    }

    // ==================== 断言/校验工具 ====================

    /**
     * 校验Request
     * 对应TS: function assertRequest(request)
     */
    public static void assertRequest(Request request) {
        if (request == null) {
            throw new RuntimeException("request: expected object but got null");
        }
        if (request.getId() == null || !(request.getId() instanceof String)) {
            throw new RuntimeException("request.id: expected string, got " + request.getId());
        }
        if (request.getMeta() == null) {
            throw new RuntimeException("request.meta: expected object, got null");
        }
        if (request.getMethod() == null) {
            throw new RuntimeException("request.method: expected string, got null");
        }
        if (request.getObjectId() == null) {
            throw new RuntimeException("request.objectId: expected string, got null");
        }
        if (request.getArgs() == null) {
            throw new RuntimeException("request.args: expected array, got null");
        }
    }

    /**
     * 校验请求参数是否可序列化
     * 对应TS: function assertArgJSON(request)
     */
    public static void assertArgJSON(Request request) {
        for (int i = 0; i < request.getArgs().size(); i++) {
            Object arg = request.getArgs().get(i);
            if (!isSerializableDeep(arg)) {
                System.err.println("Non-serializable arg: " + arg);
                throw new RuntimeException(request.getMethod() + ".args[" + i + "] is not serializable");
            }
        }
    }

    /**
     * 校验Response
     * 对应TS: function assertResponse(response)
     */
    public static void assertResponse(Response response) {
        if (response == null) {
            throw new RuntimeException("response: expected object but got null");
        }
        if (response.getId() == null) {
            throw new RuntimeException("response.id: expected string, got null");
        }
        if (response.getIdFor() == null) {
            throw new RuntimeException("response.idFor: expected string, got null");
        }
    }

    /**
     * 从Map构建ProxyDescriber (用于JSON反序列化后的转换)
     */
    @SuppressWarnings("unchecked")
    public static ProxyDescriber mapToProxyDescriber(Map<String, Object> map) {
        return ProxyDescriber.fromMap(map);
    }
}
