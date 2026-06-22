package com.lee.archery.jdbc;

import java.sql.Types;

/**
 * JDBC 结果列描述，统一服务查询结果和 metadata 结果集。
 */
final class ArcheryColumn {
    private final String label;
    private final int jdbcType;
    private final String typeName;


    ArcheryColumn(String label) {
        this(label, Types.VARCHAR, "VARCHAR");
    }


    ArcheryColumn(String label, int jdbcType, String typeName) {
        this.label = label;
        this.jdbcType = jdbcType;
        this.typeName = typeName;
    }


    String getLabel() {
        return label;
    }


    int getJdbcType() {
        return jdbcType;
    }


    String getTypeName() {
        return typeName;
    }
}
