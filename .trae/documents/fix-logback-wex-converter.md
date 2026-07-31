# 修复 Logback `%wEx` 转换器未注册错误

## 问题

应用启动失败：

```
ERROR in ch.qos.logback.core.pattern.parser.Compiler - There is no conversion supplier registered for conversion word [wEx]
ERROR in ... - [wEx] is not a valid conversion word
```

## 根因分析（基于源码核对）

文件 [logback-spring.xml](file:///d:/project/aiFileParser/src/main/resources/logback-spring.xml) 中：

- 第 18 行 `CONSOLE_PATTERN` 使用了 `%wEx`
- 第 22 行 `FILE_PATTERN` 使用了 `%wEx`

`%wEx` 是 Spring Boot 提供的 `ExtendedWhitespaceThrowableProxyConverter` 转换词，它**只在引入 Spring Boot
的 `org/springframework/boot/logging/logback/defaults.xml` 时才会被自动注册**。

当前这份自定义 `logback-spring.xml` 没有引入该 defaults.xml，所以 `%wEx` 未注册 →
启动时报 `[wEx] is not a valid conversion word`。

对照：第 15-16 行的 `%clr` 是手动用 `<conversionRule>` 注册的，所以它正常工作；而 `%wEx` 没有手动注册，才报错。

## 修复方案

在 `<configuration>` 顶部、`%clr` 的 `<conversionRule>` 旁边，**手动注册 `%wEx` 转换器**，与 Spring Boot defaults.xml
内部做法一致。

这样做的好处：

- 最小化改动，单文件单处插入；
- 保留 `%wEx` 原有语义（异常堆栈带空白换行包装，比标准 `%ex` 更易读）；
- 与已有的 `%clr` 手动注册方式保持一致，自包含、不依赖额外 include；
- 不引入 Spring Boot defaults.xml，避免其默认 `CONSOLE_LOG_PATTERN`/`FILE_LOG_PATTERN` 等属性对当前自定义 pattern 造成副作用。

## 具体改动

文件：`d:\project\aiFileParser\src\main\resources\logback-spring.xml`

在原第 14-16 行（`<!-- 控制台彩色输出 -->` 注释及其下 `%clr` 注册）处，追加一行 `%wEx` 的注册：

```xml
<!-- 控制台彩色输出 -->
<conversionRule conversionWord="clr"
                converterClass="org.springframework.boot.logging.logback.ColorConverter"/>
<conversionRule conversionWord="wEx"
                converterClass="org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter"/>
```

其余内容（pattern、appender、profile）保持不变。

## 验证步骤

1. 编译：`mvn clean compile -DskipTests`
2. 启动应用：`mvn spring-boot:run`，确认日志中不再出现 `[wEx] is not a valid conversion word`，且启动成功。
3. 访问 `http://localhost:8080/aifp/health/ping`，确认正常返回 `Result` JSON。
4. （可选）人为触发一次异常（如访问不存在的接口产生 500），确认堆栈能按 `%wEx` 格式正常输出到控制台/文件。

## 假设与决策

- 不改用 `%ex`：虽然 `%ex` 是 logback 内置、永远可用，但会丢失 `%wEx` 的空白换行美化效果；既然 Spring Boot
  同包就提供了该转换器，直接注册更优。
- 不引入 Spring Boot defaults.xml：会额外注入默认 pattern 属性，对当前自包含配置无益且可能产生属性覆盖歧义。
- 该修复与阶段 2（数据库层）无耦合，可独立完成。
