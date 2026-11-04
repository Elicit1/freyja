# 登录与接口鉴权

系统使用单管理员账户。所有后端业务接口（包括 `/health`、`/system/mcp/**`、上传下载及后续新增接口）均需登录 Token；只有 `POST /auth/login` 允许匿名访问。MCP 协议端点 `/mcp`、`/mcp/**` 使用独立 MCP 令牌及原有项目、工具权限。两种令牌不能互换。

通过前端代理访问时，以下 REST 路径增加 `/api` 前缀；直连后端 `8080` 端口时使用原路径。

## 配置与首次登录

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `FREYJA_AUTH_USERNAME` | `admin` | 管理员用户名，最多 64 字符 |
| `FREYJA_AUTH_PASSWORD` | 空 | 12～256 字符；留空时每次启动生成随机临时密码，在后端启动日志显示 |
| `FREYJA_AUTH_TOKEN_TTL_SECONDS` | `43200` | Token 有效期，默认 12 小时，允许 60～604800 秒 |

Docker 部署在 `.env` 中设置这些变量；IDE 启动可以使用环境变量，或参考 `application-local.yaml.example`。未设置密码时，查找后端日志中的“临时管理员密码”；Docker 可执行 `docker compose logs backend`。重启会重新生成临时密码。设置固定密码后，同一账户配置下的已有会话可在后端重启后继续使用，直到 Redis 会话过期。变更用户名或密码会使旧会话验证失败。多实例部署应配置相同的管理员账户并共享 Redis。

前端 `/login` 登录成功后返回原页面，右上角提供退出入口。登录信息保存在当前标签页的 `sessionStorage`；请求统一携带 Bearer Token，过期或收到 401 时清理会话并返回登录页。

## 接口

### `POST /auth/login`

```json
{"username":"admin","password":"your-configured-password"}
```

成功响应：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "token": "<random-opaque-token>",
    "tokenType": "Bearer",
    "username": "admin",
    "expiresAt": "2026-11-04T12:00:00Z"
  }
}
```

之后调用普通业务接口时携带：

```http
Authorization: Bearer <login-token>
```

用户名或密码错误返回 HTTP 401。同一连接来源 IP 每分钟最多尝试登录 10 次，超出返回 HTTP 429；不会读取客户端传入的转发 IP 头。经同一个反向代理进入的请求可能共享该限额。

### `GET /auth/me`

需要登录 Token；返回 `data.username`。

### `POST /auth/logout`

需要登录 Token；删除当前 Redis 会话，返回统一成功响应。该 Token 随即失效，不影响其他登录会话。已经建立的 WebSocket 在下一次收发（含心跳）时拒绝继续传输并关闭。

### `POST /auth/ws-ticket`

需要登录 Token；返回 `data.ticket`。凭证有效期为 30 秒且只能使用一次，用于浏览器连接 `/ws/task-center?ticket=<ticket>`（后端兼容 `/api/ws/task-center`）。握手需要合法的一次性凭证及同源 Origin；不接受 URL 中的登录 Token。前端开发代理和 Nginx 均保留 WebSocket 请求 Host。

一次性凭证绑定登录会话；凭证过期、重复使用或对应登录已注销均无法握手。服务器对已建立连接的每次入站消息和出站推送再次检查 Redis 会话，登录失效以关闭码 `4401` 关闭连接。

## 存储和错误语义

- 密码在服务内通过 PBKDF2-HMAC-SHA256 验证；登录 Token 使用 32 字节安全随机数生成。Redis 仅保存登录 Token 的 SHA-256 索引、账户凭据派生版本和带 TTL 的会话，不保存明文密码或登录 Token。
- Token 不自动续期；Redis TTL 到期后需重新登录。Redis 无法访问时普通接口拒绝请求并返回 HTTP 503，不降级放行。
- 普通接口缺失、错误或过期 Token 返回 HTTP 401 和 `{"code":401,"msg":"请先登录或重新登录","data":null}`。登录及鉴权响应禁用缓存。
- MCP 端点继续使用 `Authorization: Bearer <mcp-token>`，保留 MCP 的独立关闭开关、Origin 白名单及项目/工具权限。先登录工作台，再进入“系统管理 → MCP 服务”维护 MCP 令牌。
- MinIO 预签名直传、媒体预览和 FastAPI 网关属于各自服务的访问边界，不使用 Java 工作台登录 Token；Java 后端的上传、预签名申请及归档接口均需对应业务登录或 MCP 权限。
