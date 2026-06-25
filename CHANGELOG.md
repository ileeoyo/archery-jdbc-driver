# Changelog

## [0.2.1] - 2026-06-25

### 修复

- 将默认查询限制从 100 调整为 500，提升常规结果预览可用性。
- 元数据缓存按 database 与 schema 共同隔离，避免同一实例切换 database 后复用旧库表结构。

## [0.2.0] - 2026-06-23

首次发布 Archery JDBC Driver，用于在 DataGrip、IDEA 等支持 JDBC 的工具中通过 Archery Web 接口访问数据库查询能力。

### 新增

- 支持 `jdbc:archery:` 连接协议，可通过 JDBC URL 和 Properties 配置 Archery 地址、实例、库、schema、账号和密码。
- 支持登录 Archery Web，维护 Cookie 与 CSRF token，并在会话过期时自动重新登录一次。
- 支持执行只读 SQL 查询，并限制仅允许 `SELECT`、`SHOW`、`DESC`、`EXPLAIN` 等安全查询语句。
- 支持 `Statement`、`PreparedStatement`、`ResultSet` 和常用字段类型映射，满足基础查询和结果读取场景。
- 支持 `DatabaseMetaData`，可向 IDE 暴露 schema、表、字段等元数据信息，便于数据库浏览和补全。
- 打包时内置运行时依赖，生成可直接添加到 DataGrip/IDEA Driver 配置中的独立 jar。
