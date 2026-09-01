package com.lee.archery.tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Properties;

/**
 * Archery JDBC 命令行查询入口，供 Skill 或 shell 通过 fat jar 调用。
 *
 * 用法：
 *   java -jar archery-query-runner.jar --base-url https://archery.example.com --instance xxx --db-name xxx --format csv --sql "SELECT ..."
 *   echo "SELECT ..." | java -jar archery-query-runner.jar --base-url https://archery.example.com --instance xxx --limit 0 --format json
 *
 * 凭据从环境变量读取，避免出现在命令行参数中：
 *   ARCHERY_USER          Archery 用户名
 *   ARCHERY_PASSWORD      Archery 密码
 *
 * 非敏感连接配置支持命令行参数优先、环境变量兜底：
 *   ARCHERY_BASE_URL      Archery 站点地址
 *   ARCHERY_INSTANCE      Archery 实例名
 *   ARCHERY_DB_NAME       默认 database，可空
 *   ARCHERY_LIMIT         查询最大返回行数，默认 500，0 表示 Archery max
 *
 * SQL 来源优先级：--sql 参数 > 标准输入。
 * 退出码：0 成功；2 参数/环境问题；1 查询或连接失败。
 */
public final class QueryRunner {

    private static final int EXIT_QUERY_ERROR = 1;
    private static final int EXIT_USAGE_ERROR = 2;
    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_CELL_LENGTH = 2000;

    public static void main(String[] args) {
        String sql = null;
        String format = "csv";
        String baseUrlArgument = null;
        String instanceArgument = null;
        String dbNameArgument = null;
        String limitArgument = null;
        for (int i = 0; i < args.length; i++) {
            if ("--sql".equals(args[i]) && i + 1 < args.length) {
                sql = args[++i];
            } else if ("--format".equals(args[i]) && i + 1 < args.length) {
                format = args[++i];
            } else if ("--base-url".equals(args[i]) && i + 1 < args.length) {
                baseUrlArgument = args[++i];
            } else if ("--instance".equals(args[i]) && i + 1 < args.length) {
                instanceArgument = args[++i];
            } else if ("--db-name".equals(args[i]) && i + 1 < args.length) {
                dbNameArgument = args[++i];
            } else if ("--limit".equals(args[i]) && i + 1 < args.length) {
                limitArgument = args[++i];
            } else {
                fail(EXIT_USAGE_ERROR, "未知参数: " + args[i]
                    + "，支持 --sql <sql>、--format csv|json、--base-url <url>、--instance <name>、--db-name <db> 和 --limit <num>");
            }
        }
        if (!"csv".equals(format) && !"json".equals(format)) {
            fail(EXIT_USAGE_ERROR, "--format 只支持 csv 或 json，当前值: " + format);
        }
        if (sql == null) {
            sql = readStdin();
        }
        if (sql.isEmpty()) {
            fail(EXIT_USAGE_ERROR, "未提供 SQL，请通过 --sql 参数或标准输入传入");
        }

        // 命令行参数描述本次查询目标，环境变量作为默认连接上下文。
        String baseUrl = requiredValue(firstNonEmpty(baseUrlArgument, env("ARCHERY_BASE_URL", "")), "base-url 或 ARCHERY_BASE_URL");
        String instance = requiredValue(firstNonEmpty(instanceArgument, env("ARCHERY_INSTANCE", "")), "instance 或 ARCHERY_INSTANCE");
        String db = firstNonEmpty(dbNameArgument, env("ARCHERY_DB_NAME", ""));
        String username = requireEnv("ARCHERY_USER");
        String password = requireEnv("ARCHERY_PASSWORD");
        int limit = parseLimit(firstNonEmpty(limitArgument, env("ARCHERY_LIMIT", String.valueOf(DEFAULT_LIMIT))));

        StringBuilder url = new StringBuilder("jdbc:archery:").append(baseUrl)
                .append("?instanceName=").append(urlEncode(instance))
                .append("&limitNum=").append(limit);
        if (!db.isEmpty()) {
            url.append("&dbName=").append(urlEncode(db));
        }

        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);

        try (Connection conn = DriverManager.getConnection(url.toString(), props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if ("json".equals(format)) {
                printJson(rs);
            } else {
                printCsv(rs);
            }
        } catch (Exception e) {
            fail(EXIT_QUERY_ERROR, "查询失败: " + e.getMessage());
        }
    }

    /** CSV 输出首行表头，单元格做引号转义，超长内容截断防止刷屏。 */
    private static void printCsv(ResultSet rs) throws Exception {
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        StringBuilder header = new StringBuilder();
        for (int i = 1; i <= cols; i++) {
            if (i > 1) {
                header.append(',');
            }
            header.append(csvEscape(meta.getColumnLabel(i)));
        }
        System.out.println(header);

        while (rs.next()) {
            StringBuilder row = new StringBuilder();
            for (int i = 1; i <= cols; i++) {
                if (i > 1) {
                    row.append(',');
                }
                row.append(csvEscape(truncate(rs.getString(i))));
            }
            System.out.println(row);
        }
    }

    /** JSON 输出固定为 columns、rows 和 rowCount，行值统一为字符串或 null。 */
    private static void printJson(ResultSet rs) throws Exception {
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        StringBuilder out = new StringBuilder("{\"columns\":[");
        for (int i = 1; i <= cols; i++) {
            if (i > 1) {
                out.append(',');
            }
            out.append(jsonString(meta.getColumnLabel(i)));
        }
        out.append("],\"rows\":[");

        int rowCount = 0;
        while (rs.next()) {
            if (rowCount > 0) {
                out.append(',');
            }
            out.append('[');
            for (int i = 1; i <= cols; i++) {
                if (i > 1) {
                    out.append(',');
                }
                String value = rs.getString(i);
                out.append(value == null ? "null" : jsonString(truncate(value)));
            }
            out.append(']');
            rowCount++;
        }
        out.append("],\"rowCount\":").append(rowCount).append('}');
        System.out.println(out);
    }

    private static String readStdin() {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            fail(EXIT_USAGE_ERROR, "读取标准输入失败: " + e.getMessage());
        }
        return sb.toString().trim();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > MAX_CELL_LENGTH ? value.substring(0, MAX_CELL_LENGTH) + "..." : value;
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null ? defaultValue : value.trim();
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            fail(EXIT_USAGE_ERROR, "缺少环境变量 " + name + "，请先配置 Archery 连接信息");
        }
        return value.trim();
    }

    private static String requiredValue(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            fail(EXIT_USAGE_ERROR, "缺少 " + name + "，请通过命令行参数或环境变量配置");
        }
        return value.trim();
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.trim().isEmpty() ? first.trim() : second.trim();
    }

    private static int parseLimit(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                fail(EXIT_USAGE_ERROR, "limit 不能小于 0: " + value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            fail(EXIT_USAGE_ERROR, "limit 不是合法数字: " + value);
            return DEFAULT_LIMIT;
        }
    }

    /** URL 查询参数值转义，仅处理 Archery 实例名和库名中可能出现的特殊字符。 */
    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static void fail(int exitCode, String message) {
        System.err.println(message);
        System.exit(exitCode);
    }

    private QueryRunner() {
    }
}
