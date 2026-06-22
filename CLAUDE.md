# 项目协作规则

## 构建规则

- 本项目使用 Gradle Wrapper，不是 Maven 项目；不要用 Maven 命令构建。
- 当前 `build.gradle.kts` 配置源码 `sourceCompatibility` 和 `targetCompatibility` 为 Java 8。
- Gradle Wrapper 运行时需要 JDK 17 或更高版本；如果本机默认 JDK 低于该版本，应仅在当前命令临时切换 `JAVA_HOME`。
