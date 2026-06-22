package com.lee.archery.client;

/**
 * Archery SQL 查询请求参数。
 */
public final class ArcheryQueryRequest {
    private final String sql;
    private final String tableName;
    private final String dbName;
    private final String schemaName;


    public ArcheryQueryRequest(String sql, String tableName) {
        this(sql, tableName, "", "");
    }


    public ArcheryQueryRequest(String sql, String tableName, String dbName, String schemaName) {
        this.sql = sql;
        this.tableName = tableName == null ? "" : tableName;
        this.dbName = dbName == null ? "" : dbName;
        this.schemaName = schemaName == null ? "" : schemaName;
    }


    public String getSql() {
        return sql;
    }


    public String getTableName() {
        return tableName;
    }


    public String getDbName() {
        return dbName;
    }


    public String getSchemaName() {
        return schemaName;
    }
}
