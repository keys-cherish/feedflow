FROM rust:1.82-slim AS builder
WORKDIR /app
RUN apt-get update && apt-get install -y pkg-config libssl-dev && rm -rf /var/lib/apt/lists/*
COPY Cargo.toml Cargo.lock ./
COPY src/ src/
COPY static/ static/
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /app/target/release/feedflow .
EXPOSE 3200
ENV FEEDFLOW_HOST=0.0.0.0
ENV FEEDFLOW_PORT=3200
ENV FEEDFLOW_DB_PATH=/data/feedflow.db
VOLUME ["/data"]
CMD ["./feedflow"]
