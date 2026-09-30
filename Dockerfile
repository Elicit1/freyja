# ==============================================================================
# Freyja Backend Dockerfile (Spring Boot 4 + JDK 26 + FFmpeg)
# ==============================================================================

# 阶段一：基于 Temurin JDK 26 与 Maven 构建应用可执行 JAR
FROM eclipse-temurin:26-jdk AS builder
ARG MAINLAND=false
WORKDIR /build

# 优先复制 Maven 包装器与 POM 依赖描述，利用 Docker 层缓存
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && \
    if [ "$MAINLAND" = "true" ]; then \
        ./mvnw dependency:go-offline -B -s .mvn/settings-docker.xml || true; \
    else \
        ./mvnw dependency:go-offline -B || true; \
    fi

# 复制业务源码并打包（跳过单元测试）
COPY src/ src/
RUN if [ "$MAINLAND" = "true" ]; then \
        ./mvnw clean package -DskipTests -B -s .mvn/settings-docker.xml; \
    else \
        ./mvnw clean package -DskipTests -B; \
    fi

# 阶段二：生产运行时镜像 (Temurin JRE 26 + FFmpeg 系统多媒体工具)
FROM eclipse-temurin:26-jre
ARG MAINLAND=false
WORKDIR /app

# 安装 ffmpeg、ffprobe 与健康检查 curl 工具 (若处于大陆则静默使用阿里源加速)
RUN if [ "$MAINLAND" = "true" ]; then \
        sed -i 's/deb.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list.d/debian.sources 2>/dev/null || \
        sed -i 's/deb.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list 2>/dev/null || \
        sed -i 's/archive.ubuntu.com/mirrors.aliyun.com/g' /etc/apt/sources.list 2>/dev/null || true; \
    fi && \
    apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg curl && \
    rm -rf /var/lib/apt/lists/*

# 从构建阶段复制 jar 产物
COPY --from=builder /build/target/freyja-server.jar app.jar

# 复制预置 skills 资源包以支持无依赖开箱即用自动导入
COPY skills/ /app/skills/

# 创建日志目录
RUN mkdir -p /app/logs
VOLUME /app/logs

EXPOSE 8080

# 默认 JVM 参数与环境参数
ENV JAVA_OPTS="-Xms512m -Xmx2048m -XX:+UseG1GC"
ENV SERVER_PORT=8080
ENV FREYJA_MEDIA_TOOLS_FFMPEG_PATH="ffmpeg"
ENV FREYJA_MEDIA_TOOLS_FFPROBE_PATH="ffprobe"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
