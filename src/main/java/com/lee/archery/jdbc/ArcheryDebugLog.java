package com.lee.archery.jdbc;

import com.lee.archery.config.ArcheryJdbcConfig;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地诊断日志，仅在 JDBC URL 配置 debugLog 时记录 IDE 与 ResultSet 的交互过程。
 * 逐值读取结果集会产生数十万条记录，因此按路径常驻缓冲区，避免每行都开关文件。
 */
final class ArcheryDebugLog {
    /**
     * 两次 flush 之间最多缓冲的行数，控制进程被强杀时的日志损失。
     */
    private static final int FLUSH_EVERY_LINES = 200;
    /**
     * 距上次 flush 的最长间隔，避免低频写入时尾部内容长时间滞留内存。
     */
    private static final long FLUSH_INTERVAL_MILLIS = 5_000;

    /**
     * 按日志路径缓存的写入器，不同数据源可能配置不同文件。
     */
    private static final Map<String, LogWriter> WRITERS = new LinkedHashMap<>();

    static {
        // IDEA 用 Terminating 强杀远程进程，不执行 close，靠停机关钩兜底刷盘
        Runtime.getRuntime().addShutdownHook(new Thread(ArcheryDebugLog::flushAll, "archery-debug-log-flush"));
    }


    private ArcheryDebugLog() {
    }


    static void write(ArcheryJdbcConfig config, String message) {
        if (config == null || config.getDebugLogPath() == null || config.getDebugLogPath().trim().isEmpty()) {
            return;
        }
        LogWriter writer = writer(config.getDebugLogPath());
        if (writer == null) {
            return;
        }
        writer.write(timestamp() + " " + message);
    }


    private static synchronized LogWriter writer(String path) {
        LogWriter writer = WRITERS.get(path);
        if (writer == null) {
            writer = LogWriter.open(path);
            if (writer == null) {
                return null;
            }
            WRITERS.put(path, writer);
        }
        return writer;
    }


    private static synchronized void flushAll() {
        for (LogWriter writer : WRITERS.values()) {
            writer.flush();
        }
    }


    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
    }


    /**
     * 常驻缓冲的单文件写入器，兼顾写入吞吐与进程被强杀时的日志完整性。
     * 行数与时间双阈值触发刷盘，最坏只损失最后少量日志。
     */
    private static final class LogWriter {
        private final PrintWriter writer;
        private int pendingLines;
        private long lastFlushAt = System.currentTimeMillis();


        private LogWriter(PrintWriter writer) {
            this.writer = writer;
        }


        private static LogWriter open(String path) {
            try {
                return new LogWriter(new PrintWriter(new BufferedWriter(new FileWriter(path, true), 8192)));
            } catch (IOException ignored) {
                // 诊断日志不能影响正常查询。
                return null;
            }
        }


        private synchronized void write(String line) {
            writer.println(line);
            pendingLines++;
            if (pendingLines >= FLUSH_EVERY_LINES || System.currentTimeMillis() - lastFlushAt >= FLUSH_INTERVAL_MILLIS) {
                flush();
            }
        }


        private synchronized void flush() {
            writer.flush();
            pendingLines = 0;
            lastFlushAt = System.currentTimeMillis();
        }
    }
}
