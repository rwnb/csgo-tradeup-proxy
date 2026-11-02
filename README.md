# csgo-tradeup-proxy

从 `csgo-tradeup-back` 提取的独立 Spring Boot 空项目，包名为 `com.csgo.tradeup.proxy`。沿用 Spring Boot 2.2.2、MyBatis-Plus 3.3.1、MySQL、Redis/Lettuce、Swagger 2 和 Maven Wrapper，使用 JDK 17 构建及运行，保持原项目 Java 8 字节码级别。

## 已包含

- Web、AOP、参数校验基础依赖，BCrypt 密码工具、跨域配置。
- `ResultInfo`、`ResultCode`、`ResultHelper` 统一响应，以及业务异常和全局异常处理。
- MyBatis-Plus 分页、乐观锁配置，分页参数、分页结果与转换工具。
- Redis 字符串键/JSON 值序列化，以及带有效期缓存、原子写入、校验删除、计数工具。
- Swagger 开关、控制台及按天滚动日志、环境变量配置。
- `GET /health` 进程存活接口。

这是基础工程，尚未接入 Xunics 或其他代理业务，也没有登录认证、角色权限、Steam 业务、业务 Mapper 和业务表 SQL。跨域默认允许任意来源但不携带 Cookie 凭据；后续业务按需要接入认证。Swagger 不预设原后端的 Token 登录方案。

## 配置与启动

安装 JDK 17。首次使用 Maven Wrapper 需要网络下载 Maven 和依赖。

```bash
cp .env.example .env
# 编辑 .env，填写当前服务的数据库和 Redis 配置。
set -a
source .env
set +a
./mvnw spring-boot:run
```

默认端口 `8092`，MySQL 数据库名 `csgo_tradeup_proxy`，Redis 数据库编号 `2`，可通过 `.env.example` 中的变量覆盖。没有预置真实账号密码。数据源初始化和 Flyway 默认关闭，不会自动建表或执行原业务 SQL；需要数据库业务时自行创建数据库并配置授权。

存活检查：`http://127.0.0.1:8092/health`，返回：

```json
{"success":true,"code":200,"message":"成功","data":{"application":"csgo-tradeup-proxy"}}
```

该接口只检查进程，不代表数据库或 Redis 已连通。本地查看文档时使用 `SWAGGER_ENABLED=true ./mvnw spring-boot:run`，访问 `http://127.0.0.1:8092/swagger-ui.html`。Swagger 默认关闭。

## 测试与打包

```bash
./mvnw clean verify
java -jar target/csgo-tradeup-proxy.jar
```

运行 JAR 前同样需导出环境变量，也可通过外部 `config/application.yml` 配置连接信息。测试使用内存 H2 验证数据源，检查统一响应、异常处理、跨域、Swagger 及 Redis 序列化配置，不依赖外部 MySQL/Redis，也不执行真实 Redis 命令。

后续业务 Controller 放在 `com.csgo.tradeup.proxy.controller`，Mapper 放在 `com.csgo.tradeup.proxy.repository.mapper`。空项目尚无 Mapper，启动时出现未找到 Mapper 的提示属预期。
