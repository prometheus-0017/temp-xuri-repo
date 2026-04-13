package com.xuri.rpc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息接收器，负责处理收到的RPC请求和响应
 * 对应TS中: export class MessageReceiver
 */
public class MessageReceiver {
    private Map<String, Object> rpcServer; // 对应TS: rpcServer?: Record<string, Function>
    private final List<Interceptor> interceptors = new ArrayList<>();
    private final Set<String> objectWithContext = new HashSet<>();
    private AutoWrapper resultAutoWrapper = Rpc.SHALLOW_AUTO_WRAPPER;
    private final String hostId;

    public void setResultAutoWrapper(AutoWrapper autoWrapper) {
        this.resultAutoWrapper = autoWrapper;
    }

    /**
     * 构造函数
     * 对应TS中: constructor(hostId?: string)
     *
     * 初始化时注册内置对象 'main0'，提供 getMain 和 reRegister 方法
     */
    public MessageReceiver(String hostId) {
        this.hostId = hostId;
        String hostIdToSend = getHostId();

        // 注册内置对象 main0
        Map<String, Object> main0 = new HashMap<>();
        main0.put("getMain", (RpcFunction) (Object... args) -> {
            String objectId = args.length > 0 ? (String) args[0] : null;
            if (objectId == null) {
                objectId = "main";
            }
            return Rpc.asProxy(getProxyManager().getById(objectId), hostIdToSend);
        });
        main0.put("reRegister", (RpcFunction) (Object... args) -> {
            @SuppressWarnings("unchecked")
            List<List<Object>> list = (List<List<Object>>) args[0];
            for (List<Object> item : list) {
                String objectId = (String) item.get(0);
                getProxyManager().reRegister(objectId);
            }
            return null;
        });
        getProxyManager().set(main0, "main0");
    }

    public MessageReceiver() {
        this(null);
    }

    /**
     * RPC函数接口，用于Java中表示可通过RPC调用的函数
     * 对应TS中: Record<string, Function> 中的 Function
     */
    @FunctionalInterface
    public interface RpcFunction {
        Object apply(Object... args);
    }

    /**
     * 设置主服务对象
     * 对应TS中: setMain(obj: Record<string, Function>)
     */
    public void setMain(Map<String, Object> obj) {
        this.rpcServer = obj;
        setObject("main", this.rpcServer, false);
    }

    /**
     * 注册对象到代理管理器
     * 对应TS中: setObject(id: string, obj: Record<string, Function>, withContext: boolean)
     */
    public void setObject(String id, Object obj, boolean withContext) {
        getProxyManager().set(obj, id);
        if (withContext) {
            objectWithContext.add(id);
        }
    }

    /**
     * 添加拦截器
     * 对应TS中: addInterceptor(interceptor: Interceptor)
     */
    public void addInterceptor(Interceptor interceptor) {
        interceptors.add(interceptor);
    }

    void putAwait(String id, java.util.concurrent.CompletableFuture<Object> future, Message request) {
        getReqPending().put(id, new PendingRequest(future, request, System.currentTimeMillis()));
    }

    public int currentWaitingCount() {
        return getReqPending().size();
    }

    /**
     * 拦截器链式执行 (with context模式)
     * 对应TS中: async withContext(message, client, args, func)
     *
     * 构建拦截器执行链:
     * interceptor[0] → interceptor[1] → ... → interceptor[n] → 实际函数调用
     */
    /* @async 原TS中为async方法，此处已转换为同步阻塞 */
    @SuppressWarnings("unchecked")
    public Object withContext(Request message, Client client, Object[] args, Object func) {
        final MessageReceiver self = this;
        final Object[] result = new Object[1];

        Map<String, Object> context = new HashMap<>();
        context.put("setContext", (RpcFunction) (Object... contextArgs) -> {
            result[0] = contextArgs[0];
            return null;
        });

        // 构建拦截器执行链
        // 对应TS中: function generateInteceptorExecutor(indexOfInteceptor: number): NextFunction
        Runnable firstExecutor = generateInterceptorExecutor(0, self, context, message, client, args, func, result);
        /* @async 原TS中 await firstInteceptorExecetor()，此处同步执行 */
        firstExecutor.run();
        return result[0];
    }

    /**
     * 递归生成拦截器执行器
     * 对应TS中: function generateInteceptorExecutor(indexOfInteceptor: number): NextFunction
     */
    @SuppressWarnings("unchecked")
    private Runnable generateInterceptorExecutor(
            int index, MessageReceiver self, Map<String, Object> context,
            Request message, Client client, Object[] args, Object func, Object[] result) {

        if (index < self.interceptors.size()) {
            return () -> {
                /* @async 原TS中 async function executeThisInteceptor()，此处同步执行 */
                Interceptor interceptor = self.interceptors.get(index);
                Runnable next = () -> {
                    /* @async 原TS中 const generateAndExecuteNext = async() => { ... }，此处同步执行 */
                    Runnable executor = generateInterceptorExecutor(
                            index + 1, self, context, message, client, args, func, result);
                    executor.run();
                };
                interceptor.intercept(context, message, client, next);
            };
        } else {
            return () -> {
                /* @async 原TS中 async () => { result.value = await func(context, ...args) }，此处同步执行 */
                if (func instanceof RpcFunction) {
                    // 在withContext模式下，第一个参数是context
                    Object[] fullArgs = new Object[args.length + 1];
                    fullArgs[0] = context;
                    System.arraycopy(args, 0, fullArgs, 1, args.length);
                    result[0] = ((RpcFunction) func).apply(fullArgs);
                } else if (func instanceof Map) {
                    // func 可能是一个包含方法的Map，此时应该在外部已选择具体方法
                    throw new RuntimeException("func should be a callable, not a Map");
                } else {
                    throw new RuntimeException("unsupported func type: " + func.getClass());
                }
            };
        }
    }

    public ObjectOfProxyManager getProxyManager() {
        return Rpc.getOrCreateOption(hostId).objectOfProxyManager;
    }

    public RemoteProxyManager getRunnableProxyManager() {
        return Rpc.getOrCreateOption(hostId).runnableProxyManager;
    }

    public String getHostId() {
        return Rpc.getOrCreateOption(hostId).hostId;
    }

    ConcurrentHashMap<String, PendingRequest> getReqPending() {
        return Rpc.getOrCreateOption(hostId).requestPendingDict;
    }

    /**
     * 处理收到的消息（请求或响应）
     * 对应TS中: async onReceiveMessage(messageRecv: Request|Response, clientForCallBack: Client)
     *
     * 请求处理流程:
     * 1. 从代理管理器中获取目标对象
     * 2. 转换参数
     * 3. 执行方法（可能经过拦截器链）
     * 4. 包装结果并通过sender发送响应
     *
     * 响应处理流程:
     * 1. 查找对应的PendingRequest
     * 2. 根据状态码resolve或reject对应的CompletableFuture
     */
    /* @async 原TS中为async方法，此处已转换为同步阻塞 */
    @SuppressWarnings("unchecked")
    public void onReceiveMessage(Message messageRecv, Client clientForCallBack) {
        if (clientForCallBack == null) {
            throw new RuntimeException("clientForCallBack must not null");
        }
        if (!(clientForCallBack instanceof Client)) {
            throw new RuntimeException("clientForCallBack must be a Client");
        }

        if (Rpc.isDebug()) {
            if (messageRecv instanceof Response) {
                Response resp = (Response) messageRecv;
                System.out.println(getHostId() + " received a reply, which is for "
                        + resp.getId() + " and it is " + resp.getIdFor() + " " + resp);
            } else {
                Request req = (Request) messageRecv;
                System.out.println(getHostId() + " received a request, which id is "
                        + req.getId() + " " + req);
            }
        }

        // 判断是请求还是响应
        if (!Rpc.isResponse(messageRecv)) {
            // === 处理请求 ===
            Request message = (Request) messageRecv;
            try {
                Object object = getProxyManager().getById(message.getObjectId());
                if (object == null) {
                    clientForCallBack.getSender().send(
                            Rpc.generateErrorReply(message, "object not found", 100));
                    return;
                }

                // 转换参数
                Object[] args = new Object[message.getArgs().size()];
                for (int i = 0; i < message.getArgs().size(); i++) {
                    args[i] = clientForCallBack.transformArg(message.getArgs().get(i), null);
                }

                Object result = null;
                boolean shouldWithContext = objectWithContext.contains(message.getObjectId());

                if ("__call__".equals(message.getMethod())) {
                    // 对象本身是可调用的
                    if (shouldWithContext) {
                        result = withContext(message, clientForCallBack, args, object);
                    } else {
                        if (object instanceof RpcFunction) {
                            result = ((RpcFunction) object).apply(args);
                        } else {
                            throw new RuntimeException("object is not callable");
                        }
                    }
                } else {
                    // 调用对象的指定方法
                    if (object instanceof Map) {
                        Map<String, Object> objMap = (Map<String, Object>) object;
                        Object method = objMap.get(message.getMethod());
                        if (method == null) {
                            throw new RuntimeException("method not found: " + message.getMethod());
                        }
                        if (shouldWithContext) {
                            result = withContext(message, clientForCallBack, args, method);
                        } else {
                            if (method instanceof RpcFunction) {
                                result = ((RpcFunction) method).apply(args);
                            } else {
                                throw new RuntimeException("method is not callable: " + message.getMethod());
                            }
                        }
                    } else {
                        throw new RuntimeException("object does not support method invocation: "
                                + object.getClass());
                    }
                }

                // 通过autoWrapper包装结果
                result = resultAutoWrapper.wrap(result);
                // 对应TS中: result = await result (等待Promise resolve)
                // Java同步版本中不需要此步骤

                // 包装结果为ArgObj格式
                Object wrappedResult = clientForCallBack.toArgObj(result);

                if (Rpc.isDebug()) {
                    if (!Rpc.isSerializableDeep(wrappedResult)) {
                        System.err.println(message.getMethod() + ": result is not serializable");
                        throw new RuntimeException(message.getMethod() + ": result is not serializable");
                    }
                }

                // 发送成功响应
                Response response = new Response();
                response.setId(Rpc.getId());
                response.setIdFor(message.getId());
                response.setMeta(new HashMap<>());
                response.setData(wrappedResult);
                response.setStatus(200);
                clientForCallBack.getSender().send(response);

            } catch (Exception e) {
                // 发送错误响应
                String trace = null;
                if (e.getStackTrace() != null) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(e.toString()).append("\n");
                    for (StackTraceElement elem : e.getStackTrace()) {
                        sb.append("  at ").append(elem.toString()).append("\n");
                    }
                    trace = sb.toString();
                }

                Response errorResponse = new Response();
                errorResponse.setId(Rpc.getId());
                errorResponse.setIdFor(message.getId());
                errorResponse.setMeta(new HashMap<>());
                errorResponse.setData(null);
                errorResponse.setTrace(trace);
                errorResponse.setStatus(-1);
                clientForCallBack.getSender().send(errorResponse);

                System.err.println("Error handling request: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            // === 处理响应 ===
            Response message = (Response) messageRecv;
            String idFor = message.getIdFor();

            ConcurrentHashMap<String, PendingRequest> reqPending = getReqPending();
            PendingRequest pending = reqPending.get(idFor);

            if (pending == null) {
                System.err.println("[" + getHostId() + "] no pending request for id " + idFor + " " + message);
                return;
            }
            reqPending.remove(idFor);

            if (message.getStatus() == 200) {
                pending.future.complete(clientForCallBack.reverseToArgObj(message.getData()));
            } else {
                pending.future.completeExceptionally(
                        new RuntimeException("RPC error: status=" + message.getStatus()
                                + ", trace=" + message.getTrace()));
            }
        }
    }
}
