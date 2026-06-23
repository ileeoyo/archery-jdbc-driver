# 项目协作规则

## 构建规则

- 本项目使用 Gradle Wrapper，不是 Maven 项目；不要用 Maven 命令构建。
- 当前 `build.gradle.kts` 配置源码 `sourceCompatibility` 和 `targetCompatibility` 为 Java 8。
- Gradle Wrapper 运行时需要 JDK 17 或更高版本；如果本机默认 JDK 低于该版本，应仅在当前命令临时切换 `JAVA_HOME`。

## 发版规则

- 当用户要求升级版本号或准备发版时，必须先拉取远端最新 tag 列表，确保基于最新版本历史判断。
- 生成 changelog 前，必须找到当前版本之前的最近一个版本 tag，并对比该 tag 与当前代码的差异。
- changelog 内容必须基于实际代码 diff、提交记录和当前变更生成，禁止仅凭版本号或主观猜测编写。
- changelog 必须写入项目根目录 `CHANGELOG.md`，供 GitHub Actions Release workflow 后续读取。
- 如果项目中不存在 `CHANGELOG.md`，发版前需要创建该文件，并按当前版本追加对应版本说明。
