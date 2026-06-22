package com.lee.archery.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Archery DatabaseMetaData，向 IDEA/DataGrip 暴露库、表、字段等只读补全信息。
 */
public final class ArcheryDatabaseMetaData implements InvocationHandler {
    private final ArcheryConnection connection;


    private ArcheryDatabaseMetaData(ArcheryConnection connection) {
        this.connection = connection;
    }


    public static DatabaseMetaData create(ArcheryConnection connection) {
        return ArcheryJdbcObjects.proxy(DatabaseMetaData.class, new ArcheryDatabaseMetaData(connection));
    }


    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (ArcheryJdbcObjects.isWrapperMethod(method)) {
            return ArcheryJdbcObjects.handleWrapper(proxy, method, args, this);
        }
        String name = method.getName();
        if ("getConnection".equals(name)) {
            return connection.proxy();
        }
        if ("getURL".equals(name)) {
            return connection.config().getBaseUrl();
        }
        if ("getUserName".equals(name)) {
            return connection.config().getUsername();
        }
        if ("getDatabaseProductName".equals(name)) {
            return "MySQL";
        }
        if ("getDatabaseProductVersion".equals(name)) {
            return "Archery";
        }
        if ("getDriverName".equals(name)) {
            return "Archery JDBC Driver";
        }
        if ("getDriverVersion".equals(name)) {
            return "0.1.7";
        }
        if ("getDriverMajorVersion".equals(name) || "getDatabaseMajorVersion".equals(name)
            || "getJDBCMajorVersion".equals(name)) {
            return 0;
        }
        if ("getDriverMinorVersion".equals(name) || "getDatabaseMinorVersion".equals(name)
            || "getJDBCMinorVersion".equals(name)) {
            return 1;
        }
        if ("isReadOnly".equals(name)) {
            return true;
        }
        if ("getIdentifierQuoteString".equals(name)) {
            return "`";
        }
        if ("getSearchStringEscape".equals(name)) {
            return "\\";
        }
        if ("getCatalogSeparator".equals(name)) {
            return ".";
        }
        if ("getCatalogTerm".equals(name)) {
            return "database";
        }
        if ("getSchemaTerm".equals(name)) {
            return "schema";
        }
        if (name.startsWith("supportsCatalogsIn")) {
            return false;
        }
        if (name.startsWith("supportsSchemasIn")) {
            return true;
        }
        if ("supportsCatalogsInTableDefinitions".equals(name)) {
            return false;
        }
        if ("supportsSchemasInTableDefinitions".equals(name)) {
            return true;
        }
        if ("getTableTypes".equals(name)) {
            return tableTypes();
        }
        if ("getCatalogs".equals(name)) {
            return catalogs();
        }
        if ("getSchemas".equals(name)) {
            return schemas(args);
        }
        if ("getTables".equals(name)) {
            return tables(args);
        }
        if ("getColumns".equals(name)) {
            return columns(args);
        }
        if ("getTypeInfo".equals(name)) {
            return typeInfo();
        }
        if ("getPrimaryKeys".equals(name) || "getImportedKeys".equals(name) || "getExportedKeys".equals(name)
            || "getIndexInfo".equals(name) || "getProcedures".equals(name) || "getProcedureColumns".equals(name)
            || "getFunctions".equals(name) || "getFunctionColumns".equals(name)) {
            return empty(columnsFor(name));
        }
        if ("supportsTransactions".equals(name) || "supportsBatchUpdates".equals(name)
            || "supportsStoredProcedures".equals(name)) {
            return false;
        }
        if ("getDefaultTransactionIsolation".equals(name)) {
            return Connection.TRANSACTION_NONE;
        }
        if ("supportsResultSetType".equals(name)) {
            int type = ((Number) args[0]).intValue();
            return type == ResultSet.TYPE_FORWARD_ONLY || type == ResultSet.TYPE_SCROLL_INSENSITIVE;
        }
        if ("supportsResultSetConcurrency".equals(name)) {
            int type = ((Number) args[0]).intValue();
            return (type == ResultSet.TYPE_FORWARD_ONLY || type == ResultSet.TYPE_SCROLL_INSENSITIVE)
                && ((Number) args[1]).intValue() == ResultSet.CONCUR_READ_ONLY;
        }
        if ("getSQLKeywords".equals(name) || "getNumericFunctions".equals(name) || "getStringFunctions".equals(name)
            || "getSystemFunctions".equals(name) || "getTimeDateFunctions".equals(name)) {
            return "";
        }
        return ArcheryJdbcObjects.defaultValue(method.getReturnType());
    }


    private ResultSet catalogs() throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : connection.schemas()) {
            rows.add(row("TABLE_CAT", name));
        }
        return resultSet(columns("TABLE_CAT"), rows);
    }


    private ResultSet schemas(Object[] args) throws SQLException {
        String schemaPattern = args != null && args.length > 1 ? stringArg(args[1], "%") : "%";
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String schema : connection.schemas()) {
            if (matches(schema, schemaPattern)) {
                rows.add(row("TABLE_SCHEM", schema, "TABLE_CATALOG", null));
            }
        }
        return resultSet(columns("TABLE_SCHEM", "TABLE_CATALOG"), rows);
    }


    private ResultSet tables(Object[] args) throws SQLException {
        String catalog = null;
        Object schemaArg = args != null && args.length > 1 ? args[1] : null;
        String tablePattern = args != null && args.length > 2 ? stringArg(args[2], "%") : "%";
        String[] types = args != null && args.length > 3 && args[3] instanceof String[] ? (String[]) args[3] : null;
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!acceptType(types, "TABLE")) {
            return resultSet(tableColumns(), rows);
        }
        for (String schema : schemaNames(schemaArg)) {
            for (String tableName : tableNames(schema, tablePattern)) {
                rows.add(row("TABLE_CAT", catalog, "TABLE_SCHEM", schema, "TABLE_NAME", tableName,
                    "TABLE_TYPE", "TABLE", "REMARKS", ""));
            }
        }
        return resultSet(tableColumns(), rows);
    }


    private ResultSet columns(Object[] args) throws SQLException {
        String catalog = null;
        Object schemaArg = args != null && args.length > 1 ? args[1] : null;
        String tablePattern = args != null && args.length > 2 ? stringArg(args[2], "%") : "%";
        String columnPattern = args != null && args.length > 3 ? stringArg(args[3], "%") : "%";
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String schema : schemaNames(schemaArg)) {
            for (String tableName : tableNames(schema, tablePattern)) {
                for (ArcheryTableDefinition.Column column : safeTableDefinition(schema, tableName).getColumns()) {
                    if (matches(column.getName(), columnPattern)) {
                        rows.add(columnRow(catalog, schema, tableName, column));
                    }
                }
            }
        }
        return resultSet(columnColumns(), rows);
    }


    private List<String> tableNames(String schema, String tablePattern) {
        List<String> names = new ArrayList<>();
        for (String tableName : safeTableNames(schema)) {
            if (matches(tableName, tablePattern)) {
                names.add(tableName);
            }
        }
        if (names.isEmpty() && tablePattern != null && !"%".equals(tablePattern)) {
            names.add(tablePattern);
        }
        return names;
    }


    private List<String> safeTableNames(String schema) {
        try {
            return connection.tableNames(schema);
        } catch (SQLException ignored) {
            return new ArrayList<>();
        }
    }


    private ArcheryTableDefinition safeTableDefinition(String schema, String tableName) {
        try {
            return connection.tableDefinition(schema, tableName);
        } catch (SQLException ignored) {
            return ArcheryTableDefinition.empty(tableName);
        }
    }


    private ResultSet tableTypes() {
        return resultSet(columns("TABLE_TYPE"), Arrays.asList(row("TABLE_TYPE", "TABLE")));
    }


    private ResultSet typeInfo() {
        List<ArcheryColumn> columns = columns("TYPE_NAME", "DATA_TYPE", "PRECISION", "LITERAL_PREFIX", "LITERAL_SUFFIX",
            "CREATE_PARAMS", "NULLABLE", "CASE_SENSITIVE", "SEARCHABLE", "UNSIGNED_ATTRIBUTE", "FIXED_PREC_SCALE",
            "AUTO_INCREMENT", "LOCAL_TYPE_NAME", "MINIMUM_SCALE", "MAXIMUM_SCALE", "SQL_DATA_TYPE", "SQL_DATETIME_SUB",
            "NUM_PREC_RADIX");
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("TYPE_NAME", "VARCHAR", "DATA_TYPE", Types.VARCHAR, "PRECISION", 255, "LITERAL_PREFIX", "'",
            "LITERAL_SUFFIX", "'", "NULLABLE", DatabaseMetaData.typeNullable, "CASE_SENSITIVE", true,
            "SEARCHABLE", DatabaseMetaData.typeSearchable, "NUM_PREC_RADIX", 10));
        rows.add(row("TYPE_NAME", "BIGINT", "DATA_TYPE", Types.BIGINT, "PRECISION", 19, "NULLABLE",
            DatabaseMetaData.typeNullable, "SEARCHABLE", DatabaseMetaData.typeSearchable, "NUM_PREC_RADIX", 10));
        rows.add(row("TYPE_NAME", "TIMESTAMP", "DATA_TYPE", Types.TIMESTAMP, "PRECISION", 26, "NULLABLE",
            DatabaseMetaData.typeNullable, "SEARCHABLE", DatabaseMetaData.typeSearchable, "NUM_PREC_RADIX", 10));
        return resultSet(columns, rows);
    }


    private Map<String, Object> columnRow(String catalog, String schema, String tableName, ArcheryTableDefinition.Column column) {
        return row("TABLE_CAT", catalog, "TABLE_SCHEM", schema, "TABLE_NAME", tableName, "COLUMN_NAME", column.getName(),
            "DATA_TYPE", column.jdbcType(), "TYPE_NAME", column.getType(), "COLUMN_SIZE", column.size(),
            "BUFFER_LENGTH", null, "DECIMAL_DIGITS", column.scale(), "NUM_PREC_RADIX", 10, "NULLABLE",
            column.isNullable() ? ResultSetMetaData.columnNullable : ResultSetMetaData.columnNoNulls,
            "REMARKS", column.getComment(), "COLUMN_DEF", column.getDefaultValue(), "SQL_DATA_TYPE", null,
            "SQL_DATETIME_SUB", null, "CHAR_OCTET_LENGTH", column.size(), "ORDINAL_POSITION", column.getOrdinal(),
            "IS_NULLABLE", column.isNullable() ? "YES" : "NO", "SCOPE_CATALOG", null, "SCOPE_SCHEMA", null,
            "SCOPE_TABLE", null, "SOURCE_DATA_TYPE", null, "IS_AUTOINCREMENT",
            column.getExtra().toLowerCase(java.util.Locale.ROOT).contains("auto_increment") ? "YES" : "NO",
            "IS_GENERATEDCOLUMN", "NO");
    }


    private ResultSet empty(List<ArcheryColumn> columns) {
        return resultSet(columns, new ArrayList<>());
    }


    private ResultSet resultSet(List<ArcheryColumn> columns, List<Map<String, Object>> rows) {
        return ArcheryResultSet.create(null, columns, rows);
    }


    private List<ArcheryColumn> tableColumns() {
        return columns("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS");
    }


    private List<ArcheryColumn> columnColumns() {
        return columns("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
            "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS",
            "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION",
            "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE", "IS_AUTOINCREMENT",
            "IS_GENERATEDCOLUMN");
    }


    private List<ArcheryColumn> columnsFor(String methodName) {
        if ("getPrimaryKeys".equals(methodName)) {
            return columns("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME");
        }
        return columns("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME");
    }


    private List<ArcheryColumn> columns(String... names) {
        List<ArcheryColumn> columns = new ArrayList<>();
        for (String name : names) {
            columns.add(new ArcheryColumn(name));
        }
        return columns;
    }


    private Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            row.put(String.valueOf(values[index]), values[index + 1]);
        }
        return row;
    }


    private List<String> schemaNames(Object schemaArg) throws SQLException {
        String schemaPattern = stringArg(schemaArg, null);
        List<String> names = new ArrayList<>();
        if (schemaPattern == null || schemaPattern.isEmpty()) {
            names.add(connection.currentSchema());
            return names;
        }
        if ("%".equals(schemaPattern)) {
            names.addAll(connection.schemas());
            return names;
        }
        for (String schema : connection.schemas()) {
            if (matches(schema, schemaPattern)) {
                names.add(schema);
            }
        }
        if (names.isEmpty()) {
            names.add(schemaPattern);
        }
        return names;
    }


    private String stringArg(Object arg, String defaultValue) {
        if (arg == null) {
            return defaultValue;
        }
        String value = String.valueOf(arg);
        return value.isEmpty() ? defaultValue : value;
    }


    private boolean matches(String value, String pattern) {
        if (pattern == null || "%".equals(pattern)) {
            return true;
        }
        return value.matches(toJdbcPatternRegex(pattern));
    }


    private String toJdbcPatternRegex(String pattern) {
        StringBuilder regex = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < pattern.length(); index++) {
            char current = pattern.charAt(index);
            if (escaped) {
                regex.append(Pattern.quote(String.valueOf(current)));
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '%') {
                regex.append(".*");
            } else if (current == '_') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(current)));
            }
        }
        if (escaped) {
            regex.append(Pattern.quote("\\"));
        }
        return regex.toString();
    }


    private boolean acceptType(String[] requestedTypes, String tableType) {
        if (requestedTypes == null || requestedTypes.length == 0) {
            return true;
        }
        for (String requestedType : requestedTypes) {
            if (tableType.equalsIgnoreCase(requestedType)) {
                return true;
            }
        }
        return false;
    }
}
