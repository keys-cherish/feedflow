# FeedFlow 技术文档

## 项目概述

FeedFlow 是一个高性能的 RSS 阅读器后端，使用 Rust 构建。集成了 Web 前端，支持 OPML 导入导出和文件夹管理。

**技术栈**: Rust + Axum + SQLite + 原生 HTML/CSS/JS

## 架构设计

### 目录结构

```
feedflow/
├── src/
│   ├── main.rs          # 入口：配置加载、数据库初始化、路由挂载、HTTP 服务启动
│   ├── config/mod.rs    # 应用配置（环境变量 → AppConfig 结构体）
│   ├── db/mod.rs        # 数据库层：SQLite 连接管理 + 所有 CRUD 操作
│   ├── models/mod.rs    # 数据模型：Feed、Article、Folder、FolderWithCount 等
│   ├── feed/mod.rs      # 抓取引擎：HTTP 请求、Feed 解析、HTML 清洗
│   ├── services/mod.rs  # 业务逻辑层：订阅/刷新/OPML 导入
│   ├── api/mod.rs       # HTTP API：路由定义 + 所有 handler 函数
│   └── opml.rs          # OPML 模块：解析和生成 OPML 2.0 XML
├── static/
│   └── index.html       # Web 前端 SPA（内嵌到二进制文件）
├── Cargo.toml           # Rust 依赖配置
├── issues.md            # 已知问题
├── updates.md           # 更新日志
└── docs/
    └── technical.md     # 本文档
```

### 分层架构

```
┌─────────────────────────────────────────┐
│            Web 前端 (index.html)         │  ← 内嵌 SPA，通过 Fetch API 调用后端
├─────────────────────────────────────────┤
│            API 层 (api/mod.rs)           │  ← Axum 路由 + Handler，请求验证
├─────────────────────────────────────────┤
│         服务层 (services/mod.rs)         │  ← 业务逻辑编排，协调抓取引擎与数据库
├──────────────────┬──────────────────────┤
│ 抓取引擎         │ OPML 模块             │
│ (feed/mod.rs)    │ (opml.rs)            │  ← 外部数据获取/解析
├──────────────────┴──────────────────────┤
│          数据库层 (db/mod.rs)            │  ← SQLite CRUD，Mutex 单连接
├─────────────────────────────────────────┤
│          数据模型 (models/mod.rs)        │  ← 共享类型定义
└─────────────────────────────────────────┘
```

## 核心模块详解

### 1. 数据库层 (`src/db/mod.rs`)

**连接管理**: 使用 `Mutex<Connection>` 包装 SQLite 连接，保证线程安全。开启 WAL 模式提升并发读性能。

```rust
pub struct Database {
    conn: Mutex<Connection>,
}
```

**表结构**:
- `feeds`: 订阅源信息（URL、标题、ETag、上次抓取时间等）
- `articles`: 文章（标题、内容、已读/收藏状态等）
- `folders`: 文件夹（名称、排序、父文件夹）
- `feed_folders`: 订阅源-文件夹多对多关联表

**关键查询**:
- `get_all_folders()`: 使用 `LEFT JOIN feed_folders` + `GROUP BY` 获取文件夹列表及其订阅源数量
- `insert_article_if_new()`: 通过 `feed_id + guid` 去重，避免重复插入
- `get_feed_folder_map()`: 返回所有关联关系，用于 OPML 导出

### 2. 抓取引擎 (`src/feed/mod.rs`)

**Feed 发现**: `discover_feed()` 方法支持：
1. 直接检测 Content-Type（XML/RSS/Atom → 直接返回）
2. 解析 HTML 中的 `<link rel="alternate">` 标签
3. 尝试常见路径（`/feed`, `/rss`, `/atom.xml` 等）

**条件请求**: 使用 `If-None-Match`（ETag）和 `If-Modified-Since` 头避免重复下载未变化的 Feed。304 响应不消耗带宽。

**内容清洗**:
- 服务端使用 `ammonia` 库做 HTML 白名单过滤（防 XSS）
- 前端二次清洗：移除 `<script>`、`<iframe>`、事件处理属性、`javascript:` URL

**Feed 解析**: 使用 `feed-rs` 库统一解析 RSS 2.0、Atom、JSON Feed 格式。

### 3. OPML 模块 (`src/opml.rs`)

使用 `quick-xml` 库的事件驱动 API 处理 OPML 2.0 XML。

**导出流程**:
1. 查询所有 feeds、folders、feed-folder 关联
2. 构建 `folder_id → Vec<Feed>` 映射
3. 先输出各文件夹及其包含的 feed outline
4. 再输出不属于任何文件夹的 feed

**导入解析**: 使用栈结构处理嵌套的 `<outline>` 元素：
```rust
// Event::Start(<outline>) → 推入栈
// Event::Empty(<outline>) → 直接添加到当前父级
// Event::End(</outline>) → 弹出栈，添加到父级
```

**数据结构**:
```rust
pub struct OpmlOutline {
    pub text: String,           // 显示名称
    pub xml_url: Option<String>, // Feed URL（有则为订阅源，无则为文件夹）
    pub html_url: Option<String>,// 网站主页 URL
    pub children: Vec<OpmlOutline>, // 子节点
}
```

### 4. API 层 (`src/api/mod.rs`)

**路由总览** (21 个端点):

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | Web 前端入口 |
| GET | `/favicon.ico` | SVG Favicon |
| POST | `/api/feeds` | 订阅新 Feed |
| GET | `/api/feeds` | 列出所有 Feed |
| DELETE | `/api/feeds/{id}` | 取消订阅 |
| POST | `/api/feeds/{id}/refresh` | 刷新单个 Feed |
| GET | `/api/feeds/{id}/articles` | 获取 Feed 文章 |
| POST | `/api/feeds/{id}/read-all` | 全部标记已读 |
| GET | `/api/articles` | 所有文章（时间线） |
| GET | `/api/articles/unread` | 未读文章 |
| PUT | `/api/articles/{id}/read` | 标记已读/未读 |
| PUT | `/api/articles/{id}/star` | 收藏/取消收藏 |
| POST | `/api/folders` | 创建文件夹 |
| GET | `/api/folders` | 列出文件夹 |
| PUT | `/api/folders/{id}` | 更新文件夹 |
| DELETE | `/api/folders/{id}` | 删除文件夹 |
| POST | `/api/folders/{id}/feeds/{fid}` | 添加 Feed 到文件夹 |
| DELETE | `/api/folders/{id}/feeds/{fid}` | 从文件夹移除 Feed |
| POST | `/api/opml/import` | 导入 OPML |
| GET | `/api/opml/export` | 导出 OPML |
| POST | `/api/refresh` | 刷新所有 Feed |
| GET | `/api/stats` | 统计信息 |

**统一响应格式**:
```json
{
  "success": true,
  "data": { ... },
  "error": null
}
```

**静态文件内嵌**: 前端 HTML 通过 `include_str!()` 宏在编译时嵌入二进制文件，部署时只需单个 exe 文件。

### 5. Web 前端 (`static/index.html`)

**技术方案**: 单文件 SPA，全部 CSS + JS 内联，零外部依赖，无需构建工具。

**布局**: CSS Flexbox 三栏布局
- 左侧边栏 (250px)：导航项 + 文件夹树 + 订阅源列表
- 中间文章列表 (350px)：标题 + 来源 + 时间 + 分页加载
- 右侧阅读面板 (flex-grow)：文章标题/正文/操作按钮

**前端状态管理**: 使用全局 `state` 对象：
```javascript
var state = {
    feeds: [],          // 所有订阅源
    folders: [],        // 所有文件夹
    articles: [],       // 当前视图的文章列表
    stats: {},          // 统计信息
    currentView: "all", // 当前视图（all/unread/starred/feed:xxx）
    currentArticle: null, // 当前选中的文章
    folderFeeds: {},    // 文件夹→订阅源ID映射
    feedMap: {},        // feedId→Feed对象映射（快速查找）
};
```

**安全措施**:
- HTML 内容渲染前经过 `sanitizeHtml()` 清洗
- 移除 `<script>`、`<style>`、`<iframe>`、`<object>`、`<embed>`、`<form>` 标签
- 移除所有 `on*` 事件属性
- 移除 `javascript:`、`vbscript:`、`data:text/html` URL
- 外部链接强制 `target="_blank" rel="noopener noreferrer"`

**响应式断点**:
- `> 768px`: 三栏完整显示
- `≤ 768px`: 隐藏阅读面板，两栏显示
- `≤ 480px`: 隐藏侧边栏，仅显示文章列表

## 配置说明

通过环境变量配置（均为可选，有默认值）：

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `FEEDFLOW_HOST` | `0.0.0.0` | HTTP 监听地址 |
| `FEEDFLOW_PORT` | `3200` | HTTP 端口 |
| `FEEDFLOW_DB_PATH` | `feedflow.db` | SQLite 数据库文件路径 |
| `FEEDFLOW_LOG_LEVEL` | `info` | 日志级别 (trace/debug/info/warn/error) |
| `FEEDFLOW_REFRESH_INTERVAL` | `900` | 自动刷新间隔（秒），0 = 禁用 |

## 依赖说明

| 依赖 | 版本 | 用途 |
|------|------|------|
| `axum` | 0.8 | Web 框架 |
| `tokio` | 1 | 异步运行时 |
| `reqwest` | 0.12 | HTTP 客户端（rustls-tls） |
| `feed-rs` | 2 | RSS/Atom/JSON Feed 解析 |
| `rusqlite` | 0.32 | SQLite 驱动（bundled 模式） |
| `quick-xml` | 0.37 | OPML XML 解析/生成 |
| `ammonia` | 4 | HTML 白名单清洗 |
| `serde` | 1 | JSON 序列化/反序列化 |
| `chrono` | 0.4 | 日期时间处理 |
| `uuid` | 1 | 唯一 ID 生成 |
| `tower-http` | 0.6 | CORS、日志、压缩中间件 |
| `tracing` | 0.1 | 结构化日志 |
| `anyhow` | 1 | 错误处理 |
| `thiserror` | 2 | 自定义错误类型 |

## 编译与运行

```bash
# Debug 编译
cargo build

# Release 编译（优化，推荐）
cargo build --release

# 运行
./target/release/feedflow.exe

# 自定义配置运行
FEEDFLOW_PORT=8080 FEEDFLOW_DB_PATH=/data/feedflow.db ./target/release/feedflow.exe
```

编译后为单个可执行文件（约 19MB），包含 SQLite 引擎和 Web 前端，无需额外依赖。
