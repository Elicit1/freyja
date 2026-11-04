# 登录与接口鉴权

系统使用单管理员账户。所有后端业务接口（包括 `/health`、`/system/mcp/**`、上传下载及后续新增接口）均需登录 Token；只有 `POST /auth/login` 允许匿名访问。MCP 协议端点 `/mcp`、`/mcp/**` 使用独立 MCP 令牌及原有项目、工具权限。两种令牌不能互换。

通过前端代理访问时，以下 REST 路径增加 `/api` 前缀；直连后端 `8080` 端口时使用原路径。

## 配置与首次登录

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `FREYJA_AUTH_USERNAME` | `admin` | 管理员用户名，最多 64 字符 |
| `FREYJA_AUTH_PASSWORD` | 空 | 引导密码，12～256 字符；尚未在页面修改密码且此项为空时，启动生成临时密码并显示在日志 |
| `FREYJA_AUTH_TOKEN_TTL_SECONDS` | `43200` | Token 有效期，默认 12 小时，允许 60～604800 秒 |

Docker 部署在 `.env` 中设置这些变量；IDE 启动可以使用环境变量，或参考 `application-local.yaml.example`。尚未保存登录密码且未设置引导密码时，查找后端日志中的“临时管理员密码”；Docker 可执行 `docker compose logs backend`。

登录后点击右上角账户菜单 → **修改密码**，验证当前密码并保存新密码。新密码以独立随机盐的 PBKDF2 哈希存入私有表 `sys_admin_credential`，重启服务或清空 Redis 后仍然有效。数据库中已保存的密码优先于 `FREYJA_AUTH_PASSWORD`，也不再生成临时密码；环境变量不会覆盖页面设置的新密码。系统参数管理接口不暴露此表。

修改密码后，全部旧登录 Token 和待用 WebSocket 凭证失效，当前页面回到登录页，MCP 令牌不变。多实例部署应配置相同的管理员用户名并共享数据库和 Redis；会话验证读取当前数据库凭据版本，其他实例的旧会话同样失效。未修改密码时，保留原有引导配置行为。

前端 `/login` 登录成功后返回原页面，右上角账户菜单提供修改密码、退出登录入口。登录信息保存在当前标签页的 `sessionStorage`；请求统一携带 Bearer Token，过期或收到 401 时清理会话并返回登录页。

登录页共用工作台的 Logo、四套主题和明暗模式，自动沿用当前外观设置；右上角“片场外观”复用系统主题面板，切换结果在登录前后保持一致。Freyja 主题使用工作台已有插画，其他主题使用对应的品牌色和背景。

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

### `POST /auth/password`

需要登录 Token：

```json
{"oldPassword":"current-password","newPassword":"your-new-permanent-password"}
```

新密码须为 12～256 字符、不能全为空白且不能与旧密码相同。原密码错误或新密码不符合要求返回 HTTP 400，不清除仍然有效的登录会话；同一账户每分钟最多尝试 10 次，超出返回 HTTP 429。密码并发修改发生版本冲突时返回 HTTP 409；数据库保存失败返回 HTTP 503，不返回成功结果。成功返回统一成功响应，所有旧 Token 即刻验证失败。

忘记页面设置的密码时，管理员可停止后端，在数据库删除该用户名对应的 `sys_admin_credential` 记录，然后配置 `FREYJA_AUTH_PASSWORD` 并重新启动，用引导密码登录后重新设置。

### `POST /auth/ws-ticket`

需要登录 Token；返回 `data.ticket`。凭证有效期为 30 秒且只能使用一次，用于浏览器连接 `/ws/task-center?ticket=<ticket>`（后端兼容 `/api/ws/task-center`）。握手需要合法的一次性凭证及同源 Origin；不接受 URL 中的登录 Token。前端开发代理和 Nginx 均保留 WebSocket 请求 Host。

一次性凭证绑定登录会话；凭证过期、重复使用或对应登录已注销均无法握手。服务器对已建立连接的每次入站消息和出站推送再次检查 Redis 会话，登录失效以关闭码 `4401` 关闭连接。

## 存储和错误语义

- 密码在服务内通过 PBKDF2-HMAC-SHA256 验证；页面保存的密码使用独立随机盐，数据库只保存编码后的哈希及随机会话版本。登录 Token 使用 32 字节安全随机数生成。Redis 仅保存登录 Token 的 SHA-256 索引、账户会话版本和带 TTL 的会话，不保存明文密码或登录 Token。
- Token 不自动续期；Redis TTL 到期后需重新登录。Redis 无法访问时普通接口拒绝请求并返回 HTTP 503，不降级放行。
- 普通接口缺失、错误或过期 Token 返回 HTTP 401 和 `{"code":401,"msg":"请先登录或重新登录","data":null}`。登录及鉴权响应禁用缓存。
- MCP 端点继续使用 `Authorization: Bearer <mcp-token>`，保留 MCP 的独立关闭开关、Origin 白名单及项目/工具权限。先登录工作台，再进入“系统管理 → MCP 服务”维护 MCP 令牌。
- MinIO 预签名直传、媒体预览和 FastAPI 网关属于各自服务的访问边界，不使用 Java 工作台登录 Token；Java 后端的上传、预签名申请及归档接口均需对应业务登录或 MCP 权限。
