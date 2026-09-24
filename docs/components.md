# 组件服务清单

> 本项目依赖的外部服务连接信息与运维备忘。涉及账号密码等敏感信息，请勿提交到公网仓库。

## 服务连接信息

| 服务 | 主机 / IP | 端口 | 账号 / 用户名 | 密码 / 密钥 | 备注 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| MySQL 8.0 | 127.0.0.1 | 3306 | root | root | Workbench 直接连接 |
| Redis | 127.0.0.1 | 6379 | (无) | 123456 | 开启 AOF 持久化 |
| MinIO Web 页面 | http://127.0.0.1 | 9001 | minioadmin | minioadmin123 | 浏览器可视化后台 |
| MinIO S3 API | http://127.0.0.1 | 9000 | minioadmin | minioadmin123 | Java SDK / 后端对接端点 |

## 使用说明

- MySQL：后端 JDBC 数据源，`jdbc:mysql://127.0.0.1:3306/freyja`（数据库 `freyja`）。
- Redis：字典缓存 + ComfyUI 生成任务 Stream 队列。
- MinIO：Web 在线预览与资产备份；Web 控制台 9001，S3 API 9000。