package com.lee.archery.client;

/**
 * Archery SQL 查询请求参数。
 */
public final class ArcheryQueryRequest {
    private final String sql;
    private final String tableName;
    private final String dbName;
    private final String schemaName;
    private final int limitNum;


    public ArcheryQueryRequest(String sql, String tableName) {
        this(sql, tableName, "", "", 0);
    }


    public ArcheryQueryRequest(String sql, String tableName, String dbName, String schemaName) {
        this(sql, tableName, dbName, schemaName, 0);
    }


    public ArcheryQueryRequest(String sql, String tableName, String dbName, String schemaName, int limitNum) {
        this.sql = sql;
        this.tableName = tableName == null ? "" : tableName;
        this.dbName = dbName == null ? "" : dbName;
        this.schemaName = schemaName == null ? "" : schemaName;
        this.limitNum = limitNum;
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


    public int getLimitNum() {
        return limitNum;
    }
}
