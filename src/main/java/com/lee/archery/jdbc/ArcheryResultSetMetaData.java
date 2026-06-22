package com.lee.archery.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * 查询结果列元数据，向 IDEA/DataGrip Result tab 提供列名、类型和只读属性。
 */
public final class ArcheryResultSetMetaData implements InvocationHandler {
    private final List<ArcheryColumn> columns;


    private ArcheryResultSetMetaData(List<ArcheryColumn> columns) {
        this.columns = columns;
    }


    public static ResultSetMetaData create(List<ArcheryColumn> columns) {
        return ArcheryJdbcObjects.proxy(ResultSetMetaData.class, new ArcheryResultSetMetaData(columns));
    }


    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (ArcheryJdbcObjects.isWrapperMethod(method)) {
            return ArcheryJdbcObjects.handleWrapper(proxy, method, args, this);
        }
        String name = method.getName();
        if ("getColumnCount".equals(name)) {
            return columns.size();
        }
        if ("getColumnLabel".equals(name) || "getColumnName".equals(name)) {
            return column(args).getLabel();
        }
        if ("getColumnType".equals(name)) {
            return column(args).getJdbcType();
        }
        if ("getColumnTypeName".equals(name)) {
            return column(args).getTypeName();
        }
        if ("getColumnClassName".equals(name)) {
            return className(column(args).getJdbcType());
        }
        if ("getColumnDisplaySize".equals(name)) {
            return 255;
        }
        if ("getPrecision".equals(name) || "getScale".equals(name)) {
            return 0;
        }
        if ("isNullable".equals(name)) {
            return ResultSetMetaData.columnNullableUnknown;
        }
        if ("isReadOnly".equals(name)) {
            return true;
        }
        if ("isWritable".equals(name) || "isDefinitelyWritable".equals(name) || "isAutoIncrement".equals(name)
            || "isCurrency".equals(name) || "isSigned".equals(name) || "isCaseSensitive".equals(name)
            || "isSearchable".equals(name)) {
            return false;
        }
        if ("getCatalogName".equals(name) || "getSchemaName".equals(name) || "getTableName".equals(name)) {
            return "";
        }
        return ArcheryJdbcObjects.defaultValue(method.getReturnType());
    }


    private ArcheryColumn column(Object[] args) throws SQLException {
        int index = normalizedColumnIndex(((Number) args[0]).intValue());
        return columns.get(index - 1);
    }


    /**
     * 兼容 IDE 刷新 metadata 时传入的零号列探测，避免刷新流程提前中断。
     */
    private int normalizedColumnIndex(int index) throws SQLException {
        if (index == 0 && !columns.isEmpty()) {
            return 1;
        }
        if (index < 1 || index > columns.size()) {
            throw new SQLException("Column index out of range: " + index);
        }
        return index;
    }


    private String className(int type) {
        switch (type) {
            case Types.INTEGER:
                return Integer.class.getName();
            case Types.BIGINT:
                return Long.class.getName();
            case Types.DOUBLE:
            case Types.FLOAT:
                return Double.class.getName();
            case Types.DECIMAL:
            case Types.NUMERIC:
                return java.math.BigDecimal.class.getName();
            case Types.BOOLEAN:
            case Types.BIT:
                return Boolean.class.getName();
            case Types.DATE:
                return java.sql.Date.class.getName();
            case Types.TIMESTAMP:
                return java.sql.Timestamp.class.getName();
            default:
                return String.class.getName();
        }
    }
}
