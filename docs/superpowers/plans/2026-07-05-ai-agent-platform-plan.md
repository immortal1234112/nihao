





# AI Agent 企业级创作平台 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 构建企业级 AI Agent 创作平台完整基础框架，Maven 多模块，7 个微服务。

**Architecture:** Spring Boot 3.2 + Spring Cloud 2023.0.x 微服务体系，Nacos 注册配置，RabbitMQ 异步，Redis 缓存，MySQL + MinIO。

**Tech Stack:** Java 17, Spring Boot 3.2.x, Spring Cloud 2023.0.x, Spring Cloud Alibaba 2023.0.x, Nacos, OpenFeign, RabbitMQ, Redis 7, MySQL 8.0, MinIO, MyBatis-Plus, OkHttp, Lombok, Hutool

## Global Constraints

- Java 17, Spring Boot 3.2.x, Spring Cloud 2023.0.x, Spring Cloud Alibaba 2023.0.x
- Maven 多模块，父 POM `<dependencyManagement>` 统一版本管理
- 所有服务注册到 Nacos，配置从 Nacos 拉取
- 所有 API 使用 `com.platform.core.base.Result<T>` 统一响应体
- 包名 `com.platform.{module}`
- 数据库 MyBatis-Plus，实体继承 `BaseEntity`（含雪花 ID）
- 分布式 ID 使用 `SnowflakeIdGenerator`（Hutool Snowflake）
- 消息队列 RabbitMQ + Jackson2JsonMessageConverter
- 大模型适配器仅定义接口，不实现具体模型调用
- 设计文档参考: `docs/superpowers/specs/2026-07-05-ai-agent-platform-design.md`

---

### Task 1: 父 POM 与项目目录结构

**Files:**
- Create: `pom.xml` (父 POM)
- Create: 所有模块的 `pom.xml` 骨架
- Create: 所有模块的 `src/main/java` 和 `src/main/resources` 目录

**Interfaces:**
- Produces: 父 POM `<dependencyManagement>` 和 `<modules>`，所有后续 Task 的子模块继承此父 POM

- [ ] **Step 1: 清理旧文件并创建完整目录结构**

```bash
rm -rf src out
for mod in common-core common-web common-security common-mq common-storage common-feign; do
  pkg=$(echo $mod | sed 's/common-//')
  mkdir -p "common-lib/$mod/src/main/java/com/platform/$pkg"
done
for svc in gateway agent-core user-service security-service image-engine video-engine storage-service; do
  pkg=$(echo $svc | sed 's/-//g')
  mkdir -p "$svc/src/main/java/com/platform/$pkg"
  mkdir -p "$svc/src/main/resources"
done
```

- [ ] **Step 2: 编写父 POM**

创建 `pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.platform</groupId>
    <artifactId>ai-agent-platform</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>AI Agent Platform</name>

    <modules>
        <module>common-lib/common-core</module>
        <module>common-lib/common-web</module>
        <module>common-lib/common-security</module>
        <module>common-lib/common-mq</module>
        <module>common-lib/common-storage</module>
        <module>common-lib/common-feign</module>
        <module>gateway</module>
        <module>agent-core</module>
        <module>user-service</module>
        <module>security-service</module>
        <module>image-engine</module>
        <module>video-engine</module>
        <module>storage-service</module>
    </modules>

    <properties>
        <java.version>17</java.version>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <spring-boot.version>3.2.5</spring-boot.version>
        <spring-cloud.version>2023.0.1</spring-cloud.version>
        <spring-cloud-alibaba.version>2023.0.1.0</spring-cloud-alibaba.version>
        <mybatis-plus.version>3.5.6</mybatis-plus.version>
        <lombok.version>1.18.32</lombok.version>
        <jjwt.version>0.12.5</jjwt.version>
        <minio.version>8.5.7</minio.version>
        <okhttp.version>4.12.0</okhttp.version>
        <hutool.version>5.8.27</hutool.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>com.alibaba.cloud</groupId>
                <artifactId>spring-cloud-alibaba-dependencies</artifactId>
                <version>${spring-cloud-alibaba.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <!-- Internal modules -->
            <dependency>
                <groupId>com.platform</groupId>
                <artifactId>common-core</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.platform</groupId>
                <artifactId>common-web</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.platform</groupId>
                <artifactId>common-security</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.platform</groupId>
                <artifactId>common-mq</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.platform</groupId>
                <artifactId>common-storage</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.platform</groupId>
                <artifactId>common-feign</artifactId>
                <version>${project.version}</version>
            </dependency>
            <!-- Third-party -->
            <dependency>
                <groupId>com.baomidou</groupId>
                <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
                <version>${mybatis-plus.version}</version>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-api</artifactId>
                <version>${jjwt.version}</version>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-impl</artifactId>
                <version>${jjwt.version}</version>
                <scope>runtime</scope>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-jackson</artifactId>
                <version>${jjwt.version}</version>
                <scope>runtime</scope>
            </dependency>
            <dependency>
                <groupId>io.minio</groupId>
                <artifactId>minio</artifactId>
                <version>${minio.version}</version>
            </dependency>
            <dependency>
                <groupId>com.squareup.okhttp3</groupId>
                <artifactId>okhttp</artifactId>
                <version>${okhttp.version}</version>
            </dependency>
            <dependency>
                <groupId>cn.hutool</groupId>
                <artifactId>hutool-all</artifactId>
                <version>${hutool.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <version>${spring-boot.version}</version>
                    <executions>
                        <execution>
                            <goals><goal>repackage</goal></goals>
                        </execution>
                    </executions>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

- [ ] **Step 3: 全局编译验证**

```bash
mvn validate
```

---

### Task 2: common-core 公共核心模块

**Files:**
- Create: `common-lib/common-core/pom.xml`
- Create: `common-lib/common-core/src/main/java/com/platform/core/base/Result.java`
- Create: `common-lib/common-core/src/main/java/com/platform/core/base/PageResult.java`
- Create: `common-lib/common-core/src/main/java/com/platform/core/base/BaseEntity.java`
- Create: `common-lib/common-core/src/main/java/com/platform/core/exception/BizException.java`
- Create: `common-lib/common-core/src/main/java/com/platform/core/exception/ErrorCode.java`
- Create: `common-lib/common-core/src/main/java/com/platform/core/exception/GlobalExceptionHandler.java`
- Create: `common-lib/common-core/src/main/java/com/platform/core/util/SnowflakeIdGenerator.java`
- Create: `common-lib/common-core/src/main/java/com/platform/core/util/JsonUtils.java`

**Interfaces:**
- `Result<T>`: `{int code, String message, T data, String requestId, long timestamp}` + static `ok(T)`, `ok()`, `fail(ErrorCode)`, `fail(int, String)`
- `PageResult<T>`: `{List<T> records, PageInfo pagination}` where `PageInfo(int page, int size, long total, int totalPages)`
- `BaseEntity`: `{String id(@TableId ASSIGN_ID), LocalDateTime createdAt(@TableField INSERT), LocalDateTime updatedAt(@TableField INSERT_UPDATE)}`
- `BizException(code, message)` inherited from RuntimeException
- `ErrorCode enum`: PARAM_INVALID(1001), PARAM_MISSING(1002), TOKEN_EXPIRED(2001), TOKEN_INVALID(2002), NO_PERMISSION(2003), API_KEY_INVALID(2004), QUOTA_EXCEEDED(3001), CONCURRENCY_LIMIT(3002), RATE_LIMITED(3003), TASK_NOT_FOUND(4001), FILE_NOT_FOUND(4002), USER_NOT_FOUND(4003), SERVICE_TIMEOUT(5001), MODEL_UNAVAILABLE(5002), STORAGE_ERROR(5003), INTERNAL_ERROR(5099), CONTENT_BLOCKED(9001), SENSITIVE_PROMPT(9002)
- `GlobalExceptionHandler`: `@RestControllerAdvice` handles BizException/MethodArgumentNotValidException/Exception → Result.fail
- `SnowflakeIdGenerator`: `@Component`, `nextId():String` (19-digit), `nextId(prefix:String):String`
- `JsonUtils`: wraps Jackson ObjectMapper, `toJson(Object):String`, `fromJson(String,Class):T`

- [ ] **Step 1: 编写 common-core/pom.xml**

依赖: lombok(optional), jackson-databind, mybatis-plus-spring-boot3-starter, hutool-all, spring-boot-starter-web(provided), jakarta.validation-api

- [ ] **Step 2: 编写 BaseEntity**

标准 MyBatis-Plus 实体基类，`@TableId(type = IdType.ASSIGN_ID)` 自动雪花 ID。

- [ ] **Step 3: 编写 Result + PageResult**

Result: code 默认 0，message 默认 "success"，`ok()` 工厂方法链式创建。
PageResult: records + pagination。

- [ ] **Step 4: 编写 ErrorCode + BizException + GlobalExceptionHandler**

- ErrorCode enum 含全部 18 个错误码
- BizException(code, message) 继承 RuntimeException
- GlobalExceptionHandler `@RestControllerAdvice` 三类异常处理

- [ ] **Step 5: 编写 SnowflakeIdGenerator + JsonUtils**

SnowflakeIdGenerator: `IdUtil.getSnowflake(1, 1).nextId()`
JsonUtils: `new ObjectMapper()` 包装

- [ ] **Step 6: 验证编译**

```bash
mvn clean compile -pl common-lib/common-core
```

---

### Task 3: common-web 公共 Web 模块

**Files:**
- Create: `common-lib/common-web/pom.xml`
- Create: `common-lib/common-web/src/main/java/com/platform/web/context/RequestContext.java`
- Create: `common-lib/common-web/src/main/java/com/platform/web/context/TenantContext.java`
- Create: `common-lib/common-web/src/main/java/com/platform/web/context/UserContext.java`
- Create: `common-lib/common-web/src/main/java/com/platform/web/interceptor/RequestIdInterceptor.java`
- Create: `common-lib/common-web/src/main/java/com/platform/web/interceptor/TenantContextInterceptor.java`
- Create: `common-lib/common-web/src/main/java/com/platform/web/interceptor/UserContextInterceptor.java`
- Create: `common-lib/common-web/src/main/java/com/platform/web/config/JacksonConfig.java`
- Create: `common-lib/common-web/src/main/java/com/platform/web/config/WebMvcConfig.java`
- Create: `common-lib/common-web/src/main/java/com/platform/web/config/FeignConfig.java`
- Create: `common-lib/common-web/src/main/java/com/platform/web/annotation/AuditLog.java`
- Create: `common-lib/common-web/src/main/java/com/platform/web/annotation/RequireRole.java`
- Create: `common-lib/common-web/src/main/java/com/platform/web/aspect/AuditLogAspect.java`
- Create: `common-lib/common-web/src/main/java/com/platform/web/aspect/RequireRoleAspect.java`

**Interfaces:**
- `RequestContext`: ThreadLocal<String> — `get()`, `set(String)`, `clear()`
- `TenantContext`: ThreadLocal<String> — `get()`, `set(String)`, `clear()`
- `UserContext`: ThreadLocal<UserInfo> — UserInfo: `record(String userId, String username, List<String> roles)`; static `get()`, `set(UserInfo)`, `clear()`, `getUserId()`, `getRoles()`
- `RequestIdInterceptor`: preHandle reads `X-Request-Id` (or generates UUID) → RequestContext.set(); afterCompletion → RequestContext.clear()
- `TenantContextInterceptor`: preHandle reads `X-Tenant-Id` → TenantContext.set(); afterCompletion → TenantContext.clear()
- `UserContextInterceptor`: preHandle reads `X-User-Id`, `X-Username`, `X-User-Roles` → UserContext.set(); afterCompletion → UserContext.clear()
- `JacksonConfig`: `@Configuration`, `Jackson2ObjectMapperBuilderCustomizer` — camelCase, LocalDateTime→ISO, NON_NULL
- `WebMvcConfig`: `@Configuration implements WebMvcConfigurer`, addInterceptors registers all 3 interceptors for `/api/**`
- `FeignConfig`: `@Configuration`, `RequestInterceptor` — reads contexts and injects into Feign request headers
- `@AuditLog(String action, String detail)`: method-level annotation; Aspect sends AuditLogMessage via RabbitTemplate asynchronously
- `@RequireRole(String[] value)`: method-level annotation; Aspect reads UserContext.getRoles() and throws NO_PERMISSION if mismatch

- [ ] **Step 1: 编写 common-web/pom.xml** — 依赖 common-core + spring-boot-starter-web + spring-boot-starter-aop + spring-cloud-starter-openfeign

- [ ] **Step 2: 编写三个 ThreadLocal Context 类** — 各自包含 private static ThreadLocal + 3 static methods

- [ ] **Step 3: 编写三个 Interceptor** — 实现 `HandlerInterceptor`，在 preHandle 中读取 Header 注入 Context，afterCompletion 中 clear

- [ ] **Step 4: 编写 JacksonConfig + WebMvcConfig + FeignConfig**

- JacksonConfig: `@Bean Jackson2ObjectMapperBuilderCustomizer`
- WebMvcConfig: `addInterceptors(registry)` 注册三个拦截器
- FeignConfig: `RequestInterceptor` 透传 `X-Request-Id`, `X-Tenant-Id`, `X-User-Id` 头

- [ ] **Step 5: 编写 @AuditLog + @RequireRole 注解和切面**

- `@AuditLog`: `@Target(METHOD)`, `@Retention(RUNTIME)`, String action, String detail
- `AuditLogAspect`: `@Around("@annotation(auditLog)")` — 从 RequestContext/UserContext 取值 → 构建 AuditLogMessage → RabbitTemplate.convertAndSend(AUDIT_EXCHANGE, "audit.log.#", message)
- `@RequireRole`: `@Target(METHOD)`, `@Retention(RUNTIME)`, String[] value
- `RequireRoleAspect`: `@Before("@annotation(requireRole)")` — 从 `UserContext.getRoles()` 取 roles → 交集判断 → 无交集抛 `BizException(NO_PERMISSION)`

- [ ] **Step 6: 验证编译**

```bash
mvn clean compile -pl common-lib/common-core,common-lib/common-web
```

---

### Task 4: common-security 公共安全模块

**Files:**
- Create: `common-lib/common-security/pom.xml`
- Create: `common-lib/common-security/src/main/java/com/platform/security/jwt/JwtProperties.java`
- Create: `common-lib/common-security/src/main/java/com/platform/security/jwt/JwtUtils.java`
- Create: `common-lib/common-security/src/main/java/com/platform/security/jwt/TokenPair.java`
- Create: `common-lib/common-security/src/main/java/com/platform/security/crypto/AesUtils.java`
- Create: `common-lib/common-security/src/main/java/com/platform/security/crypto/BCryptUtils.java`

**Interfaces:**
- `JwtProperties`: `@ConfigurationProperties("jwt")`, fields: `String secret`, `long accessTokenTtl = 7200`, `long refreshTokenTtl = 604800`
- `JwtUtils`: uses jjwt library
  - `generateAccessToken(userId, username, tenantId, roles):String` — claims: sub=userId, username, tenantId, roles(comma-separated), iat, exp=now+7200
  - `generateRefreshToken(userId):String` — claims: sub=userId, type="refresh", exp=now+604800
  - `validateToken(token):boolean` — parse + catch exceptions → false
  - `parseClaims(token):Claims`
- `TokenPair`: `record(String accessToken, String refreshToken, long expiresIn)`
- `AesUtils`: `encrypt(plainText, key):String`, `decrypt(cipherText, key):String` using AES/GCM/NoPadding via javax.crypto
- `BCryptUtils`: `@Component`, wraps `BCryptPasswordEncoder`, `encode(raw):String`, `matches(raw, encoded):boolean`

- [ ] **Step 1: 编写 common-security/pom.xml** — 依赖 common-core + jjwt-api + jjwt-impl(runtime) + jjwt-jackson(runtime) + spring-security-crypto + spring-boot-starter

- [ ] **Step 2: 编写 JwtProperties + TokenPair + JwtUtils**

- JwtProperties: `@ConfigurationProperties(prefix = "jwt")`, `@Component`, `@RefreshScope`
- TokenPair: Java record
- JwtUtils: `@Component`, 从 JwtProperties 读取 secret 构建 `SecretKey`，使用 `Jwts.builder()` / `Jwts.parser().verifyWith(key).build()`

- [ ] **Step 3: 编写 AesUtils + BCryptUtils**

- AesUtils: `SecretKeySpec` + `GCMParameterSpec`(128bit)，返回 Base64 编码密文
- BCryptUtils: `new BCryptPasswordEncoder()`

- [ ] **Step 4: 验证编译**

```bash
mvn clean compile -pl common-lib/common-core,common-lib/common-security
```

---

### Task 5: common-mq 公共消息队列模块

**Files:**
- Create: `common-lib/common-mq/pom.xml`
- Create: `common-lib/common-mq/src/main/java/com/platform/mq/config/RabbitMqConfig.java`
- Create: `common-lib/common-mq/src/main/java/com/platform/mq/exchange/ExchangeConstants.java`
- Create: `common-lib/common-mq/src/main/java/com/platform/mq/exchange/QueueConstants.java`
- Create: `common-lib/common-mq/src/main/java/com/platform/mq/message/TaskMessage.java`
- Create: `common-lib/common-mq/src/main/java/com/platform/mq/message/TaskResultMessage.java`
- Create: `common-lib/common-mq/src/main/java/com/platform/mq/message/AuditLogMessage.java`

**Interfaces:**
- `ExchangeConstants`: `TASK_EXCHANGE = "platform.task.exchange"`, `AUDIT_EXCHANGE = "platform.audit.exchange"`, `NOTIFY_EXCHANGE = "platform.notify.exchange"`, `TASK_DLX_EXCHANGE = "platform.task.dlx.exchange"` — all TopicExchange type
- `QueueConstants`: `IMAGE_TASK_QUEUE = "image.task.queue"`, `VIDEO_TASK_QUEUE = "video.task.queue"`, `TASK_RESULT_QUEUE = "task.result.queue"`, `AUDIT_LOG_QUEUE = "audit.log.queue"`, `TASK_DLQ = "task.dead.letter.queue"`
- `TaskMessage`: `record(String taskId, String userId, String tenantId, String workflowType, String engineType, Map<String, Object> params, int stepOrder, int retryCount)` — Serializable
- `TaskResultMessage`: `record(String taskId, String engineType, String status, List<FileInfo> outputFiles, String errorInfo, long durationMs)` + nested `FileInfo(String fileId, String url, String thumbnailUrl)`
- `AuditLogMessage`: `record(String userId, String tenantId, String action, String detail, String ip, LocalDateTime timestamp, String result)`
- `RabbitMqConfig`: `@Configuration`
  - `@Bean Jackson2JsonMessageConverter`
  - `@Bean TopicExchange taskExchange()`, `auditExchange()`, `notifyExchange()`, `taskDlxExchange()`
  - `@Bean Queue imageTaskQueue()`, `videoTaskQueue()`, `taskResultQueue()`, `auditLogQueue()`, `taskDeadLetterQueue()` — all with `x-dead-letter-exchange: platform.task.dlx.exchange`
  - `@Bean Binding` for each queue with corresponding routing keys: `task.image.*`, `task.video.*`, `task.result.#`, `audit.log.#`, `task.dead.#`

- [ ] **Step 1: 编写 common-mq/pom.xml** — 依赖 common-core + spring-boot-starter-amqp + jackson-databind

- [ ] **Step 2: 编写常量类 + 消息体 records**

ExchangeConstants & QueueConstants: all `public static final String`
TaskMessage, TaskResultMessage, AuditLogMessage: Java records implementing Serializable

- [ ] **Step 3: 编写 RabbitMqConfig**

```java
@Configuration
public class RabbitMqConfig {
    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public TopicExchange taskExchange() {
        return new TopicExchange(ExchangeConstants.TASK_EXCHANGE);
    }
    // ... all 4 exchanges, 5 queues, 5 bindings
    // Each business queue: QueueBuilder.durable(name).deadLetterExchange(TASK_DLX_EXCHANGE).build()
    // Bindings: BindingBuilder.bind(queue).to(exchange).with(routingKey)
}
```

- [ ] **Step 4: 验证编译**

```bash
mvn clean compile -pl common-lib/common-core,common-lib/common-mq
```

---

### Task 6: common-storage 公共存储模块

**Files:**
- Create: `common-lib/common-storage/pom.xml`
- Create: `common-lib/common-storage/src/main/java/com/platform/storage/StorageAdapter.java`
- Create: `common-lib/common-storage/src/main/java/com/platform/storage/UploadResult.java`
- Create: `common-lib/common-storage/src/main/java/com/platform/storage/FileMetadata.java`
- Create: `common-lib/common-storage/src/main/java/com/platform/storage/PartResult.java`

**Interfaces:**
- `StorageAdapter` interface — full method signatures per design doc §6.1:
  - `String providerName()`
  - `boolean isAvailable()`
  - `UploadResult upload(InputStream data, String objectKey, String contentType, Map<String, String> metadata)`
  - `String initiateMultipart(String objectKey, String contentType)`
  - `PartResult uploadPart(String uploadId, int partNumber, InputStream data)`
  - `UploadResult completeMultipart(String uploadId, List<PartResult> parts)`
  - `void abortMultipart(String uploadId)`
  - `InputStream download(String objectKey)`
  - `String presignedUrl(String objectKey, Duration ttl)`
  - `boolean exists(String objectKey)`
  - `FileMetadata metadata(String objectKey)`
  - `void delete(String objectKey)`
  - `void batchDelete(List<String> objectKeys)`
  - `InputStream thumbnail(String objectKey, int width, int height)`
- `UploadResult`: `record(String fileId, String objectKey, String url, Long fileSize)`
- `FileMetadata`: `record(String objectKey, String contentType, Long fileSize, String md5, Integer width, Integer height, Integer durationMs, LocalDateTime lastModified)`
- `PartResult`: `record(int partNumber, String etag)`

- [ ] **Step 1: 编写 common-storage/pom.xml** — 依赖 common-core（纯接口模块，无额外运行时依赖）

- [ ] **Step 2: 编写 StorageAdapter 接口 + 3 个 DTO record**

- [ ] **Step 3: 验证编译**

```bash
mvn clean compile -pl common-lib/common-core,common-lib/common-storage
```

---

### Task 7: common-feign 公共远程调用模块

**Files:**
- Create: `common-lib/common-feign/pom.xml`
- Create: `common-lib/common-feign/src/main/java/com/platform/feign/UserFeignClient.java`
- Create: `common-lib/common-feign/src/main/java/com/platform/feign/SecurityFeignClient.java`
- Create: `common-lib/common-feign/src/main/java/com/platform/feign/StorageFeignClient.java`
- Create: `common-lib/common-feign/src/main/java/com/platform/feign/dto/UserDTO.java`
- Create: `common-lib/common-feign/src/main/java/com/platform/feign/dto/QuotaDTO.java`
- Create: `common-lib/common-feign/src/main/java/com/platform/feign/dto/AuditResult.java`
- Create: `common-lib/common-feign/src/main/java/com/platform/feign/dto/FileDTO.java`
- Create: `common-lib/common-feign/src/main/java/com/platform/feign/fallback/UserFeignFallback.java`
- Create: `common-lib/common-feign/src/main/java/com/platform/feign/fallback/SecurityFeignFallback.java`
- Create: `common-lib/common-feign/src/main/java/com/platform/feign/fallback/StorageFeignFallback.java`

**Interfaces:**
- `UserFeignClient`: `@FeignClient(name="user-service", path="/api/v1/internal/users", fallback=UserFeignFallback.class)`
  - `@GetMapping("/{userId}") getUser(@PathVariable String userId): Result<UserDTO>`
  - `@GetMapping("/{userId}/quota") getQuota(@PathVariable String userId, @RequestParam String type): Result<QuotaDTO>`
  - `@PostMapping("/{userId}/quota/consume") consumeQuota(@PathVariable String userId, @RequestBody Map<String,Object> body): Result<Boolean>`
- `SecurityFeignClient`: `@FeignClient(name="security-service", path="/api/v1/internal/audit", fallback=SecurityFeignFallback.class)`
  - `@PostMapping("/prompt") auditPrompt(@RequestBody Map<String,String> body): Result<AuditResult>`
  - `@PostMapping("/content") auditContent(@RequestBody Map<String,String> body): Result<AuditResult>`
- `StorageFeignClient`: `@FeignClient(name="storage-service", path="/api/v1/internal/files", fallback=StorageFeignFallback.class)`
  - `@GetMapping("/{fileId}") getFile(@PathVariable String fileId): Result<FileDTO>`
  - `@PostMapping(value="/upload", consumes=MediaType.MULTIPART_FORM_DATA_VALUE) upload(@RequestPart MultipartFile file): Result<UploadResult>`
  - `@DeleteMapping("/{fileId}") delete(@PathVariable String fileId): Result<Void>`
- DTOs: `UserDTO(String username, String email, String tenantId, String status)`, `QuotaDTO(String quotaType, long limit, long used)`, `AuditResult(boolean passed, String reason)`, `FileDTO(String objectKey, String url, String contentType, Long fileSize)`
- All Fallbacks return `Result.fail(ErrorCode.SERVICE_TIMEOUT)`

- [ ] **Step 1: 编写 common-feign/pom.xml** — 依赖 common-core + common-storage + spring-cloud-starter-openfeign + spring-boot-starter-web

- [ ] **Step 2: 编写 DTO classes** — 4 个简单 POJO/record

- [ ] **Step 3: 编写 3 个 FeignClient 接口 + 3 个 Fallback**

每个 Fallback 实现对应 FeignClient，所有方法返回 `Result.fail(SERVICE_TIMEOUT)`。

- [ ] **Step 4: 验证编译**

```bash
mvn clean compile -pl common-lib/common-feign
```

---

## Phase 2: 基础设施服务 (Tasks 8-10)

### Task 8: Gateway 网关服务

**Files:**
- Create: `gateway/pom.xml`
- Create: `gateway/src/main/java/com/platform/gateway/GatewayApplication.java`
- Create: `gateway/src/main/java/com/platform/gateway/filter/JwtAuthFilter.java`
- Create: `gateway/src/main/java/com/platform/gateway/filter/RateLimitFilter.java`
- Create: `gateway/src/main/java/com/platform/gateway/filter/TenantContextFilter.java`
- Create: `gateway/src/main/java/com/platform/gateway/filter/AuditLogGatewayFilter.java`
- Create: `gateway/src/main/resources/bootstrap.yml`

**Interfaces:**
- Consumes: common-core, common-security (JwtUtils), common-mq (RabbitMqConfig, AuditLogMessage)
- Produces: Gateway 启动类 + 4 个 GlobalFilter

- [ ] **Step 1: 编写 gateway/pom.xml**

依赖: spring-cloud-starter-gateway, spring-cloud-starter-loadbalancer, spring-cloud-starter-alibaba-nacos-discovery, spring-cloud-starter-alibaba-nacos-config, common-core, common-security, common-mq, spring-boot-starter-data-redis-reactive. **不依赖 spring-boot-starter-web**（Gateway 使用 WebFlux）。

- [ ] **Step 2: 编写 GatewayApplication**

```java
@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

- [ ] **Step 3: 编写 JwtAuthFilter (order=0)**

实现 `GlobalFilter, Ordered`:
- 白名单: `/api/v1/auth/login`, `/api/v1/auth/register`, `/api/v1/auth/refresh`, `/api/v1/open/**` → chain.filter(exchange)
- 从 `Authorization: Bearer <token>` 提取 JWT → `JwtUtils.validateToken()` → `JwtUtils.parseClaims()`
- 通过 `ServerHttpRequest.mutate().header(...)` 注入: `X-User-Id`, `X-Username`, `X-Tenant-Id`, `X-User-Roles`
- 验证失败 → 返回 `Result.fail(TOKEN_INVALID)` JSON

- [ ] **Step 4: 编写 RateLimitFilter (order=1)**

基于 Redis Reactive + Lua 滑动窗口脚本，KEY: `ratelimit:{userId|ip}:{path}`，窗口大小和阈值从 Nacos 配置读取。

- [ ] **Step 5: 编写 TenantContextFilter (order=2) + AuditLogGatewayFilter (order=3)**

- TenantContextFilter: 确保 `X-Tenant-Id` 存在于转发头（Gateway 下游内网服务需要）
- AuditLogGatewayFilter: 记录请求路径、方法、状态码、耗时，异步发 `AuditLogMessage` 到 MQ（使用 `RabbitTemplate`）

- [ ] **Step 6: 编写 bootstrap.yml**

```yaml
spring:
  application:
    name: gateway
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:localhost:8848}
      config:
        server-addr: ${NACOS_ADDR:localhost:8848}
        file-extension: yaml
        shared-configs:
          - data-id: gateway-routes.yaml
            group: DEFAULT_GROUP
            refresh: true
server:
  port: 8080
jwt:
  secret: ${JWT_SECRET:change-me-in-production-min-32-chars}
  access-token-ttl: 7200
  refresh-token-ttl: 604800
```

- [ ] **Step 7: 验证编译**

```bash
mvn clean compile -pl gateway
```

---

### Task 9: User-Service 用户服务

**Files:**
- Create: `user-service/pom.xml`
- Create: `user-service/src/main/java/com/platform/user/UserServiceApplication.java`
- Create: `user-service/src/main/java/com/platform/user/entity/UserAccount.java`
- Create: `user-service/src/main/java/com/platform/user/entity/Tenant.java`
- Create: `user-service/src/main/java/com/platform/user/entity/UserTenant.java`
- Create: `user-service/src/main/java/com/platform/user/entity/ApiKey.java`
- Create: `user-service/src/main/java/com/platform/user/mapper/` (4 Mapper interfaces)
- Create: `user-service/src/main/java/com/platform/user/controller/AuthController.java`
- Create: `user-service/src/main/java/com/platform/user/controller/UserController.java`
- Create: `user-service/src/main/java/com/platform/user/controller/TenantController.java`
- Create: `user-service/src/main/java/com/platform/user/controller/InternalUserController.java`
- Create: `user-service/src/main/java/com/platform/user/service/AuthService.java`
- Create: `user-service/src/main/java/com/platform/user/service/QuotaService.java`
- Create: `user-service/src/main/java/com/platform/user/config/MyMetaObjectHandler.java`
- Create: `user-service/src/main/resources/bootstrap.yml`

**Interfaces:**
- Consumes: common-core, common-web, common-security (JwtUtils, BCryptUtils, AesUtils), common-mq
- Produces:

**Entities** (all extend BaseEntity):
- `UserAccount`: username(unique), passwordHash, email, phone, avatarUrl, status(ACTIVE/DISABLED/LOCKED), lastLoginAt, lastLoginIp
- `Tenant`: name, status(ACTIVE/SUSPENDED/EXPIRED), quotaConfig(JSON string), storageProvider(default: "minio")
- `UserTenant`: userId, tenantId, roleCode — unique key(userId, tenantId)
- `ApiKey`: userId, tenantId, keyHash(SHA-256), keyPrefix(前8位展示), name, rateLimitQpm=60, lastUsedAt, expiresAt, status(ACTIVE/REVOKED)

**AuthService:**
- `login(username, password) → TokenPair`: BCrypt verify → lookup UserTenant for tenantId + roleCode → JwtUtils.generateAccessToken + generateRefreshToken → update lastLoginAt/Ip → return TokenPair
- `refresh(refreshToken) → TokenPair`: validate refresh token → revoke old (Redis blacklist TTL=7d) → generate new pair
- `logout(refreshToken)`: add refresh token to Redis blacklist
- `register(RegisterRequest) → UserAccount`: validate uniqueness → create user + default tenant → assign TENANT_ADMIN role → return user

**QuotaService:**
- `checkAndConsume(userId, tenantId, quotaType, amount) → boolean`: Redis Lua script atomically checks limit vs used and INCRBY if under limit. Key format: `quota:{tenantId}:{userId}:{type}:{dateKey}`
- `getRemaining(userId, tenantId, quotaType) → long`: GET from Redis, fallback to MySQL quota_usage table

**Controllers:**
- `AuthController` (`/api/v1/auth`): POST login, refresh, logout, register — all @AuditLog annotated
- `UserController` (`/api/v1/users`): GET/PUT /me, GET /me/quota, GET/POST/DELETE /me/api-keys
- `TenantController` (`/api/v1/tenants`): GET /{id}, PUT /{id}, GET /{id}/members, POST /{id}/members, DELETE /{id}/members/{userId} — @RequireRole(TENANT_ADMIN)
- `InternalUserController` (`/api/v1/internal/users`): GET /{userId}, GET /{userId}/quota?type=, POST /{userId}/quota/consume

**MyMetaObjectHandler:** MyBatis-Plus auto-fill `createdAt`/`updatedAt`

- [ ] **Step 1: 编写 user-service/pom.xml** — 依赖所有 common 模块 + spring-boot-starter-web + mybatis-plus + mysql-connector-j + nacos + redis

- [ ] **Step 2: 编写实体类 + Mapper** — 4 个 JPA 实体 + 4 个 MyBatis-Plus Mapper

- [ ] **Step 3: 编写 AuthService** — 完整登录/刷新/登出/注册逻辑

- [ ] **Step 4: 编写 QuotaService** — Redis Lua 原子配额检查

- [ ] **Step 5: 编写 4 个 Controller** — RESTful API，参数校验 `@Valid`，统一返回 `Result<T>`

- [ ] **Step 6: 编写 bootstrap.yml** — Nacos 连接 + MySQL 数据源 + Redis 连接 + JWT 配置

- [ ] **Step 7: 验证编译**

```bash
mvn clean compile -pl user-service
```

---

### Task 10: Storage-Service 存储服务

**Files:**
- Create: `storage-service/pom.xml`
- Create: `storage-service/src/main/java/com/platform/storagesvc/StorageServiceApplication.java`
- Create: `storage-service/src/main/java/com/platform/storagesvc/entity/StorageFile.java`
- Create: `storage-service/src/main/java/com/platform/storagesvc/mapper/StorageFileMapper.java`
- Create: `storage-service/src/main/java/com/platform/storagesvc/adapter/MinioStorageAdapter.java`
- Create: `storage-service/src/main/java/com/platform/storagesvc/controller/FileController.java`
- Create: `storage-service/src/main/java/com/platform/storagesvc/controller/InternalFileController.java`
- Create: `storage-service/src/main/java/com/platform/storagesvc/service/FileService.java`
- Create: `storage-service/src/main/resources/bootstrap.yml`

**Interfaces:**
- Consumes: common-core, common-web, common-storage, common-mq
- Produces: FileController, InternalFileController, MinioStorageAdapter, FileService

**StorageFile entity** (extends BaseEntity):
userId, tenantId, originalName, objectKey, contentType, fileSize, fileMd5, width, height, durationMs, thumbnailKey, provider(default:"minio"), storageClass(TEMP/ASSET/ARCHIVE), visibility(PUBLIC/PRIVATE/TENANT), expireAt, sourceTaskId, sourceType(IMAGE_GEN/VIDEO_GEN/UPLOAD)

**MinioStorageAdapter** implements StorageAdapter:
- Constructor: `MinioClient`, `bucketName`
- upload: `minioClient.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(stream, size, -1).contentType(ct).build())`
- presignedUrl: `minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().bucket(bucket).object(key).method(Method.GET).expiry((int)ttl.toSeconds()).build())`
- thumbnail: `BufferedImage` scale → `ImageIO.write` → `ByteArrayInputStream`
- initiateMultipart/uploadPart/completeMultipart: MinIO S3 multipart APIs

**FileService:**
- `upload(userId, tenantId, file, storageClass) → StorageFile`: adapter.upload() + save metadata to DB
- `getPresignedUploadUrl(userId, tenantId, filename) → PresignResult`: adapter.presignedUrl(PUT) + create pending record
- `confirmUpload(fileId)`: update record status
- `download(fileId) → Resource`: adapter.download() → InputStreamResource
- `list(userId, tenantId, page, size) → PageResult<StorageFile>`
- `delete(fileId)`: adapter.delete() + mark deleted in DB
- `cleanupExpiredTempFiles()`: `@Scheduled(cron="0 0 2 * * ?")` — delete TEMP files with expire_at < now()

**FileController** (`/api/v1/files`):
POST /upload, GET /presign?filename=&contentType=, POST /presign/callback, GET /?page=&size=, GET /{id}, GET /{id}/download, DELETE /{id}

**InternalFileController** (`/api/v1/internal/files`):
GET /{fileId}, POST /upload(@RequestPart MultipartFile), DELETE /{fileId}

- [ ] **Step 1: 编写 storage-service/pom.xml** — 依赖 common-core/web/storage/mq + minio + spring-boot-starter-web + mybatis-plus + mysql + nacos + redis

- [ ] **Step 2: 编写 StorageFile 实体 + Mapper**

- [ ] **Step 3: 编写 MinioStorageAdapter** — 使用 MinIO SDK `io.minio:minio:8.5.7`，构造函数注入 `@Value("${minio.endpoint}")`, `@Value("${minio.access-key}")`, `@Value("${minio.secret-key}")`, `@Value("${minio.bucket}")`

- [ ] **Step 4: 编写 FileService** — 协调 StorageAdapter + MyBatis-Plus Mapper

- [ ] **Step 5: 编写 Controller** — 两个 Controller，文件直传使用 `MultipartFile`

- [ ] **Step 6: 验证编译**

```bash
mvn clean compile -pl storage-service
```

---

## Phase 3: 业务核心服务 (Tasks 11-12)

### Task 11: Agent-Core 任务编排核心

**Files:**
- Create: `agent-core/pom.xml`
- Create: `agent-core/src/main/java/com/platform/agent/AgentCoreApplication.java`
- Create: `agent-core/src/main/java/com/platform/agent/entity/AgentTask.java`
- Create: `agent-core/src/main/java/com/platform/agent/entity/WorkflowStep.java`
- Create: `agent-core/src/main/java/com/platform/agent/mapper/AgentTaskMapper.java`
- Create: `agent-core/src/main/java/com/platform/agent/mapper/WorkflowStepMapper.java`
- Create: `agent-core/src/main/java/com/platform/agent/controller/TaskController.java`
- Create: `agent-core/src/main/java/com/platform/agent/controller/AdminController.java`
- Create: `agent-core/src/main/java/com/platform/agent/controller/OpenApiController.java`
- Create: `agent-core/src/main/java/com/platform/agent/service/TaskService.java`
- Create: `agent-core/src/main/java/com/platform/agent/service/WorkflowEngine.java`
- Create: `agent-core/src/main/java/com/platform/agent/service/IntentParser.java`
- Create: `agent-core/src/main/java/com/platform/agent/consumer/TaskResultConsumer.java`
- Create: `agent-core/src/main/resources/bootstrap.yml`

**Interfaces:**
- Consumes: common-core, common-web, common-mq, common-feign
- Produces: TaskController(task CRUD), AdminController(stats/review), OpenApiController(open API), WorkflowEngine, IntentParser, TaskResultConsumer

**AgentTask entity**: userId, tenantId, workflowType(SINGLE/PIPELINE/BATCH), status(CREATED→VALIDATING→PENDING→PROCESSING→DONE→REVIEWING→PASS/REJECT + RETRYING + FAILED + CANCELLED), requestParams(JSON string), result(JSON string), errorMsg, retryCount(default 0), priority(default 0), completedAt

**WorkflowStep entity**: taskId, stepOrder, stepType(IMAGE/VIDEO/AUDIT/POST_PROCESS), status(PENDING→PROCESSING→DONE/FAILED/SKIPPED), input(JSON), output(JSON), startedAt, finishedAt

**TaskService:**
- `submitTask(userId, tenantId, request) → AgentTask`:
  1. Quota check via UserFeignClient.consumeQuota()
  2. Prompt audit via SecurityFeignClient.auditPrompt()
  3. Build workflow steps via WorkflowEngine.buildWorkflow()
  4. Save AgentTask + WorkflowSteps → status=VALIDATING→PENDING
  5. Send TaskMessage via RabbitTemplate to TASK_EXCHANGE with routingKey=`task.{image|video}.{workflowType}`
- `getTask(taskId) → AgentTaskDTO` (includes steps)
- `listTasks(userId, tenantId, page, status) → PageResult<AgentTask>`
- `cancelTask(taskId) → AgentTask`: set status=CANCELLED, notify Engine via MQ

**WorkflowEngine:**
- `buildWorkflow(request) → List<WorkflowStep>`:
  - SINGLE: 1 generate step + 1 audit step
  - PIPELINE: N sequential steps as defined in request
  - BATCH: N parallel generate steps + 1 merge step
- `advanceStep(taskId)`: find next PENDING step → set PROCESSING → send TaskMessage; if no more → set DONE → trigger content safety audit via SecurityFeignClient
- `handleStepFailure(taskId, stepOrder, error)`: if retryCount < 3 → RETRYING re-send; else → FAILED

**IntentParser:**
- `parse(prompt) → Intent(String theme, String style, String type, String aspectRatio, String tone)`
- 关键词匹配: type(poster/illustration/photo/logo), aspectRatio(portrait→1024×1536/landscape→1536×1024/square→1024×1024), style(cyberpunk/photorealistic/anime/…), tone(dark/bright/warm/cool)

**TaskResultConsumer:**
- `@RabbitListener(queues = TASK_RESULT_QUEUE)`
- onMessage(TaskResultMessage): update step status → if DONE advanceStep(); if FAIL handleStepFailure(); update AgentTask

**Controllers:**
- `TaskController` (`/api/v1/tasks`): POST /image, /video, /batch, /workflow; GET /; GET /{id}; GET /{id}/result; DELETE /{id}
- `AdminController` (`/api/v1/admin`): GET /stats; GET /tasks; PUT /tasks/{id}/review
- `OpenApiController` (`/api/v1/open`): POST /image; POST /video; GET /tasks/{id}; GET /tasks/{id}/result

- [ ] **Step 1: 编写 agent-core/pom.xml** — 依赖 spring-boot-starter-web + mybatis-plus + mysql + nacos + rabbitmq + redis + all common modules

- [ ] **Step 2: 编写实体 + Mapper** — AgentTask, WorkflowStep, 2 Mappers

- [ ] **Step 3: 编写 TaskService + WorkflowEngine + IntentParser**

- [ ] **Step 4: 编写 TaskResultConsumer**

- [ ] **Step 5: 编写 3 个 Controller**

- [ ] **Step 6: 验证编译**

```bash
mvn clean compile -pl agent-core
```

---

### Task 12: Security-Service 安全服务

**Files:**
- Create: `security-service/pom.xml`
- Create: `security-service/src/main/java/com/platform/securitysvc/SecurityServiceApplication.java`
- Create: `security-service/src/main/java/com/platform/securitysvc/controller/InternalAuditController.java`
- Create: `security-service/src/main/java/com/platform/securitysvc/service/AuditService.java`
- Create: `security-service/src/main/java/com/platform/securitysvc/service/PromptAuditService.java`
- Create: `security-service/src/main/java/com/platform/securitysvc/service/ContentSafetyService.java`
- Create: `security-service/src/main/java/com/platform/securitysvc/service/WatermarkService.java`
- Create: `security-service/src/main/java/com/platform/securitysvc/consumer/AuditLogConsumer.java`
- Create: `security-service/src/main/java/com/platform/securitysvc/entity/AuditLog.java`
- Create: `security-service/src/main/java/com/platform/securitysvc/mapper/AuditLogMapper.java`
- Create: `security-service/src/main/resources/bootstrap.yml`

**Interfaces:**
- Consumes: common-core, common-web, common-mq, common-security (AesUtils, BCryptUtils)
- Produces: InternalAuditController, PromptAuditService, ContentSafetyService, WatermarkService, AuditLogConsumer

**AuditLog entity**: userId, tenantId, action, detail, ip, actionTime, result(SUCCESS/FAIL)

**PromptAuditService:**
- `@RefreshScope`, sensitive words from Nacos config `security.sensitive-words` (comma-separated)
- `audit(prompt) → AuditResult`: case-insensitive contains check → `AuditResult(boolean passed, String reason)`

**ContentSafetyService (预留):**
- `checkImage(url) → AuditResult`: stub returns passed, reserved for NSFW API integration
- `checkVideo(url) → AuditResult`: stub returns passed

**WatermarkService:**
- `injectWatermark(byte[] imageBytes, String userId) → byte[]`: `BufferedImage` + `Graphics2D.drawString("AI Generated | User:" + userId)` at bottom-right, semi-transparent white

**AuditLogConsumer:**
- `@RabbitListener(queues = AUDIT_LOG_QUEUE)`
- Bulk insert AuditLog entities into MySQL

**InternalAuditController** (`/api/v1/internal/audit`):
- POST /prompt: `@RequestBody {prompt}` → PromptAuditService.audit() → AuditResult
- POST /content: `@RequestBody {fileUrl, type}` → ContentSafetyService → AuditResult
- POST /watermark: `@RequestParam file MultipartFile` + `@RequestParam userId` → WatermarkService.injectWatermark() → byte[] (image/png)

- [ ] **Step 1: 编写 security-service/pom.xml** — 依赖 spring-boot-starter-web + mybatis-plus + mysql + nacos + rabbitmq + redis + all common modules

- [ ] **Step 2: 编写 AuditLog 实体 + Mapper**

- [ ] **Step 3: 编写 PromptAuditService + ContentSafetyService + WatermarkService**

- [ ] **Step 4: 编写 AuditLogConsumer**

- [ ] **Step 5: 编写 InternalAuditController**

- [ ] **Step 6: 验证编译**

```bash
mvn clean compile -pl security-service
```

---

## Phase 4: 计算引擎 (Tasks 13-14)

### Task 13: Image-Engine 图片生成引擎

**Files:**
- Create: `image-engine/pom.xml`
- Create: `image-engine/src/main/java/com/platform/image/ImageEngineApplication.java`
- Create: `image-engine/src/main/java/com/platform/image/adapter/ImageGenerationAdapter.java`
- Create: `image-engine/src/main/java/com/platform/image/model/GenerateRequest.java`
- Create: `image-engine/src/main/java/com/platform/image/model/GenerationResult.java`
- Create: `image-engine/src/main/java/com/platform/image/model/ModelCapability.java`
- Create: `image-engine/src/main/java/com/platform/image/model/TaskStatus.java`
- Create: `image-engine/src/main/java/com/platform/image/client/ApiClient.java`
- Create: `image-engine/src/main/java/com/platform/image/client/RetryPolicy.java`
- Create: `image-engine/src/main/java/com/platform/image/client/CircuitBreaker.java`
- Create: `image-engine/src/main/java/com/platform/image/service/ImageGenerationFacade.java`
- Create: `image-engine/src/main/java/com/platform/image/service/ModelRegistry.java`
- Create: `image-engine/src/main/java/com/platform/image/service/PostProcessor.java`
- Create: `image-engine/src/main/java/com/platform/image/consumer/TaskConsumer.java`
- Create: `image-engine/src/main/resources/bootstrap.yml`

**Interfaces:**
- Consumes: common-core, common-mq, common-storage, common-feign (StorageFeignClient)
- Produces: TaskConsumer (rabbitmq), ImageGenerationFacade, ModelRegistry, ApiClient

**Adapter interface** (copied from common-storage pattern):
```java
public interface ImageGenerationAdapter {
    String modelName();
    ModelCapability capability();
    List<GenerationResult> generate(GenerateRequest request);
    TaskStatus queryStatus(String externalTaskId);
    boolean healthCheck();
}
```

**Model DTOs:**
- `GenerateRequest`: prompt, size(String), count(int), style, format, quality, negativePrompt, referenceImages(List)
- `GenerationResult`: url, base64Data, format, width, height, seed
- `ModelCapability`: supportedResolutions(List), supportedStyles(List), supportedFormats(List), maxBatchSize(int)
- `TaskStatus` enum: PENDING, PROCESSING, DONE, FAILED + progress(int 0-100)

**ApiClient:**
- OkHttpClient singleton: connectTimeout=10s, readTimeout=60s
- `callSync(endpoint, apiKey, body) → String`: POST JSON → response body
- `callAsync(endpoint, apiKey, body) → String`: POST → extract taskId from 202 response
- `pollStatus(endpoint, taskId, apiKey, timeout) → TaskStatus`: GET polling every 2s
- `streamSSE(endpoint, apiKey, body) → Flux<String>`: SSE streaming (reserved)

**RetryPolicy:**
- `shouldRetry(attempt, statusCode) → boolean`: timeout→retry(max3), 429→retry(backoff 1s/2s/4s), 5xx→retry(max2), 4xx→no retry
- `sleepBeforeRetry(attempt)`: Thread.sleep with exponential backoff

**CircuitBreaker (singleton per model):**
- States: CLOSED→OPEN→HALF_OPEN→CLOSED
- OPEN when failureRate >= 50% over last 10 requests
- OPEN duration: 30s → HALF_OPEN
- HALF_OPEN: allow 3 probe requests, all success→CLOSED, any fail→OPEN

**ModelRegistry:**
- Manages `List<ImageGenerationAdapter>` (injected via Spring)
- `select(GenerateRequest) → ImageGenerationAdapter`: filter by capability match → pick lowest load
- `getAvailableModels() → List<String>`

**ImageGenerationFacade:**
- `generate(taskId, request)`:
  1. ModelRegistry.select(request) → adapter
  2. CircuitBreaker.allowRequest() check
  3. ApiClient.callSync/callAsync → raw response
  4. adapter.generate() or adapter.queryStatus() → List<GenerationResult>
  5. PostProcessor.process() → format convert + resize + thumbnail
  6. Upload each result to Storage-Service
  7. Publish TaskResultMessage to TASK_RESULT_QUEUE
  8. On error: TaskResultMessage(status=FAILED, errorInfo=...)

**PostProcessor:**
- `process(List<GenerationResult>) → List<GenerationResult>`: format normalization(png), resize if exceeds max, generate thumbnail(200×200), optionally call Security-Service watermark

**TaskConsumer:**
- `@RabbitListener(queues = IMAGE_TASK_QUEUE, concurrency = "3-10")`
- `onMessage(TaskMessage, Channel, @Header(DELIVERY_TAG) long tag)`:
  1. Idempotency: `redisTemplate.opsForValue().setIfAbsent("task:lock:" + taskId, "1", Duration.ofHours(1))`
  2. facade.generate(taskId, params)
  3. channel.basicAck(tag)
  4. On failure: retry ≤3 → basicNack(reqeue); retry >3 → basicNack(false) → DLQ

- [ ] **Step 1: 编写 image-engine/pom.xml** — 依赖 spring-boot-starter-web + nacos + rabbitmq + redis + common-core + common-mq + common-storage + common-feign + okhttp

- [ ] **Step 2: 编写适配器接口 + DTO** — 4 个 model 类

- [ ] **Step 3: 编写 ApiClient + RetryPolicy + CircuitBreaker** — OkHttp client, backoff, state machine

- [ ] **Step 4: 编写 ModelRegistry + ImageGenerationFacade + PostProcessor**

- [ ] **Step 5: 编写 TaskConsumer** — RabbitMQ consumer with manual ACK + idempotency

- [ ] **Step 6: 验证编译**

```bash
mvn clean compile -pl image-engine
```

---

### Task 14: Video-Engine 视频生成引擎

**Files:**
- Create: `video-engine/pom.xml`
- Create: `video-engine/src/main/java/com/platform/video/VideoEngineApplication.java`
- Create: `video-engine/src/main/java/com/platform/video/adapter/VideoGenerationAdapter.java`
- Create: `video-engine/src/main/java/com/platform/video/model/VideoGenerateRequest.java`
- Create: `video-engine/src/main/java/com/platform/video/model/VideoGenerationResult.java`
- Create: `video-engine/src/main/java/com/platform/video/model/VideoModelCapability.java`
- Create: `video-engine/src/main/java/com/platform/video/client/ApiClient.java`
- Create: `video-engine/src/main/java/com/platform/video/client/RetryPolicy.java`
- Create: `video-engine/src/main/java/com/platform/video/client/CircuitBreaker.java`
- Create: `video-engine/src/main/java/com/platform/video/service/VideoGenerationFacade.java`
- Create: `video-engine/src/main/java/com/platform/video/service/ModelRegistry.java`
- Create: `video-engine/src/main/java/com/platform/video/service/VideoPostProcessor.java`
- Create: `video-engine/src/main/java/com/platform/video/consumer/TaskConsumer.java`
- Create: `video-engine/src/main/resources/bootstrap.yml`

**Interfaces:**
- Consumes: common-core, common-mq, common-storage, common-feign
- Produces: TaskConsumer, VideoGenerationFacade, VideoPostProcessor

Same structure as Image-Engine with these key differences:

**VideoGenerationAdapter:**
```java
public interface VideoGenerationAdapter {
    String modelName();
    VideoModelCapability capability();
    VideoGenerationResult textToVideo(VideoGenerateRequest request);
    VideoGenerationResult imageToVideo(VideoGenerateRequest request);
    TaskStatus queryStatus(String externalTaskId);
    boolean healthCheck();
}
```

**VideoGenerateRequest**: prompt, duration(int seconds), fps(int), resolution(String), style, sourceImageUrl, negativePrompt

**VideoGenerationResult**: videoUrl, thumbnailUrl, format, width, height, duration, fps, fileSize

**VideoModelCapability**: maxDuration(int), supportedResolutions, supportedFps, maxBatchSize

**ApiClient differences:**
- readTimeout = 300 seconds (video generation is slow)
- Poll interval: 5s (not 2s)
- CircuitBreaker timeout threshold: 15 minutes

**VideoPostProcessor:**
- Extract first frame as thumbnail: `BufferedImage` from video file (reserved for FFmpeg/JCodec)
- Upload video + thumbnail to Storage-Service
- File storageClass = ASSET (longer TTL)

**TaskConsumer:**
- `@RabbitListener(queues = VIDEO_TASK_QUEUE, concurrency = "1-3")` — serial consumption (prefetch=1) due to video resource intensity
- Same idempotency, retry, and DLQ pattern as Image-Engine

- [ ] **Step 1: 编写 video-engine/pom.xml** — 同 image-engine

- [ ] **Step 2: 编写适配器接口 + Video DTOs**

- [ ] **Step 3: 编写 ApiClient + RetryPolicy + CircuitBreaker** — 超时/轮询参数适配视频场景

- [ ] **Step 4: 编写 ModelRegistry + VideoGenerationFacade + VideoPostProcessor**

- [ ] **Step 5: 编写 TaskConsumer** — prefetch=1, 15min timeout

- [ ] **Step 6: 验证编译**

```bash
mvn clean compile -pl video-engine
```

---

## Phase 5: 集成验证 (Task 15)

### Task 15: 全量编译与部署配置

**Files:**
- Create: `docker-compose.yml`
- Create: `sql/init.sql`
- Create: `README.md`

- [ ] **Step 1: 全量编译验证**

```bash
mvn clean package -DskipTests
```
Expected: BUILD SUCCESS for all 13 modules.

- [ ] **Step 2: 编写 docker-compose.yml**

```yaml
version: '3.8'
services:
  nacos:
    image: nacos/nacos-server:v2.3.0
    container_name: nacos
    ports:
      - "8848:8848"
      - "9848:9848"
    environment:
      MODE: standalone
    volumes:
      - nacos_data:/home/nacos/data

  mysql:
    image: mysql:8.0
    container_name: mysql
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: root123
    volumes:
      - ./sql:/docker-entrypoint-initdb.d
      - mysql_data:/var/lib/mysql

  redis:
    image: redis:7-alpine
    container_name: redis
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data

  rabbitmq:
    image: rabbitmq:3.12-management-alpine
    container_name: rabbitmq
    ports:
      - "5672:5672"
      - "15672:15672"
    environment:
      RABBITMQ_DEFAULT_USER: guest
      RABBITMQ_DEFAULT_PASS: guest
    volumes:
      - rabbitmq_data:/var/lib/rabbitmq

  minio:
    image: minio/minio:latest
    container_name: minio
    ports:
      - "9000:9000"
      - "9001:9001"
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    volumes:
      - minio_data:/data

volumes:
  nacos_data:
  mysql_data:
  redis_data:
  rabbitmq_data:
  minio_data:
```

- [ ] **Step 3: 编写 SQL 初始化脚本**

Full DDL in `sql/init.sql`:

```sql
-- Create databases
CREATE DATABASE IF NOT EXISTS agent_platform DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS user_service DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS security_service DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS storage_service DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE agent_platform;
CREATE TABLE IF NOT EXISTS agent_task (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,
    tenant_id VARCHAR(32) NOT NULL,
    workflow_type VARCHAR(16) NOT NULL COMMENT 'SINGLE|PIPELINE|BATCH',
    status VARCHAR(16) NOT NULL DEFAULT 'CREATED',
    request_params JSON,
    result JSON,
    error_msg TEXT,
    retry_count INT DEFAULT 0,
    priority INT DEFAULT 0,
    completed_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_tenant (user_id, tenant_id),
    INDEX idx_status (status),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_workflow_step (
    id VARCHAR(32) PRIMARY KEY,
    task_id VARCHAR(32) NOT NULL,
    step_order INT NOT NULL,
    step_type VARCHAR(16) NOT NULL COMMENT 'IMAGE|VIDEO|AUDIT|POST_PROCESS',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    input JSON,
    output JSON,
    started_at DATETIME,
    finished_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

USE user_service;
CREATE TABLE IF NOT EXISTS user_account (
    id VARCHAR(32) PRIMARY KEY,
    username VARCHAR(64) UNIQUE NOT NULL,
    password_hash VARCHAR(256) NOT NULL,
    email VARCHAR(128),
    phone VARCHAR(32),
    avatar_url VARCHAR(512),
    status VARCHAR(16) DEFAULT 'ACTIVE' COMMENT 'ACTIVE|DISABLED|LOCKED',
    last_login_at DATETIME,
    last_login_ip VARCHAR(64),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tenant (
    id VARCHAR(32) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(16) DEFAULT 'ACTIVE' COMMENT 'ACTIVE|SUSPENDED|EXPIRED',
    quota_config JSON,
    storage_provider VARCHAR(32) DEFAULT 'minio',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_tenant (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,
    tenant_id VARCHAR(32) NOT NULL,
    role_code VARCHAR(32) NOT NULL COMMENT 'SUPER_ADMIN|TENANT_ADMIN|DEVELOPER|CONTENT_CREATOR|VIEWER',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_tenant (user_id, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS api_key (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,
    tenant_id VARCHAR(32) NOT NULL,
    key_hash VARCHAR(256) NOT NULL,
    key_prefix VARCHAR(16) NOT NULL,
    name VARCHAR(128),
    rate_limit_qpm INT DEFAULT 60,
    last_used_at DATETIME,
    expires_at DATETIME,
    status VARCHAR(16) DEFAULT 'ACTIVE' COMMENT 'ACTIVE|REVOKED',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quota_usage (
    id VARCHAR(32) PRIMARY KEY,
    tenant_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(32),
    quota_type VARCHAR(16) NOT NULL COMMENT 'IMAGE|VIDEO|STORAGE|API_CALL',
    period VARCHAR(16) NOT NULL COMMENT 'DAILY|MONTHLY|TOTAL',
    period_key VARCHAR(16) NOT NULL,
    used BIGINT DEFAULT 0,
    limit_amount BIGINT NOT NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_quota (tenant_id, user_id, quota_type, period, period_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

USE security_service;
CREATE TABLE IF NOT EXISTS audit_log (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32),
    tenant_id VARCHAR(32),
    action VARCHAR(64) NOT NULL,
    detail TEXT,
    ip VARCHAR(64),
    action_time DATETIME NOT NULL,
    result VARCHAR(16) COMMENT 'SUCCESS|FAIL',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_tenant (user_id, tenant_id),
    INDEX idx_action_time (action_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

USE storage_service;
CREATE TABLE IF NOT EXISTS storage_file (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,
    tenant_id VARCHAR(32) NOT NULL,
    original_name VARCHAR(512),
    object_key VARCHAR(1024) NOT NULL,
    content_type VARCHAR(128),
    file_size BIGINT,
    file_md5 VARCHAR(64),
    width INT,
    height INT,
    duration_ms INT,
    thumbnail_key VARCHAR(1024),
    provider VARCHAR(32) DEFAULT 'minio',
    storage_class VARCHAR(16) DEFAULT 'TEMP' COMMENT 'TEMP|ASSET|ARCHIVE',
    visibility VARCHAR(16) DEFAULT 'PRIVATE' COMMENT 'PUBLIC|PRIVATE|TENANT',
    expire_at DATETIME,
    source_task_id VARCHAR(32),
    source_type VARCHAR(16) COMMENT 'IMAGE_GEN|VIDEO_GEN|UPLOAD',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_tenant (user_id, tenant_id),
    INDEX idx_task (source_task_id),
    INDEX idx_expire (expire_at),
    INDEX idx_storage_class (storage_class)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 4: 编写 README.md**

Include: project intro, architecture diagram (refer to design doc), quick start (docker-compose up + mvn spring-boot:run per service), module descriptions, API overview table.

- [ ] **Step 5: 基础设施启动验证**

```bash
docker-compose up -d
# Wait for all services healthy
docker-compose ps  # all should show "Up"
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: enterprise AI Agent platform complete framework

- 13 Maven modules: 6 common-lib + 7 microservices
- Gateway: SCG + JWT auth + rate limiting + audit filter chain
- User-Service: auth (JWT dual token), RBAC (5 roles), tenant isolation, quota
- Security-Service: prompt audit, content safety (reserved), watermark, audit log
- Agent-Core: task orchestration, workflow engine (Single/Pipeline/Batch), smart routing
- Image/Video-Engine: model adapter pattern, API client (OkHttp), retry/circuit-breaker
- Storage-Service: MinIO adapter, presigned upload, file lifecycle management
- Docker Compose for infrastructure (Nacos, MySQL, Redis, RabbitMQ, MinIO)"
```

---

## Summary

| Phase | Tasks | Content | File Count |
|-------|-------|---------|-----------|
| Phase 1 | 1-7 | Project scaffold + 6 common-lib modules | ~45 |
| Phase 2 | 8-10 | Gateway + User-Service + Storage-Service | ~25 |
| Phase 3 | 11-12 | Agent-Core + Security-Service | ~20 |
| Phase 4 | 13-14 | Image-Engine + Video-Engine | ~25 |
| Phase 5 | 15 | Integration (Docker + SQL + README) | 3 |

**Total: 15 Tasks, ~118 files**
