use std::collections::BTreeMap;

use crate::util::{coerce_domains, parse_dc_ip};

pub struct Cfg {
    pub host: String,
    pub port: u16,
    pub secret: [u8; 16],
    pub dc: BTreeMap<u32, String>,
    pub cfproxy: bool,
    pub user_domains: Vec<String>,
    pub workers: Vec<String>,
    pub pool_size: usize,
    pub no_secure: bool,
    /// "auto" (TLS:443 then plain:80), "plain", "tls"
    pub cf_mode: String,
    pub force_test_dc: bool,
}

fn str_list(v: Option<&serde_json::Value>) -> Vec<String> {
    match v {
        Some(serde_json::Value::Array(a)) => a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect(),
        Some(serde_json::Value::String(s)) => vec![s.clone()],
        _ => vec![],
    }
}

pub fn parse(json: &str) -> Result<Cfg, String> {
    let v: serde_json::Value = serde_json::from_str(json).map_err(|e| format!("Bad config: {e}"))?;
    let secret_hex = v["secret"].as_str().unwrap_or("");
    if secret_hex.len() != 32 {
        return Err("Secret must be exactly 32 hex characters".into());
    }
    let mut secret = [0u8; 16];
    for i in 0..16 {
        secret[i] = u8::from_str_radix(&secret_hex[2 * i..2 * i + 2], 16)
            .map_err(|_| "Secret must be valid hex".to_string())?;
    }
    let dc_list = match v.get("dc_ip") {
        Some(x) => str_list(Some(x)),
        None => vec!["2:149.154.167.220".into(), "4:149.154.167.220".into()],
    };
    let dc = parse_dc_ip(&dc_list)?;
    let port = v["port"].as_u64().unwrap_or(1443);
    if port == 0 || port > 65535 {
        return Err("Bad port".into());
    }
    Ok(Cfg {
        host: v["host"].as_str().unwrap_or("127.0.0.1").to_string(),
        port: port as u16,
        secret,
        dc,
        cfproxy: v["cfproxy"].as_bool().unwrap_or(true),
        user_domains: coerce_domains(&str_list(v.get("cfproxy_user_domain"))),
        workers: coerce_domains(&str_list(v.get("cfproxy_worker_domain"))),
        pool_size: v["pool_size"].as_u64().unwrap_or(8).min(64) as usize,
        no_secure: v["no_secure"].as_bool().unwrap_or(false),
        cf_mode: v["cf_mode"].as_str().unwrap_or("auto").to_string(),
        force_test_dc: v["force_test_dc"].as_bool().unwrap_or(false),
    })
}
