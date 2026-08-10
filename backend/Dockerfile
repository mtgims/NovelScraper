# NovelScraper backend — FastAPI + SQLite. No GPU and no server-side TTS (clients
# synthesize), so the image ships without kokoro-onnx/onnxruntime. Data (SQLite +
# covers/images/caches) lives on the /data volume.
FROM python:3.14-slim AS build
ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
# build-essential is a safety net for any dep without a prebuilt aarch64 wheel.
RUN apt-get update && apt-get install -y --no-install-recommends build-essential \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements-core.txt .
RUN pip install --prefix=/install -r requirements-core.txt

FROM python:3.14-slim
ENV PYTHONUNBUFFERED=1 NOVELSCRAPER_DATA_DIR=/data
WORKDIR /app
COPY --from=build /install /usr/local
COPY app ./app
COPY site_profiles ./site_profiles
RUN useradd --create-home --uid 10001 appuser \
    && mkdir -p /data && chown -R appuser:appuser /data /app
USER appuser
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
