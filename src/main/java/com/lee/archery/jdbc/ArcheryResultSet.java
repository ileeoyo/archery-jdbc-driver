package com.lee.archery.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.lee.archery.client.ArcheryQueryResponse;
import com.lee.archery.config.ArcheryJdbcConfig;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.*;
import java.sql.Date;
import java.util.*;

/**
 * Archery 查询结果集，把 Web JSON 行列数据映射成 JDBC ResultSet。
 */
public final class ArcheryResultSet implements InvocationHandler {
    private final Statement statement;
    private final ArcheryJdbcConfig config;
    private final List<ArcheryColumn> columns;
    private final List<Map<String, Object>> rows;
    private final ResultSetMetaData metaData;
    private final int resultSetType;
    private final int resultSetConcurrency;
    private int cursor = -1;
    private boolean closed;
    private Object lastValue;


    private ArcheryResultSet(Statement statement, ArcheryJdbcConfig config, List<ArcheryColumn> columns,
                             List<Map<String, Object>> rows, int resultSetType, int resultSetConcurrency) {
        this.statement = statement;
        this.config = config;
        this.columns = columns;
        this.rows = rows;
        this.metaData = ArcheryResultSetMetaData.create(columns);
        this.resultSetType = resultSetType;
        this.resultSetConcurrency = resultSetConcurrency;
    }


    public static ResultSet create(Statement statement, ArcheryQueryResponse response) {
        return create(statement, response, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    }


    public static ResultSet create(Statement statement, ArcheryQueryResponse response, int resultSetType,
                                   int resultSetConcurrency) {
        return create(statement, null, response, resultSetType, resultSetConcurrency);
    }


    public static ResultSet create(Statement statement, ArcheryJdbcConfig config, ArcheryQueryResponse response,
                                   int resultSetType, int resultSetConcurrency) {
        List<ArcheryColumn> columns = readColumns(response);
        List<Map<String, Object>> rows = readRows(response.getRows(), columns);
        ArcheryDebugLog.write(config, "createResultSet columns=" + columns.size() + ", rows=" + rows.size()
            + ", rowNodeArray=" + response.getRows().isArray());
        return create(statement, config, columns, rows, resultSetType, resultSetConcurrency);
    }


    public static ResultSet create(Statement statement, List<ArcheryColumn> columns, List<Map<String, Object>> rows) {
        return create(statement, null, columns, rows, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    }


    public static ResultSet create(Statement statement, List<ArcheryColumn> columns, List<Map<String, Object>> rows,
                                   int resultSetType, int resultSetConcurrency) {
        return create(statement, null, columns, rows, resultSetType, resultSetConcurrency);
    }


    public static ResultSet create(Statement statement, ArcheryJdbcConfig config, List<ArcheryColumn> columns,
                                   List<Map<String, Object>> rows, int resultSetType, int resultSetConcurrency) {
        return ArcheryJdbcObjects.proxy(ResultSet.class,
            new ArcheryResultSet(statement, config, columns, rows, resultSetType, resultSetConcurrency));
    }


    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (ArcheryJdbcObjects.isWrapperMethod(method)) {
            return ArcheryJdbcObjects.handleWrapper(proxy, method, args, this);
        }
        String name = method.getName();
        if ("close".equals(name)) {
            closed = true;
            return null;
        }
        if ("isClosed".equals(name)) {
            return closed;
        }
        ensureOpen();
        if ("next".equals(name)) {
            if (cursor + 1 < rows.size()) {
                cursor++;
                ArcheryDebugLog.write(config, "ResultSet.next -> true, cursor=" + cursor + ", row=" + (cursor + 1));
                return true;
            }
            cursor = rows.size();
            ArcheryDebugLog.write(config, "ResultSet.next -> false, cursor=afterLast, rows=" + rows.size());
            return false;
        }
        if ("beforeFirst".equals(name)) {
            ensureScrollable();
            cursor = -1;
            return null;
        }
        if ("afterLast".equals(name)) {
            ensureScrollable();
            cursor = rows.size();
            return null;
        }
        if ("first".equals(name)) {
            ensureScrollable();
            return moveTo(0);
        }
        if ("last".equals(name)) {
            ensureScrollable();
            return moveTo(rows.size() - 1);
        }
        if ("previous".equals(name)) {
            ensureScrollable();
            return moveTo(cursor - 1);
        }
        if ("absolute".equals(name)) {
            ensureScrollable();
            return absolute(((Number) args[0]).intValue());
        }
        if ("relative".equals(name)) {
            ensureScrollable();
            return moveTo(cursor + ((Number) args[0]).intValue());
        }
        if ("getRow".equals(name)) {
            return cursor >= 0 && cursor < rows.size() ? cursor + 1 : 0;
        }
        if ("wasNull".equals(name)) {
            return lastValue == null;
        }
        if ("getMetaData".equals(name)) {
            return metaData;
        }
        if ("getStatement".equals(name)) {
            return statement;
        }
        if ("getType".equals(name)) {
            return resultSetType;
        }
        if ("getConcurrency".equals(name)) {
            return resultSetConcurrency;
        }
        if ("getHoldability".equals(name)) {
            return ResultSet.CLOSE_CURSORS_AT_COMMIT;
        }
        if ("getFetchDirection".equals(name)) {
            return ResultSet.FETCH_FORWARD;
        }
        if ("setFetchDirection".equals(name)) {
            if (((Number) args[0]).intValue() != ResultSet.FETCH_FORWARD) {
                throw ArcheryJdbcObjects.unsupported("Non-forward fetch direction");
            }
            return null;
        }
        if ("setFetchSize".equals(name)) {
            return null;
        }
        if ("getFetchSize".equals(name)) {
            return 0;
        }
        if ("findColumn".equals(name)) {
            return findColumn(String.valueOf(args[0]));
        }
        if ("isBeforeFirst".equals(name)) {
            return cursor < 0 && !rows.isEmpty();
        }
        if ("isAfterLast".equals(name)) {
            return cursor >= rows.size() && !rows.isEmpty();
        }
        if ("isFirst".equals(name)) {
            return cursor == 0 && !rows.isEmpty();
        }
        if ("isLast".equals(name)) {
            return cursor == rows.size() - 1 && !rows.isEmpty();
        }
        if (isColumnGetter(name, args)) {
            return convertGetter(name, args, method.getReturnType());
        }
        return ArcheryJdbcObjects.defaultValue(method.getReturnType());
    }


    private boolean isColumnGetter(String name, Object[] args) {
        return args != null && args.length > 0 && (args[0] instanceof Number || args[0] instanceof String)
            && ("getObject".equals(name) || "getString".equals(name) || "getBoolean".equals(name)
            || "getByte".equals(name) || "getShort".equals(name) || "getInt".equals(name)
            || "getLong".equals(name) || "getFloat".equals(name) || "getDouble".equals(name)
            || "getBigDecimal".equals(name) || "getDate".equals(name) || "getTimestamp".equals(name));
    }


    private Object convertGetter(String name, Object[] args, Class<?> returnType) throws SQLException {
        Object value = value(args);
        if ("getObject".equals(name)) {
            return value;
        }
        if ("getString".equals(name)) {
            return value == null ? null : String.valueOf(value);
        }
        if ("getBoolean".equals(name)) {
            if (value instanceof Boolean) {
                return value;
            }
            if (value instanceof Number) {
                return ((Number) value).longValue() != 0L;
            }
            return Boolean.parseBoolean(String.valueOf(value));
        }
        if ("getByte".equals(name)) {
            return value == null ? (byte) 0 : (byte) toLong(value);
        }
        if ("getShort".equals(name)) {
            return value == null ? (short) 0 : (short) toLong(value);
        }
        if ("getInt".equals(name)) {
            return value == null ? 0 : (int) toLong(value);
        }
        if ("getLong".equals(name)) {
            return value == null ? 0L : toLong(value);
        }
        if ("getFloat".equals(name)) {
            return value == null ? 0F : Float.parseFloat(String.valueOf(value));
        }
        if ("getDouble".equals(name)) {
            return value == null ? 0D : Double.parseDouble(String.valueOf(value));
        }
        if ("getBigDecimal".equals(name)) {
            return ArcheryJdbcObjects.toBigDecimal(value);
        }
        if ("getDate".equals(name)) {
            return value == null ? null : Date.valueOf(String.valueOf(value));
        }
        if ("getTimestamp".equals(name)) {
            return value == null ? null : Timestamp.valueOf(String.valueOf(value));
        }
        return ArcheryJdbcObjects.defaultValue(returnType);
    }


    private long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String text = String.valueOf(value).trim();
        if ("YES".equalsIgnoreCase(text) || "TRUE".equalsIgnoreCase(text)) {
            return 1L;
        }
        if ("NO".equalsIgnoreCase(text) || "FALSE".equalsIgnoreCase(text)) {
            return 0L;
        }
        return Long.parseLong(text);
    }


    private Object value(Object[] args) throws SQLException {
        if (cursor < 0 || cursor >= rows.size()) {
            throw new SQLException("ResultSet cursor is not positioned on a row");
        }
        Object key = args[0];
        String columnName;
        if (key instanceof Number) {
            int columnIndex = normalizedColumnIndex(((Number) key).intValue());
            columnName = columns.get(columnIndex - 1).getLabel();
        } else {
            columnName = columns.get(findColumn(String.valueOf(key)) - 1).getLabel();
        }
        Object value = rows.get(cursor).get(columnName);
        lastValue = value;
        ArcheryDebugLog.write(config, "ResultSet.value column=" + columnName + ", row=" + (cursor + 1)
            + ", null=" + (value == null));
        return value;
    }


    /**
     * 兼容 IDE 刷新 metadata 时传入的零号列探测，避免刷新流程提前中断。
     */
    private int normalizedColumnIndex(int columnIndex) throws SQLException {
        if (columnIndex == 0 && !columns.isEmpty()) {
            return 1;
        }
        if (columnIndex < 1 || columnIndex > columns.size()) {
            throw new SQLException("Column index out of range: " + columnIndex);
        }
        return columnIndex;
    }


    private int findColumn(String label) throws SQLException {
        for (int index = 0; index < columns.size(); index++) {
            if (columns.get(index).getLabel().equalsIgnoreCase(label)) {
                return index + 1;
            }
        }
        throw new SQLException("Unknown column: " + label);
    }


    private boolean absolute(int row) {
        boolean moved;
        if (row > 0) {
            moved = moveTo(row - 1);
        } else if (row < 0) {
            moved = moveTo(rows.size() + row);
        } else {
            cursor = -1;
            moved = false;
        }
        ArcheryDebugLog.write(config, "ResultSet.absolute(" + row + ") -> " + moved + ", cursor=" + cursor
            + ", rows=" + rows.size());
        return moved;
    }


    private boolean moveTo(int targetCursor) {
        boolean moved = targetCursor >= 0 && targetCursor < rows.size();
        cursor = moved ? targetCursor : targetCursor < 0 ? -1 : rows.size();
        ArcheryDebugLog.write(config, "ResultSet.moveTo(" + targetCursor + ") -> " + moved + ", cursor=" + cursor
            + ", rows=" + rows.size());
        return moved;
    }


    private void ensureOpen() throws SQLException {
        if (closed) {
            throw ArcheryJdbcObjects.closed("ResultSet");
        }
    }


    private void ensureScrollable() throws SQLException {
        if (resultSetType == ResultSet.TYPE_FORWARD_ONLY) {
            throw ArcheryJdbcObjects.unsupported("Scrollable ResultSet");
        }
    }


    private static List<ArcheryColumn> readColumns(ArcheryQueryResponse response) {
        List<ArcheryColumn> columns = new ArrayList<>();
        JsonNode columnList = response.getColumnList();
        if (columnList.isArray()) {
            for (JsonNode column : columnList) {
                columns.add(new ArcheryColumn(column.asText()));
            }
        }
        if (columns.isEmpty() && response.getRows().isArray() && response.getRows().size() > 0) {
            JsonNode first = response.getRows().get(0);
            if (first.isObject()) {
                first.fieldNames().forEachRemaining(name -> columns.add(new ArcheryColumn(name)));
            }
        }
        return columns;
    }


    private static List<Map<String, Object>> readRows(JsonNode rowNode, List<ArcheryColumn> columns) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!rowNode.isArray()) {
            return rows;
        }
        for (JsonNode row : rowNode) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            if (row.isArray()) {
                for (int index = 0; index < columns.size() && index < row.size(); index++) {
                    mapped.put(columns.get(index).getLabel(), jsonValue(row.get(index)));
                }
            } else if (row.isObject()) {
                for (ArcheryColumn column : columns) {
                    JsonNode value = row.get(column.getLabel());
                    if (value == null) {
                        value = row.get(column.getLabel().toLowerCase(Locale.ROOT));
                    }
                    mapped.put(column.getLabel(), jsonValue(value));
                }
            }
            rows.add(mapped);
        }
        return rows;
    }


    private static Object jsonValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isFloatingPointNumber()) {
            return value.asDouble();
        }
        return value.asText();
    }
}
