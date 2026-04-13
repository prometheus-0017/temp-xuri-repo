package com.xuri.rpc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 参数转换器，负责将对象转换为RPC传输格式，以及反向转换
 * 对应TS中: class ArgTranslator
 */
public class ArgTranslator {

    /**
     * 自定义参数转换器接口
     * 对应TS中: interface CustomArgTranslatorFunction
     */
    public interface CustomArgTranslatorFunction {
        boolean match(Object obj);

        Object translate(Object obj);

        Object reverseTranslate(Object obj);
    }

    private String typeIndicator = "__type";
    private final List<CustomArgTranslatorFunction> customTranslators = new ArrayList<>();

    public void setTypeIndicator(String typeIndicator) {
        this.typeIndicator = typeIndicator;
    }

    public void addCustomTranslator(CustomArgTranslatorFunction translator) {
        customTranslators.add(translator);
    }

    /**
     * 将对象转换为RPC传输格式
     * 对应TS中: toArgObj(target: any, asProxyLocal: (obj: any) => any): any
     *
     * 转换规则：
     * 1. PreArgObj → 直接返回其data
     * 2. 简单类型(String/Number/Boolean/null/byte[]) → 原样返回
     * 3. List → 递归转换每一项
     * 4. Map (plain object) → 递归转换每个属性
     * 5. 自定义转换器匹配 → 使用自定义转换
     * 6. 其他复杂对象 → 调用asProxyLocal包装为代理
     *
     * @param target       要转换的对象
     * @param asProxyLocal 代理包装函数，对应TS中 (obj) => asProxy(obj, hostId)
     * @return 转换后的对象
     */
    @SuppressWarnings("unchecked")
    public Object toArgObj(Object target, Function<Object, Object> asProxyLocal) {

        // 1. PreArgObj - 预标记的参数对象
        if (target instanceof PreArgObj) {
            PreArgObj pre = (PreArgObj) target;
            if ("proxy".equals(pre.getType())) {
                return pre.getData();
            } else if ("data".equals(pre.getType())) {
                return pre.getData();
            } else {
                throw new UnsupportedOperationException("not implemented for type: " + pre.getType());
            }
        }

        // 2. 简单类型 - 直接返回
        if (Rpc.isSimpleObject(target)) {
            return target;
        }

        // 3. 列表 (Array) - 递归处理每一项
        if (target instanceof List) {
            List<Object> list = (List<Object>) target;
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(toArgObj(item, asProxyLocal));
            }
            return result;
        }

        // 4. 字典 (plain object) - 递归处理每个属性
        // 对应TS中 isDict(target) (已修复: Object.getPrototypeOf(obj) === Object.prototype)
        // Java中 Map 等价于 JS plain object
        if (target instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) target;
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                result.put(entry.getKey(), toArgObj(entry.getValue(), asProxyLocal));
            }
            return result;
        }

        // 5. 自定义转换器
        for (CustomArgTranslatorFunction translator : customTranslators) {
            if (translator.match(target)) {
                return translator.translate(target);
            }
        }

        // 6. 其他对象 (类实例等) - 调用 asProxy 包装为代理
        return asProxyLocal.apply(target);
    }

    /**
     * 将RPC传输格式反向转换为对象
     * 对应TS中: reverseToArgObj(target: any, client: Client): any
     *
     * @param target 从RPC接收到的数据
     * @param client 关联的Client，用于创建远程代理
     * @return 转换后的对象
     */
    @SuppressWarnings("unchecked")
    public Object reverseToArgObj(Object target, Client client) {

        // 检查是否是代理描述 (包含typeIndicator字段)
        if (target instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) target;

            if (map.containsKey(typeIndicator)) {
                // 这是一个代理对象描述
                ProxyDescriber data;
                Object rawData = map.get("data");
                if (rawData instanceof ProxyDescriber) {
                    data = (ProxyDescriber) rawData;
                } else if (rawData instanceof Map) {
                    data = ProxyDescriber.fromMap((Map<String, Object>) rawData);
                } else {
                    throw new RuntimeException("unexpected proxy data type: " + rawData);
                }
                Object result = client.createRemoteProxy(data);
                client.getRunnableProxyManager().set(data.getId(), result);
                return result;
            }

            // 普通字典 - 递归处理每个属性
            Map<String, Object> resultMap = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                resultMap.put(entry.getKey(), reverseToArgObj(entry.getValue(), client));
            }
            return resultMap;
        }

        // 列表 - 递归处理每一项
        if (target instanceof List) {
            List<Object> list = (List<Object>) target;
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(reverseToArgObj(item, client));
            }
            return result;
        }

        // 自定义反向转换器
        for (CustomArgTranslatorFunction translator : customTranslators) {
            if (translator.match(target)) {
                return translator.reverseTranslate(target);
            }
        }

        // 其他类型直接返回
        return target;
    }
}
