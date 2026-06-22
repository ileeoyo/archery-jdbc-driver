package com.lee.archery.jdbc;

import com.lee.archery.client.ArcheryHttpClient;
import com.lee.archery.config.ArcheryJdbcConfig;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Archery JDBC Connection，维护只读连接状态、HTTP 会话和 metadata 入口。
 */
public final class ArcheryConnection implements InvocationHandler {
    private final ArcheryJdbcConfig config;
    private final ArcheryHttpClient client;
    private final Connection proxy;
    private boolean closed;
    private boolean autoCommit = true;
    private String catalog;
    private String schema;
    private final java.util.Map<String, java.util.List<String>> tableNamesBySchema = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, java.util.List<String>> columnNamesByTable = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, ArcheryTableDefinition> tableDefinitions = new java.util.LinkedHashMap<>();


    private ArcheryConnection(ArcheryJdbcConfig config) {
        this.config = config;
        this.client = new ArcheryHttpClient(config);
        this.catalog = config.getDbName();
        this.schema = config.getSchemaName() == null || config.getSchemaName().isEmpty()
            ? config.getDbName() : config.getSchemaName();
        this.proxy = ArcheryJdbcObjects.proxy(Connection.class, this);
    }


    public static Connection create(String url, Properties info) throws SQLException {
        ArcheryJdbcConfig config = ArcheryJdbcConfig.parse(url, info);
        ArcheryConnection connection = new ArcheryConnection(config);
        connection.client.login();
        return connection.proxy;
    }


    Connection proxy() {
        return proxy;
    }


    ArcheryJdbcConfig config() {
        return config;
    }


    ArcheryHttpClient client() {
        return client;
    }


    String currentDatabase() throws SQLException {
        if (catalog != null && !catalog.isEmpty()) {
            return catalog;
        }
        if (config.getDbName() != null && !config.getDbName().isEmpty()) {
            catalog = config.getDbName();
            return catalog;
        }
        String selectedSchema = currentSchema();
        catalog = selectedSchema;
        return selectedSchema;
    }


    String currentSchema() throws SQLException {
        if (schema != null && !schema.isEmpty()) {
            return schema;
        }
        if (config.getSchemaName() != null && !config.getSchemaName().isEmpty()) {
            schema = config.getSchemaName();
            return schema;
        }
        if (config.getDbName() != null && !config.getDbName().isEmpty()) {
            schema = config.getDbName();
            return schema;
        }
        java.util.List<String> schemas = schemas();
        if (!schemas.isEmpty()) {
            schema = schemas.get(0);
            return schema;
        }
        throw new SQLException("No Archery database/schema is selected");
    }


    java.util.List<String> schemas() throws SQLException {
        java.util.List<String> names = new java.util.ArrayList<>(client.resource("database", "", "", "").getNames());
        if (names.isEmpty() && config.getDbName() != null && !config.getDbName().isEmpty()) {
            names.add(config.getDbName());
        }
        return names;
    }


    java.util.List<String> tableNames() throws SQLException {
        return tableNames(currentSchema());
    }


    java.util.List<String> tableNames(String schemaName) throws SQLException {
        String selectedSchema = schemaName == null || schemaName.isEmpty() ? currentSchema() : schemaName;
        java.util.List<String> tableNames = tableNamesBySchema.get(selectedSchema);
        if (tableNames == null) {
            tableNames = new java.util.ArrayList<>(client.resource("table", currentDatabase(), selectedSchema, "").getNames());
            tableNamesBySchema.put(selectedSchema, tableNames);
        }
        return tableNames;
    }


    java.util.List<String> columnNames(String tableName) throws SQLException {
        return columnNames(currentSchema(), tableName);
    }


    java.util.List<String> columnNames(String schemaName, String tableName) throws SQLException {
        String selectedSchema = schemaName == null || schemaName.isEmpty() ? currentSchema() : schemaName;
        String key = selectedSchema + "." + tableName;
        java.util.List<String> columnNames = columnNamesByTable.get(key);
        if (columnNames == null) {
            columnNames = new java.util.ArrayList<>();
            for (ArcheryTableDefinition.Column column : tableDefinition(selectedSchema, tableName).getColumns()) {
                columnNames.add(column.getName());
            }
            if (columnNames.isEmpty()) {
                columnNames.addAll(client.resource("column", currentDatabase(), selectedSchema, tableName).getNames());
            }
            columnNamesByTable.put(key, columnNames);
        }
        return columnNames;
    }


    ArcheryTableDefinition tableDefinition(String tableName) throws SQLException {
        return tableDefinition(currentSchema(), tableName);
    }


    ArcheryTableDefinition tableDefinition(String schemaName, String tableName) throws SQLException {
        String selectedSchema = schemaName == null || schemaName.isEmpty() ? currentSchema() : schemaName;
        String key = selectedSchema + "." + tableName;
        ArcheryTableDefinition definition = tableDefinitions.get(key);
        if (definition == null) {
            definition = ArcheryTableDefinition.fromDescribe(tableName, client.describeTable(currentDatabase(), selectedSchema, tableName));
            tableDefinitions.put(key, definition);
        }
        return definition;
    }


    void useCatalog(String catalog) {
        this.catalog = catalog;
        this.schema = catalog;
    }


    private void useSchema(String schema) {
        this.schema = schema;
    }


    private void ensureSupportedResultSet(int type, int concurrency) throws SQLException {
        boolean supportedType = type == java.sql.ResultSet.TYPE_FORWARD_ONLY || type == java.sql.ResultSet.TYPE_SCROLL_INSENSITIVE;
        if (!supportedType || concurrency != java.sql.ResultSet.CONCUR_READ_ONLY) {
            throw ArcheryJdbcObjects.unsupported("ResultSet type/concurrency");
        }
    }


    void ensureOpen() throws SQLException {
        if (closed) {
            throw ArcheryJdbcObjects.closed("Connection");
        }
    }


    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (ArcheryJdbcObjects.isWrapperMethod(method)) {
            return ArcheryJdbcObjects.handleWrapper(proxy, method, args, this);
        }
        String name = method.getName();
        if ("close".equals(name)) {
            closed = true;
            client.close();
            return null;
        }
        if ("isClosed".equals(name)) {
            return closed;
        }
        if ("isValid".equals(name)) {
            return !closed;
        }
        ensureOpen();
        ArcheryDebugLog.write(config, "Connection." + name + ArcheryJdbcObjects.methodArgs(args));
        if ("createStatement".equals(name)) {
            if (args != null && args.length >= 2) {
                int type = ((Number) args[0]).intValue();
                int concurrency = ((Number) args[1]).intValue();
                ensureSupportedResultSet(type, concurrency);
                return ArcheryStatement.create(this, type, concurrency);
            }
            return ArcheryStatement.create(this);
        }
        if ("prepareStatement".equals(name)) {
            if (args != null && args.length >= 3 && args[1] instanceof Number && args[2] instanceof Number) {
                int type = ((Number) args[1]).intValue();
                int concurrency = ((Number) args[2]).intValue();
                ensureSupportedResultSet(type, concurrency);
                return ArcheryPreparedStatement.create(this, String.valueOf(args[0]), type, concurrency);
            }
            return ArcheryPreparedStatement.create(this, String.valueOf(args[0]));
        }
        if ("getMetaData".equals(name)) {
            return ArcheryDatabaseMetaData.create(this);
        }
        if ("setReadOnly".equals(name)) {
            return null;
        }
        if ("isReadOnly".equals(name)) {
            return true;
        }
        if ("setAutoCommit".equals(name)) {
            autoCommit = (Boolean) args[0];
            return null;
        }
        if ("getAutoCommit".equals(name)) {
            return autoCommit;
        }
        if ("commit".equals(name) || "rollback".equals(name)) {
            if (autoCommit) {
                throw new SQLException("Cannot call " + name + " when autoCommit is true");
            }
            return null;
        }
        if ("getCatalog".equals(name)) {
            return catalog == null || catalog.isEmpty() ? currentSchema() : catalog;
        }
        if ("setCatalog".equals(name)) {
            useCatalog(args == null || args[0] == null ? null : String.valueOf(args[0]));
            return null;
        }
        if ("getSchema".equals(name)) {
            return schema == null || schema.isEmpty() ? currentSchema() : schema;
        }
        if ("setSchema".equals(name)) {
            useSchema(args == null || args[0] == null ? null : String.valueOf(args[0]));
            return null;
        }
        if ("nativeSQL".equals(name)) {
            return args[0];
        }
        if ("getTransactionIsolation".equals(name)) {
            return Connection.TRANSACTION_NONE;
        }
        if ("setTransactionIsolation".equals(name)) {
            if (((Number) args[0]).intValue() != Connection.TRANSACTION_NONE) {
                throw ArcheryJdbcObjects.unsupported("Transactions");
            }
            return null;
        }
        if ("getWarnings".equals(name)) {
            return null;
        }
        if ("clearWarnings".equals(name) || "abort".equals(name) || "setNetworkTimeout".equals(name)) {
            return null;
        }
        if ("getNetworkTimeout".equals(name)) {
            return config.getReadTimeoutMillis();
        }
        if ("createClob".equals(name) || "createBlob".equals(name) || "createNClob".equals(name)
            || "createSQLXML".equals(name) || "createArrayOf".equals(name) || "createStruct".equals(name)
            || "prepareCall".equals(name) || "setSavepoint".equals(name)) {
            throw ArcheryJdbcObjects.unsupported(name);
        }
        return ArcheryJdbcObjects.defaultValue(method.getReturnType());
    }

}
