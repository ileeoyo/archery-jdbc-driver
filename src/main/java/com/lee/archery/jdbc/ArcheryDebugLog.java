package com.lee.archery.jdbc;

import com.lee.archery.config.ArcheryJdbcConfig;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 本地诊断日志，仅在 JDBC URL 配置 debugLog 时记录 IDE 与 ResultSet 的交互过程。
 */
final class ArcheryDebugLog {
    private ArcheryDebugLog() {
    }


    static void write(ArcheryJdbcConfig config, String message) {
        if (config == null || config.getDebugLogPath() == null || config.getDebugLogPath().trim().isEmpty()) {
            return;
        }
        try (PrintWriter writer = new PrintWriter(new FileWriter(config.getDebugLogPath(), true))) {
            writer.println(timestamp() + " " + message);
        } catch (IOException ignored) {
            // 诊断日志不能影响正常查询。
        }
    }


    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
    }
}
