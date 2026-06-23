# Archery JDBC Driver

Archery JDBC Driver 是一个面向 IntelliJ IDEA / DataGrip 的只读 JDBC Driver。它把 Archery Web 查询接口包装成标准 JDBC 连接，让 IDE 复用 SQL Console、SQL dialect、Result tab 和基础 Database 面板补全能力。

Driver 不会直接连接业务数据库，所有登录、查询和 metadata 获取都通过 Archery Web 接口完成，因此仍受 Archery 账号权限、审计、脱敏等体系约束。

## 当前能力

- 支持 `jdbc:archery:` URL。
- 支持 Archery 账号密码登录，并维护 Django session 与 CSRF。
- 支持 `SELECT`、`SHOW`、`DESC`、`DESCRIBE`、`EXPLAIN`、`WITH` 开头的只读 SQL。
- 支持把 Archery `/query/` 返回的 `column_list` 和 `rows` 映射成 JDBC `ResultSet`。
- 支持通过 Archery resource 接口暴露 catalog、schema、table metadata。
- 支持通过 Archery describe table 接口解析字段、字段类型、默认值、注释、索引和部分约束 metadata。
- 支持适配 DataGrip 常见 `information_schema` metadata 查询，避免把 IDE introspection SQL 直接发给 Archery 查询接口。
- 不支持 DDL、DML、真实事务、批处理、存储过程、外键 metadata 和查询取消。

## 构建 Driver Jar

本项目源码目标版本是 Java 8，但 Gradle Wrapper 运行时需要 JDK 17 或更高版本。

```bash
./gradlew jar
```

构建产物位于：

```text
build/libs/archery-jdbc-driver-<version>.jar
```

如果在 Windows PowerShell 中执行，可使用：

```powershell
.\gradlew.bat jar
```

## IDEA / DataGrip 接入步骤

在 DataGrip 或 IntelliJ IDEA Ultimate 中手动添加自定义 JDBC Driver：

- 打开 `Database` 工具窗口。
- 点击 `+`，选择 `Driver`。
- 添加构建出的 `archery-jdbc-driver-<version>.jar`。
- 设置 `Driver class` 为 `com.lee.archery.jdbc.ArcheryDriver`。
- 新建 Data Source，选择刚创建的 Driver。
- 填写 JDBC URL、用户名、密码。
- 点击 `Test Connection` 验证登录。
- 打开 Query Console 执行 `select 1`。

## JDBC URL

固定默认 database 的 URL：

```text
jdbc:archery:https://archery.example.com?instanceName=xxx&dbName=xxx
```

不固定 database、由 DataGrip schema 下拉选择的 URL：

```text
jdbc:archery:https://archery.example.com?instanceName=xxx
```

支持参数：

| 参数 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `instanceName` | 是 | 无 | Archery 实例名，对应查询页面选择的实例 |
| `dbName` | 否 | 空字符串 | 默认 database；不填写时可在 Database 工具中勾选并切换 schema/database |
| `schemaName` | 否 | 空字符串 | 默认 schema，MySQL 场景通常可为空 |
| `username` | 否 | `Properties.user` | Archery 用户名 |
| `password` | 是 | 无 | Archery 密码 |
| `contextPath` | 否 | `/sql` | Archery SQL Web 路由前缀 |
| `limitNum` | 否 | `100` | 传给 Archery 查询接口的默认 limit 参数 |
| `connectTimeoutMillis` | 否 | `10000` | HTTP 连接超时 |
| `readTimeoutMillis` | 否 | `60000` | HTTP 读取超时 |
| `debugLog` | 否 | 空字符串 | 本地诊断日志路径；为空时不写日志 |

DataGrip 通常会把用户名和密码通过 Driver Properties 传入，也可以直接放在 URL 查询参数中。

## Archery 路由说明

Archery v1.14.0 源码中 SQL app 路由可能以根路径暴露，例如 `/query/`、`/instance/instance_resource/`、`/instance/describetable/`。很多部署会把它挂到 `/sql` 前缀下，对外表现为 `/sql/query/`。

本 Driver 默认优先使用：

```text
/sql/query/
/sql/instance/instance_resource/
/sql/instance/describetable/
```

如果接口返回 404，Driver 会自动 fallback 到根路径：

```text
/query/
/instance/instance_resource/
/instance/describetable/
```

如果你的 Archery 部署没有 `/sql` 前缀，也可以在 URL 中显式设置：

```text
jdbc:archery:https://archery.example.com?instanceName=xxx&contextPath=/
```

登录页和认证接口固定使用站点根路径：

```text
/login/
/authenticate/
```

## Metadata 说明

Driver 通过两类方式给 IDEA / DataGrip 提供基础 metadata：

- `DatabaseMetaData` 方法：例如 `getCatalogs()`、`getSchemas()`、`getTables()`、`getColumns()`、`getTypeInfo()`。
- DataGrip introspection SQL 适配：拦截常见 `information_schema.schemata`、`information_schema.tables`、`information_schema.columns`、`information_schema.statistics`、`information_schema.table_constraints`、`information_schema.key_column_usage` 等查询。

库表信息主要来自 Archery resource 接口。字段、字段类型、默认值、注释、索引和唯一约束信息主要来自 describe table 接口返回的 MySQL 建表语句。

当前 metadata 仍是为 IDE 补全和展示服务的轻量实现，不保证覆盖完整 MySQL metadata：

- `getPrimaryKeys()`、`getImportedKeys()`、`getExportedKeys()`、`getIndexInfo()`、存储过程和函数相关方法目前返回结构正确但行为空的 `ResultSet`。
- 系统库表，例如 `mysql.*`、`performance_schema.*`、`sys.*`，通常返回空结果以避免影响 introspection。
- 外键 metadata 暂未实现。

## 只读限制

Driver 不会直接执行变更 SQL。以下能力会抛出不支持异常或保持只读行为：

- `executeUpdate()` / `executeLargeUpdate()`
- `addBatch()` / `executeBatch()` / `executeLargeBatch()`
- `prepareCall()`
- DDL / DML SQL
- 查询取消
- Blob、Clob、SQLXML、Array、Struct、Savepoint 等 JDBC 高级对象

事务相关行为：

- `supportsTransactions()` 返回 `false`。
- `getDefaultTransactionIsolation()` 返回 `TRANSACTION_NONE`。
- `commit()` / `rollback()` 不会提交或回滚真实数据库事务；当 `autoCommit=true` 时会按 JDBC 语义报错，当 `autoCommit=false` 时为空操作。

## PreparedStatement 说明

当前 `PreparedStatement` 不做服务端预编译。Driver 会在客户端保存参数，并在执行时把 `?` 替换成 SQL 字面量后委托给普通 `Statement`。

支持基础 `setXxx(index, value)` 和 `setNull(index, ...)` 参数设置。需要注意：当前实现没有完整 SQL 解析能力，如果 SQL 字符串字面量或注释中包含 `?`，也可能被当作参数占位符处理。

## 调试日志

默认不写本地日志。排查 IDE 调用链或 ResultSet 行为时，可以通过 `debugLog` 指定日志文件路径：

```text
jdbc:archery:https://archery.example.com?instanceName=xxx&dbName=xxx&debugLog=/tmp/archery-jdbc-debug.log
```

日志会记录 Connection、Statement、PreparedStatement、ResultSet 和 metadata 查询的关键调用摘要。日志写入失败不会影响正常查询。

## 常见问题

### Test Connection 返回登录页或 403

通常是 CSRF、session 或登录方式问题。Driver 会先请求 `/login/` 获取 CSRF，再提交 `/authenticate/`。如果 Archery 开启了 SSO、验证码或 2FA，账号密码登录可能无法完成。

### 查询接口 404

检查 `contextPath`。如果部署路由是 `/query/`，设置 `contextPath=/`；如果部署路由是 `/sql/query/`，保持默认值即可。Driver 对 `/query/`、`/instance/instance_resource/`、`/instance/describetable/` 有 404 fallback，但显式配置正确的 `contextPath` 更清晰。

### Database 面板不显示库表

先确认当前账号在 Archery 中有目标实例、库和表的查询权限。metadata 获取失败时 Driver 会返回空结果集，避免影响基本 SQL 查询。DataGrip 侧也需要确认 schema 已勾选、对象过滤未隐藏目标对象，并刷新 introspection 缓存。

### 字段类型不准确

查询结果列如果缺少明确类型信息，默认按 `VARCHAR` 暴露给 IDE。Database 面板中的字段 metadata 会尽量从 describe table 返回的建表语句解析真实类型，但复杂 MySQL 类型仍可能映射不完整。
