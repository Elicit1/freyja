# ==============================================================================
# Freyja Frontend Dockerfile (Vite + Vue 3 + Nginx)
# ==============================================================================

# 阶段一：构建前端 SPA 静态产物
FROM node:22-alpine AS builder
ARG MAINLAND=false
WORKDIR /app

# 优先安装依赖包以复用缓存
COPY package*.json ./
RUN if [ "$MAINLAND" = "true" ]; then \
        npm config set registry https://registry.npmmirror.com; \
    fi && \
    npm install

# 复制源码并执行打包构建
COPY . .
RUN npm run build

# 阶段二：使用轻量级 Nginx Alpine 镜像托管静态资源并反向代理
FROM nginx:alpine
WORKDIR /usr/share/nginx/html
LABEL org.opencontainers.image.title="Freyja Frontend" \
      org.opencontainers.image.version="1.0.0"

# 清理默认静态网页
RUN rm -rf ./*

# 复制打包产物与 Nginx 虚拟主机配置
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
