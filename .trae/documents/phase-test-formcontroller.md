# FormController 切片测试（@WebMvcTest）

## 摘要

为 `FormController` 的 4 个接口各编写一个测试方法，采用 Spring Boot 切片测试 `@WebMvcTest` +
Mockito `@MockBean FormService`，验证路由、参数绑定、JSON 序列化、`@Valid` 校验失败、以及 `BusinessException`
经 `GlobalExceptionHandler` 转换为统一 `Result` 的链路。**不依赖 MySQL/DashScope Key**，可离线运行。

## 当前状态分析（基于源码核对）

- `FormController`（`src/main/java/com/aifp/aiagent/controller/FormController.java`）有 4 个接口：
    - `POST /form/create` → `Result<Long>`，入参 `FormCreateDTO`（`@Valid`）。
    - `GET /form/{id}` → `Result<FormVO>`。
    - `POST /form/{id}/field` → `Result<Long>`，入参 `FormFieldCreateDTO`（`@Valid`）。
    - `DELETE /form/{id}/field/{fieldId}` → `Result<Void>`。
- 全局异常处理器 `GlobalExceptionHandler`（`@RestControllerAdvice`）已存在，会随 `@WebMvcTest`
  自动被扫描到，负责将 `BusinessException` 转为 `Result.fail(code,msg)`、`MethodArgumentNotValidException`
  转为 `Result.fail(40001,...)`。
- `Result<T>` 字段：`code`、`message`、`data`、`timestamp`。
- DTO 校验约束：`FormCreateDTO.formName`(@NotBlank)；`FormFieldCreateDTO.fieldName`(@NotBlank)、`fieldCode`(
  @NotBlank+`^[a-zA-Z][a-zA-Z0-9_]*$`)、`fieldType`(@NotNull)。
-
状态码：`SUCCESS=200`，`PARAM_VALID_ERROR=40001`，`FORM_NOT_FOUND=5001`，`FIELD_CODE_DUPLICATE=5002`，`FIELD_NOT_FOUND=5003`。
- 启动类 `AiFileParserApplication` 上有 `@MapperScan("com.aifp.aiagent.repository")`，会触发 MyBatis mapper 扫描（需
  DB）；`@WebMvcTest` 默认不加载 `@MapperScan`，但 `spring-ai-alibaba-starter-dashscope`
  的 `DashScopeAgentAutoConfiguration`（`com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration`
  ）要求 `spring.ai.dashscope.api-key` 非空，**必须在测试中排除该自动配置**，否则上下文启动失败。
- `pom.xml` 已有 `spring-boot-starter-test`（含 JUnit5 + MockMvc + Mockito + AssertJ），无需新增依赖。
- `FieldType` 枚举用 `@EnumValue` 存 code，Jackson 默认按枚举 `name()` 序列化（请求体传 `"STRING"` 等 name 即可反序列化）。

## 关键设计决策

1. **测试类型**：`@WebMvcTest(controllers = FormController.class, excludeFilters = ...)`。
    - 通过 `excludeFilters` 排除启动类 `AiFileParserApplication` 上的 `@MapperScan`，避免触发 mapper 扫描。
    - 通过 `@ImportAutoConfiguration`
      之外的方式：用 `@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, DashScopeAgentAutoConfiguration.class})`
      双保险，确保切片测试不连 DB、不调 AI。
    - 同时 `@MockBean FormService` 提供桩实现；`@MockBean` 也会阻止 `FormServiceImpl` 等真实 Service 被装配。
2. **MockMvc 基址**：`@WebMvcTest` 不应用 `server.servlet.context-path=/aifp`（该属性仅在 Servlet
   容器启动时生效），所以测试请求路径直接用 `/form/...`。
3. **不写 Service 层测试**：用户明确说"完善对应 FormController 的测试方法"，仅聚焦 Controller 切片。
4. **每个 Controller 方法至少一个用例**，并补充关键的校验失败/异常转换用例，确保异常链路被覆盖（属于 Controller 切片的合理职责）。
5. **断言风格**：用 MockMvc `jsonPath` + `Mockito.verify`/`when`，结合 AssertJ `assertThat`。
6. **共享测试数据构造**：在测试类内私有方法构造合法 `FormCreateDTO` / `FormVO`，避免重复。
7. **`Result.timestamp`** 是动态值，断言时不比较，只校验 code/message/data。

## 待新增文件

### `src/test/java/com/aifp/aiagent/controller/FormControllerTest.java`

包名与被测类同包（`com.aifp.aiagent.controller`），符合 Maven 测试目录约定。

**类级注解：**

```java
@WebMvcTest(controllers = FormController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = AiFileParserApplication.class))
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
```

注：为彻底排除 DashScope 与 DataSource 自动配置，使用

```java
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        DashScopeAgentAutoConfiguration.class
})
```

（`DashScopeAgentAutoConfiguration`
全限定名 `com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration`。）

字段：

```java

@Autowired
MockMvc mockMvc;
@Autowired
ObjectMapper objectMapper;
@MockBean
FormService formService;
```

**测试方法（每个 Controller 方法至少 1 个 + 关键异常路径）：**

| #  | 方法名                                             | 覆盖接口                              | 场景                                                               |
|----|-------------------------------------------------|-----------------------------------|------------------------------------------------------------------|
| 1  | `createForm_shouldReturnFormId`                 | POST /form/create                 | 合法请求(含字段列表) → 200, data=formId, verify service.createForm called |
| 2  | `createForm_shouldFailWhenFormNameBlank`        | POST /form/create                 | formName 空 → 40001, service 未被调用                                 |
| 3  | `createForm_shouldFailWhenFieldCodeInvalid`     | POST /form/create                 | fieldCode 含非法字符 → 40001                                          |
| 4  | `getForm_shouldReturnFormDetail`                | GET /form/{id}                    | 合法 id → 200, data.formName 校验                                    |
| 5  | `getForm_shouldReturn5001WhenNotFound`          | GET /form/{id}                    | service 抛 FORM_NOT_FOUND → 5001                                  |
| 6  | `addField_shouldReturnFieldId`                  | POST /form/{id}/field             | 合法字段 → 200, data=fieldId                                         |
| 7  | `addField_shouldFailWhenFieldTypeNull`          | POST /form/{id}/field             | fieldType 空 → 40001                                              |
| 8  | `addField_shouldReturn5002WhenCodeDuplicate`    | POST /form/{id}/field             | service 抛 FIELD_CODE_DUPLICATE → 5002                            |
| 9  | `deleteField_shouldReturn200`                   | DELETE /form/{id}/field/{fieldId} | 正常软删 → 200, verify service.deleteField called                    |
| 10 | `deleteField_shouldReturn5003WhenFieldNotFound` | DELETE /form/{id}/field/{fieldId} | service 抛 FIELD_NOT_FOUND → 5003                                 |

> 10 个用例 = 4 接口各 1 正向 + 关键校验/异常分支。满足"每个方法都写一个适合的测试方法"且覆盖异常转换链路。

**辅助构造方法（私有）：**

- `validCreateDTO()` → 含 formName、description、2 个字段。
- `sampleFormVO(Long id)` → 含 formName 与字段列表。
- `singleFieldDTO(String code)` → 合法单字段。

**桩与断言模板（示例方法 1）：**

```java

@Test
void createForm_shouldReturnFormId() throws Exception {
    FormCreateDTO dto = validCreateDTO();
    when(formService.createForm(any())).thenReturn(1785508135L);

    mockMvc.perform(post("/form/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(dto)))
            .andDo(print())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data").value(1785508135));
    verify(formService).createForm(any(FormCreateDTO.class));
}
```

**异常桩模板（示例方法 5）：**

```java

@Test
void getForm_shouldReturn5001WhenNotFound() throws Exception {
    when(formService.getFormById(9999L))
            .thenThrow(new BusinessException(ResultCode.FORM_NOT_FOUND));

    mockMvc.perform(get("/form/9999"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(5001))
            .andExpect(jsonPath("$.message").value("表单不存在"));
}
```

## 验证步骤

1. 编译 + 运行测试：
   ```powershell
   mvn -q -Dtest=FormControllerTest test
   ```
   预期：10 个用例全绿，BUILD SUCCESS，**无需 MySQL/DashScope Key**。
2. 全量测试回归：
   ```powershell
   mvn test
   ```
3. 关键检查点：
    - `excludeFilters` 成功排除 `@MapperScan` → 上下文不报 mapper 扫描错误。
    - `DashScopeAgentAutoConfiguration` 被排除 → 不报 "API key must be set"。
    - `@MockBean FormService` 注入到 `FormController` → 真实 `FormServiceImpl` 不被装配。
    - 校验失败用例返回 `code=40001`；业务异常用例返回对应 5xxx 错误码。

## 假设与决策

- 采用 `@WebMvcTest` 切片（用户已确认），不写纯 standalone。
- 必须排除 `DashScopeAgentAutoConfiguration` 与 `DataSourceAutoConfiguration`，并排除启动类的 `@MapperScan`，否则切片测试无法离线启动。
- 测试请求路径用 `/form/...`（无 `/aifp` 前缀），因为 `@WebMvcTest` 的 MockMvc 不应用 `server.servlet.context-path`。
- `FieldType` 在 JSON 中传枚举名（如 `"STRING"`），Jackson 默认可反序列化。
- 仅测 Controller 切片，不测 Service/Repository 层（超出本次范围）。
- `Result.timestamp` 为动态值，断言时不比较。
- 不新增任何生产代码，仅新增 1 个测试类；不修改 pom（依赖已具备）。
