package com.xuri.rpc;

/**
 * 预参数对象，用于在toArgObj转换前标记参数类型
 * 对应TS中: export class PreArgObj
 *
 * type可选值: "proxy", "data", "datetime", null
 */
public class PreArgObj {
    private String type;
    private Object data;

    public PreArgObj(String type, Object data) {
        this.type = type;
        this.data = data;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }
}
