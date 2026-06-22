package com.lee.archery.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DataGrip metadata SQL 处理器，把 IDE 发起的 information_schema 查询转换成本地 JDBC metadata 结果集。
 */
final class ArcheryMetadataQueryHandler {
    private final ArcheryConnection connection;
    private final int resultSetType;
    private final int resultSetConcurrency;


    ArcheryMetadataQueryHandler(ArcheryConnection connection, int resultSetType, int resultSetConcurrency) {
        this.connection = connection;
        this.resultSetType = resultSetType;
        this.resultSetConcurrency = resultSetConcurrency;
    }


    /**
     * 尝试处理 IDE metadata 查询；非 metadata SQL 返回 null，交由普通查询链路执行。
     */
    ResultSet tryHandle(Statement statement, String sql) throws SQLException {
        String normalized = normalizedMetadataSql(sql);
        if (normalized.contains("information_schema.schemata")) {
            return schemataResult(statement);
        }
        if (normalized.contains("information_schema.tables") && normalized.contains("auto_increment")) {
            return autoIncrementResult(statement, sql);
        }
        if (normalized.contains("information_schema.columns") && normalized.contains("minor_name")) {
            return minorNamesResult(statement, sql);
        }
        if (normalized.contains("information_schema.tables") && normalized.contains("major_name")) {
            return majorNamesResult(statement, sql);
        }
        if (normalized.contains("information_schema.tables") && normalized.contains("table_collation")) {
            return tablesAndViewsResult(statement, sql);
        }
        if (normalized.contains("information_schema.columns")) {
            return columnsResult(statement, sql);
        }
        if (normalized.contains("information_schema.statistics")) {
            return statisticsResult(statement, sql);
        }
        if (normalized.contains("information_schema.table_constraints")) {
            return tableConstraintsResult(statement, sql);
        }
        if (normalized.contains("information_schema.key_column_usage")) {
            return keyColumnUsageResult(statement, sql);
        }
        if (normalized.contains("information_schema.")) {
            return emptyInformationSchemaResult(statement, sql);
        }
        if (isSystemMetadataQuery(normalized)) {
            return emptySystemMetadataResult(statement, sql);
        }
        return null;
    }


    private ResultSet schemataResult(Statement statement) throws SQLException {
        List<ArcheryColumn> columns = metadataColumns("schema_name", "default_collation_name");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String schema : connection.schemas()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("schema_name", schema);
            row.put("default_collation_name", "utf8mb4_general_ci");
            rows.add(row);
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery information_schema.schemata rows=" + rows.size());
        return resultSet(statement, columns, rows);
    }


    private ResultSet majorNamesResult(Statement statement, String sql) throws SQLException {
        List<ArcheryColumn> columns = metadataColumns("schema_name", "major_name", "major_kind", "routine_kind");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String schema : metadataSchemas(sql)) {
            for (String tableName : connection.tableNames(schema)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("schema_name", schema);
                row.put("major_name", tableName);
                row.put("major_kind", "T");
                row.put("routine_kind", null);
                rows.add(row);
            }
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery majorNames rows=" + rows.size());
        return resultSet(statement, columns, rows);
    }


    private ResultSet tablesAndViewsResult(Statement statement, String sql) throws SQLException {
        List<ArcheryColumn> columns = metadataColumns("table_name", "table_type", "table_comment", "engine",
            "table_collation", "create_options", "view_definer");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String schema : metadataSchemas(sql)) {
            for (String tableName : connection.tableNames(schema)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("table_name", tableName);
                row.put("table_type", "BASE TABLE");
                row.put("table_comment", "");
                row.put("engine", "");
                row.put("table_collation", "utf8mb4_general_ci");
                row.put("create_options", "");
                row.put("view_definer", null);
                rows.add(row);
            }
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery tablesAndViews rows=" + rows.size());
        return resultSet(statement, columns, rows);
    }


    private ResultSet minorNamesResult(Statement statement, String sql) throws SQLException {
        List<ArcheryColumn> columns = metadataColumns("schema_name", "major_name", "major_kind", "position",
            "direction", "minor_name");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MetadataTable table : metadataTables(sql)) {
            ArcheryTableDefinition definition = connection.tableDefinition(table.schema, table.name);
            for (ArcheryTableDefinition.Column column : definition.getColumns()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("schema_name", table.schema);
                row.put("major_name", table.name);
                row.put("major_kind", "T");
                row.put("position", column.getOrdinal());
                row.put("direction", null);
                row.put("minor_name", column.getName());
                rows.add(row);
            }
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery minorNames rows=" + rows.size());
        return resultSet(statement, columns, rows);
    }


    private ResultSet columnsResult(Statement statement, String sql) throws SQLException {
        List<ArcheryColumn> columns = metadataColumns("ordinal_position", "column_name", "column_type",
            "column_default", "generation_expression", "table_name", "column_comment", "is_nullable", "extra",
            "collation_name");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MetadataTable table : metadataTables(sql)) {
            ArcheryTableDefinition definition = connection.tableDefinition(table.schema, table.name);
            for (ArcheryTableDefinition.Column column : definition.getColumns()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ordinal_position", column.getOrdinal());
                row.put("column_name", column.getName());
                row.put("column_type", column.getType());
                row.put("column_default", column.getDefaultValue());
                row.put("generation_expression", "");
                row.put("table_name", table.name);
                row.put("column_comment", column.getComment());
                row.put("is_nullable", column.isNullable() ? "YES" : "NO");
                row.put("extra", column.getExtra());
                row.put("collation_name", column.jdbcType() == java.sql.Types.VARCHAR || column.jdbcType() == java.sql.Types.LONGVARCHAR
                    ? "utf8mb4_general_ci" : null);
                rows.add(row);
            }
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery columns rows=" + rows.size());
        return resultSet(statement, columns, rows);
    }


    private ResultSet autoIncrementResult(Statement statement, String sql) throws SQLException {
        List<ArcheryColumn> columns = metadataColumns("table_name", "auto_increment");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MetadataTable table : metadataTables(sql)) {
            Long autoIncrement = connection.tableDefinition(table.schema, table.name).getAutoIncrement();
            if (autoIncrement != null) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("table_name", table.name);
                row.put("auto_increment", autoIncrement);
                rows.add(row);
            }
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery autoIncrement rows=" + rows.size());
        return resultSet(statement, columns, rows);
    }


    private ResultSet statisticsResult(Statement statement, String sql) throws SQLException {
        List<ArcheryColumn> columns = metadataColumns("table_name", "index_name", "column_name", "non_unique",
            "seq_in_index", "collation", "cardinality", "index_type", "expression");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MetadataTable table : metadataTables(sql)) {
            for (ArcheryTableDefinition.Index index : connection.tableDefinition(table.schema, table.name).getIndexes()) {
                int seq = 1;
                for (String columnName : index.getColumns()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("table_name", table.name);
                    row.put("index_name", index.getName());
                    row.put("column_name", columnName);
                    row.put("non_unique", index.isUnique() ? 0 : 1);
                    row.put("seq_in_index", seq++);
                    row.put("collation", "A");
                    row.put("cardinality", null);
                    row.put("index_type", "BTREE");
                    row.put("expression", null);
                    rows.add(row);
                }
            }
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery statistics rows=" + rows.size());
        return resultSet(statement, columns, rows);
    }


    private ResultSet tableConstraintsResult(Statement statement, String sql) throws SQLException {
        List<ArcheryColumn> columns = metadataColumns("constraint_name", "table_name", "constraint_type", "enforced");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MetadataTable table : metadataTables(sql)) {
            for (ArcheryTableDefinition.Index index : connection.tableDefinition(table.schema, table.name).getIndexes()) {
                if (index.isUnique()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("constraint_name", index.getName());
                    row.put("table_name", table.name);
                    row.put("constraint_type", index.isPrimary() ? "PRIMARY KEY" : "UNIQUE");
                    row.put("enforced", "YES");
                    rows.add(row);
                }
            }
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery tableConstraints rows=" + rows.size());
        return resultSet(statement, columns, rows);
    }


    private ResultSet keyColumnUsageResult(Statement statement, String sql) throws SQLException {
        List<ArcheryColumn> columns = metadataColumns("constraint_name", "table_name", "column_name", "ordinal_position",
            "referenced_table_schema", "referenced_table_name", "referenced_column_name");
        List<Map<String, Object>> rows = new ArrayList<>();
        if (requiresReferencedColumns(sql)) {
            ArcheryDebugLog.write(connection.config(), "metadataQuery keyColumnUsage rows=0, reason=foreignKeysUnsupported");
            return resultSet(statement, columns, rows);
        }
        for (MetadataTable table : metadataTables(sql)) {
            for (ArcheryTableDefinition.Index index : connection.tableDefinition(table.schema, table.name).getIndexes()) {
                if (index.isUnique()) {
                    int seq = 1;
                    for (String columnName : index.getColumns()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("constraint_name", index.getName());
                        row.put("table_name", table.name);
                        row.put("column_name", columnName);
                        row.put("ordinal_position", seq++);
                        row.put("referenced_table_schema", null);
                        row.put("referenced_table_name", null);
                        row.put("referenced_column_name", null);
                        rows.add(row);
                    }
                }
            }
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery keyColumnUsage rows=" + rows.size());
        return resultSet(statement, columns, rows);
    }


    /**
     * 当前表结构解析只覆盖主键和唯一索引，外键 metadata 查询不能返回 referenced 字段为空的伪外键行。
     */
    private boolean requiresReferencedColumns(String sql) {
        String normalized = normalizedMetadataSql(sql);
        return normalized.contains("referenced_column_name is not null")
            || normalized.contains("referenced_table_name is not null")
            || normalized.contains("referenced_table_schema is not null");
    }


    private ResultSet emptyInformationSchemaResult(Statement statement, String sql) {
        List<ArcheryColumn> columns = metadataColumns(selectedColumnNames(sql));
        ArcheryDebugLog.write(connection.config(), "metadataQuery emptyInformationSchema columns=" + columns.size());
        return resultSet(statement, columns, new ArrayList<Map<String, Object>>());
    }


    private ResultSet emptySystemMetadataResult(Statement statement, String sql) {
        List<ArcheryColumn> columns = metadataColumns(selectedSystemColumnNames(sql));
        ArcheryDebugLog.write(connection.config(), "metadataQuery emptySystemMetadata columns=" + columns.size());
        return resultSet(statement, columns, new ArrayList<Map<String, Object>>());
    }


    private ResultSet resultSet(Statement statement, List<ArcheryColumn> columns, List<Map<String, Object>> rows) {
        return ArcheryResultSet.create(statement, connection.config(), columns, rows, resultSetType, ResultSet.CONCUR_READ_ONLY);
    }


    private boolean isSystemMetadataQuery(String normalized) {
        return normalized.contains("from mysql.") || normalized.contains("from performance_schema.")
            || normalized.contains("from sys.") || normalized.contains("from information_schema")
            || normalized.startsWith("-- retrieve users") || normalized.startsWith("-- retrieve routine grants");
    }


    private String[] selectedSystemColumnNames(String sql) {
        String normalized = normalizedMetadataSql(sql);
        if (normalized.contains("mysql.user") || normalized.startsWith("-- retrieve users")) {
            return new String[]{"Host", "User", "ssl_type", "ssl_cipher", "x509_issuer", "x509_subject",
                "max_questions", "max_updates", "max_connections", "max_user_connections", "plugin", "is_role"};
        }
        if (normalized.contains("procs_priv") || normalized.startsWith("-- retrieve routine grants")) {
            return new String[]{"Host", "User", "Routine_name", "Proc_priv", "is_proc"};
        }
        return selectedColumnNames(sql);
    }


    private String[] selectedColumnNames(String sql) {
        String normalized = normalizedMetadataSql(sql);
        if (normalized.contains("auto_increment")) {
            return new String[]{"table_name", "auto_increment"};
        }
        if (normalized.contains("schema_privileges")) {
            return new String[]{"grantee", "table_schema", "privilege_type", "is_grantable"};
        }
        if (normalized.contains("user_privileges")) {
            return new String[]{"grantee", "privilege_type", "is_grantable"};
        }
        if (normalized.contains("statistics")) {
            return new String[]{"table_name", "index_name", "column_name", "non_unique", "seq_in_index", "index_type"};
        }
        if (normalized.contains("table_constraints")) {
            return new String[]{"constraint_schema", "table_name", "constraint_name", "constraint_type"};
        }
        if (normalized.contains("key_column_usage")) {
            return new String[]{"constraint_schema", "table_name", "column_name", "constraint_name", "ordinal_position"};
        }
        if (normalized.contains("parameters")) {
            return new String[]{"specific_schema", "specific_name", "parameter_name", "parameter_mode", "data_type"};
        }
        if (normalized.contains("views")) {
            return new String[]{"table_name", "view_definition", "definer"};
        }
        if (normalized.contains("schema_name") && normalized.contains("default_collation_name")) {
            return new String[]{"schema_name", "default_collation_name"};
        }
        if (normalized.contains("routine_name")) {
            return new String[]{"routine_name", "routine_type"};
        }
        if (normalized.contains("trigger_name")) {
            return new String[]{"trigger_name", "event_manipulation", "event_object_table"};
        }
        if (normalized.contains("event_name")) {
            return new String[]{"event_name", "event_type", "status"};
        }
        if (normalized.contains("table_name")) {
            return new String[]{"table_name"};
        }
        return new String[]{"value"};
    }


    private List<MetadataTable> metadataTables(String sql) throws SQLException {
        List<MetadataTable> tables = new ArrayList<>();
        for (String schema : metadataSchemas(sql)) {
            List<String> requestedTables = requestedTableNames(sql);
            if (requestedTables.isEmpty()) {
                requestedTables = connection.tableNames(schema);
            }
            for (String tableName : requestedTables) {
                tables.add(new MetadataTable(schema, tableName));
            }
        }
        return tables;
    }


    private List<String> metadataSchemas(String sql) throws SQLException {
        List<String> schemas = requestedSchemaNames(sql);
        if (!schemas.isEmpty()) {
            return schemas;
        }
        List<String> current = new ArrayList<>();
        current.add(connection.currentSchema());
        return current;
    }


    private List<String> requestedSchemaNames(String sql) {
        List<String> names = requestedNames(sql, "table_schema");
        for (String name : requestedNames(sql, "schema_name")) {
            addUnique(names, name);
        }
        return names;
    }


    private List<String> requestedTableNames(String sql) {
        return requestedNames(sql, "table_name");
    }


    private List<String> requestedNames(String sql, String columnName) {
        List<String> names = new ArrayList<>();
        String searchable = sql == null ? "" : sql.replace('`', ' ');
        Matcher equalsMatcher = Pattern
            .compile("(?:[a-z0-9_]+\\.)?" + columnName + "\\s*=\\s*'([^']+)'", Pattern.CASE_INSENSITIVE)
            .matcher(searchable);
        while (equalsMatcher.find()) {
            addUnique(names, equalsMatcher.group(1));
        }
        Matcher inMatcher = Pattern
            .compile("(?:[a-z0-9_]+\\.)?" + columnName + "\\s+in\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE)
            .matcher(searchable);
        while (inMatcher.find()) {
            Matcher valueMatcher = Pattern.compile("'([^']+)'").matcher(inMatcher.group(1));
            while (valueMatcher.find()) {
                addUnique(names, valueMatcher.group(1));
            }
        }
        return names;
    }


    private void addUnique(List<String> values, String value) {
        if (value != null && !values.contains(value)) {
            values.add(value);
        }
    }


    private String normalizedMetadataSql(String sql) {
        return sql == null ? "" : sql.toLowerCase(Locale.ROOT).replace("`", "");
    }


    private List<ArcheryColumn> metadataColumns(String... names) {
        List<ArcheryColumn> columns = new ArrayList<>();
        for (String name : names) {
            columns.add(new ArcheryColumn(name));
        }
        return columns;
    }


    /**
     * Metadata 查询解析出的 schema/table 组合，用于按 database 隔离表结构缓存。
     */
    private static final class MetadataTable {
        private final String schema;
        private final String name;


        private MetadataTable(String schema, String name) {
            this.schema = schema;
            this.name = name;
        }
    }
}
