# Changelog

## [0.4.0] - 2026-11-06

### 新增

- 重写 `SHOW CREATE TABLE` 解析，向 IDE 暴露真实字段类型、默认值、注释、生成列表达式、字段/表级排序规则、索引（前缀长度、`USING`、函数索引）、外键、`CHECK` 约束、分区定义和表选项，不再使用常量占位。
- 实现 `DatabaseMetaData.getPrimaryKeys()`、`getIndexInfo()`、`getImportedKeys()`、`getExportedKeys()`，此前这些方法返回空结果。
- 扩展 DataGrip `information_schema` 适配，新增 `statistics`、`table_constraints`、`key_column_usage`、`collations`、`views`、`partitions` 和自增列查询处理。
- 支持视图：以 `VIEW` 类型暴露，列名由 resource 接口补充，避免 IDE 展开视图时无列可显示。
- 支持按表结构推断库级默认字符集与排序规则，`information_schema.schemata` 不再返回固定排序规则。

### 修复

- metadata 结果列改为按 IDE 发出的 `SELECT` 别名顺序输出，修复 IDE 读取字段整体错位的问题。
- 表枚举按 SQL 中显式指定的表名过滤，避免整库刷新时对每张表各发一次 describe 打满 Archery。
- 主键同时作为唯一索引时归一处理，修复主键在约束和 `non_unique` 结果中消失的问题。
- 外键查询按 `referenced_column_name is not null` 过滤，避免返回 referenced 为空的伪外键。
- `json` 列不再被赋予排序规则，避免 IDE 生成非法 `COLLATE` DDL。
- 元数据查询统一走本地 `information_schema` 适配，不再把 IDE introspection SQL 转发给 Archery 查询接口。

### 变更

- 调试日志改为常驻缓冲写入，按行数和时间双阈值刷盘，并注册停机钩子兜底，避免逐行开关文件导致的性能损耗。
- 调试日志补充 resource、describe table 和 metadata 查询的耗时记录，SQL 只保留前 200 字符。

## [0.3.0] - 2026-09-01

### 新增

- 新增 `archery-query-runner` CLI fat jar，用于 Skill 通过 `java -jar` 调用 Archery JDBC 查询能力。

### 变更

- JDBC URL 查询参数与 Driver Properties 存在同名配置时，改为 JDBC URL 查询参数优先。
- `limitNum` 支持任意非负整数，`0` 表示 Archery max，默认最大返回行数保持为 500。
- README 补充 CLI fat jar 构建产物和 IDEA/DataGrip 配置优先级说明。

## [0.2.2] - 2026-07-27

### 修复

- 修复 Archery `/query` 返回 MySQL 协议字段类型名时，IDEA/DataGrip 将 `LONGLONG`、`LONG`、`NEWDECIMAL` 等结果列误判为字符串的问题。
- 补齐 `BIT`、`TINY`、`SHORT`、`INT24`、`FLOAT`、`DOUBLE`、`DATE`、`TIME`、`TIMESTAMP`、`YEAR`、`JSON`、`GEOMETRY` 等查询结果字段类型到 JDBC 类型码的映射。
- 完善查询结果元数据的 Java class name 映射，使整数、小数、浮点、时间、二进制和 `NULL` 类型在 IDE 中展示更准确。

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
