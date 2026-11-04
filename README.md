# csgo-tradeup-proxy

部署在海外节点的 Xunics API 代理。调用方将 `https://xunics.com` 换成代理域名，保留 `/api/public/v1/...` 路径和业务入参字段，响应统一为 back 的 `ResultInfo<T>` 格式。代理从配置读取 Xunics API Key，向上游注入 `Authorization: Bearer <api-key>`。

继承 `csgo-tradeup-back` 的 Spring Boot 2.2.2、MyBatis-Plus 3.3.1、MySQL、Redis、日志和统一异常基础设施，使用 JDK 17 构建及运行。Xunics 转发本身不读写数据库或 Redis。

## 接口映射

代理地址前缀：`https://你的代理域名/api/public`。
独立 Steam 代理服务，包名为 `com.csgo.tradeup.proxy`。负责 Steam 连接、账号密码登录、Steam Guard 等待、交易链接和库存请求；账号权限及加密凭据由 `csgo-tradeup-back` 管理。使用 JDK 17 构建及运行，保持原项目 Java 8 字节码级别。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/v1/items` | 按名称、磨损区间、价格等查询在售商品 |
| GET | `/v1/quotes/{quote_id}` | 查询有效报价 |
| POST | `/v1/orders` | 按报价 ID 购买指定商品 |
| POST | `/v1/orders/quick` | 按名称和价格上限快速采购 |
| POST | `/v1/orders/quick/batch` | 批量快速采购，最多 20 条 |
| GET | `/v1/orders` | 订单列表，可按自己的业务订单号查询 |
| GET | `/v1/orders/{order_no}` | 订单详情 |
| GET | `/v1/wallets/me` | 钱包余额 |
| GET | `/v1/wallets/me/ledger` | 资金流水 |
| GET | `/v1/wallets/me/holds` | 订单冻结资金 |

原业务入参、data 字段和约束以 [Xunics 官方文档](https://xunics.com/api/public/docs) 为准，项目保存了 [2026-11-04 OpenAPI 快照](docs/xunics-openapi.json)。快照描述的是上游协议，其中的 API Key 鉴权由本代理负责。

## Controller / Service / DTO 结构

- `XunicsItemController`：商品列表；`XunicsQuoteController`：报价详情。
- `XunicsOrderController`：报价下单、快速下单、批量下单、订单列表和详情。
- `XunicsWalletController`：余额、流水和冻结资金。
- `controller/xunics/param`：独立查询与下单参数类，带 Bean Validation 和 Swagger 注解。
- `controller/xunics/resp`：商品、贴纸、挂件、订单、批量结果、钱包、分页和错误明细对象。
- `service` / `service/impl`：每项业务的接口和实现；`integration/xunics/XunicsClient` 只处理 HTTP、鉴权及上游协议。

Controller 参照 back 的写法继承 `ResultHelper`，通过 `successResultInfo(service.xxx(param))` 返回 `ResultInfo<具体响应类型>`，不直接接收 ServletRequest 或返回 byte[]。Java 对象使用驼峰属性，JSON/查询字段保留 `market_hash_name`、`external_order_no`、`max_price` 等原协议字段。

## 返回格式与调用约定

成功返回 back 的统一格式，`data` 为原上游 data 对应的类型化对象：

```json
{
  "success": true,
  "code": 200,
  "message": "成功",
  "data": {"currency": "USD", "balance": "100.00000000", "locked_balance": "0.00000000", "available_balance": "100.00000000", "enabled": true}
}
```

失败也返回统一格式，例如上游限流：

```json
{
  "success": false,
  "code": 429,
  "message": "请求过于频繁",
  "data": {"traceId": "上游追踪ID", "details": null},
  "errorCode": "RATE_LIMITED",
  "retryAfter": 17
}
```

- 上游 `msg` 转为失败响应的 `message`，`error_code` 转为 `errorCode`；`trace_id` 位于 `data.traceId` 和 `X-Trace-Id` 响应头，校验明细在 `data.details`。有明细时 `field` 标出首个错误字段。`Retry-After` 响应头保留。
- 查询和 JSON 请求先绑定参数对象并校验，再调用 Service；本地格式/校验错误返回 HTTP 400。上游 HTTP 错误保留错误状态，响应内容转换为上述格式。
- 金额和饰品磨损保持字符串表示，不经过浮点运算；分页数据仍为 `items/total/page/size/total_pages`。JSON 空白和字段顺序不再按原字节透传。
- 批量部分失败属于正常业务结果：外层 `success=true`，逐条查看 `data.items[].accepted/error_code` 和 `data.accepted/rejected`。
- API Key 仅由服务端配置注入，不接收调用方的上游认证或 Cookie。不自动重试、不跟随重定向、不缓存或轮询订单。未知或非 JSON 的上游响应转换为统一错误。
- `trade_url` 指定收货 Steam 账号，扣款使用配置 API Key 所属钱包。Xunics 入口尚未增加调用方鉴权，部署时仅允许自己的后端通过安全组、网关或内网访问。

代理错误包括 `PROXY_NOT_CONFIGURED`（503）、`PROXY_UPSTREAM_TIMEOUT`（504）、`PROXY_UPSTREAM_UNAVAILABLE`（502）、`PROXY_INVALID_RESPONSE`（通常 502，非 JSON 上游 HTTP 错误保留其状态码）和 `PROXY_INTERNAL_ERROR`（500）。

下单超时或断连不代表采购失败，重试必须保持 `external_order_no` 和所有参数一致；不得生成新单号重新购买。`IDEMPOTENCY_CONFLICT` 按既有订单查询处理。

Steam 内部接口使用服务密钥，密钥不足 32 字节时拒绝访问。项目包含公开的测试密钥，不能用于保护生产服务。账号权限和凭据密文仍由原后端管理；本服务中的 Steam 会话与未确认事件仅保存在内存，原后端停止轮询后约 45 秒关闭会话。服务重启会丢失会话，需要重新登录。库存接口按 appId、contextId 和游标返回单页，调用方负责处理分页和失败。

服务间地址支持 HTTP 和 HTTPS。当前测试部署只运行 `proxy` 容器，通过 `http://美国服务器IP:8092` 访问；`.env` 设置 `PROXY_BIND_ADDRESS=0.0.0.0`，安全组仅允许国内后端 IP 的 `/32` 来源访问 TCP 8092。HTTP 会明文传输服务密钥及 Steam 登录凭据；正式部署应使用 HTTPS 或加密私网。项目中内置公开的测试密钥，与后端默认测试密钥一致；正式使用必须分别设置相同的随机 `STEAM_INTERNAL_KEY` 和 `STEAM_PROXY_INTERNAL_KEY`。两个服务器应同步系统时间。

IPWO 默认配置已写入项目，可用 `STEAM_COMMUNITY_PROXY_HOST`、`STEAM_COMMUNITY_PROXY_PORT`、`STEAM_COMMUNITY_PROXY_USERNAME`、`STEAM_COMMUNITY_PROXY_PASSWORD` 覆盖。Steam 服务发现、CM WebSocket 连接、登录认证、交易链接和库存请求均使用此代理；缺少代理配置时拒绝直连。此仓库公开，IPWO 默认凭据和测试密钥对所有人可见。Steam 账号凭据不要写入仓库或访问日志。

登录成功后，通过现有 CM 连接调用 `Econ.GetTradeOfferAccessToken#1`，明确传入 `generate_new_token=false` 读取现有交易链接，再由后端加密入库。网页令牌独立生成供库存使用；CM 读取失败时使用 Community 网页解析兜底，HTTP 429 最多尝试三次并尊重有限的 Retry-After 等待。读取接口及字段参照 [node-steam-user 的 econ 实现](https://github.com/DoctorMcKay/node-steam-user/blob/master/components/econ.js)。

库存、交易链接及服务间链路上线前需要在目标服务器验证。IPWO 用户名需要带地区和会话参数，默认配置使用全球随机地区、10 分钟粘性会话；可在 IPWO 控制台生成其他配置并通过环境变量覆盖。

## 配置与启动

API Key 可直接填入 `src/main/resources/application.yml` 的 `xunics.api-key`，然后重新构建。部署时推荐使用外部配置，修改后重启即可：

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
mkdir -p config
cp deploy/application.yml.example config/application.yml
# 编辑 config/application.yml 中的 xunics.api-key。
./mvnw clean verify
java -jar target/csgo-tradeup-proxy.jar
```

外部配置示例：

```yaml
server:
  port: 8092
xunics:
  base-url: https://xunics.com/api/public
  api-key: "填写你的 Xunics API Key"
  connect-timeout: 5s
  read-timeout: 120s
  connection-request-timeout: 5s
```

在海外节点从包含 `config/` 的部署目录执行 `java -jar csgo-tradeup-proxy.jar`。没有提供真实 API Key 时，服务可以启动，代理接口返回 503，不向上游发送请求。也可用 `XUNICS_API_KEY` 环境变量；如果外部配置显式填写了 `api-key`，使用该配置值。

MySQL/Redis 的配置示例见 `.env.example`。若使用 `.env`，需要先 `set -a; source .env; set +a`，Spring Boot 不会自动加载它。默认端口 8092，MySQL 数据库名 `csgo_tradeup_proxy`，Redis 库编号 2；没有业务表或自动建表操作。
Docker 使用 JDK 17 多阶段构建，并以非 root 用户运行。`.dockerignore` 排除了本机 `.env`、日志和构建产物；项目中的公开测试配置仍会打入镜像。正式环境的私有配置应由服务器环境文件覆盖，并限制文件权限。

Docker 容器内的 `127.0.0.1` 指容器自身，不是服务器宿主机；如使用宿主机的数据库或 Redis，必须填写容器可访问的地址。

跨服务器 HTTP 测试部署使用前述 Compose 配置。运行和重启容器会丢失内存中的 Steam 登录会话，需在后台重新登录；持久凭据由原后端保存。

运行 JAR 前同样需导出环境变量，也可通过外部 `config/application.yml` 配置连接信息。测试使用内存 H2 验证数据源，检查统一响应、异常处理、跨域、Swagger 及 Redis 序列化配置，不依赖外部 MySQL/Redis，也不执行真实 Redis 命令。

存活检查：`GET /health`，只检查进程，不检测上游、MySQL 或 Redis。设置 `SWAGGER_ENABLED=true` 后可访问 `/swagger-ui.html` 查看本服务路由；原协议的完整参数定义见 OpenAPI 快照。

## 调用示例

调用方不用传 Xunics API Key。下面查询接口不会产生采购：

```bash
curl --get 'http://127.0.0.1:8092/api/public/v1/items' --data-urlencode 'market_hash_name=AK-47 | Redline (Field-Tested)' --data-urlencode 'float_min=0.15' --data-urlencode 'float_max=0.20' --data-urlencode 'max_price=12.00'
curl 'http://127.0.0.1:8092/api/public/v1/wallets/me'
curl --get 'http://127.0.0.1:8092/api/public/v1/orders' --data-urlencode 'external_order_no=your-order-0001'
```

快速下单请求体示例（POST `/api/public/v1/orders/quick`，Content-Type 为 application/json；发送后会实际采购）：

```json
{
  "external_order_no": "your-order-0001",
  "market_hash_name": "AK-47 | Redline (Field-Tested)",
  "max_price": "12.00",
  "trade_url": "https://steamcommunity.com/tradeoffer/new/?partner=123456789&token=AbCdEfGh"
}
```

## 网关与验证

使用 Nginx 时保留路径前缀，超时应大于本服务 read-timeout，并关闭网关自动重试：

```nginx
location /api/public/ {
    proxy_pass http://127.0.0.1:8092;
    proxy_connect_timeout 5s;
    proxy_read_timeout 130s;
    proxy_next_upstream off;
}
```

`./mvnw clean verify` 使用本地 HTTP 模拟上游，覆盖全部 10 个接口的独立路由、DTO 字段完整性、参数绑定与校验、金额精度、统一成功/错误格式、服务端鉴权注入、批量结果和超时/断连不重试。基础测试使用内存 H2，不需要真实 MySQL/Redis，也不连接真实采购 API。

产物为 `target/csgo-tradeup-proxy.jar`。日志不会记录 HTTP wire/header 数据。项目没有业务 Mapper，启动时未找到 Mapper 的提示属预期。
