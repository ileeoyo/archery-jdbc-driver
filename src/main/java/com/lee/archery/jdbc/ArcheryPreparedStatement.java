package com.lee.archery.jdbc;

import com.lee.archery.config.ArcheryJdbcConfig;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Archery PreparedStatement，第一版仅保存参数并执行原始只读 SQL，不做服务端预编译。
 */
public final class ArcheryPreparedStatement implements InvocationHandler {
    private final ArcheryJdbcConfig config;
    private final Statement delegate;
    private final String sql;
    private final Map<Integer, Object> parameters = new LinkedHashMap<>();


    private ArcheryPreparedStatement(ArcheryJdbcConfig config, Statement delegate, String sql) {
        this.config = config;
        this.delegate = delegate;
        this.sql = sql;
    }


    public static PreparedStatement create(ArcheryConnection connection, String sql) {
        return create(connection, sql, java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY);
    }


    public static PreparedStatement create(ArcheryConnection connection, String sql, int resultSetType, int resultSetConcurrency) {
        Statement statement = ArcheryStatement.create(connection, resultSetType, resultSetConcurrency);
        return ArcheryJdbcObjects.proxy(PreparedStatement.class,
            new ArcheryPreparedStatement(connection.config(), statement, sql));
    }


    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (ArcheryJdbcObjects.isWrapperMethod(method)) {
            return ArcheryJdbcObjects.handleWrapper(proxy, method, args, this);
        }
        String name = method.getName();
        ArcheryDebugLog.write(config, "PreparedStatement." + name + ArcheryJdbcObjects.methodArgs(args)
            + ", sql=" + ArcheryJdbcObjects.abbreviate(sql, 500));
        if ("setNull".equals(name) && args != null && args.length >= 1 && args[0] instanceof Number) {
            parameters.put(((Number) args[0]).intValue(), null);
            return null;
        }
        if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Number) {
            parameters.put(((Number) args[0]).intValue(), args[1]);
            return null;
        }
        if ("clearParameters".equals(name)) {
            parameters.clear();
            return null;
        }
        if ("executeQuery".equals(name) && noSqlArgument(args)) {
            return delegate.executeQuery(renderSql());
        }
        if ("execute".equals(name) && noSqlArgument(args)) {
            return delegate.execute(renderSql());
        }
        if ("executeQuery".equals(name) || "execute".equals(name)
            || "executeUpdate".equals(name) || "executeLargeUpdate".equals(name)) {
            throw ArcheryJdbcObjects.unsupported("SQL argument on PreparedStatement");
        }
        if ("getMetaData".equals(name)) {
            throw ArcheryJdbcObjects.unsupported("PreparedStatement metadata");
        }
        if ("getParameterMetaData".equals(name)) {
            return parameterMetaData();
        }
        if (method.getDeclaringClass().isInstance(delegate)) {
            return method.invoke(delegate, args);
        }
        return ArcheryJdbcObjects.defaultValue(method.getReturnType());
    }


    private boolean noSqlArgument(Object[] args) {
        return args == null || args.length == 0;
    }


    private String renderSql() throws SQLException {
        StringBuilder rendered = new StringBuilder();
        int parameterIndex = 1;
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (lineComment) {
                rendered.append(current);
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                }
            } else if (blockComment) {
                rendered.append(current);
                if (current == '*' && next == '/') {
                    rendered.append(next);
                    index++;
                    blockComment = false;
                }
            } else if (singleQuoted) {
                rendered.append(current);
                if (current == '\\' && next != '\0') {
                    rendered.append(next);
                    index++;
                } else if (current == '\'' && next == '\'') {
                    rendered.append(next);
                    index++;
                } else if (current == '\'') {
                    singleQuoted = false;
                }
            } else if (doubleQuoted) {
                rendered.append(current);
                if (current == '\\' && next != '\0') {
                    rendered.append(next);
                    index++;
                } else if (current == '"' && next == '"') {
                    rendered.append(next);
                    index++;
                } else if (current == '"') {
                    doubleQuoted = false;
                }
            } else if (current == '-' && next == '-') {
                rendered.append(current).append(next);
                index++;
                lineComment = true;
            } else if (current == '#') {
                rendered.append(current);
                lineComment = true;
            } else if (current == '/' && next == '*') {
                rendered.append(current).append(next);
                index++;
                blockComment = true;
            } else if (current == '\'') {
                rendered.append(current);
                singleQuoted = true;
            } else if (current == '"') {
                rendered.append(current);
                doubleQuoted = true;
            } else if (current == '?') {
                if (!parameters.containsKey(parameterIndex)) {
                    throw new SQLException("Missing prepared statement parameter: " + parameterIndex);
                }
                rendered.append(literal(parameters.get(parameterIndex++)));
            } else {
                rendered.append(current);
            }
        }
        return rendered.toString();
    }


    private ParameterMetaData parameterMetaData() throws SQLException {
        int parameterCount = countParameters();
        return ArcheryJdbcObjects.proxy(ParameterMetaData.class, (metadataProxy, metadataMethod, metadataArgs) -> {
            if (ArcheryJdbcObjects.isWrapperMethod(metadataMethod)) {
                return ArcheryJdbcObjects.handleWrapper(metadataProxy, metadataMethod, metadataArgs, this);
            }
            String name = metadataMethod.getName();
            if ("getParameterCount".equals(name)) {
                return parameterCount;
            }
            if ("getParameterMode".equals(name)) {
                return ParameterMetaData.parameterModeIn;
            }
            if ("isNullable".equals(name)) {
                return ParameterMetaData.parameterNullableUnknown;
            }
            if ("getParameterType".equals(name)) {
                return java.sql.Types.VARCHAR;
            }
            if ("getParameterTypeName".equals(name)) {
                return "VARCHAR";
            }
            if ("getParameterClassName".equals(name)) {
                return String.class.getName();
            }
            return ArcheryJdbcObjects.defaultValue(metadataMethod.getReturnType());
        });
    }


    private int countParameters() {
        int parameterCount = 0;
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                }
            } else if (blockComment) {
                if (current == '*' && next == '/') {
                    index++;
                    blockComment = false;
                }
            } else if (singleQuoted) {
                if (current == '\\' && next != '\0') {
                    index++;
                } else if (current == '\'' && next == '\'') {
                    index++;
                } else if (current == '\'') {
                    singleQuoted = false;
                }
            } else if (doubleQuoted) {
                if (current == '\\' && next != '\0') {
                    index++;
                } else if (current == '"' && next == '"') {
                    index++;
                } else if (current == '"') {
                    doubleQuoted = false;
                }
            } else if (current == '-' && next == '-') {
                index++;
                lineComment = true;
            } else if (current == '#') {
                lineComment = true;
            } else if (current == '/' && next == '*') {
                index++;
                blockComment = true;
            } else if (current == '\'') {
                singleQuoted = true;
            } else if (current == '"') {
                doubleQuoted = true;
            } else if (current == '?') {
                parameterCount++;
            }
        }
        return parameterCount;
    }


    private String literal(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

}
