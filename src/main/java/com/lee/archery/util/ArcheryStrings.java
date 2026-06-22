package com.lee.archery.util;

/**
 * 字符串工具，集中处理日志和错误消息中的安全截断等通用逻辑。
 */
public final class ArcheryStrings {
    private ArcheryStrings() {
    }


    /**
     * 截断日志文本，保留足够定位问题的前缀内容。
     */
    public static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
