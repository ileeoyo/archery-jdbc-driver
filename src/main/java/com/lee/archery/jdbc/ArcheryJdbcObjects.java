package com.lee.archery.jdbc;

import com.lee.archery.util.ArcheryStrings;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.*;
import java.util.Locale;

/**
 * JDBC 接口代理工具，集中处理 Wrapper、默认值和不支持能力，避免业务类充斥样板方法。
 */
final class ArcheryJdbcObjects {
    private ArcheryJdbcObjects() {
    }


    static SQLException closed(String target) {
        return new SQLException(target + " is closed");
    }


    static SQLFeatureNotSupportedException unsupported(String feature) {
        return new SQLFeatureNotSupportedException(feature + " is not supported by Archery JDBC Driver");
    }


    static boolean isReadOnlySql(String sql) {
        if (sql == null) {
            return false;
        }
        String normalized = stripLeadingComments(sql).trim().toLowerCase(Locale.ROOT);
        return startsWithReadOnlyKeyword(normalized) || startsWithReadOnlyCte(normalized);
    }


    private static String stripLeadingComments(String sql) {
        String value = sql == null ? "" : sql.trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            if (value.startsWith("--")) {
                int lineEnd = value.indexOf('\n');
                value = lineEnd < 0 ? "" : value.substring(lineEnd + 1).trim();
                changed = true;
            } else if (value.startsWith("#")) {
                int lineEnd = value.indexOf('\n');
                value = lineEnd < 0 ? "" : value.substring(lineEnd + 1).trim();
                changed = true;
            } else if (value.startsWith("/*")) {
                int commentEnd = value.indexOf("*/", 2);
                value = commentEnd < 0 ? "" : value.substring(commentEnd + 2).trim();
                changed = true;
            }
        }
        return value;
    }


    private static boolean startsWithReadOnlyKeyword(String normalized) {
        return normalized.startsWith("select") || normalized.startsWith("show") || normalized.startsWith("desc")
            || normalized.startsWith("describe") || normalized.startsWith("explain");
    }


    private static boolean startsWithReadOnlyCte(String normalized) {
        if (!normalized.startsWith("with")) {
            return false;
        }
        int index = 4;
        if (startsWithWordAt(normalized, index, "recursive")) {
            index += "recursive".length();
        }
        while (index < normalized.length()) {
            int asIndex = normalized.indexOf(" as", index);
            if (asIndex < 0) {
                return false;
            }
            int open = normalized.indexOf('(', asIndex);
            if (open < 0) {
                return false;
            }
            int close = matchingParen(normalized, open);
            if (close < 0) {
                return false;
            }
            index = close + 1;
            while (index < normalized.length() && Character.isWhitespace(normalized.charAt(index))) {
                index++;
            }
            if (index < normalized.length() && normalized.charAt(index) == ',') {
                index++;
                continue;
            }
            return startsWithReadOnlyKeyword(normalized.substring(index).trim());
        }
        return false;
    }


    private static boolean startsWithWordAt(String value, int index, String word) {
        int start = index;
        while (start < value.length() && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        int end = start + word.length();
        return end <= value.length() && value.substring(start, end).equals(word)
            && (end == value.length() || !Character.isLetterOrDigit(value.charAt(end)));
    }


    private static int matchingParen(String value, int open) {
        int depth = 0;
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int index = open; index < value.length(); index++) {
            char current = value.charAt(index);
            char next = index + 1 < value.length() ? value.charAt(index + 1) : '\0';
            if (singleQuoted) {
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
            } else if (current == '\'') {
                singleQuoted = true;
            } else if (current == '"') {
                doubleQuoted = true;
            } else if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }


    static Object defaultValue(Class<?> returnType) {
        if (returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Byte.TYPE) {
            return (byte) 0;
        }
        if (returnType == Short.TYPE) {
            return (short) 0;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == Float.TYPE) {
            return 0F;
        }
        if (returnType == Double.TYPE) {
            return 0D;
        }
        if (returnType == Character.TYPE) {
            return (char) 0;
        }
        return null;
    }


    static Object handleWrapper(Object proxy, Method method, Object[] args, Object target) throws SQLException {
        String name = method.getName();
        if ("unwrap".equals(name)) {
            Class<?> iface = (Class<?>) args[0];
            if (iface.isInstance(proxy)) {
                return proxy;
            }
            if (iface.isInstance(target)) {
                return target;
            }
            throw new SQLException("Cannot unwrap to " + iface.getName());
        }
        if ("isWrapperFor".equals(name)) {
            Class<?> iface = (Class<?>) args[0];
            return iface.isInstance(proxy) || iface.isInstance(target);
        }
        return null;
    }


    static boolean isWrapperMethod(Method method) {
        return method.getDeclaringClass() == Wrapper.class;
    }


    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }


    static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            return new BigDecimal(String.valueOf(value));
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        return new BigDecimal(String.valueOf(value));
    }


    /**
     * 生成适合诊断日志的参数摘要，避免把超长 SQL 或敏感对象完整写入日志。
     */
    static String methodArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "()";
        }
        String first = abbreviate(String.valueOf(args[0]), 300);
        return "(" + first + (args.length > 1 ? ", ..." : "") + ")";
    }


    /**
     * 截断日志文本，保留足够定位问题的前缀内容。
     */
    static String abbreviate(String value, int maxLength) {
        return ArcheryStrings.abbreviate(value, maxLength);
    }


    static Statement statementOf(ResultSet resultSet) throws SQLException {
        return resultSet == null ? null : resultSet.getStatement();
    }
}
