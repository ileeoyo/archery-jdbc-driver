package com.lee.archery.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.lee.archery.client.ArcheryQueryResponse;

import java.sql.Types;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL 建表语句解析结果，用于向 IDE 暴露真实字段类型、默认值、注释、索引、约束和分区 metadata。
 *
 * 数据来源是 Archery describe table 返回的 SHOW CREATE TABLE 原文，所有属性都从原文解析，
 * 不再用常量占位，避免 IDE 生成 DDL 时丢失注释、索引前缀长度、外键等信息。
 *
 * 解析统一走「从左到右扫描 + 引号成对跳过」的方式，不用正则在整段文本上做贪婪匹配，
 * 因为默认值、注释、枚举取值里都可能出现逗号、括号和关键字。
 */
final class ArcheryTableDefinition {
    /**
     * 列定义中可识别的子句关键字，长的写在前面保证优先命中。
     * 匹配时要求前一个字符是空白，避免误命中标识符或类型名内部。
     */
    private static final String[] COLUMN_KEYWORDS = {
        "GENERATED ALWAYS AS", "CHARACTER SET", "COLUMN_FORMAT", "AUTO_INCREMENT", "NOT NULL",
        "ON UPDATE", "COLLATE", "CHARSET", "DEFAULT", "COMMENT", "INVISIBLE", "VISIBLE",
        "STORAGE", "VIRTUAL", "STORED", "SRID", "NULL"
    };

    /**
     * 表选项列表中会出现在 information_schema.tables.create_options 里的项。
     */
    private static final Map<String, String> CREATE_OPTION_NAMES = new LinkedHashMap<>();

    /**
     * 视图判定只匹配 CREATE 语句前缀，避免列注释里出现 "VIEW" 字样的普通表被误判。
     */
    private static final Pattern VIEW_PATTERN = Pattern.compile(
        "(?is)^CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:ALGORITHM\\s*=\\s*\\S+\\s+)?(?:DEFINER\\s*=\\s*\\S+\\s+)?"
            + "(?:SQL\\s+SECURITY\\s+\\S+\\s+)?VIEW\\s");

    private static final Pattern LENGTH_PATTERN = Pattern.compile("\\((\\d+)(?:,(\\d+))?\\)");
    private static final Pattern SCALE_PATTERN = Pattern.compile("\\((\\d+),(\\d+)\\)");

    /**
     * 各字符集真实存在的排序规则，供 IDE 建表时选择，避免给出非法值。
     */
    private static final Map<String, List<String>> COLLATIONS = new LinkedHashMap<>();

    static {
        CREATE_OPTION_NAMES.put("KEY_BLOCK_SIZE", "key_block_size");
        CREATE_OPTION_NAMES.put("STATS_PERSISTENT", "stats_persistent");
        CREATE_OPTION_NAMES.put("COMPRESSION", "compression");
        CREATE_OPTION_NAMES.put("CHECKSUM", "checksum");
        CREATE_OPTION_NAMES.put("DELAY_KEY_WRITE", "delay_key_write");
        CREATE_OPTION_NAMES.put("PACK_KEYS", "pack_keys");
        CREATE_OPTION_NAMES.put("MAX_ROWS", "max_rows");
        CREATE_OPTION_NAMES.put("MIN_ROWS", "min_rows");
        CREATE_OPTION_NAMES.put("AVG_ROW_LENGTH", "avg_row_length");

        // 默认排序规则决定 IDE 能否把 table_collation 简化成 charset；MySQL 5.7 与 8.0 的默认值不同，
        // 这里取 5.7 的 utf8mb4_general_ci，8.0 特有的 0900 系列仍保留在候选清单里
        collations("utf8mb4", "utf8mb4_general_ci", "utf8mb4_0900_ai_ci", "utf8mb4_0900_as_cs",
            "utf8mb4_unicode_ci", "utf8mb4_unicode_520_ci", "utf8mb4_bin");
        collations("utf8mb3", "utf8mb3_general_ci", "utf8mb3_unicode_ci", "utf8mb3_bin");
        collations("utf8", "utf8_general_ci", "utf8_unicode_ci", "utf8_bin");
        collations("latin1", "latin1_swedish_ci", "latin1_general_ci", "latin1_general_cs", "latin1_bin");
        collations("gbk", "gbk_chinese_ci", "gbk_bin");
        collations("gb18030", "gb18030_chinese_ci", "gb18030_bin", "gb18030_unicode_520_ci");
        collations("ascii", "ascii_general_ci", "ascii_bin");
        collations("binary", "binary");
    }

    private static void collations(String charset, String... names) {
        List<String> values = new ArrayList<>();
        Collections.addAll(values, names);
        COLLATIONS.put(charset, Collections.unmodifiableList(values));
    }


    private final String createSql;
    private final boolean view;
    private final String viewDefinition;
    private final List<Column> columns;
    private final List<Index> indexes;
    private final List<ForeignKey> foreignKeys;
    private final List<CheckConstraint> checkConstraints;
    private final List<Partition> partitions;
    private final TableOptions options;


    private ArcheryTableDefinition(String createSql, boolean view, String viewDefinition, List<Column> columns,
                                   List<Index> indexes, List<ForeignKey> foreignKeys,
                                   List<CheckConstraint> checkConstraints, List<Partition> partitions,
                                   TableOptions options) {
        this.createSql = createSql;
        this.view = view;
        this.viewDefinition = viewDefinition;
        this.columns = Collections.unmodifiableList(columns);
        this.indexes = Collections.unmodifiableList(indexes);
        this.foreignKeys = Collections.unmodifiableList(foreignKeys);
        this.checkConstraints = Collections.unmodifiableList(checkConstraints);
        this.partitions = Collections.unmodifiableList(partitions);
        this.options = options;
    }


    static ArcheryTableDefinition fromDescribe(String fallbackTableName, ArcheryQueryResponse response) {
        String createSql = createSql(response);
        if (createSql == null || createSql.isEmpty()) {
            return empty(fallbackTableName);
        }
        if (VIEW_PATTERN.matcher(createSql).find()) {
            return new ArcheryTableDefinition(createSql, true, viewDefinition(createSql), new ArrayList<Column>(),
                new ArrayList<Index>(), new ArrayList<ForeignKey>(), new ArrayList<CheckConstraint>(),
                new ArrayList<Partition>(), new TableOptions());
        }
        int bodyStart = bodyStart(createSql);
        int bodyEnd = bodyStart < 0 ? -1 : matchingParen(createSql, bodyStart);
        List<Column> columns = new ArrayList<>();
        List<Index> indexes = new ArrayList<>();
        List<ForeignKey> foreignKeys = new ArrayList<>();
        List<CheckConstraint> checkConstraints = new ArrayList<>();
        for (String line : definitionLines(createSql, bodyStart, bodyEnd)) {
            String trimmed = trimTrailingComma(line.trim());
            if (trimmed.isEmpty()) {
                continue;
            }
            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (trimmed.startsWith("`")) {
                columns.add(parseColumn(trimmed, columns.size() + 1));
            } else if (startsWithIgnoreCase(upper, "PRIMARY KEY") || startsWithIgnoreCase(upper, "UNIQUE ")
                || startsWithIgnoreCase(upper, "KEY ") || startsWithIgnoreCase(upper, "INDEX ")
                || startsWithIgnoreCase(upper, "FULLTEXT ") || startsWithIgnoreCase(upper, "SPATIAL ")) {
                indexes.add(parseIndex(trimmed));
            } else if (upper.contains("FOREIGN KEY")) {
                ForeignKey foreignKey = parseForeignKey(trimmed);
                if (foreignKey != null) {
                    foreignKeys.add(foreignKey);
                }
            } else if (upper.contains("CHECK")) {
                CheckConstraint check = parseCheckConstraint(trimmed);
                if (check != null) {
                    checkConstraints.add(check);
                }
            }
        }
        String trailer = bodyEnd < 0 ? "" : createSql.substring(bodyEnd + 1);
        TableOptions options = parseOptions(trailer);
        return new ArcheryTableDefinition(createSql, false, "", columns, indexes, foreignKeys, checkConstraints,
            parsePartitions(trailer, options), options);
    }


    static ArcheryTableDefinition empty(String tableName) {
        return new ArcheryTableDefinition("", false, "", new ArrayList<Column>(), new ArrayList<Index>(),
            new ArrayList<ForeignKey>(), new ArrayList<CheckConstraint>(), new ArrayList<Partition>(), new TableOptions());
    }


    /**
     * 视图字段占位定义。SHOW CREATE VIEW 不含字段类型，只能暴露列名，类型用 varchar 兜底避免 IDE 报错。
     */
    static Column viewColumn(String name, int ordinal) {
        return new Column(name, "varchar(0)", true, null, null, "", ordinal, "", "", "", false);
    }


    static List<String> collationsOf(String charset) {
        if (charset == null || charset.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> values = COLLATIONS.get(charset.toLowerCase(Locale.ROOT));
        return values == null ? Collections.<String>emptyList() : values;
    }


    boolean isView() {
        return view;
    }


    String getCreateSql() {
        return createSql;
    }


    String getViewDefinition() {
        return viewDefinition;
    }


    /**
     * JDBC / information_schema 语义下的表类型。
     */
    String getTableType() {
        return view ? "VIEW" : "BASE TABLE";
    }


    List<Column> getColumns() {
        return columns;
    }


    List<Index> getIndexes() {
        return indexes;
    }


    List<ForeignKey> getForeignKeys() {
        return foreignKeys;
    }


    List<CheckConstraint> getCheckConstraints() {
        return checkConstraints;
    }


    List<Partition> getPartitions() {
        return partitions;
    }


    String getTableComment() {
        return options.comment;
    }


    Long getAutoIncrement() {
        return options.autoIncrement;
    }


    String getEngine() {
        return options.engine;
    }


    String getDefaultCharset() {
        return options.charset;
    }


    /**
     * 表级排序规则只认建表语句里显式声明的 COLLATE。
     * 未声明时不能按字符集猜默认排序规则：MySQL 5.7 与 8.0 的默认值不同，
     * 猜错会让 IDE 生成与实际不符的 DDL，返回空串让 IDE 自行留空更安全。
     */
    String getCollation() {
        return options.collation;
    }


    String getCreateOptions() {
        return options.createOptions;
    }


    String getPartitionMethod() {
        return options.partitionMethod;
    }


    String getPartitionExpression() {
        return options.partitionExpression;
    }


    String getSubpartitionMethod() {
        return options.subpartitionMethod;
    }


    String getSubpartitionExpression() {
        return options.subpartitionExpression;
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
                // 表返回 Create Table，视图返回 Create View，两者都要兼容
                for (String key : new String[]{"Create Table", "Create View"}) {
                    JsonNode node = first.get(key);
                    if (node != null) {
                        return node.asText();
                    }
                }
            }
        }
        return "";
    }


    private static String viewDefinition(String createSql) {
        Matcher matcher = Pattern.compile("(?is)\\bAS\\s+(.*)$").matcher(createSql);
        return matcher.find() ? matcher.group(1).trim() : "";
    }


    /**
     * 表体左括号位置：跳过表名中的反引号和可能的库名前缀，避免表名本身带括号时定位错误。
     */
    private static int bodyStart(String createSql) {
        int cursor = skipSpaces(createSql, 0);
        cursor = skipWord(createSql, cursor, "CREATE");
        cursor = skipWord(createSql, cursor, "TEMPORARY");
        cursor = skipWord(createSql, cursor, "TABLE");
        cursor = skipWord(createSql, cursor, "IF");
        cursor = skipWord(createSql, cursor, "NOT");
        cursor = skipWord(createSql, cursor, "EXISTS");
        while (cursor < createSql.length()) {
            char c = createSql.charAt(cursor);
            if (c == '`' || c == '\'' || c == '"') {
                cursor = skipDelimited(createSql, cursor);
                continue;
            }
            if (c == '(') {
                return cursor;
            }
            cursor++;
        }
        return -1;
    }


    private static List<String> definitionLines(String createSql, int bodyStart, int bodyEnd) {
        if (bodyStart < 0 || bodyEnd <= bodyStart) {
            return Collections.emptyList();
        }
        List<String> lines = new ArrayList<>();
        String body = createSql.substring(bodyStart + 1, bodyEnd);
        StringBuilder current = new StringBuilder();
        int depth = 0;
        int index = 0;
        while (index < body.length()) {
            char c = body.charAt(index);
            if (c == '\'' || c == '"' || c == '`') {
                int end = skipDelimited(body, index);
                current.append(body, index, end);
                index = end;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                lines.add(current.toString());
                current.setLength(0);
                index++;
                continue;
            }
            current.append(c);
            index++;
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }


    private static Column parseColumn(String line, int ordinal) {
        int nameEnd = line.indexOf('`', 1);
        if (nameEnd < 0) {
            return emptyColumn(ordinal);
        }
        String name = line.substring(1, nameEnd);
        String rest = line.substring(nameEnd + 1).trim();
        Keyword first = nextKeyword(rest, 0);
        String type = (first == null ? rest : rest.substring(0, first.index())).trim();

        boolean nullable = true;
        boolean autoIncrement = false;
        boolean stored = false;
        boolean virtual = false;
        boolean defaultGenerated = false;
        String defaultValue = null;
        String comment = null;
        String charset = "";
        String collation = "";
        String onUpdate = null;
        String generationExpression = "";

        int cursor = first == null ? -1 : first.index();
        while (cursor >= 0 && cursor < rest.length()) {
            Keyword current = nextKeyword(rest, cursor);
            if (current == null) {
                break;
            }
            String clause = current.name();
            int valueStart = current.index() + current.keyword().length();
            if ("DEFAULT".equals(clause)) {
                // DEFAULT 的值可能是 NULL、字面量或表达式，整体吃掉，否则 NULL 会被当成独立关键字
                ValueToken token = readValueToken(rest, valueStart);
                defaultValue = token.value;
                defaultGenerated = token.expression;
                cursor = token.end;
                continue;
            }
            Keyword next = nextKeyword(rest, valueStart);
            String value = rest.substring(valueStart, next == null ? rest.length() : next.index()).trim();
            if ("NOT NULL".equals(clause)) {
                nullable = false;
            } else if ("NULL".equals(clause)) {
                nullable = true;
            } else if ("AUTO_INCREMENT".equals(clause)) {
                autoIncrement = true;
            } else if ("COMMENT".equals(clause)) {
                comment = unquote(value);
            } else if ("CHARACTER SET".equals(clause) || "CHARSET".equals(clause)) {
                charset = value;
            } else if ("COLLATE".equals(clause)) {
                collation = value;
            } else if ("ON UPDATE".equals(clause)) {
                onUpdate = value;
            } else if ("GENERATED ALWAYS AS".equals(clause)) {
                generationExpression = readExpression(value);
            } else if ("STORED".equals(clause)) {
                stored = true;
            } else if ("VIRTUAL".equals(clause)) {
                virtual = true;
            }
            cursor = next == null ? -1 : next.index();
        }

        // extra 按 MySQL information_schema 习惯拼装，IDE 依赖它识别自增、生成列和 on update
        List<String> extras = new ArrayList<>();
        if (autoIncrement) {
            extras.add("auto_increment");
        }
        if (onUpdate != null) {
            extras.add("on update " + onUpdate);
        }
        if (!generationExpression.isEmpty()) {
            extras.add((stored ? "STORED" : "VIRTUAL") + " GENERATED");
        } else if (defaultGenerated) {
            extras.add("DEFAULT_GENERATED");
        }
        return new Column(name, type, nullable, defaultValue, comment, join(extras), ordinal, charset, collation,
            generationExpression, autoIncrement);
    }


    private static Column emptyColumn(int ordinal) {
        return new Column("", "", true, null, null, "", ordinal, "", "", "", false);
    }


    private static Index parseIndex(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        boolean primary = startsWithIgnoreCase(upper, "PRIMARY KEY");
        // 主键本身是唯一索引，这里必须归一，否则主键会从约束和 non_unique 结果里消失
        boolean unique = primary || startsWithIgnoreCase(upper, "UNIQUE ");
        String indexType = "";
        if (startsWithIgnoreCase(upper, "FULLTEXT")) {
            indexType = "FULLTEXT";
        } else if (startsWithIgnoreCase(upper, "SPATIAL")) {
            indexType = "SPATIAL";
        }
        String name = primary ? "PRIMARY" : "";
        int searchFrom = 0;
        if (!primary) {
            int first = line.indexOf('`');
            if (first >= 0) {
                int second = line.indexOf('`', first + 1);
                if (second > first) {
                    name = line.substring(first + 1, second);
                    searchFrom = second + 1;
                }
            }
        } else {
            searchFrom = firstKeywordEnd(line, "PRIMARY KEY");
        }
        int columnsStart = line.indexOf('(', Math.max(searchFrom, 0));
        int columnsEnd = columnsStart < 0 ? -1 : matchingParen(line, columnsStart);
        List<IndexColumn> columns = new ArrayList<>();
        if (columnsStart >= 0 && columnsEnd > columnsStart) {
            for (String item : splitTopLevel(line.substring(columnsStart + 1, columnsEnd))) {
                IndexColumn column = parseIndexColumn(item);
                if (column != null) {
                    columns.add(column);
                }
            }
        }
        String trailing = columnsEnd < 0 ? "" : line.substring(columnsEnd + 1);
        // COMMENT 里的文字可能包含 "USING"，先截掉注释段再判断索引类型
        String head = stripOptions(trailing);
        Matcher using = Pattern.compile("(?i)\\bUSING\\s+(\\w+)").matcher(head);
        if (using.find()) {
            indexType = using.group(1).toUpperCase(Locale.ROOT);
        }
        String indexComment = optionValue(trailing, "COMMENT");
        return new Index(name, unique, primary, columns, indexType, indexComment);
    }


    /**
     * 只保留索引尾部子句里非 COMMENT 的部分，用于安全匹配 USING。
     */
    private static String stripOptions(String trailing) {
        int commentIndex = indexOfWord(trailing, "COMMENT");
        return commentIndex < 0 ? trailing : trailing.substring(0, commentIndex);
    }


    private static String optionValue(String text, String keyword) {
        int index = indexOfWord(text, keyword);
        if (index < 0) {
            return "";
        }
        int cursor = skipSpaces(text, index + keyword.length());
        if (cursor < text.length() && text.charAt(cursor) == '=') {
            cursor++;
        }
        ValueToken token = readValueToken(text, cursor);
        return token.value == null ? "" : token.value;
    }


    private static IndexColumn parseIndexColumn(String rawItem) {
        String item = rawItem.trim();
        if (item.isEmpty()) {
            return null;
        }
        if (!item.startsWith("`")) {
            // 函数索引没有字段名，直接把整个表达式暴露给 IDE
            return new IndexColumn(item.replace("`", "").trim(), 0, "A", item);
        }
        int nameEnd = item.indexOf('`', 1);
        if (nameEnd < 0) {
            return null;
        }
        String name = item.substring(1, nameEnd);
        String rest = item.substring(nameEnd + 1).trim();
        int subPart = 0;
        if (rest.startsWith("(")) {
            int close = rest.indexOf(')');
            if (close > 1) {
                subPart = parseInt(rest.substring(1, close));
                rest = rest.substring(close + 1).trim();
            }
        }
        String direction = startsWithIgnoreCase(rest, "DESC") ? "D" : "A";
        return new IndexColumn(name, subPart, direction, null);
    }


    /**
     * 手工扫描外键定义，不依赖正则在嵌套括号和尾部动作上的贪婪匹配。
     */
    private static ForeignKey parseForeignKey(String line) {
        int foreignIndex = indexOfWord(line, "FOREIGN");
        if (foreignIndex < 0) {
            return null;
        }
        int keyIndex = indexOfWord(line, "KEY");
        int columnsStart = keyIndex < 0 ? -1 : line.indexOf('(', keyIndex);
        int columnsEnd = columnsStart < 0 ? -1 : matchingParen(line, columnsStart);
        int referencesIndex = indexOfWord(line, "REFERENCES");
        if (columnsEnd < 0 || referencesIndex < 0) {
            return null;
        }
        List<String> columns = identifiers(line.substring(columnsStart + 1, columnsEnd));
        int cursor = skipSpaces(line, referencesIndex + "REFERENCES".length());
        String referencedSchema = "";
        String referencedTable;
        if (cursor < line.length() && line.charAt(cursor) == '`') {
            int firstEnd = skipDelimited(line, cursor);
            String firstName = line.substring(cursor + 1, firstEnd - 1);
            cursor = skipSpaces(line, firstEnd);
            if (cursor < line.length() && line.charAt(cursor) == '.') {
                referencedSchema = firstName;
                cursor = skipSpaces(line, cursor + 1);
                if (cursor < line.length() && line.charAt(cursor) == '`') {
                    int secondEnd = skipDelimited(line, cursor);
                    referencedTable = line.substring(cursor + 1, secondEnd - 1);
                    cursor = secondEnd;
                } else {
                    return null;
                }
            } else {
                referencedTable = firstName;
            }
        } else {
            int end = cursor;
            while (end < line.length() && !Character.isWhitespace(line.charAt(end)) && line.charAt(end) != '(') {
                end++;
            }
            referencedTable = line.substring(cursor, end);
            cursor = end;
        }
        int refColumnsStart = line.indexOf('(', cursor);
        int refColumnsEnd = refColumnsStart < 0 ? -1 : matchingParen(line, refColumnsStart);
        if (refColumnsEnd < 0) {
            return null;
        }
        List<String> referencedColumns = identifiers(line.substring(refColumnsStart + 1, refColumnsEnd));
        String actions = line.substring(refColumnsEnd + 1);
        String constraintName = "";
        int constraintIndex = indexOfWord(line, "CONSTRAINT");
        if (constraintIndex >= 0 && constraintIndex < foreignIndex) {
            int nameStart = skipSpaces(line, constraintIndex + "CONSTRAINT".length());
            if (nameStart < line.length() && line.charAt(nameStart) == '`') {
                int nameEnd = skipDelimited(line, nameStart);
                constraintName = line.substring(nameStart + 1, nameEnd - 1);
            }
        }
        return new ForeignKey(constraintName, columns, referencedSchema, referencedColumns, referencedTable,
            action(actions, "DELETE"), action(actions, "UPDATE"));
    }


    /**
     * CHECK 约束尾部可能带 /*!80016 NOT ENFORCED *&#47; 之类的版本注释，用括号配对取表达式而不是正则锚定行尾。
     */
    private static CheckConstraint parseCheckConstraint(String line) {
        int checkIndex = indexOfWord(line, "CHECK");
        if (checkIndex < 0) {
            return null;
        }
        int expressionStart = line.indexOf('(', checkIndex);
        int expressionEnd = expressionStart < 0 ? -1 : matchingParen(line, expressionStart);
        if (expressionEnd < 0) {
            return null;
        }
        String constraintName = "";
        int constraintIndex = indexOfWord(line, "CONSTRAINT");
        if (constraintIndex >= 0 && constraintIndex < checkIndex) {
            int nameStart = skipSpaces(line, constraintIndex + "CONSTRAINT".length());
            if (nameStart < line.length() && line.charAt(nameStart) == '`') {
                int nameEnd = skipDelimited(line, nameStart);
                constraintName = line.substring(nameStart + 1, nameEnd - 1);
            }
        }
        return new CheckConstraint(constraintName, unwrapParens(line.substring(expressionStart, expressionEnd + 1)));
    }


    /**
     * 表选项按从左到右扫描，遇到 PARTITION BY 即停止，避免分区里的 COMMENT 覆盖表注释。
     * 同名选项只取第一次出现，未识别的键值对进入 create_options。
     */
    private static TableOptions parseOptions(String trailer) {
        TableOptions options = new TableOptions();
        if (trailer == null || trailer.isEmpty()) {
            return options;
        }
        List<String> createOptions = new ArrayList<>();
        int index = 0;
        while (index < trailer.length()) {
            char c = trailer.charAt(index);
            if (c == '\'' || c == '"' || c == '`') {
                index = skipDelimited(trailer, index);
                continue;
            }
            if (indexOfWord(trailer, "PARTITION", index) == index) {
                break;
            }
            OptionMatch match = matchOptionName(trailer, index);
            if (match == null) {
                index++;
                continue;
            }
            int cursor = skipSpaces(trailer, match.end);
            if (cursor >= trailer.length() || trailer.charAt(cursor) != '=') {
                index = match.end;
                continue;
            }
            ValueToken token = readValueToken(trailer, cursor + 1);
            applyOption(options, match.name, token.value);
            String createOption = CREATE_OPTION_NAMES.get(match.name);
            if (createOption != null && !options.createOptions.contains(createOption + "=")) {
                createOptions.add(createOption + "=" + token.value);
            }
            index = token.end;
        }
        if (!options.rowFormat.isEmpty()) {
            createOptions.add("row_format=" + options.rowFormat);
        }
        options.createOptions = join(createOptions);
        return options;
    }


    private static void applyOption(TableOptions options, String name, String value) {
        if ("COMMENT".equals(name)) {
            if (options.comment.isEmpty()) {
                options.comment = value == null ? "" : value;
            }
        } else if ("ENGINE".equals(name)) {
            if (options.engine.isEmpty()) {
                options.engine = value;
            }
        } else if ("DEFAULT CHARSET".equals(name) || "CHARSET".equals(name) || "CHARACTER SET".equals(name)) {
            if (options.charset.isEmpty()) {
                options.charset = value;
            }
        } else if ("COLLATE".equals(name)) {
            if (options.collation.isEmpty()) {
                options.collation = value;
            }
        } else if ("AUTO_INCREMENT".equals(name)) {
            if (options.autoIncrement == null) {
                options.autoIncrement = parseLong(value);
            }
        } else if ("ROW_FORMAT".equals(name)) {
            if (options.rowFormat.isEmpty()) {
                options.rowFormat = value;
            }
        }
    }


    /**
     * 匹配表选项名，长的名字优先，避免 DEFAULT CHARSET 被拆成 CHARSET。
     */
    private static OptionMatch matchOptionName(String text, int index) {
        String[] names = {"DEFAULT CHARSET", "CHARACTER SET", "KEY_BLOCK_SIZE", "STATS_PERSISTENT", "AVG_ROW_LENGTH",
            "DELAY_KEY_WRITE", "COMPRESSION", "AUTO_INCREMENT", "ROW_FORMAT", "PACK_KEYS", "MAX_ROWS", "MIN_ROWS",
            "CHECKSUM", "COLLATE", "CHARSET", "ENGINE", "COMMENT"};
        for (String name : names) {
            if (regionMatchesWord(text, index, name)) {
                return new OptionMatch(name, index + name.length());
            }
        }
        return null;
    }


    /**
     * 分区解析：支持 LINEAR、RANGE/LIST COLUMNS、KEY、带函数表达式和 MAXVALUE 的定义。
     */
    private static List<Partition> parsePartitions(String trailer, TableOptions options) {
        List<Partition> partitions = new ArrayList<>();
        int partitionIndex = indexOfWord(trailer, "PARTITION");
        if (partitionIndex < 0) {
            return partitions;
        }
        int byIndex = indexOfWord(trailer, "BY", partitionIndex + "PARTITION".length());
        if (byIndex < 0) {
            return partitions;
        }
        int cursor = skipSpaces(trailer, byIndex + "BY".length());
        if (regionMatchesWord(trailer, cursor, "LINEAR")) {
            cursor = skipSpaces(trailer, cursor + "LINEAR".length());
        }
        int methodStart = cursor;
        while (cursor < trailer.length() && !Character.isWhitespace(trailer.charAt(cursor))
            && trailer.charAt(cursor) != '(') {
            cursor++;
        }
        String method = trailer.substring(methodStart, cursor).toUpperCase(Locale.ROOT);
        // RANGE COLUMNS / LIST COLUMNS 是两个词组成的方法名
        int afterMethod = skipSpaces(trailer, cursor);
        if (regionMatchesWord(trailer, afterMethod, "COLUMNS")) {
            method = method + " COLUMNS";
            cursor = skipSpaces(trailer, afterMethod + "COLUMNS".length());
        }
        if (method.isEmpty()) {
            return partitions;
        }
        options.partitionMethod = method;
        int expressionStart = trailer.indexOf('(', cursor);
        if (expressionStart < 0) {
            return partitions;
        }
        int expressionEnd = matchingParen(trailer, expressionStart);
        if (expressionEnd < 0) {
            return partitions;
        }
        options.partitionExpression = trailer.substring(expressionStart + 1, expressionEnd).trim();
        int afterExpression = expressionEnd + 1;
        int definitionsStart = afterExpression;
        // 先处理子分区，否则会把子分区表达式误当成定义列表
        int subpartitionIndex = indexOfWord(trailer, "SUBPARTITION", afterExpression);
        int definitionsListStart = nextTopLevelParen(trailer, afterExpression);
        if (subpartitionIndex >= 0 && (definitionsListStart < 0 || subpartitionIndex < definitionsListStart)) {
            int subByIndex = indexOfWord(trailer, "BY", subpartitionIndex + "SUBPARTITION".length());
            if (subByIndex >= 0) {
                int subCursor = skipSpaces(trailer, subByIndex + "BY".length());
                if (regionMatchesWord(trailer, subCursor, "LINEAR")) {
                    subCursor = skipSpaces(trailer, subCursor + "LINEAR".length());
                }
                int subMethodStart = subCursor;
                while (subCursor < trailer.length() && !Character.isWhitespace(trailer.charAt(subCursor))
                    && trailer.charAt(subCursor) != '(') {
                    subCursor++;
                }
                options.subpartitionMethod = trailer.substring(subMethodStart, subCursor).toUpperCase(Locale.ROOT);
                if (regionMatchesWord(trailer, skipSpaces(trailer, subCursor), "HASH")
                    || regionMatchesWord(trailer, skipSpaces(trailer, subCursor), "KEY")) {
                    subCursor = skipSpaces(trailer, subCursor);
                }
                int subExpressionStart = trailer.indexOf('(', subCursor);
                int subExpressionEnd = subExpressionStart < 0 ? -1 : matchingParen(trailer, subExpressionStart);
                if (subExpressionEnd > subExpressionStart) {
                    options.subpartitionExpression = trailer.substring(subExpressionStart + 1, subExpressionEnd).trim();
                    definitionsStart = subExpressionEnd + 1;
                }
            }
        }
        int listStart = nextTopLevelParen(trailer, Math.max(definitionsStart, 0));
        int listEnd = listStart < 0 ? -1 : matchingParen(trailer, listStart);
        if (listEnd <= listStart) {
            return partitions;
        }
        for (String item : splitTopLevel(trailer.substring(listStart + 1, listEnd))) {
            Partition partition = parsePartition(item);
            if (partition != null) {
                partitions.add(partition);
            }
        }
        return partitions;
    }


    private static Partition parsePartition(String rawItem) {
        String item = rawItem.trim();
        if (item.isEmpty() || startsWithIgnoreCase(item, "SUBPARTITION")) {
            return null;
        }
        int partitionIndex = indexOfWord(item, "PARTITION");
        if (partitionIndex < 0) {
            return null;
        }
        int nameStart = skipSpaces(item, partitionIndex + "PARTITION".length());
        String name;
        if (nameStart < item.length() && item.charAt(nameStart) == '`') {
            int nameEnd = skipDelimited(item, nameStart);
            name = item.substring(nameStart + 1, nameEnd - 1);
            nameStart = nameEnd;
        } else {
            int end = nameStart;
            while (end < item.length() && !Character.isWhitespace(item.charAt(end))) {
                end++;
            }
            name = item.substring(nameStart, end);
            nameStart = end;
        }
        if (name.isEmpty()) {
            return null;
        }
        String description = partitionDescription(item);
        String comment = optionValue(item, "COMMENT");
        return new Partition(name, description, comment);
    }


    /**
     * 分区描述按 MySQL information_schema 语义返回纯取值：LESS THAN 取括号内容或 MAXVALUE，IN 取列表内容。
     */
    private static String partitionDescription(String item) {
        int valuesIndex = indexOfWord(item, "VALUES");
        if (valuesIndex < 0) {
            return "";
        }
        int cursor = skipSpaces(item, valuesIndex + "VALUES".length());
        if (regionMatchesWord(item, cursor, "LESS")) {
            cursor = skipSpaces(item, cursor + "LESS".length());
            if (regionMatchesWord(item, cursor, "THAN")) {
                cursor = skipSpaces(item, cursor + "THAN".length());
            }
        } else if (regionMatchesWord(item, cursor, "IN")) {
            cursor = skipSpaces(item, cursor + "IN".length());
        }
        if (regionMatchesWord(item, cursor, "MAXVALUE")) {
            return "MAXVALUE";
        }
        if (cursor < item.length() && item.charAt(cursor) == '(') {
            int end = matchingParen(item, cursor);
            if (end > cursor) {
                return item.substring(cursor + 1, end).trim();
            }
        }
        return "";
    }


    /**
     * 从指定位置查找下一个顶层子句关键字；引号和括号内的内容不参与匹配。
     */
    private static Keyword nextKeyword(String text, int from) {
        int index = Math.max(from, 0);
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '\'' || c == '"' || c == '`') {
                index = skipDelimited(text, index);
                continue;
            }
            if (c == '(') {
                int end = matchingParen(text, index);
                index = end < 0 ? text.length() : end + 1;
                continue;
            }
            for (String keyword : COLUMN_KEYWORDS) {
                if (matchesKeyword(text, index, keyword)) {
                    return new Keyword(index, keyword);
                }
            }
            index++;
        }
        return null;
    }


    private static boolean matchesKeyword(String text, int index, String keyword) {
        // 子句关键字只在空白之后成立，否则类型名（如 binary）里出现的 NULL 会被当成空值子句
        if (index > 0 && !Character.isWhitespace(text.charAt(index - 1))) {
            return false;
        }
        return regionMatchesWord(text, index, keyword);
    }


    private static boolean regionMatchesWord(String text, int index, String word) {
        if (index < 0 || index + word.length() > text.length()) {
            return false;
        }
        if (!text.regionMatches(true, index, word, 0, word.length())) {
            return false;
        }
        int after = index + word.length();
        if (after >= text.length()) {
            return true;
        }
        char next = text.charAt(after);
        return !Character.isLetterOrDigit(next) && next != '_';
    }


    /**
     * 读取 DEFAULT 或选项值：字面量、标识符、括号表达式，以及带前缀的位串字面量。
     */
    private static ValueToken readValueToken(String text, int from) {
        int index = skipSpaces(text, from);
        if (index >= text.length()) {
            return new ValueToken(null, text.length(), false);
        }
        char first = text.charAt(index);
        if (first == '\'' || first == '"') {
            int end = skipDelimited(text, index);
            return new ValueToken(unquote(text.substring(index, end)), end, false);
        }
        if (first == '(') {
            int end = matchingParen(text, index);
            if (end < 0) {
                return new ValueToken(text.substring(index).trim(), text.length(), true);
            }
            return new ValueToken(unwrapParens(text.substring(index, end + 1)), end + 1, true);
        }
        int end = index;
        while (end < text.length() && isValueChar(text.charAt(end))) {
            end++;
        }
        // DEFAULT b'0' 这类带前缀字面量，前缀之后仍有一个引号串
        if (end < text.length() && (text.charAt(end) == '\'' || text.charAt(end) == '"')) {
            end = skipDelimited(text, end);
        } else if (end < text.length() && text.charAt(end) == '(') {
            int close = matchingParen(text, end);
            if (close > 0) {
                end = close + 1;
            }
        }
        String value = text.substring(index, end).trim();
        // DEFAULT NULL 在 MySQL 语义上是「无默认值」，不能当字符串 "NULL" 暴露
        if ("NULL".equalsIgnoreCase(value)) {
            return new ValueToken(null, end, false);
        }
        return new ValueToken(value, end, false);
    }


    private static boolean isValueChar(char c) {
        return !Character.isWhitespace(c) && c != ',' && c != ')' && c != '(' && c != '\'' && c != '"' && c != '`';
    }


    private static int skipSpaces(String text, int from) {
        int index = Math.max(from, 0);
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }


    private static int skipWord(String text, int from, String word) {
        int index = skipSpaces(text, from);
        if (regionMatchesWord(text, index, word)) {
            return skipSpaces(text, index + word.length());
        }
        return index;
    }


    /**
     * 返回跳过字符串或反引号标识符之后的下标。反引号只认双写转义，引号还认反斜杠转义。
     */
    private static int skipDelimited(String text, int start) {
        char quote = text.charAt(start);
        boolean backslashEscapes = quote != '`';
        int index = start + 1;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (backslashEscapes && c == '\\' && index + 1 < text.length()) {
                index += 2;
                continue;
            }
            if (c == quote) {
                if (index + 1 < text.length() && text.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        return text.length();
    }


    private static int matchingParen(String text, int start) {
        int depth = 0;
        int index = start;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '\'' || c == '"' || c == '`') {
                index = skipDelimited(text, index);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return index;
            }
            index++;
        }
        return -1;
    }


    /**
     * 查找 from 之后第一个顶层左括号，跳过字符串与嵌套括号。
     */
    private static int nextTopLevelParen(String text, int from) {
        int index = Math.max(from, 0);
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '\'' || c == '"' || c == '`') {
                index = skipDelimited(text, index);
                continue;
            }
            if (c == '(') {
                return index;
            }
            if (c == ')') {
                index++;
                continue;
            }
            index++;
        }
        return -1;
    }


    private static int indexOfWord(String text, String word) {
        return indexOfWord(text, word, 0);
    }


    /**
     * 从 from 开始查找独立单词，引号和反引号内部不参与匹配。
     */
    private static int indexOfWord(String text, String word, int from) {
        int index = Math.max(from, 0);
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '\'' || c == '"' || c == '`') {
                index = skipDelimited(text, index);
                continue;
            }
            if (regionMatchesWord(text, index, word) && isWordBoundaryBefore(text, index)) {
                return index;
            }
            index++;
        }
        return -1;
    }


    private static boolean isWordBoundaryBefore(String text, int index) {
        if (index == 0) {
            return true;
        }
        char previous = text.charAt(index - 1);
        return !Character.isLetterOrDigit(previous) && previous != '_';
    }


    private static int firstKeywordEnd(String text, String keyword) {
        int index = indexOfWord(text, keyword.split(" ")[0]);
        return index < 0 ? 0 : index + keyword.length();
    }


    /**
     * 去掉字面量外层引号并还原 MySQL 转义：'' 与 \x 两种写法都要还原，
     * 否则包含反斜杠或引号的默认值、注释会与真实值不一致。
     */
    private static String unquote(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() < 2) {
            return trimmed;
        }
        char quote = trimmed.charAt(0);
        if (quote != '\'' && quote != '"') {
            return trimmed;
        }
        int end = skipDelimited(trimmed, 0);
        String body = trimmed.substring(1, Math.max(1, end - 1));
        StringBuilder result = new StringBuilder(body.length());
        int index = 0;
        while (index < body.length()) {
            char c = body.charAt(index);
            if (c == '\\' && index + 1 < body.length()) {
                char escaped = body.charAt(index + 1);
                result.append(unescape(escaped));
                index += 2;
                continue;
            }
            if (c == quote && index + 1 < body.length() && body.charAt(index + 1) == quote) {
                result.append(quote);
                index += 2;
                continue;
            }
            result.append(c);
            index++;
        }
        return result.toString();
    }


    private static char unescape(char escaped) {
        switch (escaped) {
            case 'n':
                return '\n';
            case 't':
                return '\t';
            case 'r':
                return '\r';
            case '0':
                return '\0';
            case 'b':
                return '\b';
            case 'Z':
                return '\032';
            default:
                return escaped;
        }
    }


    private static String unwrapParens(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.startsWith("(") && trimmed.endsWith(")")
            && matchingParen(trimmed, 0) == trimmed.length() - 1) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }


    /**
     * 生成列表达式：去掉最外层括号，保留内部原文。
     */
    private static String readExpression(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("(")) {
            int end = matchingParen(trimmed, 0);
            if (end > 0) {
                return unwrapParens(trimmed.substring(0, end + 1));
            }
        }
        return trimmed;
    }


    private static List<String> identifiers(String group) {
        List<String> values = new ArrayList<>();
        if (group == null) {
            return values;
        }
        int index = 0;
        while (index < group.length()) {
            char c = group.charAt(index);
            if (c == '`') {
                int end = skipDelimited(group, index);
                values.add(group.substring(index + 1, Math.max(index + 1, end - 1)));
                index = end;
                continue;
            }
            if (c == ',') {
                index++;
                continue;
            }
            int start = index;
            while (index < group.length() && group.charAt(index) != ',' && !Character.isWhitespace(group.charAt(index))) {
                index++;
            }
            if (index == start) {
                // 只有空白字符时内层循环不推进，必须显式跳过，否则外层 while 会原地死循环
                index++;
                continue;
            }
            String value = group.substring(start, index).trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }


    /**
     * 解析 ON DELETE / ON UPDATE 后的动作。动作最多两个单词（SET NULL、NO ACTION），
     * 遇到下一个 ON 或行尾即停止，避免把后续的 ON UPDATE 一起吞掉。
     */
    private static String action(String actions, String name) {
        int index = indexOfWord(actions, "ON");
        while (index >= 0) {
            int cursor = skipSpaces(actions, index + "ON".length());
            if (regionMatchesWord(actions, cursor, name)) {
                int valueStart = skipSpaces(actions, cursor + name.length());
                List<String> words = new ArrayList<>();
                int current = valueStart;
                while (words.size() < 2 && current < actions.length()) {
                    int start = skipSpaces(actions, current);
                    if (start >= actions.length() || regionMatchesWord(actions, start, "ON")) {
                        break;
                    }
                    int end = start;
                    while (end < actions.length() && Character.isLetter(actions.charAt(end))) {
                        end++;
                    }
                    if (end == start) {
                        break;
                    }
                    words.add(actions.substring(start, end));
                    current = end;
                }
                return join(words).toUpperCase(Locale.ROOT);
            }
            index = indexOfWord(actions, "ON", index + "ON".length());
        }
        return "";
    }


    private static List<String> splitTopLevel(String value) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        int index = 0;
        while (index < value.length()) {
            char c = value.charAt(index);
            if (c == '\'' || c == '"' || c == '`') {
                int end = skipDelimited(value, index);
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


    private static String trimTrailingComma(String value) {
        return value.endsWith(",") ? value.substring(0, value.length() - 1).trim() : value;
    }


    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
    }


    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(value);
        }
        return result.toString();
    }


    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }


    private static Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }


    /**
     * 列定义子句关键字的命中结果。
     */
    private static final class Keyword {
        private final int index;
        private final String keyword;


        private Keyword(int index, String keyword) {
            this.index = index;
            this.keyword = keyword;
        }


        private int index() {
            return index;
        }


        private String keyword() {
            return keyword;
        }


        private String name() {
            return keyword.trim().toUpperCase(Locale.ROOT);
        }
    }


    /**
     * 表选项名及其结束位置。
     */
    private static final class OptionMatch {
        private final String name;
        private final int end;


        private OptionMatch(String name, int end) {
            this.name = name;
            this.end = end;
        }
    }


    /**
     * DEFAULT 子句或表选项的取值及其结束位置。
     */
    private static final class ValueToken {
        private final String value;
        private final int end;
        private final boolean expression;


        private ValueToken(String value, int end, boolean expression) {
            this.value = value;
            this.end = end;
            this.expression = expression;
        }
    }


    /**
     * 表级选项，集中承载建表语句尾部解析结果。
     */
    private static final class TableOptions {
        private String comment = "";
        private Long autoIncrement;
        private String engine = "";
        private String charset = "";
        private String collation = "";
        private String rowFormat = "";
        private String createOptions = "";
        private String partitionMethod = "";
        private String partitionExpression = "";
        private String subpartitionMethod = "";
        private String subpartitionExpression = "";
    }


    /**
     * 字段定义，包含 DDL 生成需要的排序规则和生成列表达式。
     */
    static final class Column {
        private final String name;
        private final String type;
        private final boolean nullable;
        private final String defaultValue;
        private final String comment;
        private final String extra;
        private final int ordinal;
        private final String charset;
        private final String collation;
        private final String generationExpression;
        private final boolean autoIncrement;


        Column(String name, String type, boolean nullable, String defaultValue, String comment, String extra, int ordinal,
               String charset, String collation, String generationExpression, boolean autoIncrement) {
            this.name = name;
            this.type = type;
            this.nullable = nullable;
            this.defaultValue = defaultValue;
            this.comment = comment;
            this.extra = extra;
            this.ordinal = ordinal;
            this.charset = charset;
            this.collation = collation;
            this.generationExpression = generationExpression;
            this.autoIncrement = autoIncrement;
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


        String getCharset() {
            return charset;
        }


        String getCollation() {
            return collation;
        }


        /**
         * 生成列表达式，非生成列为空串，与 MySQL information_schema 一致。
         */
        String getGenerationExpression() {
            return generationExpression;
        }


        boolean isAutoIncrement() {
            return autoIncrement;
        }


        boolean isCharacterType() {
            String lower = type.toLowerCase(Locale.ROOT);
            if (lower.startsWith("json") || lower.startsWith("enum") || lower.startsWith("set")) {
                return lower.startsWith("enum") || lower.startsWith("set");
            }
            int jdbcType = jdbcType();
            return jdbcType == Types.VARCHAR || jdbcType == Types.CHAR || jdbcType == Types.LONGVARCHAR;
        }


        int jdbcType() {
            String lower = type.toLowerCase(Locale.ROOT);
            // enum / set 必须先于 text / blob 判断，否则取值里含 "text" 会被误映射成 LONGVARCHAR
            if (lower.startsWith("enum") || lower.startsWith("set")) {
                return Types.CHAR;
            }
            if (lower.startsWith("bigint") || lower.startsWith("serial")) {
                return Types.BIGINT;
            }
            if (lower.startsWith("tinyint")) {
                return Types.TINYINT;
            }
            if (lower.startsWith("smallint")) {
                return Types.SMALLINT;
            }
            if (lower.startsWith("mediumint") || lower.startsWith("int")) {
                return Types.INTEGER;
            }
            if (lower.startsWith("decimal") || lower.startsWith("numeric")) {
                return Types.DECIMAL;
            }
            if (lower.startsWith("double")) {
                return Types.DOUBLE;
            }
            if (lower.startsWith("float")) {
                return Types.FLOAT;
            }
            if (lower.startsWith("bit")) {
                return Types.BIT;
            }
            if (lower.startsWith("datetime") || lower.startsWith("timestamp")) {
                return Types.TIMESTAMP;
            }
            if (lower.startsWith("date")) {
                return Types.DATE;
            }
            if (lower.startsWith("time")) {
                return Types.TIME;
            }
            if (lower.startsWith("year")) {
                return Types.SMALLINT;
            }
            if (lower.startsWith("varbinary")) {
                return Types.VARBINARY;
            }
            if (lower.startsWith("binary")) {
                return Types.BINARY;
            }
            if (lower.contains("blob")) {
                return Types.LONGVARBINARY;
            }
            if (lower.contains("text") || lower.startsWith("json")) {
                return Types.LONGVARCHAR;
            }
            if (lower.startsWith("geometry") || lower.startsWith("point") || lower.startsWith("linestring")
                || lower.startsWith("polygon") || lower.startsWith("multipoint") || lower.startsWith("multilinestring")
                || lower.startsWith("multipolygon") || lower.startsWith("geomcollection")
                || lower.startsWith("geometrycollection")) {
                return Types.VARBINARY;
            }
            return Types.VARCHAR;
        }


        int size() {
            String lower = type.toLowerCase(Locale.ROOT);
            if (lower.startsWith("datetime") || lower.startsWith("timestamp")) {
                return 19;
            }
            if (lower.startsWith("date") || lower.startsWith("time")) {
                return 10;
            }
            if (lower.startsWith("enum") || lower.startsWith("set")) {
                return maxEnumValueLength(type);
            }
            Matcher matcher = LENGTH_PATTERN.matcher(type);
            if (matcher.find()) {
                return parseInt(matcher.group(1));
            }
            if (lower.startsWith("tinytext") || lower.startsWith("tinyblob")) {
                return 255;
            }
            if (lower.startsWith("mediumtext") || lower.startsWith("mediumblob")) {
                return 16_777_215;
            }
            if (lower.startsWith("longtext") || lower.startsWith("longblob")) {
                return Integer.MAX_VALUE;
            }
            if (lower.contains("text") || lower.contains("blob") || lower.startsWith("json")) {
                return 65_535;
            }
            return 0;
        }


        int scale() {
            String lower = type.toLowerCase(Locale.ROOT);
            Matcher matcher = LENGTH_PATTERN.matcher(type);
            if (lower.startsWith("datetime") || lower.startsWith("timestamp") || lower.startsWith("time")) {
                return matcher.find() ? parseInt(matcher.group(1)) : 0;
            }
            Matcher scaleMatcher = SCALE_PATTERN.matcher(type);
            return scaleMatcher.find() ? parseInt(scaleMatcher.group(2)) : 0;
        }


        private static int maxEnumValueLength(String type) {
            int open = type.indexOf('(');
            int close = type.lastIndexOf(')');
            if (open < 0 || close <= open) {
                return 0;
            }
            int max = 0;
            for (String item : splitTopLevel(type.substring(open + 1, close))) {
                String value = unquote(item.trim());
                if (value != null) {
                    max = Math.max(max, value.length());
                }
            }
            return max;
        }
    }


    /**
     * 索引定义，保留前缀长度、排序方向、索引类型和索引注释。
     */
    static final class Index {
        private final String name;
        private final boolean unique;
        private final boolean primary;
        private final List<IndexColumn> columns;
        private final String type;
        private final String comment;


        Index(String name, boolean unique, boolean primary, List<IndexColumn> columns, String type, String comment) {
            this.name = name;
            this.unique = unique;
            this.primary = primary;
            this.columns = Collections.unmodifiableList(columns);
            this.type = type;
            this.comment = comment;
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


        List<IndexColumn> getColumns() {
            return columns;
        }


        /**
         * 索引字段名列表，函数索引保留表达式。
         */
        List<String> getColumnNames() {
            List<String> names = new ArrayList<>();
            for (IndexColumn column : columns) {
                names.add(column.getName());
            }
            return names;
        }


        String getComment() {
            return comment;
        }


        /**
         * information_schema.statistics.index_type 语义，未显式声明时为 BTREE。
         */
        String indexType() {
            return type.isEmpty() ? "BTREE" : type;
        }
    }


    /**
     * 索引中的单个字段，subPart 为前缀长度，direction 为 A / D。
     */
    static final class IndexColumn {
        private final String name;
        private final int subPart;
        private final String direction;
        private final String expression;


        IndexColumn(String name, int subPart, String direction, String expression) {
            this.name = name;
            this.subPart = subPart;
            this.direction = direction;
            this.expression = expression;
        }


        String getName() {
            return name;
        }


        int getSubPart() {
            return subPart;
        }


        String getDirection() {
            return direction;
        }


        String getExpression() {
            return expression;
        }
    }


    /**
     * 外键定义，来自建表语句中的 FOREIGN KEY 子句。
     */
    static final class ForeignKey {
        private final String name;
        private final List<String> columns;
        private final String referencedSchema;
        private final List<String> referencedColumns;
        private final String referencedTable;
        private final String deleteRule;
        private final String updateRule;


        ForeignKey(String name, List<String> columns, String referencedSchema, List<String> referencedColumns,
                   String referencedTable, String deleteRule, String updateRule) {
            this.name = name;
            this.columns = Collections.unmodifiableList(columns);
            this.referencedSchema = referencedSchema;
            this.referencedColumns = Collections.unmodifiableList(referencedColumns);
            this.referencedTable = referencedTable;
            this.deleteRule = deleteRule;
            this.updateRule = updateRule;
        }


        String getName() {
            return name;
        }


        List<String> getColumns() {
            return columns;
        }


        String getReferencedSchema() {
            return referencedSchema;
        }


        List<String> getReferencedColumns() {
            return referencedColumns;
        }


        String getReferencedTable() {
            return referencedTable;
        }


        String getDeleteRule() {
            return deleteRule;
        }


        String getUpdateRule() {
            return updateRule;
        }
    }


    /**
     * CHECK 约束定义。
     */
    static final class CheckConstraint {
        private final String name;
        private final String expression;


        CheckConstraint(String name, String expression) {
            this.name = name;
            this.expression = expression;
        }


        String getName() {
            return name;
        }


        String getExpression() {
            return expression;
        }
    }


    /**
     * 分区定义。
     */
    static final class Partition {
        private final String name;
        private final String description;
        private final String comment;


        Partition(String name, String description, String comment) {
            this.name = name;
            this.description = description;
            this.comment = comment;
        }


        String getName() {
            return name;
        }


        String getDescription() {
            return description;
        }


        String getComment() {
            return comment;
        }
    }
}
