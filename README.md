# csgo-tradeup-proxy

独立 Steam 代理服务，包名为 `com.csgo.tradeup.proxy`。负责 Steam 连接、账号密码登录、Steam Guard 等待、交易链接和库存请求；账号权限及加密凭据由 `csgo-tradeup-back` 管理。使用 JDK 17 构建及运行，保持原项目 Java 8 字节码级别。

## 已包含

- Web、AOP、参数校验基础依赖，BCrypt 密码工具、跨域配置。
- `ResultInfo`、`ResultCode`、`ResultHelper` 统一响应，以及业务异常和全局异常处理。
- MyBatis-Plus 分页、乐观锁配置，分页参数、分页结果与转换工具。
- Redis 字符串键/JSON 值序列化，以及带有效期缓存、原子写入、校验删除、计数工具。
- Swagger 开关、控制台及按天滚动日志、环境变量配置。
- `GET /health` 进程存活接口。

Steam 内部接口使用服务密钥，密钥不足 32 字节时拒绝访问。项目包含公开的测试密钥，不能用于保护生产服务。账号权限和凭据密文仍由原后端管理；本服务中的 Steam 会话与未确认事件仅保存在内存，原后端停止轮询后约 45 秒关闭会话。服务重启会丢失会话，需要重新登录。库存接口按 appId、contextId 和游标返回单页，调用方负责处理分页和失败。

服务间地址支持 HTTP 和 HTTPS。当前测试部署只运行 `proxy` 容器，通过 `http://美国服务器IP:8092` 访问；`.env` 设置 `PROXY_BIND_ADDRESS=0.0.0.0`，安全组仅允许国内后端 IP 的 `/32` 来源访问 TCP 8092。HTTP 会明文传输服务密钥及 Steam 登录凭据；正式部署应使用 HTTPS 或加密私网。项目中内置公开的测试密钥，与后端默认测试密钥一致；正式使用必须分别设置相同的随机 `STEAM_INTERNAL_KEY` 和 `STEAM_PROXY_INTERNAL_KEY`。两个服务器应同步系统时间。

IPWO 默认配置已写入项目，可用 `STEAM_COMMUNITY_PROXY_HOST`、`STEAM_COMMUNITY_PROXY_PORT`、`STEAM_COMMUNITY_PROXY_USERNAME`、`STEAM_COMMUNITY_PROXY_PASSWORD` 覆盖。Steam 服务发现、CM WebSocket 连接、登录认证、交易链接和库存请求均使用此代理；缺少代理配置时拒绝直连。此仓库公开，IPWO 默认凭据和测试密钥对所有人可见。Steam 账号凭据不要写入仓库或访问日志。

登录成功后，通过现有 CM 连接调用 `Econ.GetTradeOfferAccessToken#1`，明确传入 `generate_new_token=false` 读取现有交易链接，再由后端加密入库。网页令牌独立生成供库存使用；CM 读取失败时使用 Community 网页解析兜底，HTTP 429 最多尝试三次并尊重有限的 Retry-After 等待。读取接口及字段参照 [node-steam-user 的 econ 实现](https://github.com/DoctorMcKay/node-steam-user/blob/master/components/econ.js)。

库存、交易链接及服务间链路上线前需要在目标服务器验证。IPWO 用户名需要带地区和会话参数，默认配置使用全球随机地区、10 分钟粘性会话；可在 IPWO 控制台生成其他配置并通过环境变量覆盖。

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

### 构建工具与镜像

- 项目使用 Maven Wrapper，首次运行自动下载 Maven 3.9.6；构建和运行环境使用 Eclipse Temurin Java 17。`pom.xml` 中的 `java.version=1.8` 是本项目源码的编译目标，不代表可以用 Java 8 运行整个服务及其依赖。
- `Dockerfile` 使用多阶段构建：先在 `eclipse-temurin:17-jdk` 中执行 `./mvnw -B -ntp -DskipTests package` 生成 `target/csgo-tradeup-proxy.jar`，再复制到 `eclipse-temurin:17-jre` 镜像，以非 root 用户运行，容器端口为 8092。
- GitHub Actions 手动构建时，先用 JDK 17 执行 `./mvnw -B -ntp verify`，然后使用 Docker Buildx 构建并推送 `linux/amd64` 镜像，标签格式为 `15565780467w/csgo-tradeup-proxy:run-运行ID-次数`。Dockerfile 中的打包步骤跳过重复测试，实际测试由前面的 Actions 步骤执行。
### 在 GitHub 上构建并推送镜像

1. 先将需要发布的代码提交并推送到 GitHub 分支。Actions 只能构建选中分支已经推送的代码，本机未提交或未推送的修改不会包含在内。
2. 在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 中，配置变量 `DOCKERHUB_USERNAME`（当前部署脚本要求为 `15565780467w`）和 secret `DOCKERHUB_TOKEN`（具有推送权限的 Docker Hub 访问令牌）。
3. 打开 **Actions → 构建并推送 Docker 镜像 → Run workflow**，选择要发布的分支并运行。
4. 等待工作流成功，在该次运行的 **Summary** 复制镜像地址，例如 `15565780467w/csgo-tradeup-proxy:run-41603894140-1`，然后按下面的服务器部署步骤操作。

### 服务器部署步骤

1. 在 GitHub 仓库的 **Actions → 构建并推送 Docker 镜像 → Run workflow** 运行构建，等待成功，在运行结果 Summary 复制完整镜像标签。工作流不会自动部署。
2. 登录海外服务器，进入 `csgo-tradeup-proxy` 目录；确保目录中有与本次部署相匹配版本的 `compose.yaml`、`scripts/deploy.sh`、`.env.example`，并已安装 Docker 和 Docker Compose。部署脚本要求服务器架构为 `x86_64`。若镜像为私有仓库，先执行 `docker login` 登录有拉取权限的 Docker Hub 账号。
3. 首次部署时准备 `.env`：可由部署脚本根据 `.env.example` 自动创建，也可先复制并编辑；已有 `.env` 不会被脚本覆盖。按实际环境配置 `STEAM_INTERNAL_KEY`、IPWO 等参数；开放 8092 时需要设置 `PROXY_BIND_ADDRESS=0.0.0.0` 并限制安全组来源。服务密钥应与国内后端的 `STEAM_PROXY_INTERNAL_KEY` 一致。
4. 用构建成功后得到的完整镜像标签执行部署。下面以一个历史标签演示，实际发布时替换成刚构建的标签：

```bash
cd /root/csgo-tradeup-proxy
./scripts/deploy.sh 15565780467w/csgo-tradeup-proxy:run-41603894140-1
```

脚本会校验配置、拉取指定镜像、重建 proxy 容器并检查本机 `/health`；部署前记录当前镜像标签，失败时可使用旧标签重新执行脚本回退。容器重建会断开内存中的 Steam 会话，账号可能需要重新登录。

部署前可运行以下命令查看当前容器的镜像标签，并保存输出用于回退（首次部署没有容器时可跳过）：

```bash
docker inspect csgo-tradeup-proxy-proxy-1 --format '{{.Config.Image}}'
```

部署后检查容器和日志：

```bash
docker ps --filter name=csgo-tradeup-proxy-proxy
docker logs --tail=100 csgo-tradeup-proxy-proxy-1
curl -fsS http://127.0.0.1:8092/health
```

健康检查只证明进程存活；Steam 登录、IPWO、库存以及国内后端到海外服务的链路仍需分别实测。

### 本地测试

```bash
./mvnw clean verify
java -jar target/csgo-tradeup-proxy.jar
```

Docker 使用 JDK 17 多阶段构建，并以非 root 用户运行。`.dockerignore` 排除了本机 `.env`、日志和构建产物；项目中的公开测试配置仍会打入镜像。正式环境的私有配置应由服务器环境文件覆盖，并限制文件权限。

Docker 容器内的 `127.0.0.1` 指容器自身，不是服务器宿主机；如使用宿主机的数据库或 Redis，必须填写容器可访问的地址。

跨服务器 HTTP 测试部署使用前述 Compose 配置。运行和重启容器会丢失内存中的 Steam 登录会话，需在后台重新登录；持久凭据由原后端保存。

运行 JAR 前同样需导出环境变量，也可通过外部 `config/application.yml` 配置连接信息。测试使用内存 H2 验证数据源，检查统一响应、异常处理、跨域、Swagger 及 Redis 序列化配置，不依赖外部 MySQL/Redis，也不执行真实 Redis 命令。

后续业务 Controller 放在 `com.csgo.tradeup.proxy.controller`，Mapper 放在 `com.csgo.tradeup.proxy.repository.mapper`。空项目尚无 Mapper，启动时出现未找到 Mapper 的提示属预期。
