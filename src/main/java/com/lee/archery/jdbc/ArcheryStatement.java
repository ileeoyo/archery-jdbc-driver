package com.lee.archery.jdbc;

import com.lee.archery.client.ArcheryQueryRequest;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;

/**
 * Archery JDBC Statement，只允许执行只读 SQL，避免绕过 Archery 工单体系执行变更语句。
 */
public final class ArcheryStatement implements InvocationHandler {
    private final ArcheryConnection connection;
    private final int resultSetType;
    private final int resultSetConcurrency;
    private final ArcheryMetadataQueryHandler metadataQueryHandler;
    private boolean closed;
    private ResultSet currentResultSet;
    private int queryTimeout;
    private int maxRows;


    private ArcheryStatement(ArcheryConnection connection, int resultSetType, int resultSetConcurrency) {
        this.connection = connection;
        this.resultSetType = resultSetType;
        this.resultSetConcurrency = resultSetConcurrency;
        this.metadataQueryHandler = new ArcheryMetadataQueryHandler(connection, resultSetType, resultSetConcurrency);
    }


    public static Statement create(ArcheryConnection connection) {
        return create(connection, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    }


    public static Statement create(ArcheryConnection connection, int resultSetType, int resultSetConcurrency) {
        return ArcheryJdbcObjects.proxy(Statement.class, new ArcheryStatement(connection, resultSetType, resultSetConcurrency));
    }


    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (ArcheryJdbcObjects.isWrapperMethod(method)) {
            return ArcheryJdbcObjects.handleWrapper(proxy, method, args, this);
        }
        String name = method.getName();
        if ("close".equals(name)) {
            closed = true;
            currentResultSet = null;
            return null;
        }
        if ("isClosed".equals(name)) {
            return closed;
        }
        ensureOpen();
        ArcheryDebugLog.write(connection.config(), "Statement." + name + ArcheryJdbcObjects.methodArgs(args));
        if ("executeQuery".equals(name)) {
            return executeQuery((Statement) proxy, String.valueOf(args[0]));
        }
        if ("execute".equals(name)) {
            return execute((Statement) proxy, String.valueOf(args[0]));
        }
        if ("executeUpdate".equals(name) || "executeLargeUpdate".equals(name)) {
            throw ArcheryJdbcObjects.unsupported("Update SQL");
        }
        if ("getResultSet".equals(name)) {
            return currentResultSet;
        }
        if ("getConnection".equals(name)) {
            return connection.proxy();
        }
        if ("getUpdateCount".equals(name)) {
            return -1;
        }
        if ("getLargeUpdateCount".equals(name)) {
            return -1L;
        }
        if ("getResultSetType".equals(name)) {
            return resultSetType;
        }
        if ("getResultSetConcurrency".equals(name)) {
            return resultSetConcurrency;
        }
        if ("getResultSetHoldability".equals(name)) {
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
        if ("getMoreResults".equals(name)) {
            return false;
        }
        if ("setQueryTimeout".equals(name)) {
            int timeout = ((Number) args[0]).intValue();
            if (timeout < 0) {
                throw new SQLException("Query timeout must be non-negative");
            }
            queryTimeout = timeout;
            return null;
        }
        if ("getQueryTimeout".equals(name)) {
            return queryTimeout;
        }
        if ("setMaxRows".equals(name)) {
            int rows = ((Number) args[0]).intValue();
            if (rows < 0) {
                throw new SQLException("Max rows must be non-negative");
            }
            maxRows = rows;
            return null;
        }
        if ("getMaxRows".equals(name)) {
            return maxRows;
        }
        if ("cancel".equals(name)) {
            throw ArcheryJdbcObjects.unsupported("Query cancel");
        }
        if ("clearWarnings".equals(name) || "clearBatch".equals(name) || "closeOnCompletion".equals(name)) {
            return null;
        }
        if ("getWarnings".equals(name)) {
            return null;
        }
        if ("isCloseOnCompletion".equals(name)) {
            return false;
        }
        if ("addBatch".equals(name) || "executeBatch".equals(name) || "executeLargeBatch".equals(name)) {
            throw ArcheryJdbcObjects.unsupported("Batch SQL");
        }
        return ArcheryJdbcObjects.defaultValue(method.getReturnType());
    }


    private boolean execute(Statement statement, String sql) throws SQLException {
        if (executeUse(sql)) {
            currentResultSet = null;
            return false;
        }
        currentResultSet = executeQuery(statement, sql);
        return true;
    }


    private ResultSet executeQuery(Statement statement, String sql) throws SQLException {
        connection.ensureOpen();
        if (executeUse(sql)) {
            throw new SQLException("USE does not produce a ResultSet");
        }
        if (!ArcheryJdbcObjects.isReadOnlySql(sql)) {
            throw new SQLFeatureNotSupportedException("Only SELECT/SHOW/DESC/EXPLAIN SQL is supported");
        }
        ResultSet metadataResultSet = metadataQueryHandler.tryHandle(statement, sql);
        if (metadataResultSet != null) {
            currentResultSet = metadataResultSet;
            return currentResultSet;
        }
        String database = connection.currentDatabase();
        String schema = connection.currentSchema();
        currentResultSet = ArcheryResultSet.create(statement, connection.config(),
            connection.client().query(new ArcheryQueryRequest(sql, "", database, schema, maxRows)), resultSetType, resultSetConcurrency);
        ArcheryDebugLog.write(connection.config(), "executeQuery type=" + resultSetType + ", concurrency="
            + resultSetConcurrency + ", sql=" + ArcheryJdbcObjects.abbreviate(sql, 500));
        return currentResultSet;
    }


    private boolean executeUse(String sql) {
        String normalized = sql == null ? "" : sql.trim();
        if (!normalized.toLowerCase(java.util.Locale.ROOT).startsWith("use ")) {
            return false;
        }
        String catalog = normalized.substring(4).trim();
        if (catalog.endsWith(";")) {
            catalog = catalog.substring(0, catalog.length() - 1).trim();
        }
        catalog = catalog.replace("`", "");
        if (!catalog.isEmpty()) {
            connection.useCatalog(catalog);
        }
        return true;
    }


    private void ensureOpen() throws SQLException {
        if (closed) {
            throw ArcheryJdbcObjects.closed("Statement");
        }
    }
}
