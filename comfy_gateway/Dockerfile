# ==============================================================================
# ComfyUI Universal Multi-Workflow Gateway Dockerfile (FastAPI + Uvicorn)
# ==============================================================================

FROM python:3.11-slim
ARG MAINLAND=false

WORKDIR /app

# 安装必要的系统网络工具 (若处于大陆则静默使用阿里源加速)
RUN if [ "$MAINLAND" = "true" ]; then \
        sed -i 's/deb.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list.d/debian.sources 2>/dev/null || \
        sed -i 's/deb.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list 2>/dev/null || true; \
    fi && \
    apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# 优先安装 Python 依赖 (若处于大陆则静默使用清华镜像源加速)
COPY requirements.txt .
RUN if [ "$MAINLAND" = "true" ]; then \
        pip config set global.index-url https://pypi.tuna.tsinghua.edu.cn/simple; \
    fi && \
    pip install --no-cache-dir -r requirements.txt

# 复制代码与工作流配置
COPY . .

# 确保媒体产物目录存在
RUN mkdir -p static/outputs

EXPOSE 8000

ENV PYTHONUNBUFFERED=1
ENV GATEWAY_HOST=0.0.0.0
ENV GATEWAY_PORT=8000

CMD ["python", "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
