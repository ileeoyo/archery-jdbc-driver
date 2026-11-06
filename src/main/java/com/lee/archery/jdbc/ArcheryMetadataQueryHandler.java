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
    /**
     * 视图没有建表语句，无法从 DDL 解析字段，用占位类型暴露列名供 IDE 展开。
     */
    private static final String VIEW_COLUMN_TYPE = "varchar(0)";

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
        if (isLocalSystemProbeQuery(sql, normalized)) {
            return localSystemProbeResult(statement, sql);
        }
        // metadata 查询整体耗时是 IDE 刷新慢的主要嫌疑，这里记录入口到出口的总耗时
        long startedAt = System.currentTimeMillis();
        try {
            ResultSet result = handleMetadataQuery(statement, sql, normalized);
            if (result != null) {
                ArcheryDebugLog.write(connection.config(), "metadataQuery total elapsedMs="
                    + (System.currentTimeMillis() - startedAt) + ", sql=" + abbreviate(sql));
            }
            return result;
        } catch (SQLException e) {
            ArcheryDebugLog.write(connection.config(), "metadataQuery failed elapsedMs="
                + (System.currentTimeMillis() - startedAt) + ", sql=" + abbreviate(sql) + ", error=" + e.getMessage());
            throw e;
        }
    }


    /**
     * 日志里的 SQL 只保留前 200 字符，避免 describe 触发的大量长 SQL 撑爆日志。
     */
    private String abbreviate(String sql) {
        String value = sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
        return value.length() > 200 ? value.substring(0, 200) + "..." : value;
    }


    private ResultSet handleMetadataQuery(Statement statement, String sql, String normalized) throws SQLException {
        if (normalized.contains("information_schema.schemata")) {
            return schemataResult(statement, sql);
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
        if (normalized.contains("information_schema.collations")) {
            return collationsResult(statement, sql);
        }
        if (normalized.contains("information_schema.views") && normalized.contains("view_definition")) {
            return viewsResult(statement, sql);
        }
        if (normalized.contains("information_schema.partitions")) {
            return partitionsResult(statement, sql);
        }
        if (normalized.contains("information_schema.")) {
            return emptyInformationSchemaResult(statement, sql);
        }
        if (isSystemMetadataQuery(normalized)) {
            return emptySystemMetadataResult(statement, sql);
        }
        return null;
    }


    private ResultSet schemataResult(Statement statement, String sql) throws SQLException {
        List<ArcheryColumn> columns = resultColumns(sql, "schema_name", "default_collation_name");
        List<Map<String, Object>> rows = new ArrayList<>();
        String currentSchema = connection.currentSchema();
        for (String schema : connection.schemas()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("schema_name", schema);
            // 库级排序规则只能靠该库的表结构推断；非当前库不额外请求，避免一次 schemata 触发全库 describe
            // 推断不出来时保持 null，不填默认值，避免 IDE 拿到与实际不符的排序规则
            String collation = schema.equals(currentSchema) ? connection.defaultCollation(schema) : "";
            row.put("default_collation_name", collation.isEmpty() ? null : collation);
            rows.add(row);
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery information_schema.schemata rows=" + rows.size());
        return resultSet(statement, columns, rows);
    }


    private ResultSet majorNamesResult(Statement statement, String sql) throws SQLException {
        List<ArcheryColumn> columns = resultColumns(sql, "schema_name", "major_name", "major_kind", "routine_kind");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String schema : metadataSchemas(sql)) {
            for (String tableName : requestedOrAllTableNames(schema, sql)) {
                ArcheryTableDefinition definition = connection.tableDefinition(schema, tableName);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("schema_name", schema);
                row.put("major_name", tableName);
                // 视图必须以 V 暴露，否则 IDE 会把它当普通表，无法生成 CREATE VIEW
                row.put("major_kind", definition.isView() ? "V" : "T");
                row.put("routine_kind", null);
                rows.add(row);
            }
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery majorNames rows=" + rows.size());
        return resultSet(statement, columns, rows);
    }


    private ResultSet tablesAndViewsResult(Statement statement, String sql) throws SQLException {
        List<ArcheryColumn> columns = resultColumns(sql, "table_name", "table_type", "table_comment", "engine",
            "table_collation", "create_options", "view_definer");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String schema : metadataSchemas(sql)) {
            for (String tableName : requestedOrAllTableNames(schema, sql)) {
                ArcheryTableDefinition definition = connection.tableDefinition(schema, tableName);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("table_name", tableName);
                row.put("table_type", definition.getTableType());
                row.put("table_comment", definition.getTableComment());
                row.put("engine", definition.getEngine());
                row.put("table_collation", definition.getCollation());
                row.put("create_options", definition.getCreateOptions());
                row.put("view_definer", null);
                rows.add(row);
            }
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery tablesAndViews rows=" + rows.size());
        return resultSet(statement, columns, rows);
    }


    private ResultSet minorNamesResult(Statement statement, String sql) throws SQLException {
        List<ArcheryColumn> columns = resultColumns(sql, "schema_name", "major_name", "major_kind", "position",
            "direction", "minor_name");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MetadataTable table : metadataTables(sql)) {
            ArcheryTableDefinition definition = connection.tableDefinition(table.schema, table.name);
            for (ArcheryTableDefinition.Column column : tableColumns(definition, table)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("schema_name", table.schema);
                row.put("major_name", table.name);
                row.put("major_kind", definition.isView() ? "V" : "T");
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
        List<ArcheryColumn> columns = resultColumns(sql, "ordinal_position", "column_name", "column_type",
            "column_default", "generation_expression", "table_name", "column_comment", "is_nullable", "extra",
            "collation_name");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MetadataTable table : metadataTables(sql)) {
            ArcheryTableDefinition definition = connection.tableDefinition(table.schema, table.name);
            for (ArcheryTableDefinition.Column column : tableColumns(definition, table)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ordinal_position", column.getOrdinal());
                row.put("column_name", column.getName());
                row.put("column_type", column.getType());
                row.put("column_default", column.getDefaultValue());
                row.put("generation_expression", column.getGenerationExpression());
                row.put("table_name", table.name);
                row.put("column_comment", column.getComment());
                row.put("is_nullable", column.isNullable() ? "YES" : "NO");
                row.put("extra", column.getExtra());
                row.put("collation_name", columnCollation(definition, column));
                rows.add(row);
            }
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery columns rows=" + rows.size());
        return resultSet(statement, columns, rows);
    }


    /**
     * 视图的 SHOW CREATE VIEW 不含字段定义，列名改由 resource 接口补充，避免 IDE 展开视图时无列可显示。
     */
    private List<ArcheryTableDefinition.Column> tableColumns(ArcheryTableDefinition definition, MetadataTable table)
        throws SQLException {
        if (!definition.isView()) {
            return definition.getColumns();
        }
        List<ArcheryTableDefinition.Column> columns = new ArrayList<>();
        int ordinal = 1;
        for (String columnName : connection.columnNames(table.schema, table.name)) {
            columns.add(ArcheryTableDefinition.viewColumn(columnName, ordinal++));
        }
        return columns;
    }


    /**
     * 排序规则只取实际声明值：先看字段级 COLLATE，再看表级 COLLATE。
     * 都没有时返回 null，不按字符集猜默认排序规则，避免生成与实际不符的 DDL。
     * json 列在 MySQL 里没有排序规则，必须先排除，否则 IDE 会给 json 列生成 COLLATE。
     */
    private String columnCollation(ArcheryTableDefinition definition, ArcheryTableDefinition.Column column) {
        if (column.getCollation() != null && !column.getCollation().isEmpty()) {
            return column.getCollation();
        }
        if (!column.isCharacterType()) {
            return null;
        }
        String tableCollation = definition.getCollation();
        return tableCollation.isEmpty() ? null : tableCollation;
    }


    private ResultSet autoIncrementResult(Statement statement, String sql) throws SQLException {
        List<ArcheryColumn> columns = resultColumns(sql, "table_name", "auto_increment");
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
        List<ArcheryColumn> columns = resultColumns(sql, "table_name", "index_name", "index_comment", "index_type",
            "non_unique", "column_name", "sub_part", "collation", "cardinality", "expression", "seq_in_index");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MetadataTable table : metadataTables(sql)) {
            for (ArcheryTableDefinition.Index index : connection.tableDefinition(table.schema, table.name).getIndexes()) {
                int seq = 1;
                for (ArcheryTableDefinition.IndexColumn column : index.getColumns()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("table_name", table.name);
                    row.put("index_name", index.getName());
                    row.put("index_comment", index.getComment());
                    row.put("index_type", index.indexType());
                    row.put("non_unique", index.isUnique() ? 0 : 1);
                    row.put("column_name", column.getName());
                    row.put("sub_part", column.getSubPart() > 0 ? column.getSubPart() : null);
                    row.put("collation", column.getDirection());
                    row.put("cardinality", null);
                    row.put("expression", column.getExpression());
                    row.put("seq_in_index", seq++);
                    rows.add(row);
                }
            }
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery statistics rows=" + rows.size());
        return resultSet(statement, columns, rows);
    }


    private ResultSet tableConstraintsResult(Statement statement, String sql) throws SQLException {
        List<ArcheryColumn> columns = resultColumns(sql, "constraint_name", "constraint_schema", "table_name",
            "constraint_type", "enforced");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MetadataTable table : metadataTables(sql)) {
            ArcheryTableDefinition definition = connection.tableDefinition(table.schema, table.name);
            for (ArcheryTableDefinition.Index index : definition.getIndexes()) {
                if (index.isUnique()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("constraint_name", index.getName());
                    row.put("constraint_schema", table.schema);
                    row.put("table_name", table.name);
                    row.put("constraint_type", index.isPrimary() ? "PRIMARY KEY" : "UNIQUE");
                    row.put("enforced", "YES");
                    rows.add(row);
                }
            }
            for (ArcheryTableDefinition.ForeignKey foreignKey : definition.getForeignKeys()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("constraint_name", foreignKey.getName());
                row.put("constraint_schema", table.schema);
                row.put("table_name", table.name);
                row.put("constraint_type", "FOREIGN KEY");
                row.put("enforced", "YES");
                rows.add(row);
            }
            for (ArcheryTableDefinition.CheckConstraint check : definition.getCheckConstraints()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("constraint_name", check.getName());
                row.put("constraint_schema", table.schema);
                row.put("table_name", table.name);
                row.put("constraint_type", "CHECK");
                row.put("enforced", "YES");
                rows.add(row);
            }
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery tableConstraints rows=" + rows.size());
        return resultSet(statement, columns, rows);
    }


    private ResultSet keyColumnUsageResult(Statement statement, String sql) throws SQLException {
        List<ArcheryColumn> columns = resultColumns(sql, "constraint_name", "constraint_schema", "table_name",
            "column_name", "ordinal_position", "referenced_table_schema", "referenced_table_name",
            "referenced_column_name");
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean referencedOnly = requiresReferencedColumns(sql);
        for (MetadataTable table : metadataTables(sql)) {
            ArcheryTableDefinition definition = connection.tableDefinition(table.schema, table.name);
            for (ArcheryTableDefinition.Index index : definition.getIndexes()) {
                if (!index.isUnique() || referencedOnly) {
                    continue;
                }
                int seq = 1;
                for (String columnName : index.getColumnNames()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("constraint_name", index.getName());
                    row.put("constraint_schema", table.schema);
                    row.put("table_name", table.name);
                    row.put("column_name", columnName);
                    row.put("ordinal_position", seq++);
                    row.put("referenced_table_schema", null);
                    row.put("referenced_table_name", null);
                    row.put("referenced_column_name", null);
                    rows.add(row);
                }
            }
            for (ArcheryTableDefinition.ForeignKey foreignKey : definition.getForeignKeys()) {
                int count = Math.max(foreignKey.getColumns().size(), foreignKey.getReferencedColumns().size());
                for (int index = 0; index < count; index++) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("constraint_name", foreignKey.getName());
                    row.put("constraint_schema", table.schema);
                    row.put("table_name", table.name);
                    row.put("column_name", index < foreignKey.getColumns().size() ? foreignKey.getColumns().get(index) : null);
                    row.put("ordinal_position", index + 1);
                    row.put("referenced_table_schema", foreignKey.getReferencedSchema().isEmpty()
                        ? table.schema : foreignKey.getReferencedSchema());
                    row.put("referenced_table_name", foreignKey.getReferencedTable());
                    row.put("referenced_column_name", index < foreignKey.getReferencedColumns().size()
                        ? foreignKey.getReferencedColumns().get(index) : null);
                    rows.add(row);
                }
            }
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery keyColumnUsage rows=" + rows.size());
        return resultSet(statement, columns, rows);
    }


    /**
     * 只要求外键字段的查询（referenced_column_name is not null）过滤掉主键和唯一键行，避免返回 referenced 为空的伪外键。
     */
    private boolean requiresReferencedColumns(String sql) {
        String normalized = normalizedMetadataSql(sql);
        return normalized.contains("referenced_column_name is not null")
            || normalized.contains("referenced_table_name is not null")
            || normalized.contains("referenced_table_schema is not null");
    }


    /**
     * 排序规则清单是 IDE 建表时的候选项，清单本身是该字符集真实存在的排序规则。
     * 但哪个是默认值无法从 Archery 接口获知，因此不做 is_default 标记，避免 IDE 据此生成错误的 DDL。
     */
    private ResultSet collationsResult(Statement statement, String sql) throws SQLException {
        List<ArcheryColumn> columns = resultColumns(sql, "collation_name", "character_set_name", "is_default");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String schema : metadataSchemas(sql)) {
            String charset = connection.defaultCharset(schema);
            if (charset.isEmpty()) {
                continue;
            }
            for (String collation : ArcheryTableDefinition.collationsOf(charset)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("collation_name", collation);
                row.put("character_set_name", charset);
                row.put("is_default", 0);
                rows.add(row);
            }
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery collations rows=" + rows.size());
        return resultSet(statement, columns, rows);
    }


    private ResultSet viewsResult(Statement statement, String sql) throws SQLException {
        List<ArcheryColumn> columns = resultColumns(sql, "table_name", "view_definition");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MetadataTable table : metadataTables(sql)) {
            ArcheryTableDefinition definition = connection.tableDefinition(table.schema, table.name);
            if (definition.isView() && !definition.getViewDefinition().isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("table_name", table.name);
                row.put("view_definition", definition.getViewDefinition());
                rows.add(row);
            }
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery views rows=" + rows.size());
        return resultSet(statement, columns, rows);
    }


    private ResultSet partitionsResult(Statement statement, String sql) throws SQLException {
        List<ArcheryColumn> columns = resultColumns(sql, "table_name", "partition_name", "subpartition_name",
            "partition_ordinal_position", "subpartition_ordinal_position", "partition_method", "subpartition_method",
            "partition_expression", "subpartition_expression", "partition_description", "partition_comment");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MetadataTable table : metadataTables(sql)) {
            ArcheryTableDefinition definition = connection.tableDefinition(table.schema, table.name);
            int ordinal = 1;
            for (ArcheryTableDefinition.Partition partition : definition.getPartitions()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("table_name", table.name);
                row.put("partition_name", partition.getName());
                row.put("subpartition_name", null);
                row.put("partition_ordinal_position", ordinal++);
                row.put("subpartition_ordinal_position", null);
                row.put("partition_method", definition.getPartitionMethod());
                row.put("subpartition_method", definition.getSubpartitionMethod().isEmpty()
                    ? null : definition.getSubpartitionMethod());
                row.put("partition_expression", definition.getPartitionExpression());
                row.put("subpartition_expression", definition.getSubpartitionExpression().isEmpty()
                    ? null : definition.getSubpartitionExpression());
                row.put("partition_description", partition.getDescription());
                row.put("partition_comment", partition.getComment());
                rows.add(row);
            }
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery partitions rows=" + rows.size());
        return resultSet(statement, columns, rows);
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


    /**
     * metadata 结果列以 IDE 发出的 SELECT 别名顺序为准。
     * IDE 按列序号读取结果，若驱动自行增删或重排列，字段会整体错位。
     */
    private List<ArcheryColumn> resultColumns(String sql, String... fallback) {
        List<String> names = selectAliases(sql);
        if (names.isEmpty()) {
            names = Arrays.asList(fallback);
        }
        List<ArcheryColumn> columns = new ArrayList<>();
        for (String name : names) {
            columns.add(new ArcheryColumn(name));
        }
        return columns;
    }


    /**
     * 解析 SELECT 列表里各项的别名；解析失败返回空列表，由调用方退回固定列名。
     */
    private List<String> selectAliases(String sql) {
        if (sql == null) {
            return Collections.emptyList();
        }
        int selectIndex = indexOfKeyword(sql, "select", 0);
        if (selectIndex < 0) {
            return Collections.emptyList();
        }
        int fromIndex = indexOfKeyword(sql, "from", selectIndex + "select".length());
        if (fromIndex < 0) {
            return Collections.emptyList();
        }
        List<String> aliases = new ArrayList<>();
        for (String item : splitExpressions(sql.substring(selectIndex + "select".length(), fromIndex))) {
            String alias = aliasOf(item);
            if (alias != null && !alias.isEmpty()) {
                aliases.add(alias);
            }
        }
        return aliases;
    }


    private String aliasOf(String item) {
        String value = item.trim();
        if (value.isEmpty()) {
            return null;
        }
        Matcher explicit = Pattern.compile("(?is)^(.+?)\\s+as\\s+([A-Za-z0-9_.`]+)$").matcher(value);
        if (explicit.matches()) {
            return unqualified(explicit.group(2));
        }
        Matcher implicit = Pattern.compile("(?s)^(.+)\\s+([A-Za-z0-9_.`]+)$").matcher(value);
        if (implicit.matches()) {
            // 形如 "null expression" 的隐式别名，取最后一个标识符
            return unqualified(implicit.group(2));
        }
        return unqualified(value);
    }


    /**
     * IDE 的 metadata 查询常写成 c.constraint_name 这类限定名，而结果集的列标签只是列名本身。
     * 不去掉表前缀会导致按该别名取不到行数据，整行返回 null。
     */
    private String unqualified(String name) {
        String value = name.replace("`", "").trim();
        int dot = value.lastIndexOf('.');
        return dot >= 0 && dot + 1 < value.length() ? value.substring(dot + 1) : value;
    }


    /**
     * 查找独立的 SQL 关键字，跳过字符串、反引号、注释和括号内部。
     */
    private int indexOfKeyword(String sql, String keyword, int from) {
        int index = Math.max(from, 0);
        while (index < sql.length()) {
            char c = sql.charAt(index);
            if (c == '\'' || c == '"' || c == '`') {
                index = skipQuoted(sql, index);
                continue;
            }
            if (sql.startsWith("--", index)) {
                int end = sql.indexOf('\n', index);
                index = end < 0 ? sql.length() : end + 1;
                continue;
            }
            if (c == '(') {
                index = skipParen(sql, index);
                continue;
            }
            if (sql.regionMatches(true, index, keyword, 0, keyword.length())
                && (index == 0 || !isIdentifierChar(sql.charAt(index - 1)))
                && (index + keyword.length() >= sql.length() || !isIdentifierChar(sql.charAt(index + keyword.length())))) {
                return index;
            }
            index++;
        }
        return -1;
    }


    private boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }


    private int skipQuoted(String sql, int start) {
        char quote = sql.charAt(start);
        int index = start + 1;
        while (index < sql.length()) {
            char c = sql.charAt(index);
            if (c == '\\' && index + 1 < sql.length()) {
                index += 2;
                continue;
            }
            if (c == quote) {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        return sql.length();
    }


    private int skipParen(String sql, int start) {
        int depth = 0;
        int index = start;
        while (index < sql.length()) {
            char c = sql.charAt(index);
            if (c == '\'' || c == '"' || c == '`') {
                index = skipQuoted(sql, index);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return index + 1;
            }
            index++;
        }
        return sql.length();
    }


    private List<String> splitExpressions(String value) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        int index = 0;
        while (index < value.length()) {
            char c = value.charAt(index);
            if (c == '\'' || c == '"' || c == '`') {
                int end = skipQuoted(value, index);
                current.append(value, index, end);
                index = end;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                index++;
                continue;
            }
            current.append(c);
            index++;
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }


    /**
     * DataGrip 会频繁执行无 FROM 的环境探测 SQL，这类查询本地即可回答，避免污染 Archery 查询日志。
     */
    private ResultSet localSystemProbeResult(Statement statement, String sql) throws SQLException {
        List<ArcheryColumn> columns = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        for (SelectExpression expression : selectExpressions(sql)) {
            columns.add(new ArcheryColumn(expression.label));
            row.put(expression.label, localSystemValue(expression.expression));
        }
        ArcheryDebugLog.write(connection.config(), "metadataQuery localSystemProbe columns=" + columns.size());
        return resultSet(statement, columns, Collections.singletonList(row));
    }


    private Object localSystemValue(String expression) throws SQLException {
        String normalized = expression.toLowerCase(Locale.ROOT).trim();
        if (normalized.startsWith("database()")) {
            return connection.currentDatabase();
        }
        if (normalized.startsWith("schema()")) {
            return connection.currentSchema();
        }
        if (normalized.startsWith("left(user()")) {
            return connection.config().getUsername();
        }
        if (normalized.startsWith("user()") || normalized.startsWith("current_user()")) {
            return connection.config().getUsername() + "@archery";
        }
        if (normalized.matches("-?\\d+")) {
            return Long.valueOf(normalized);
        }
        if (normalized.startsWith("'") && normalized.endsWith("'")) {
            return expression.substring(1, expression.length() - 1).replace("''", "'");
        }
        if ("null".equals(normalized)) {
            return null;
        }
        return "";
    }


    private boolean isLocalSystemProbeQuery(String sql, String normalized) {
        if (!normalized.startsWith("select ") || normalized.contains(" from ")) {
            return false;
        }
        List<SelectExpression> expressions = selectExpressions(sql);
        if (expressions.isEmpty()) {
            return false;
        }
        for (SelectExpression expression : expressions) {
            if (!isSafeLocalSystemExpression(expression.expression)) {
                return false;
            }
        }
        return true;
    }


    private boolean isSafeLocalSystemExpression(String expression) {
        String normalized = expression.toLowerCase(Locale.ROOT).trim();
        return normalized.startsWith("database()") || normalized.startsWith("schema()")
            || normalized.startsWith("user()") || normalized.startsWith("current_user()")
            || normalized.startsWith("left(user()") || normalized.matches("-?\\d+")
            || (normalized.startsWith("'") && normalized.endsWith("'")) || "null".equals(normalized);
    }


    private List<SelectExpression> selectExpressions(String sql) {
        String selectList = trimLimit(sql.trim().substring("select".length()).trim());
        List<SelectExpression> expressions = new ArrayList<>();
        for (String expression : splitSelectList(selectList)) {
            expressions.add(selectExpression(expression));
        }
        return expressions;
    }


    private String trimLimit(String selectList) {
        return selectList.replaceFirst("(?is)\\s+limit\\s+\\d+\\s*;?\\s*$", "").replaceFirst(";\\s*$", "").trim();
    }


    private List<String> splitSelectList(String selectList) {
        List<String> expressions = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean singleQuoted = false;
        for (int index = 0; index < selectList.length(); index++) {
            char ch = selectList.charAt(index);
            if (ch == '\'' && (index == 0 || selectList.charAt(index - 1) != '\\')) {
                singleQuoted = !singleQuoted;
            }
            if (!singleQuoted && ch == '(') {
                depth++;
            } else if (!singleQuoted && ch == ')' && depth > 0) {
                depth--;
            } else if (!singleQuoted && ch == ',' && depth == 0) {
                expressions.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        if (current.length() > 0) {
            expressions.add(current.toString().trim());
        }
        return expressions;
    }


    private SelectExpression selectExpression(String rawExpression) {
        Matcher aliasMatcher = Pattern.compile("(?is)(.+?)\\s+as\\s+([a-z0-9_`]+)$").matcher(rawExpression);
        if (aliasMatcher.matches()) {
            return new SelectExpression(aliasMatcher.group(1).trim(), cleanAlias(aliasMatcher.group(2)));
        }
        return new SelectExpression(rawExpression, rawExpression);
    }


    private String cleanAlias(String alias) {
        return alias.replace("`", "");
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


    /**
     * 表枚举优先走 SQL 里显式指定的表名。IDE 刷新整库和按单表取元数据都会调用这两个出口，
     * 不按表名过滤会对每张表各发一次 describe，大库直接打满 Archery。
     */
    private List<String> requestedOrAllTableNames(String schema, String sql) throws SQLException {
        List<String> requested = requestedTableNames(sql);
        return requested.isEmpty() ? connection.tableNames(schema) : requested;
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
     * 无 FROM 的系统探测查询表达式，保留原表达式作为默认列名。
     */
    private static final class SelectExpression {
        private final String expression;
        private final String label;


        private SelectExpression(String expression, String label) {
            this.expression = expression;
            this.label = label;
        }
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
