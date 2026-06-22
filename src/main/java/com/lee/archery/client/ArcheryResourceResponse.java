package com.lee.archery.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Archery 资源接口响应，主要用于库、schema、表、字段的 metadata 枚举。
 */
public final class ArcheryResourceResponse {
    private final List<String> names;


    public ArcheryResourceResponse(List<String> names) {
        this.names = Collections.unmodifiableList(new ArrayList<>(names));
    }


    public List<String> getNames() {
        return names;
    }
}
