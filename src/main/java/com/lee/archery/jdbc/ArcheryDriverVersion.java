package com.lee.archery.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Archery JDBC Driver 版本信息，统一承接 Gradle 构建时注入的项目版本。
 */
final class ArcheryDriverVersion {
    /**
     * 构建资源文件中的版本属性名。
     */
    private static final String VERSION_RESOURCE = "/archery-jdbc-driver.properties";

    /**
     * 版本属性键名。
     */
    private static final String VERSION_PROPERTY = "driver.version";

    /**
     * 无法读取构建版本时的兜底版本，避免驱动初始化失败。
     */
    private static final String FALLBACK_VERSION = "0.0.0";

    /**
     * 完整 Driver 版本号。
     */
    private static final String VERSION = loadVersion();

    /**
     * Driver 主版本号。
     */
    private static final int MAJOR_VERSION = parseVersionPart(0);

    /**
     * Driver 次版本号。
     */
    private static final int MINOR_VERSION = parseVersionPart(1);


    private ArcheryDriverVersion() {
    }


    /**
     * 返回完整 Driver 版本号，供 DatabaseMetaData 对外暴露。
     */
    static String version() {
        return VERSION;
    }


    /**
     * 返回 Driver 主版本号，供 java.sql.Driver 对外暴露。
     */
    static int majorVersion() {
        return MAJOR_VERSION;
    }


    /**
     * 返回 Driver 次版本号，供 java.sql.Driver 对外暴露。
     */
    static int minorVersion() {
        return MINOR_VERSION;
    }


    private static String loadVersion() {
        Properties properties = new Properties();
        try (InputStream inputStream = ArcheryDriverVersion.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (inputStream == null) {
                return FALLBACK_VERSION;
            }
            properties.load(inputStream);
            String version = properties.getProperty(VERSION_PROPERTY, FALLBACK_VERSION).trim();
            if (version.isEmpty() || version.startsWith("${")) {
                return FALLBACK_VERSION;
            }
            return version;
        } catch (IOException ignored) {
            return FALLBACK_VERSION;
        }
    }


    private static int parseVersionPart(int index) {
        String[] parts = VERSION.split("\\.");
        if (index >= parts.length) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (int position = 0; position < parts[index].length(); position++) {
            char current = parts[index].charAt(position);
            if (!Character.isDigit(current)) {
                break;
            }
            digits.append(current);
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
