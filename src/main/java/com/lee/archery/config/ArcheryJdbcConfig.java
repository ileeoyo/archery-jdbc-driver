package com.lee.archery.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Archery JDBC 连接配置，负责把 JDBC URL 和 Properties 解析成后续 HTTP 请求需要的业务参数。
 */
public final class ArcheryJdbcConfig {
    /**
     * Archery JDBC URL 前缀。
     */
    public static final String URL_PREFIX = "jdbc:archery:";

    private static final int DEFAULT_LIMIT_NUM = 500;
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int DEFAULT_READ_TIMEOUT_MILLIS = 60_000;

    private final String baseUrl;
    private final String contextPath;
    private final String instanceName;
    private final String dbName;
    private final String schemaName;
    private final String username;
    private final String password;
    private final int limitNum;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final String debugLogPath;


    private ArcheryJdbcConfig(String baseUrl, String contextPath, String instanceName, String dbName,
                              String schemaName, String username, String password, int limitNum, int connectTimeoutMillis,
                              int readTimeoutMillis, String debugLogPath) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.contextPath = normalizeContextPath(contextPath);
        this.instanceName = instanceName;
        this.dbName = dbName;
        this.schemaName = schemaName;
        this.username = username;
        this.password = password;
        this.limitNum = limitNum;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.debugLogPath = debugLogPath;
    }


    /**
     * 从 JDBC URL 和 Properties 解析连接配置，Properties 中的值优先级更高。
     */
    public static ArcheryJdbcConfig parse(String url, Properties info) throws SQLException {
        if (url == null || !url.startsWith(URL_PREFIX)) {
            throw new SQLException("Archery JDBC URL must start with " + URL_PREFIX);
        }

        Properties properties = new Properties();
        if (info != null) {
            properties.putAll(info);
        }

        String rawTarget = url.substring(URL_PREFIX.length());
        URI uri = parseTargetUri(rawTarget);
        Map<String, String> queryParams = parseQuery(uri.getRawQuery());

        String baseUrl = override(properties, queryParams, "baseUrl", buildBaseUrl(uri));
        String contextPath = override(properties, queryParams, "contextPath", "/sql");
        String instanceName = override(properties, queryParams, "instanceName", null);
        String dbName = override(properties, queryParams, "dbName", null);
        String schemaName = override(properties, queryParams, "schemaName", "");
        String username = override(properties, queryParams, "username", properties.getProperty("user"));
        String password = override(properties, queryParams, "password", null);
        int limitNum = parsePositiveInt(override(properties, queryParams, "limitNum", String.valueOf(DEFAULT_LIMIT_NUM)), "limitNum");
        int connectTimeoutMillis = parsePositiveInt(override(properties, queryParams, "connectTimeoutMillis",
            String.valueOf(DEFAULT_CONNECT_TIMEOUT_MILLIS)), "connectTimeoutMillis");
        int readTimeoutMillis = parsePositiveInt(override(properties, queryParams, "readTimeoutMillis",
            String.valueOf(DEFAULT_READ_TIMEOUT_MILLIS)), "readTimeoutMillis");
        String debugLogPath = override(properties, queryParams, "debugLog", "");

        requireText(baseUrl, "baseUrl");
        requireText(instanceName, "instanceName");
        requireText(username, "username");
        requireText(password, "password");

        return new ArcheryJdbcConfig(baseUrl, contextPath, instanceName, nullToEmpty(dbName), schemaName, username, password,
            limitNum, connectTimeoutMillis, readTimeoutMillis, debugLogPath);
    }


    public String getBaseUrl() {
        return baseUrl;
    }


    public String getContextPath() {
        return contextPath;
    }


    public String getInstanceName() {
        return instanceName;
    }


    public String getDbName() {
        return dbName;
    }


    public String getSchemaName() {
        return schemaName;
    }


    public String getUsername() {
        return username;
    }


    public String getPassword() {
        return password;
    }


    public int getLimitNum() {
        return limitNum;
    }


    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }


    public int getReadTimeoutMillis() {
        return readTimeoutMillis;
    }


    public String getDebugLogPath() {
        return debugLogPath;
    }


    /**
     * 拼接 Archery Web 业务路径，允许 contextPath 为空以兼容根路径挂载。
     */
    public String webUrl(String path) {
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return baseUrl + contextPath + normalizedPath;
    }


    /**
     * 拼接不带业务 contextPath 的 Web 业务路径，用于兼容 Archery 根路径挂载。
     */
    public String rootWebUrl(String path) {
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return baseUrl + normalizedPath;
    }


    /**
     * 拼接不带业务 contextPath 的站点路径，用于登录页和认证接口。
     */
    public String siteUrl(String path) {
        return rootWebUrl(path);
    }


    private static URI parseTargetUri(String rawTarget) throws SQLException {
        try {
            return new URI(rawTarget);
        } catch (URISyntaxException e) {
            throw new SQLException("Invalid Archery JDBC target URL: " + rawTarget, e);
        }
    }


    private static String buildBaseUrl(URI uri) throws SQLException {
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new SQLException("Archery base URL must include scheme and host");
        }
        StringBuilder builder = new StringBuilder();
        builder.append(uri.getScheme()).append("://").append(uri.getHost());
        if (uri.getPort() >= 0) {
            builder.append(':').append(uri.getPort());
        }
        return builder.toString();
    }


    private static Map<String, String> parseQuery(String rawQuery) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            if (separator < 0) {
                result.put(decode(pair), "");
            } else {
                result.put(decode(pair.substring(0, separator)), decode(pair.substring(separator + 1)));
            }
        }
        return result;
    }


    private static String decode(String value) throws SQLException {
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new SQLException("UTF-8 is not supported", e);
        }
    }


    private static String override(Properties properties, Map<String, String> queryParams, String key, String defaultValue) {
        String propertyValue = properties.getProperty(key);
        if (propertyValue != null) {
            return propertyValue;
        }
        String queryValue = queryParams.get(key);
        return queryValue != null ? queryValue : defaultValue;
    }


    private static int parsePositiveInt(String value, String name) throws SQLException {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new SQLException(name + " must be positive");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new SQLException(name + " must be a positive integer", e);
        }
    }


    private static void requireText(String value, String name) throws SQLException {
        if (value == null || value.trim().isEmpty()) {
            throw new SQLException("Missing required Archery JDBC config: " + name);
        }
    }


    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }


    private static String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }


    private static String normalizeContextPath(String value) {
        if (value == null || value.trim().isEmpty() || "/".equals(value.trim())) {
            return "";
        }
        String normalized = value.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return trimTrailingSlash(normalized);
    }
}
