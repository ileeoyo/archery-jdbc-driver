package com.lee.archery.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lee.archery.config.ArcheryJdbcConfig;
import com.lee.archery.util.ArcheryStrings;
import okhttp3.*;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Archery Web HTTP 客户端，负责维护 Django Cookie、CSRF，并把 Web 接口响应转换为 JDBC 异常或数据对象。
 */
public final class ArcheryHttpClient implements AutoCloseable {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ArcheryJdbcConfig config;
    private final InMemoryCookieJar cookieJar;
    private final OkHttpClient httpClient;
    private ArcherySession session;


    public ArcheryHttpClient(ArcheryJdbcConfig config) {
        this.config = config;
        this.cookieJar = new InMemoryCookieJar();
        this.httpClient = new OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(config.getConnectTimeoutMillis(), TimeUnit.MILLISECONDS)
            .readTimeout(config.getReadTimeoutMillis(), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    }


    /**
     * 执行账号密码登录，登录页用于获取 CSRF，认证接口用于建立 Django session。
     */
    public synchronized void login() throws SQLException {
        String csrfToken = loadLoginPageCsrf();
        postForm(config.siteUrl("/authenticate/"), new FormBody.Builder()
            .add("username", config.getUsername())
            .add("password", config.getPassword())
            .add("csrfmiddlewaretoken", csrfToken)
            .build(), csrfToken, true);
        this.session = new ArcherySession(findCsrfToken(csrfToken));
    }


    /**
     * 执行只读 SQL 查询，登录过期时自动重登一次。
     */
    public ArcheryQueryResponse query(ArcheryQueryRequest request) throws SQLException {
        FormBody body = new FormBody.Builder()
            .add("instance_name", config.getInstanceName())
            .add("db_name", request.getDbName().isEmpty() ? config.getDbName() : request.getDbName())
            .add("schema_name", request.getSchemaName().isEmpty() ? config.getSchemaName() : request.getSchemaName())
            .add("tb_name", request.getTableName())
            .add("sql_content", request.getSql())
            .add("limit_num", String.valueOf(config.getLimitNum()))
            .build();
        JsonNode data = executeWithRelogin(() -> postWebForm("/query/", body));
        return new ArcheryQueryResponse(data);
    }


    /**
     * 查询 Archery 实例资源，用于 DatabaseMetaData 的库、schema、表、字段补全。
     */
    public ArcheryResourceResponse resource(String resourceType, String dbName, String schemaName, String tableName)
        throws SQLException {
        JsonNode data = executeWithRelogin(() -> getWebJson("/instance/instance_resource/", builder -> builder
            .addQueryParameter("instance_name", config.getInstanceName())
            .addQueryParameter("db_name", nullToEmpty(dbName))
            .addQueryParameter("schema_name", nullToEmpty(schemaName))
            .addQueryParameter("tb_name", nullToEmpty(tableName))
            .addQueryParameter("resource_type", resourceType)));
        return new ArcheryResourceResponse(readNames(data));
    }


    /**
     * 查询表结构，字段资源不可用时可作为 columns metadata 的补充来源。
     */
    public ArcheryQueryResponse describeTable(String dbName, String schemaName, String tableName) throws SQLException {
        FormBody body = new FormBody.Builder()
            .add("instance_name", config.getInstanceName())
            .add("db_name", nullToEmpty(dbName))
            .add("schema_name", nullToEmpty(schemaName))
            .add("tb_name", nullToEmpty(tableName))
            .build();
        JsonNode data = executeWithRelogin(() -> postWebForm("/instance/describetable/", body));
        return new ArcheryQueryResponse(data);
    }


    @Override
    public void close() {
        cookieJar.clear();
        session = null;
    }


    private JsonNode executeWithRelogin(SqlCallable<JsonNode> callable) throws SQLException {
        ensureLoggedIn();
        try {
            return callable.call();
        } catch (SQLInvalidAuthorizationSpecException e) {
            login();
            return callable.call();
        }
    }


    private synchronized void ensureLoggedIn() throws SQLException {
        if (session == null) {
            login();
        }
    }


    private ArcherySession requireSession() throws SQLInvalidAuthorizationSpecException {
        if (session == null) {
            throw new SQLInvalidAuthorizationSpecException("Archery session is not initialized");
        }
        return session;
    }


    private String loadLoginPageCsrf() throws SQLException {
        Request request = new Request.Builder().url(config.siteUrl("/login/")).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() >= 400) {
                throw new SQLInvalidAuthorizationSpecException("Archery login page returned HTTP " + response.code());
            }
            String fromCookie = findCsrfToken(null);
            if (fromCookie != null && !fromCookie.isEmpty()) {
                return fromCookie;
            }
            String html = readBody(response);
            String fromHtml = findHtmlCsrf(html);
            if (fromHtml == null || fromHtml.isEmpty()) {
                throw new SQLInvalidAuthorizationSpecException("Archery login page did not provide CSRF token");
            }
            return fromHtml;
        } catch (IOException e) {
            throw new SQLException("Failed to load Archery login page", e);
        }
    }


    private JsonNode getWebJson(String path, UrlCustomizer customizer) throws SQLException {
        try {
            return getJson(customize(HttpUrl.parse(config.webUrl(path)).newBuilder(), customizer).build().toString());
        } catch (ArcheryNotFoundException e) {
            return getJson(customize(HttpUrl.parse(config.rootWebUrl(path)).newBuilder(), customizer).build().toString());
        }
    }


    private JsonNode postWebForm(String path, RequestBody body) throws SQLException {
        try {
            return postForm(config.webUrl(path), body, requireSession().getCsrfToken());
        } catch (ArcheryNotFoundException e) {
            return postForm(config.rootWebUrl(path), body, requireSession().getCsrfToken());
        }
    }


    private JsonNode getJson(String url) throws SQLException {
        Request request = new Request.Builder().url(url).get().build();
        return parseJsonResponse(execute(request), false);
    }


    private JsonNode postForm(String url, RequestBody body, String csrfToken) throws SQLException {
        return postForm(url, body, csrfToken, false);
    }


    private JsonNode postForm(String url, RequestBody body, String csrfToken, boolean loginResponse) throws SQLException {
        Request request = new Request.Builder()
            .url(url)
            .post(body)
            .header("X-CSRFToken", csrfToken)
            .header("Referer", config.getBaseUrl() + "/")
            .build();
        return parseJsonResponse(execute(request), loginResponse);
    }


    private Response execute(Request request) throws SQLException {
        try {
            return httpClient.newCall(request).execute();
        } catch (IOException e) {
            throw new SQLException("Archery HTTP request failed: " + request.url(), e);
        }
    }


    private JsonNode parseJsonResponse(Response response, boolean loginResponse) throws SQLException {
        try (Response closable = response) {
            int code = closable.code();
            String body = readBody(closable);
            if (code == 302 || code == 401 || code == 403) {
                throw new SQLInvalidAuthorizationSpecException("Archery authentication expired or forbidden, HTTP " + code);
            }
            if (code == 404) {
                throw new ArcheryNotFoundException("Archery endpoint not found: " + ArcheryStrings.abbreviate(body, 300));
            }
            if (code >= 400) {
                throw new SQLException("Archery returned HTTP " + code + ": " + ArcheryStrings.abbreviate(body, 300));
            }
            String contentType = closable.header("Content-Type", "");
            if (contentType.contains("text/html") || looksLikeHtml(body)) {
                throw new SQLInvalidAuthorizationSpecException("Archery returned HTML page instead of JSON, login may be required");
            }
            JsonNode json = OBJECT_MAPPER.readTree(body);
            ensureSuccess(json, loginResponse ? "Archery login failed" : "Archery request failed", loginResponse);
            return json.path("data");
        } catch (IOException e) {
            throw new SQLException("Failed to parse Archery JSON response", e);
        }
    }


    private void ensureSuccess(JsonNode json, String message, boolean authorizationError) throws SQLException {
        int status = json.path("status").asInt(0);
        if (status == 0) {
            return;
        }
        String archeryMessage = json.path("msg").asText(message);
        if (authorizationError) {
            throw new SQLInvalidAuthorizationSpecException(archeryMessage);
        }
        throw new SQLException(archeryMessage);
    }


    private String readBody(Response response) throws SQLException {
        ResponseBody body = response.body();
        if (body == null) {
            return "";
        }
        try {
            return body.string();
        } catch (IOException e) {
            throw new SQLException("Failed to read Archery HTTP response", e);
        }
    }


    private String findCsrfToken(String fallback) {
        for (List<Cookie> cookies : cookieJar.snapshot().values()) {
            for (Cookie cookie : cookies) {
                if ("csrftoken".equalsIgnoreCase(cookie.name())) {
                    return cookie.value();
                }
            }
        }
        return fallback;
    }


    private String findHtmlCsrf(String html) {
        String marker = "name=\"csrfmiddlewaretoken\"";
        int markerIndex = html.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        int valueIndex = html.indexOf("value=\"", markerIndex);
        if (valueIndex < 0) {
            return null;
        }
        int start = valueIndex + "value=\"".length();
        int end = html.indexOf('"', start);
        return end > start ? html.substring(start, end) : null;
    }


    private List<String> readNames(JsonNode data) {
        List<String> names = new ArrayList<>();
        if (!data.isArray()) {
            return names;
        }
        for (JsonNode item : data) {
            if (item.isTextual()) {
                names.add(item.asText());
            } else if (item.hasNonNull("name")) {
                names.add(item.path("name").asText());
            } else if (item.hasNonNull("text")) {
                names.add(item.path("text").asText());
            } else if (item.hasNonNull("value")) {
                names.add(item.path("value").asText());
            }
        }
        return names;
    }


    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }


    private boolean looksLikeHtml(String body) {
        String trimmed = body.trim().toLowerCase();
        return trimmed.startsWith("<!doctype html") || trimmed.startsWith("<html");
    }


    private HttpUrl.Builder customize(HttpUrl.Builder builder, UrlCustomizer customizer) {
        return customizer.customize(builder);
    }


    private interface SqlCallable<T> {
        T call() throws SQLException;
    }


    private interface UrlCustomizer {
        HttpUrl.Builder customize(HttpUrl.Builder builder);
    }


    /**
     * Archery 路由未命中异常，用于在 /sql 前缀和根路径之间做兼容重试。
     */
    private static final class ArcheryNotFoundException extends SQLException {
        private ArcheryNotFoundException(String message) {
            super(message);
        }
    }


    /**
     * 简单内存 CookieJar，按 host 保存 Django session 和 CSRF cookie。
     */
    private static final class InMemoryCookieJar implements CookieJar {
        private final Map<String, List<Cookie>> store = new HashMap<>();


        @Override
        public synchronized void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            List<Cookie> current = new ArrayList<>(store.getOrDefault(url.host(), new ArrayList<>()));
            for (Cookie cookie : cookies) {
                current.removeIf(existing -> existing.name().equals(cookie.name()));
                current.add(cookie);
            }
            store.put(url.host(), current);
        }


        @Override
        public synchronized List<Cookie> loadForRequest(HttpUrl url) {
            return new ArrayList<>(store.getOrDefault(url.host(), new ArrayList<>()));
        }


        synchronized Map<String, List<Cookie>> snapshot() {
            return new HashMap<>(store);
        }


        synchronized void clear() {
            store.clear();
        }
    }
}
