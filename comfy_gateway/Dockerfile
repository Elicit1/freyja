# ==============================================================================
# ComfyUI Universal Multi-Workflow Gateway Dockerfile (FastAPI + Uvicorn)
# ==============================================================================

FROM python:3.11-slim

WORKDIR /app

# 安装必要的系统网络工具
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# 优先安装 Python 依赖
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 复制代码与工作流配置
COPY . .

# 确保媒体产物目录存在
RUN mkdir -p static/outputs

EXPOSE 8000

ENV PYTHONUNBUFFERED=1
ENV GATEWAY_HOST=0.0.0.0
ENV GATEWAY_PORT=8000

CMD ["python", "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
