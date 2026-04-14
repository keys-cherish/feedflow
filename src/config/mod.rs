use serde::Deserialize;

/// 应用配置
#[derive(Debug, Clone, Deserialize)]
pub struct AppConfig {
    /// HTTP 服务监听地址
    pub host: String,
    /// HTTP 服务端口
    pub port: u16,
    /// 数据库文件路径
    pub db_path: String,
    /// 日志级别
    pub log_level: String,
    /// 自动刷新间隔（秒），0 表示禁用
    pub auto_refresh_interval_secs: u64,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            host: "0.0.0.0".to_string(),
            port: 3200,
            db_path: "feedflow.db".to_string(),
            log_level: "info".to_string(),
            auto_refresh_interval_secs: 900, // 15 分钟
        }
    }
}

impl AppConfig {
    /// 从环境变量加载配置（覆盖默认值）
    pub fn from_env() -> Self {
        let mut config = Self::default();

        if let Ok(host) = std::env::var("FEEDFLOW_HOST") {
            config.host = host;
        }
        if let Ok(port) = std::env::var("FEEDFLOW_PORT") {
            if let Ok(p) = port.parse() {
                config.port = p;
            }
        }
        if let Ok(db) = std::env::var("FEEDFLOW_DB_PATH") {
            config.db_path = db;
        }
        if let Ok(level) = std::env::var("FEEDFLOW_LOG_LEVEL") {
            config.log_level = level;
        }
        if let Ok(interval) = std::env::var("FEEDFLOW_REFRESH_INTERVAL") {
            if let Ok(i) = interval.parse() {
                config.auto_refresh_interval_secs = i;
            }
        }

        config
    }

    pub fn listen_addr(&self) -> String {
        format!("{}:{}", self.host, self.port)
    }
}
