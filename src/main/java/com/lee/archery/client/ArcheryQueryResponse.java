package com.lee.archery.client;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Archery 查询接口响应，保留原始 data 节点以兼容不同版本返回结构。
 */
public final class ArcheryQueryResponse {
    private final JsonNode data;


    public ArcheryQueryResponse(JsonNode data) {
        this.data = data;
    }


    public JsonNode getData() {
        return data;
    }


    public JsonNode getRows() {
        JsonNode rows = data.path("rows");
        return rows.isArray() ? rows : data.path("result");
    }


    public JsonNode getColumnList() {
        return data.path("column_list");
    }


    public JsonNode getColumnTypes() {
        return data.path("column_type");
    }
}
