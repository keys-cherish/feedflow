# FeedFlow

Self-hosted RSS reader with a built-in Web UI and a standalone Android client.

- **Web**: Rust (Axum) backend with embedded SPA, SQLite storage, port 3200
- **Android**: Kotlin + Jetpack Compose, fully local-first (no server needed)

## Features

**Core**
- RSS 2.0 / Atom feed parsing with auto-discovery
- Concurrent refresh (8-way backend, 4-way Android)
- Full-text search (supports regex)
- OPML import / export
- Folder organization
- Article starring & read tracking
- Conditional HTTP requests (ETag / Last-Modified)

**Web UI**
- Embedded SPA — no separate frontend build
- Reader view with smart title extraction
- Dark / light theme with smooth transitions
- Bangumi anime cover integration (for mikan RSS)
- AI article summarization (OpenAI / Claude compatible)

**Android**
- Material 3 design with dynamic colors (Android 12+)
- Pull-to-refresh, infinite scroll, swipe-to-delete
- Built-in reader (WebView with themed CSS)
- GZip content compression (5-10x storage savings)
- Auto tag detection (8 categories)
- Tag-based filtering
- Cache management (clear old content, view storage stats)
- Bangumi poster enrichment for anime feeds

**Docker**
- Multi-stage build (< 50MB image)
- Health check endpoint
- Graceful shutdown with auto-restart
- Timezone support
- Memory & log limits
- Webhook notifications on new articles

## Quick Start

### Docker (Recommended)

```bash
# Using docker run
docker run -d \
  --name feedflow \
  -p 3200:3200 \
  -v feedflow-data:/data \
  -e TZ=Asia/Shanghai \
  --restart unless-stopped \
  feedflow

# Using docker-compose
docker compose up -d
```

Open `http://localhost:3200` in your browser.

### Docker Compose

```yaml
services:
  feedflow:
    build: .
    ports:
      - "3200:3200"
    volumes:
      - feedflow-data:/data
    environment:
      - TZ=Asia/Shanghai
      - FEEDFLOW_REFRESH_INTERVAL=900
      # - FEEDFLOW_AUTH_TOKEN=your-secret-token
    restart: unless-stopped

volumes:
  feedflow-data:
```

### Build from Source

**Backend (Rust)**

```bash
# Prerequisites: Rust 1.82+
cargo build --release
./target/release/feedflow
```

**Android**

```bash
cd android
# Prerequisites: JDK 17, Android SDK (compileSdk 35)
./gradlew assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

## Configuration

All configuration is via environment variables:

| Variable | Default | Description |
|---|---|---|
| `FEEDFLOW_HOST` | `0.0.0.0` | Listen address |
| `FEEDFLOW_PORT` | `3200` | HTTP port |
| `FEEDFLOW_DB_PATH` | `feedflow.db` | SQLite database file path |
| `FEEDFLOW_LOG_LEVEL` | `info` | Log level (trace/debug/info/warn/error) |
| `FEEDFLOW_REFRESH_INTERVAL` | `900` | Auto-refresh interval in seconds (0 = disabled) |
| `FEEDFLOW_AUTH_TOKEN` | *(empty)* | Bearer token for API auth (empty = auth disabled) |
| `TZ` | `Asia/Shanghai` | Timezone (Docker only) |

## API Reference

Base URL: `http://localhost:3200`

### Feeds
| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/feeds` | List all feeds |
| `POST` | `/api/feeds` | Subscribe (`{"url": "..."}`) |
| `DELETE` | `/api/feeds/{id}` | Unsubscribe |
| `POST` | `/api/feeds/{id}/refresh` | Refresh single feed |
| `GET` | `/api/feeds/{id}/articles` | Feed articles (`?limit=&offset=`) |
| `POST` | `/api/feeds/{id}/read-all` | Mark all read |

### Articles
| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/articles` | All articles (paginated) |
| `GET` | `/api/articles/unread` | Unread articles |
| `GET` | `/api/articles/starred` | Starred articles |
| `GET` | `/api/articles/search` | Search (`?q=&regex=true`) |
| `PUT` | `/api/articles/{id}/read` | Set read status |
| `PUT` | `/api/articles/{id}/star` | Set starred status |
| `POST` | `/api/articles/{id}/summarize` | AI summarize |

### Folders
| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/folders` | List folders |
| `POST` | `/api/folders` | Create folder |
| `PUT` | `/api/folders/{id}` | Update folder |
| `DELETE` | `/api/folders/{id}` | Delete folder |
| `POST` | `/api/folders/{fid}/feeds/{id}` | Add feed to folder |
| `DELETE` | `/api/folders/{fid}/feeds/{id}` | Remove from folder |

### OPML
| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/opml/import` | Import OPML (`{"xml": "..."}`) |
| `GET` | `/api/opml/export` | Export OPML 2.0 XML |

### System
| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/health` | Health check |
| `GET` | `/api/stats` | Feed/article/unread counts |
| `POST` | `/api/refresh` | Refresh all feeds |
| `GET` | `/api/settings` | Get settings (keys masked) |
| `PUT` | `/api/settings` | Update settings |
| `GET` | `/api/cache/stats` | Cache/storage statistics |
| `POST` | `/api/cache/clear` | Clear old article content |
| `POST` | `/api/notifications/test` | Test webhook |

## Android Client

The Android app is a standalone local-first RSS reader — it does **not** require the backend server.

**Key features:**
- Add RSS/Atom feeds directly
- Automatic tag detection from feed URL/title
- Tag filtering on home screen
- Long-press feed card to edit tags
- Built-in reader with themed WebView
- Content compressed with GZip (transparent)
- Storage management in Settings

**Minimum requirements:** Android 8.0 (API 26)

## Development

```
feedflow/
├── src/                    # Rust backend
│   ├── main.rs             # Server entry, auto-refresh task
│   ├── api/mod.rs          # HTTP routes & handlers
│   ├── db/mod.rs           # SQLite CRUD
│   ├── feed/mod.rs         # RSS fetch engine
│   ├── models/mod.rs       # Data models
│   ├── services/mod.rs     # Business logic
│   ├── auth.rs             # Bearer token middleware
│   ├── config/mod.rs       # Environment config
│   └── opml.rs             # OPML import/export
├── static/index.html       # Embedded Web SPA
├── docker/entrypoint.sh    # Docker entrypoint
├── Dockerfile              # Multi-stage build
├── docker-compose.yml      # Compose config
└── android/                # Android client (independent)
    └── app/src/main/java/com/feedflow/app/
        ├── MainActivity.kt
        ├── models.kt       # UI models
        ├── db.kt           # Room database
        ├── repository.kt   # Data repository
        ├── RssParser.kt    # Feed parser
        ├── AppLogger.kt    # File logger
        └── ui/             # Compose screens
```

## License

MIT
