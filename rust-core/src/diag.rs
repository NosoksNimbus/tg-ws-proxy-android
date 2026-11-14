//! Connectivity check: same probes and JSON shape as the Python core's `diagnose`.
use std::future::Future;
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use tokio::net::{lookup_host, TcpStream};
use tokio::time::timeout;

use crate::config;
use crate::util::*;
use crate::ws;

async fn probe(name: String, tmo: Duration, fut: impl Future<Output = Result<(), String>>) -> Value {
    let started = Instant::now();
    match timeout(tmo, fut).await {
        Ok(Ok(())) => json!({"name": name, "ok": true, "ms": started.elapsed().as_millis() as u64}),
        Ok(Err(e)) => json!({"name": name, "ok": false, "error": e.chars().take(80).collect::<String>()}),
        Err(_) => json!({"name": name, "ok": false, "error": "timeout"}),
    }
}

async fn tcp_connect(host: String, port: u16) -> Result<(), String> {
    TcpStream::connect((host.as_str(), port)).await.map(|_| ()).map_err(|e| ws::WsError::io(e).0)
}

async fn reach(ip: String, port: u16) -> bool {
    matches!(timeout(Duration::from_millis(3500), TcpStream::connect((ip.as_str(), port))).await, Ok(Ok(_)))
}

/// WebSocket handshake that says exactly where it fails: dns / tcp 443+80 / handshake.
async fn staged_ws(host: String, path: String) -> Result<(), String> {
    let ip = match timeout(Duration::from_secs(3), lookup_host((host.as_str(), 443u16))).await {
        Ok(Ok(it)) => it
            .filter(|a| a.is_ipv4())
            .map(|a| a.ip().to_string())
            .next()
            .ok_or_else(|| "dns: no address".to_string())?,
        Ok(Err(e)) => return Err(format!("dns: {}", ws::WsError::io(e))),
        Err(_) => return Err("dns: timeout".into()),
    };
    let (ok443, ok80) = tokio::join!(reach(ip.clone(), 443), reach(ip.clone(), 80));
    if !ok443 && !ok80 {
        return Err(format!("IP {ip} blocked (tcp 443+80)"));
    }
    match ws::connect(&host, &host, Duration::from_secs(5), &path, None, true).await {
        Ok(c) => {
            c.w.close().await;
            Ok(())
        }
        Err(e) => Err(format!(
            "{ip} tcp ok (443:{} 80:{}), handshake: {e}",
            if ok443 { "y" } else { "n" },
            if ok80 { "y" } else { "n" }
        )),
    }
}

async fn run(cfg_json: &str) -> Result<String, String> {
    let cfg = config::parse(cfg_json)?;
    let mut dcs = cfg.dc.clone();
    if dcs.is_empty() {
        dcs.insert(2, "149.154.167.220".into());
    }
    let mut hs = Vec::new();

    for (dc, ip) in dcs.clone() {
        let name = format!("WS DC{dc}");
        hs.push(tokio::spawn(probe(name, Duration::from_secs(6), async move {
            let d = ws_domains(dc, false).remove(0);
            let c = ws::connect(&ip, &d, Duration::from_secs(6), "/apiws", None, true)
                .await
                .map_err(|e| e.0)?;
            c.w.close().await;
            Ok(())
        })));
    }
    for dc in dcs.keys() {
        if let Some(ip) = dc_default_ip(*dc, false) {
            hs.push(tokio::spawn(probe(format!("TCP DC{dc}"), Duration::from_secs(6), tcp_connect(ip.to_string(), 443))));
        }
    }
    if cfg.cfproxy {
        let bases = if cfg.user_domains.is_empty() { default_cf_domains() } else { cfg.user_domains.clone() };
        let dc = *dcs.keys().next().unwrap_or(&2);
        for base in bases.iter().take(2) {
            let host = format!("kws{dc}.{base}");
            let name = format!("CF {}", base.chars().take(18).collect::<String>());
            hs.push(tokio::spawn(probe(name, Duration::from_secs(14), staged_ws(host, "/apiws".into()))));
        }
    }
    for wd in cfg.workers.iter().take(2) {
        let name = format!("Worker {}", wd.chars().take(16).collect::<String>());
        hs.push(tokio::spawn(probe(
            name,
            Duration::from_secs(14),
            staged_ws(wd.clone(), "/apiws?dst=149.154.167.220&dc=2".into()),
        )));
    }
    for (label, host) in [("NET ya.ru", "ya.ru"), ("NET 1.1.1.1", "1.1.1.1"), ("NET 8.8.8.8", "8.8.8.8")] {
        hs.push(tokio::spawn(probe(label.to_string(), Duration::from_secs(6), tcp_connect(host.to_string(), 443))));
    }

    let mut results = Vec::new();
    for h in hs {
        if let Ok(v) = h.await {
            results.push(v);
        }
    }
    Ok(json!({"results": results, "cf_needs_proxy": false}).to_string())
}

/// Blocking; runs on its own small runtime so it works whether or not the proxy is running.
pub fn diagnose(cfg_json: &str) -> String {
    let rt = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
        Ok(r) => r,
        Err(e) => return json!({"results": [], "error": e.to_string()}).to_string(),
    };
    match rt.block_on(run(cfg_json)) {
        Ok(s) => s,
        Err(e) => json!({"results": [{"name": "config", "ok": false, "error": e}], "cf_needs_proxy": false}).to_string(),
    }
}
