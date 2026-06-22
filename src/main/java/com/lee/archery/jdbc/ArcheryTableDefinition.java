package com.lee.archery.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.lee.archery.client.ArcheryQueryResponse;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * MySQL 建表语句解析结果，用于向 IDE 暴露真实字段类型、默认值、注释和索引 metadata。
 */
final class ArcheryTableDefinition {
    private final String tableName;
    private final String createSql;
    private final List<Column> columns;
    private final List<Index> indexes;
    private final String tableComment;
    private final Long autoIncrement;
    private final String engine;
    private final String defaultCharset;


    private ArcheryTableDefinition(String tableName, String createSql, List<Column> columns, List<Index> indexes,
                                   String tableComment, Long autoIncrement, String engine, String defaultCharset) {
        this.tableName = tableName;
        this.createSql = createSql;
        this.columns = Collections.unmodifiableList(columns);
        this.indexes = Collections.unmodifiableList(indexes);
        this.tableComment = tableComment;
        this.autoIncrement = autoIncrement;
        this.engine = engine;
        this.defaultCharset = defaultCharset;
    }


    static ArcheryTableDefinition fromDescribe(String fallbackTableName, ArcheryQueryResponse response) {
        String createSql = createSql(response);
        if (createSql == null || createSql.isEmpty()) {
            return empty(fallbackTableName);
        }
        String tableName = tableName(createSql, fallbackTableName);
        List<Column> columns = new ArrayList<>();
        List<Index> indexes = new ArrayList<>();
        for (String line : definitionLines(createSql)) {
            String trimmed = trimTrailingComma(line.trim());
            if (trimmed.startsWith("`")) {
                columns.add(parseColumn(trimmed, columns.size() + 1));
            } else if (startsWithIgnoreCase(trimmed, "primary key")) {
                indexes.add(parseIndex(trimmed, "PRIMARY", true, true));
            } else if (startsWithIgnoreCase(trimmed, "unique key") || startsWithIgnoreCase(trimmed, "unique index")) {
                indexes.add(parseIndex(trimmed, null, true, false));
            } else if (startsWithIgnoreCase(trimmed, "key") || startsWithIgnoreCase(trimmed, "index")) {
                indexes.add(parseIndex(trimmed, null, false, false));
            }
        }
        return new ArcheryTableDefinition(tableName, createSql, columns, indexes, tableComment(createSql),
            autoIncrement(createSql), tableOption(createSql, "ENGINE"), tableOption(createSql, "DEFAULT CHARSET"));
    }


    static ArcheryTableDefinition empty(String tableName) {
        return new ArcheryTableDefinition(tableName, "", new ArrayList<>(), new ArrayList<>(), "", null, "", "");
    }


    String getTableName() {
        return tableName;
    }


    String getCreateSql() {
        return createSql;
    }


    List<Column> getColumns() {
        return columns;
    }


    List<Index> getIndexes() {
        return indexes;
    }


    String getTableComment() {
        return tableComment;
    }


    Long getAutoIncrement() {
        return autoIncrement;
    }


    String getEngine() {
        return engine;
    }


    String getDefaultCharset() {
        return defaultCharset;
    }


    Column column(String columnName) {
        for (Column column : columns) {
            if (column.getName().equalsIgnoreCase(columnName)) {
                return column;
            }
        }
        return null;
    }


    private static String createSql(ArcheryQueryResponse response) {
        JsonNode rows = response.getRows();
        if (rows.isArray() && rows.size() > 0) {
            JsonNode first = rows.get(0);
            if (first.isArray() && first.size() > 1) {
                return first.get(1).asText();
            }
            if (first.isObject()) {
                JsonNode createTable = first.get("Create Table");
                if (createTable != null) {
                    return createTable.asText();
                }
            }
        }
        return "";
    }


    private static String tableName(String createSql, String fallback) {
        int first = createSql.indexOf('`');
        int second = first < 0 ? -1 : createSql.indexOf('`', first + 1);
        return first >= 0 && second > first ? createSql.substring(first + 1, second) : fallback;
    }


    private static List<String> definitionLines(String createSql) {
        int start = createSql.indexOf('(');
        int end = createSql.lastIndexOf(')');
        if (start < 0 || end <= start) {
            return Collections.emptyList();
        }
        List<String> lines = new ArrayList<>();
        String body = createSql.substring(start + 1, end);
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        int parentheses = 0;
        for (int index = 0; index < body.length(); index++) {
            char c = body.charAt(index);
            if (c == '\'' && (index == 0 || body.charAt(index - 1) != '\\')) {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                parentheses++;
            } else if (!quoted && c == ')') {
                parentheses--;
            }
            if (!quoted && parentheses == 0 && c == ',') {
                lines.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }


    private static Column parseColumn(String line, int ordinal) {
        int nameEnd = line.indexOf('`', 1);
        String name = line.substring(1, nameEnd);
        String rest = line.substring(nameEnd + 1).trim();
        String type = leadingType(rest);
        String lower = rest.toLowerCase(Locale.ROOT);
        boolean nullable = !lower.contains(" not null");
        boolean autoIncrement = lower.contains(" auto_increment");
        String defaultValue = clauseValue(rest, "default");
        String comment = clauseValue(rest, "comment");
        String extra = autoIncrement ? "auto_increment" : lower.contains("on update") ? rest.substring(lower.indexOf("on update")).trim() : "";
        return new Column(name, type, nullable, defaultValue, comment, extra, ordinal);
    }


    private static Index parseIndex(String line, String forcedName, boolean unique, boolean primary) {
        String name = forcedName;
        int searchFrom = 0;
        if (name == null) {
            int first = line.indexOf('`');
            int second = first < 0 ? -1 : line.indexOf('`', first + 1);
            if (first >= 0 && second > first) {
                name = line.substring(first + 1, second);
                searchFrom = second + 1;
            }
        }
        int columnsStart = line.indexOf('(', searchFrom);
        int columnsEnd = columnsStart < 0 ? -1 : matchingParen(line, columnsStart);
        List<String> columns = new ArrayList<>();
        if (columnsStart >= 0 && columnsEnd > columnsStart) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("`([^`]+)`").matcher(line.substring(columnsStart + 1, columnsEnd));
            while (matcher.find()) {
                columns.add(matcher.group(1));
            }
        }
        return new Index(name == null ? "" : name, unique, primary, columns);
    }


    private static String leadingType(String rest) {
        String lower = rest.toLowerCase(Locale.ROOT);
        int end = rest.length();
        for (String marker : new String[]{" not null", " null", " default ", " comment ", " auto_increment", " on update "}) {
            int index = lower.indexOf(marker);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        return rest.substring(0, end).trim();
    }


    private static String clauseValue(String rest, String keyword) {
        String lower = rest.toLowerCase(Locale.ROOT);
        int index = lower.indexOf(" " + keyword + " ");
        if (index < 0) {
            return null;
        }
        String value = rest.substring(index + keyword.length() + 2).trim();
        if (value.toLowerCase(Locale.ROOT).startsWith("null")) {
            return null;
        }
        if (value.startsWith("'")) {
            return unquote(value);
        }
        int end = value.length();
        for (String marker : new String[]{" comment ", " on update "}) {
            int markerIndex = value.toLowerCase(Locale.ROOT).indexOf(marker);
            if (markerIndex >= 0 && markerIndex < end) {
                end = markerIndex;
            }
        }
        return value.substring(0, end).trim();
    }


    private static String unquote(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 1; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c == '\'' && (index + 1 >= value.length() || value.charAt(index + 1) != '\'')) {
                break;
            }
            if (c == '\'' && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                index++;
            }
            result.append(c);
        }
        return result.toString();
    }


    private static String tableComment(String createSql) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("COMMENT='((?:''|[^'])*)'").matcher(createSql);
        return matcher.find() ? matcher.group(1).replace("''", "'") : "";
    }


    private static Long autoIncrement(String createSql) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("AUTO_INCREMENT=(\\d+)").matcher(createSql);
        return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
    }


    private static String tableOption(String createSql, String name) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(name + "=([^ ]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(createSql);
        return matcher.find() ? matcher.group(1) : "";
    }


    private static int matchingParen(String value, int start) {
        int depth = 0;
        for (int index = start; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }


    private static String trimTrailingComma(String value) {
        return value.endsWith(",") ? value.substring(0, value.length() - 1).trim() : value;
    }


    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
    }


    static final class Column {
        private final String name;
        private final String type;
        private final boolean nullable;
        private final String defaultValue;
        private final String comment;
        private final String extra;
        private final int ordinal;


        Column(String name, String type, boolean nullable, String defaultValue, String comment, String extra, int ordinal) {
            this.name = name;
            this.type = type;
            this.nullable = nullable;
            this.defaultValue = defaultValue;
            this.comment = comment;
            this.extra = extra;
            this.ordinal = ordinal;
        }


        String getName() {
            return name;
        }


        String getType() {
            return type;
        }


        boolean isNullable() {
            return nullable;
        }


        String getDefaultValue() {
            return defaultValue;
        }


        String getComment() {
            return comment;
        }


        String getExtra() {
            return extra;
        }


        int getOrdinal() {
            return ordinal;
        }


        int jdbcType() {
            String lower = type.toLowerCase(Locale.ROOT);
            if (lower.startsWith("bigint")) {
                return Types.BIGINT;
            }
            if (lower.startsWith("int") || lower.startsWith("tinyint") || lower.startsWith("smallint") || lower.startsWith("mediumint")) {
                return Types.INTEGER;
            }
            if (lower.startsWith("decimal") || lower.startsWith("numeric")) {
                return Types.DECIMAL;
            }
            if (lower.startsWith("datetime") || lower.startsWith("timestamp")) {
                return Types.TIMESTAMP;
            }
            if (lower.startsWith("date")) {
                return Types.DATE;
            }
            if (lower.contains("text") || lower.startsWith("json")) {
                return Types.LONGVARCHAR;
            }
            return Types.VARCHAR;
        }


        int size() {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\((\\d+)(?:,(\\d+))?\\)").matcher(type);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
        }


        int scale() {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\(\\d+,(\\d+)\\)").matcher(type);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
        }
    }


    static final class Index {
        private final String name;
        private final boolean unique;
        private final boolean primary;
        private final List<String> columns;


        Index(String name, boolean unique, boolean primary, List<String> columns) {
            this.name = name;
            this.unique = unique;
            this.primary = primary;
            this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        }


        String getName() {
            return name;
        }


        boolean isUnique() {
            return unique;
        }


        boolean isPrimary() {
            return primary;
        }


        List<String> getColumns() {
            return columns;
        }
    }
}
