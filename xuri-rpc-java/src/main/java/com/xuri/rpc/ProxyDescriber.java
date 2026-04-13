package com.xuri.rpc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 代理描述器，描述一个远程对象的元信息
 * 对应TS中: interface ProxyDescriber
 */
public class ProxyDescriber {
    private String id;
    private String hostId;
    private List<MemberDescriber> members;

    public ProxyDescriber() {
        this.members = new ArrayList<>();
    }

    public ProxyDescriber(String id, String hostId, List<MemberDescriber> members) {
        this.id = id;
        this.hostId = hostId;
        this.members = members != null ? members : new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHostId() {
        return hostId;
    }

    public void setHostId(String hostId) {
        this.hostId = hostId;
    }

    public List<MemberDescriber> getMembers() {
        return members;
    }

    public void setMembers(List<MemberDescriber> members) {
        this.members = members;
    }

    /**
     * 代理成员描述
     * 对应TS中: {type: 'function' | 'property', name: string}
     */
    public static class MemberDescriber {
        private String type; // "function" 或 "property"
        private String name;

        public MemberDescriber() {
        }

        public MemberDescriber(String type, String name) {
            this.type = type;
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /**
     * 从Map（JSON反序列化结果）构建ProxyDescriber
     */
    @SuppressWarnings("unchecked")
    public static ProxyDescriber fromMap(Map<String, Object> map) {
        ProxyDescriber describer = new ProxyDescriber();
        describer.setId((String) map.get("id"));
        describer.setHostId((String) map.get("hostId"));

        List<MemberDescriber> members = new ArrayList<>();
        Object rawMembers = map.get("members");
        if (rawMembers instanceof List) {
            for (Object item : (List<Object>) rawMembers) {
                if (item instanceof Map) {
                    Map<String, Object> memberMap = (Map<String, Object>) item;
                    members.add(new MemberDescriber(
                            (String) memberMap.get("type"),
                            (String) memberMap.get("name")
                    ));
                }
            }
        }
        describer.setMembers(members);
        return describer;
    }
}
