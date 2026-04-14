use axum::{
    extract::Request,
    http::{header, StatusCode},
    middleware::Next,
    response::Response,
};

/// 认证中间件 — 检查 Authorization header 中的 Bearer token
/// 当前为预留框架，FEEDFLOW_AUTH_TOKEN 未设置时所有请求放行
pub async fn auth_middleware(req: Request, next: Next) -> Result<Response, StatusCode> {
    let token = std::env::var("FEEDFLOW_AUTH_TOKEN").unwrap_or_default();

    // 未配置 token 则跳过认证（本地/开发模式）
    if token.is_empty() {
        return Ok(next.run(req).await);
    }

    // 放行健康检查和静态资源
    let path = req.uri().path();
    if path == "/health" || path == "/" || path == "/favicon.ico" {
        return Ok(next.run(req).await);
    }

    // 放行登录接口本身
    if path == "/api/auth/login" {
        return Ok(next.run(req).await);
    }

    // 检查 Bearer token
    let auth_header = req
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok());

    match auth_header {
        Some(h) if h.starts_with("Bearer ") && &h[7..] == token => {
            Ok(next.run(req).await)
        }
        _ => Err(StatusCode::UNAUTHORIZED),
    }
}
