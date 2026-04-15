use anyhow::Result;
use reqwest::Client;
use serde_json::json;
use tracing::info;

use crate::models::DownloadConfig;

/// Push a torrent URL to the configured download client
pub async fn add_torrent(client: &Client, config: &DownloadConfig, torrent_url: &str) -> Result<String> {
    match config.client_type.as_str() {
        "qbittorrent" => qbittorrent_add(client, config, torrent_url).await,
        "aria2" => aria2_add(client, config, torrent_url).await,
        "transmission" => transmission_add(client, config, torrent_url).await,
        _ => anyhow::bail!("Unknown client type: {}", config.client_type),
    }
}

/// Test connection to the download client
pub async fn test_connection(client: &Client, config: &DownloadConfig) -> Result<bool> {
    match config.client_type.as_str() {
        "qbittorrent" => qbittorrent_test(client, config).await,
        "aria2" => aria2_test(client, config).await,
        "transmission" => transmission_test(client, config).await,
        _ => Ok(false),
    }
}

// ---- qBittorrent ----

async fn qbittorrent_login(client: &Client, config: &DownloadConfig) -> Result<String> {
    let url = format!("{}/api/v2/auth/login", config.host.trim_end_matches('/'));
    let resp = client.post(&url)
        .form(&[("username", &config.username), ("password", &config.password)])
        .send().await?;
    let cookie = resp.headers().get("set-cookie")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.split(';').next())
        .unwrap_or("").to_string();
    let text = resp.text().await?;
    if text.to_lowercase().contains("ok") { Ok(cookie) }
    else { anyhow::bail!("qBit login failed: {}", text) }
}

async fn qbittorrent_test(client: &Client, config: &DownloadConfig) -> Result<bool> {
    qbittorrent_login(client, config).await.map(|_| true)
}

async fn qbittorrent_add(client: &Client, config: &DownloadConfig, torrent_url: &str) -> Result<String> {
    let cookie = qbittorrent_login(client, config).await?;
    let url = format!("{}/api/v2/torrents/add", config.host.trim_end_matches('/'));
    let mut form = reqwest::multipart::Form::new()
        .text("urls", torrent_url.to_string())
        .text("tags", "feedflow".to_string());
    if !config.save_path.is_empty() {
        form = form.text("savepath", config.save_path.clone());
    }
    let resp = client.post(&url)
        .header("Cookie", cookie)
        .multipart(form)
        .send().await?;
    if resp.status().is_success() {
        info!("qBit torrent added: {}", torrent_url);
        Ok(torrent_url.to_string())
    } else {
        anyhow::bail!("qBit add failed: HTTP {}", resp.status())
    }
}

// ---- Aria2 ----

async fn aria2_rpc(client: &Client, config: &DownloadConfig, method: &str, params: serde_json::Value) -> Result<serde_json::Value> {
    let url = format!("{}/jsonrpc", config.host.trim_end_matches('/'));
    let body = json!({"jsonrpc": "2.0", "id": "feedflow", "method": method, "params": params});
    let resp = client.post(&url).json(&body).send().await?;
    let json: serde_json::Value = resp.json().await?;
    if let Some(err) = json.get("error") {
        anyhow::bail!("Aria2 error: {}", err);
    }
    Ok(json)
}

async fn aria2_test(client: &Client, config: &DownloadConfig) -> Result<bool> {
    let params = if config.password.is_empty() { json!([]) }
        else { json!([format!("token:{}", config.password)]) };
    aria2_rpc(client, config, "aria2.getVersion", params).await.map(|_| true)
}

async fn aria2_add(client: &Client, config: &DownloadConfig, torrent_url: &str) -> Result<String> {
    let mut p = vec![];
    if !config.password.is_empty() { p.push(json!(format!("token:{}", config.password))); }
    p.push(json!([torrent_url]));
    let mut opts = json!({});
    if !config.save_path.is_empty() { opts["dir"] = json!(config.save_path); }
    p.push(opts);
    let result = aria2_rpc(client, config, "aria2.addUri", json!(p)).await?;
    let gid = result["result"].as_str().unwrap_or("").to_string();
    info!("Aria2 task added: gid={}", gid);
    Ok(gid)
}

// ---- Transmission ----

fn transmission_rpc<'a>(client: &'a Client, config: &'a DownloadConfig, body: &'a serde_json::Value, session_id: Option<&'a str>) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<(serde_json::Value, Option<String>)>> + Send + 'a>> {
    Box::pin(async move {
        let url = format!("{}/transmission/rpc", config.host.trim_end_matches('/'));
        let mut req = client.post(&url).json(body);
        if !config.username.is_empty() {
            req = req.basic_auth(&config.username, Some(&config.password));
        }
        if let Some(sid) = session_id {
            req = req.header("X-Transmission-Session-Id", sid);
        }
        let resp = req.send().await?;
        if resp.status().as_u16() == 409 {
            // Transmission 的 CSRF 机制：首次请求返回 409 + session ID
            let new_sid = resp.headers().get("X-Transmission-Session-Id")
                .and_then(|v| v.to_str().ok()).map(String::from);
            if let Some(ref sid) = new_sid {
                return transmission_rpc(client, config, body, Some(sid)).await;
            }
        }
        let new_sid = resp.headers().get("X-Transmission-Session-Id")
            .and_then(|v| v.to_str().ok()).map(String::from);
        let json: serde_json::Value = resp.json().await?;
        Ok((json, new_sid))
    })
}

async fn transmission_test(client: &Client, config: &DownloadConfig) -> Result<bool> {
    let body = json!({"method": "session-get"});
    let (result, _) = transmission_rpc(client, config, &body, None).await?;
    Ok(result["result"].as_str() == Some("success"))
}

async fn transmission_add(client: &Client, config: &DownloadConfig, torrent_url: &str) -> Result<String> {
    let mut args = json!({"filename": torrent_url, "labels": ["feedflow"]});
    if !config.save_path.is_empty() { args["download-dir"] = json!(config.save_path); }
    let body = json!({"method": "torrent-add", "arguments": args});
    let (result, _) = transmission_rpc(client, config, &body, None).await?;
    if result["result"].as_str() == Some("success") {
        info!("Transmission torrent added: {}", torrent_url);
        Ok(torrent_url.to_string())
    } else {
        anyhow::bail!("Transmission add failed: {}", result["result"])
    }
}
