//! maji_core — 码记 Rust 后端 (全局共享 TLS + Client 配置)

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::Serialize;
use std::panic::AssertUnwindSafe;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

const HOST: &str = "api.miclaw.xiaomi.net";
const MICLAW_CHAT_URL: &str = "https://api.miclaw.xiaomi.net/osbot/pc/llm/v1/chat/completions";

/// 兜底 IPv4：冷启动 Kotlin 侧系统 DNS 解析失败时（后台网络被延迟，getaddrinfo 会无限挂起），
/// 用这个已知可用 IP 钉死，确保 reqwest 永远走 .resolve() 直连、绝不触发系统 DNS 解析。
/// 这样即使 app 处于后台受限状态，connect 顶多超时失败（可被 tokio timeout 拦截），而不会永久挂死。
const FALLBACK_V4: &str = "220.181.104.181:443";

// ── 全局共享 Client ──

/// 由 Kotlin 侧（Android 平台解析器，最可靠）解析出的 IPv4，钉死到 Client 以绕过 IPv6 黑洞。
/// 用 Mutex 而非 OnceLock：IP 会随网络切换（WiFi↔蜂窝）变化，需可更新。
static MICLAW_V4: Mutex<Option<SocketAddr>> = Mutex::new(None);

async fn shared_http_client() -> Result<reqwest::Client, String> {
    // 不用 OnceLock：IP 可能延迟解析到或随网络切换变化，需能重建 client 以应用新的 IPv4 pin。
    static CLIENT: Mutex<Option<reqwest::Client>> = Mutex::new(None);
    static CLIENT_V4: Mutex<Option<SocketAddr>> = Mutex::new(None);

    // 永远钉死 IPv4：Kotlin 没解析到就用兜底 IP，绝不让 reqwest 走系统 DNS（后台受限时会无限挂起）。
    let pinned = *MICLAW_V4.lock().unwrap();
    let want = match pinned {
        Some(a) => {
            log::info!("使用 Kotlin 解析的 IPv4: {}", a);
            a
        }
        None => match FALLBACK_V4.parse::<SocketAddr>() {
            Ok(a) => {
                log::warn!("MICLAW_V4 未设置，使用兜底 IPv4: {}", a);
                a
            }
            Err(_) => {
                log::error!("兜底 IPv4 解析失败，无法建立客户端");
                return Err("no usable IPv4".to_string());
            }
        },
    };
    let mut client_guard = CLIENT.lock().unwrap();
    let built_v4 = *CLIENT_V4.lock().unwrap();
    // 需重建：无 client / IP 从无变有 / IP 变化
    let need_rebuild = client_guard.is_none() || (Some(want) != built_v4);
    if !need_rebuild {
        return Ok(client_guard.clone().unwrap());
    }

    let mut root_store = rustls::RootCertStore::empty();
    root_store.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let tls_config = rustls::ClientConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS12, &rustls::version::TLS13])
        .expect("TLS protocol versions")
        .with_root_certificates(root_store)
        .with_no_client_auth();
    let mut builder = reqwest::Client::builder()
        .use_preconfigured_tls(tls_config)
        .timeout(Duration::from_secs(90))
        .connect_timeout(Duration::from_secs(5))
        .pool_idle_timeout(Duration::from_secs(20));
    // 强制 IPv4：规避 Android 上偶发的 IPv6 黑洞导致首连挂起 ~25s
    builder = builder.resolve(HOST, want);
    log::info!("DNS 强制 IPv4: {}", want);
    let client = builder.build().map_err(|e| format!("client: {e}"))?;
    *CLIENT_V4.lock().unwrap() = Some(want);
    *client_guard = Some(client);
    Ok(client_guard.clone().unwrap())
}

// ── MiMo API ──

async fn call_miclaw(service_token: &str, c_user_id: &str, body_json: &str) -> Result<String, String> {
    let client = shared_http_client().await?;
    let cookie = if c_user_id.trim().is_empty() {
        format!("serviceToken={service_token}")
    } else {
        format!("serviceToken={service_token}; cUserId={c_user_id}")
    };
    let body: serde_json::Value = serde_json::from_str(body_json).map_err(|e| format!("json: {e}"))?;

    // 透明重试：Android 后台网络偶发 RST 空闲连接导致 "error sending request"，
    // 每次重试换新连接即可恢复。HTTP 错误（如 401）不重试，直接返回。
    // 每次请求包 15s 总超时，避免 TLS/请求阶段挂起干等（connect_timeout 只管 TCP 建连）。
    let mut last_err = String::new();
    for attempt in 0..3 {
        if attempt > 0 {
            log::warn!("MiMo 连接失败，第{}次重试（换新连接）", attempt + 1);
            tokio::time::sleep(Duration::from_millis(300)).await;
        }
        let send_fut = client
            .post(MICLAW_CHAT_URL)
            .header("User-Agent", "node")
            .header("Accept", "*/*")
            .header("Cookie", cookie.clone())
            .json(&body)
            .send();
        match tokio::time::timeout(Duration::from_secs(15), send_fut).await {
            Ok(Ok(response)) => {
                let status = response.status();
                // 读取响应体也加超时：冷启动偶发半开连接会让 text() 一直挂到 client 90s 超时。
                let text = match tokio::time::timeout(Duration::from_secs(15), response.text()).await {
                    Ok(Ok(t)) => t,
                    Ok(Err(e)) => return Err(format!("read: {e}")),
                    Err(_) => return Err("read timeout(15s)".to_string()),
                };
                if !status.is_success() {
                    return Err(format!("HTTP {}: {}", status.as_u16(), text.chars().take(300).collect::<String>()));
                }
                if attempt > 0 {
                    log::info!("MiMo 重试成功（第{}次）", attempt + 1);
                }
                return Ok(text);
            }
            Ok(Err(e)) => {
                last_err = format!("request: {e}");
            }
            Err(_) => {
                last_err = "request timeout(15s)".to_string();
            }
        }
    }
    Err(last_err)
}

// ── 暖连接（preflight）：首连前把 DNS/TLS/套接字暖热，避免首次连接偶发失败 ──
// Android 上 Rust reqwest 首次连接常因 radio 冷 / happy-eyeballs 偶发失败，
// 这里在空闲时在后台静默预请求，把连接池暖热，正式请求即可复用 → 一次成功。

const WARMUP_BODY: &str = r#"{"model":"xiaomi/mimo","stream":false,"temperature":0,"max_tokens":8,"messages":[{"role":"user","content":"ok"}]}"#;

async fn warm_up_miclaw(service_token: &str, c_user_id: &str) -> Result<String, String> {
    let client = shared_http_client().await?;
    let cookie = if c_user_id.trim().is_empty() {
        format!("serviceToken={service_token}")
    } else {
        format!("serviceToken={service_token}; cUserId={c_user_id}")
    };
    let body: serde_json::Value = serde_json::from_str(WARMUP_BODY).unwrap_or(serde_json::Value::Null);
    let mut last_err = String::new();
    for attempt in 0..3 {
        if attempt > 0 {
            tokio::time::sleep(Duration::from_millis(500)).await;
        }
        match client
            .post(MICLAW_CHAT_URL)
            .header("User-Agent", "node")
            .header("Accept", "*/*")
            .header("Cookie", &cookie)
            .json(&body)
            .send()
            .await
        {
            Ok(r) => {
                let _ = r.status();
                let _ = r.text().await;
                return Ok("warmed".to_string());
            }
            Err(e) => {
                last_err = format!("request: {e}");
                log::warn!("warmup 尝试 {}/3 失败: {last_err}", attempt + 1);
            }
        }
    }
    Err(last_err)
}

// ── 返回结果 ──

#[derive(Serialize)]
struct CallResult {
    success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    body: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

// ── JNI ──

static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
static LOGGER: std::sync::Once = std::sync::Once::new();

fn runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("tokio")
    })
}

fn ensure_logger() {
    LOGGER.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("maji_core"),
        );
    });
}

fn to_json(r: &CallResult) -> String {
    serde_json::to_string(r).unwrap_or_default()
}

fn out(env: &mut JNIEnv<'_>, json: &str) -> jstring {
    env.new_string(json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_zhaoyi_maji_island_RustBridge_callMiclawNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    j_service_token: JString<'local>,
    j_c_user_id: JString<'local>,
    j_body_json: JString<'local>,
) -> jstring {
    ensure_logger();
    let service_token: String = env.get_string(&j_service_token).map(String::from).unwrap_or_default();
    let c_user_id: String = env.get_string(&j_c_user_id).map(String::from).unwrap_or_default();
    let body_json: String = env.get_string(&j_body_json).map(String::from).unwrap_or_default();

    log::info!("MiMo 调用 start, token_len={}", service_token.len());
    let rt = runtime();
    let res = std::panic::catch_unwind(AssertUnwindSafe(|| {
        rt.block_on(call_miclaw(&service_token, &c_user_id, &body_json))
    }));
    match res {
        Ok(Ok(text)) => {
            log::info!("MiMo 成功 bodyLen={}", text.len());
            out(&mut env, &to_json(&CallResult { success: true, body: Some(text), error: None }))
        }
        Ok(Err(e)) => {
            log::error!("MiMo 失败: {e}");
            out(&mut env, &to_json(&CallResult { success: false, body: None, error: Some(e) }))
        }
        Err(_) => {
            log::error!("MiMo 调用 panic (已被 catch_unwind 捕获，未崩溃)");
            out(&mut env, &to_json(&CallResult { success: false, body: None, error: Some("rust panic in call_miclaw".into()) }))
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_zhaoyi_maji_island_RustBridge_warmUpMiclawNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    j_service_token: JString<'local>,
    j_c_user_id: JString<'local>,
) -> jstring {
    ensure_logger();
    let service_token: String = env.get_string(&j_service_token).map(String::from).unwrap_or_default();
    let c_user_id: String = env.get_string(&j_c_user_id).map(String::from).unwrap_or_default();

    log::info!("warmup start, token_len={}", service_token.len());
    let rt = runtime();
    let res = std::panic::catch_unwind(AssertUnwindSafe(|| {
        rt.block_on(warm_up_miclaw(&service_token, &c_user_id))
    }));
    match res {
        Ok(Ok(_)) => {
            log::info!("warmup 成功，连接池已暖热");
            out(&mut env, &to_json(&CallResult { success: true, body: Some("warmed".into()), error: None }))
        }
        Ok(Err(e)) => {
            log::error!("warmup 失败: {e}");
            out(&mut env, &to_json(&CallResult { success: false, body: None, error: Some(e) }))
        }
        Err(_) => {
            log::error!("warmup panic (已被 catch_unwind 捕获，未崩溃)");
            out(&mut env, &to_json(&CallResult { success: false, body: None, error: Some("rust panic in warm_up_miclaw".into()) }))
        }
    }
}

/// 由 Kotlin 侧用 Android 平台解析器解析出 IPv4 后调用，钉死到 Client 绕过 IPv6 黑洞。
#[no_mangle]
pub extern "system" fn Java_com_zhaoyi_maji_island_RustBridge_setMiclawV4Native(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    j_ip: JString<'_>,
) {
    ensure_logger();
    let ip: String = env.get_string(&j_ip).map(String::from).unwrap_or_default();
    // Kotlin 传纯 IPv4，补上 443 端口组成 SocketAddr。trim 兜底，防止偶发的尾随空白导致解析失败。
    let ip = ip.trim().to_string();
    let with_port = format!("{}:443", ip);
    let addr = match with_port.parse::<SocketAddr>() {
        Ok(a) => a,
        Err(e) => {
            log::warn!("setMiclawV4 解析失败: {} ({})，回退兜底 IPv4", ip, e);
            FALLBACK_V4.parse::<SocketAddr>().unwrap()
        }
    };
    *MICLAW_V4.lock().unwrap() = Some(addr);
    log::info!("setMiclawV4: {}", addr);
}

#[no_mangle]
pub unsafe extern "system" fn JNI_OnLoad(_vm: *mut jni::sys::JavaVM, _: *mut std::ffi::c_void) -> jni::sys::jint {
    ensure_logger();
    jni::sys::JNI_VERSION_1_6
}
