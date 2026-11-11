FROM python:3.11-slim

ARG PIP_INDEX_URL=https://pypi.org/simple
WORKDIR /app
COPY requirements.txt /tmp/image-requirements.txt
RUN pip install --no-cache-dir --index-url "$PIP_INDEX_URL" -r /tmp/image-requirements.txt \
    && useradd --uid 10001 --create-home imageapi
COPY --chown=10001:10001 . /app/comfy_image_service
RUN mkdir -p /app/comfy_image_service/data && chown 10001:10001 /app/comfy_image_service/data
USER 10001:10001
ENV PYTHONUNBUFFERED=1 IMAGE_HOST=0.0.0.0 IMAGE_PORT=8011
EXPOSE 8011
CMD ["python", "-m", "comfy_image_service"]
