# FeedFlow 更新日志

## v0.1.1 (2026-04-14)

### 新增功能
- **文件夹管理**: 完整的文件夹 CRUD API
  - 创建、列出、更新、删除文件夹
  - 将订阅源添加到文件夹 / 从文件夹移除
  - 文件夹列表包含订阅源数量统计
- **OPML 导入/导出**: 支持 RSS 阅读器间的订阅迁移
  - `POST /api/opml/import` 批量导入 OPML 文件
  - `GET /api/opml/export` 导出 OPML 2.0 XML（含文件夹结构）
  - 自动创建 OPML 中的文件夹并关联订阅源
- **Web 前端**: 内嵌单页应用（SPA）
  - 三栏布局：侧边栏 / 文章列表 / 阅读面板
  - 深色主题，响应式设计
  - 键盘快捷键 (j/k/r/s/Shift+R)
  - 右键菜单管理订阅源
  - 订阅源添加、OPML 文件导入/导出
  - 文件夹创建和订阅源拖拽分组
  - 全中文界面

### Bug 修复
- 修复订阅源发现（discover_feed）失败时整个订阅流程中断的问题
  - 现在 discovery 失败会静默回退到直接使用原始 URL
- API 错误信息现在显示完整错误链（使用 `{:#}` 格式化 anyhow 错误）
- 添加 favicon.ico 路由，消除浏览器控制台 404 错误

### 技术变更
- 新增 `src/opml.rs` 模块（quick-xml 解析/生成 OPML 2.0）
- `src/db/mod.rs` 新增 8 个文件夹相关数据库查询方法
- `src/api/mod.rs` 新增 8 个 API 路由 + 对应 handler
- `src/services/mod.rs` 新增 `import_opml` 方法
- `src/models/mod.rs` 新增 `FolderWithCount`、`OpmlImportResult` 类型
- `static/index.html` 内嵌 SPA 前端（~1800 行，CSS + vanilla JS）
- 前端通过 `include_str!()` 编译进二进制文件，无需外部静态文件

## v0.1.0 (初始版本)

### 核心功能
- RSS/Atom/JSON Feed 订阅和抓取
- 自动发现网页中的 RSS 链接
- 条件请求支持（ETag / Last-Modified）
- 文章 CRUD（已读/未读、收藏/取消收藏）
- 自动定时刷新（可配置间隔）
- HTML 内容白名单清洗（ammonia）
- SQLite 数据库存储（WAL 模式）
- RESTful JSON API
