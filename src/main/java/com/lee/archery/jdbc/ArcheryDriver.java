package com.lee.archery.jdbc;

import com.lee.archery.config.ArcheryJdbcConfig;

import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Archery JDBC Driver 入口，负责被 DriverManager 和 IDEA/DataGrip 识别并创建只读连接。
 */
public final class ArcheryDriver implements Driver {
    static {
        try {
            DriverManager.registerDriver(new ArcheryDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        return ArcheryConnection.create(url, info);
    }


    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(ArcheryJdbcConfig.URL_PREFIX);
    }


    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[]{
            property("instanceName", true, "Archery 实例名"),
            property("dbName", true, "默认数据库名"),
            property("schemaName", false, "默认 schema"),
            property("username", true, "Archery 用户名"),
            property("password", true, "Archery 密码"),
            property("contextPath", false, "Archery SQL Web 路由前缀，默认 /sql")
        };
    }


    @Override
    public int getMajorVersion() {
        return ArcheryDriverVersion.majorVersion();
    }


    @Override
    public int getMinorVersion() {
        return ArcheryDriverVersion.minorVersion();
    }


    @Override
    public boolean jdbcCompliant() {
        return false;
    }


    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("java.util.logging is not used");
    }


    private DriverPropertyInfo property(String name, boolean required, String description) {
        DriverPropertyInfo propertyInfo = new DriverPropertyInfo(name, null);
        propertyInfo.required = required;
        propertyInfo.description = description;
        return propertyInfo;
    }
}
