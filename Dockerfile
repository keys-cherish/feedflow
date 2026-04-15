# ─────────────────────────── Build Stage ───────────────────────────
FROM rust:1.82-slim AS builder
WORKDIR /app
RUN apt-get update && apt-get install -y pkg-config libssl-dev && rm -rf /var/lib/apt/lists/*
COPY Cargo.toml Cargo.lock ./
COPY src/ src/
COPY static/ static/
RUN cargo build --release

# ─────────────────────────── Runtime Stage ───────────────────────────
FROM debian:bookworm-slim

LABEL maintainer="FeedFlow Team"
LABEL org.opencontainers.image.title="FeedFlow"
LABEL org.opencontainers.image.description="Self-hosted RSS reader with Web UI and Android client"
LABEL org.opencontainers.image.version="0.1.1"
LABEL org.opencontainers.image.source="https://github.com/feedflow/feedflow"

RUN apt-get update && apt-get install -y ca-certificates curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /app/target/release/feedflow .
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 3200

# ── Environment defaults ──
ENV FEEDFLOW_HOST=0.0.0.0
ENV FEEDFLOW_PORT=3200
ENV FEEDFLOW_DB_PATH=/data/feedflow.db
ENV FEEDFLOW_LOG_LEVEL=info
# Auto-refresh interval in seconds (0 = disabled, default 900 = 15min)
ENV FEEDFLOW_REFRESH_INTERVAL=900
# Set timezone (e.g. Asia/Shanghai, America/New_York)
ENV TZ=Asia/Shanghai
# Optional: set a bearer token to enable authentication
# ENV FEEDFLOW_AUTH_TOKEN=your-secret-token

VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:3200/health || exit 1

ENTRYPOINT ["/entrypoint.sh"]
