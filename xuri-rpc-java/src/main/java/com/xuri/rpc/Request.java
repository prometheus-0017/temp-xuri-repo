package com.xuri.rpc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RPC请求
 * 对应TS中: export interface Request
 */
public class Request implements Message {
    private String id;
    private Map<String, Object> meta;
    private String method;
    private String objectId;
    private List<Object> args;

    public Request() {
        this.meta = new HashMap<>();
        this.args = new ArrayList<>();
    }

    public Request(String id, String method, String objectId, List<Object> args) {
        this.id = id;
        this.meta = new HashMap<>();
        this.method = method;
        this.objectId = objectId;
        this.args = args != null ? args : new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Map<String, Object> getMeta() {
        return meta;
    }

    public void setMeta(Map<String, Object> meta) {
        this.meta = meta;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getObjectId() {
        return objectId;
    }

    public void setObjectId(String objectId) {
        this.objectId = objectId;
    }

    public List<Object> getArgs() {
        return args;
    }

    public void setArgs(List<Object> args) {
        this.args = args;
    }

    @Override
    public String toString() {
        return "Request{id='" + id + "', method='" + method + "', objectId='" + objectId + "'}";
    }
}
