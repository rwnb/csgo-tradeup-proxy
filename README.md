# csgo-tradeup-proxy

从 `csgo-tradeup-back` 提取的独立 Spring Boot 空项目，包名为 `com.csgo.tradeup.proxy`。沿用 Spring Boot 2.2.2、MyBatis-Plus 3.3.1、MySQL、Redis/Lettuce、Swagger 2 和 Maven Wrapper，使用 JDK 17 构建及运行，保持原项目 Java 8 字节码级别。

## 已包含

- Web、AOP、参数校验基础依赖，BCrypt 密码工具、跨域配置。
- `ResultInfo`、`ResultCode`、`ResultHelper` 统一响应，以及业务异常和全局异常处理。
- MyBatis-Plus 分页、乐观锁配置，分页参数、分页结果与转换工具。
- Redis 字符串键/JSON 值序列化，以及带有效期缓存、原子写入、校验删除、计数工具。
- Swagger 开关、控制台及按天滚动日志、环境变量配置。
- `GET /health` 进程存活接口。

Steam 内部接口使用独立服务密钥，未配置或密钥不足 32 字节时拒绝访问。账号权限和凭据密文仍由原后端管理；本服务中的 Steam 会话与未确认事件仅保存在内存，原后端停止轮询后约 45 秒关闭会话。服务重启会丢失会话，需要重新登录。库存接口按 appId、contextId 和游标返回单页，调用方负责处理分页和失败。

跨公网部署时必须在服务前配置 HTTPS，使用受信任的证书，并限制 8092 仅能被反向代理或可信网络访问；不要将明文内部端口暴露公网。设置 `STEAM_INTERNAL_KEY` 为至少 32 字节的随机值，与后端 `STEAM_PROXY_INTERNAL_KEY` 相同。`STEAM_COMMUNITY_PROXY_HOST`、`STEAM_COMMUNITY_PROXY_PORT`、`STEAM_COMMUNITY_PROXY_USERNAME`、`STEAM_COMMUNITY_PROXY_PASSWORD` 可配置 IPWO；这些配置只用于 Community 请求，不作用于 Steam CM 登录连接。账号密码、令牌和密钥不得写入仓库、访问日志或反向代理请求体日志。两个服务器应同步系统时间。

库存、交易链接及服务间链路上线前需要在目标服务器验证，尤其是 IPWO 的代理协议与认证配置。默认未配置代理时 Community 直连。

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

Docker 使用 JDK 17 多阶段构建，并以非 root 用户运行。`.dockerignore` 排除了本机 `.env`、日志和构建产物；实际密钥由服务器上的环境文件提供，文件权限应限制为部署用户可读。

```bash
docker build -t csgo-tradeup-proxy:local .
docker run -d --name csgo-tradeup-proxy --restart unless-stopped \
  --env-file /path/to/proxy.env -p 127.0.0.1:8092:8092 csgo-tradeup-proxy:local
```

示例绑定服务器本地回环地址，服务器上的 HTTPS 反向代理再转发到 `127.0.0.1:8092`。如反向代理也运行于容器，需使用受限 Docker 网络，不要照搬该回环地址。运行和重启容器会丢失内存中的 Steam 登录会话，需在后台重新登录；持久凭据由原后端保存。

运行 JAR 前同样需导出环境变量，也可通过外部 `config/application.yml` 配置连接信息。测试使用内存 H2 验证数据源，检查统一响应、异常处理、跨域、Swagger 及 Redis 序列化配置，不依赖外部 MySQL/Redis，也不执行真实 Redis 命令。

后续业务 Controller 放在 `com.csgo.tradeup.proxy.controller`，Mapper 放在 `com.csgo.tradeup.proxy.repository.mapper`。空项目尚无 Mapper，启动时出现未找到 Mapper 的提示属预期。
