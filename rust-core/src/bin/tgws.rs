//! Desktop command-line proxy (Windows / Linux / macOS), same options as the old Python version.
use rand::RngCore;
use serde_json::json;

const HELP: &str = "\
  --host H                  listen address (default 127.0.0.1)
  --port N                  listen port (default 1443)
  --secret HEX              32 hex chars (random if omitted)
  --dc-ip N:IP              DC address, repeatable (default 2 and 4 -> 149.154.167.220)
  -v, --verbose             debug log
  --log-file PATH           also write the log to a file (rotated)
  --log-max-mb MB           max log file size before rotation (default 5)
  --log-backups N           rotated log files to keep (default 1)
  --buf-kb KB               accepted for compatibility, ignored
  --pool-size N             WebSocket pool size per DC (default 4, 0 = no direct WS)
  --cfproxy-domain DOMAIN   own Cloudflare-proxied domain, repeatable
  --cfproxy-worker-domain D own Cloudflare Worker domain, repeatable
  --no-h2                   disable the HTTP/2 media lane
  --no-cfproxy              disable the Cloudflare fallback
  --no-secure               use port 80 for Cloudflare proxy/worker
  --fake-tls-domain DOMAIN  Fake-TLS (ee secret) masking domain
  --force-test-dc           force all traffic to Telegram test DCs
  --proxy-protocol          accept PROXY protocol v1 (behind nginx/haproxy)
  -h, --help";

fn usage() -> ! {
    eprintln!("tgws {} - Telegram MTProto -> WebSocket proxy\n\nOptions:\n{HELP}", env!("CARGO_PKG_VERSION"));
    std::process::exit(2)
}

fn main() {
    let mut host = "127.0.0.1".to_string();
    let mut port = 1443u64;
    let mut secret = String::new();
    let (mut dc, mut workers, mut domains) = (Vec::<String>::new(), Vec::<String>::new(), Vec::<String>::new());
    let (mut cf, mut pool, mut verbose, mut h2) = (true, 4u64, false, true);
    let (mut fake, mut log_file) = (String::new(), String::new());
    let (mut log_mb, mut log_backups) = (5.0f64, 1u32);
    let (mut no_secure, mut test_dc, mut pp) = (false, false, false);

    let mut it = std::env::args().skip(1);
    while let Some(a) = it.next() {
        let mut val = || it.next().unwrap_or_else(|| usage());
        match a.as_str() {
            "--host" => host = val(),
            "--port" => port = val().parse().unwrap_or_else(|_| usage()),
            "--secret" => secret = val(),
            "--dc-ip" => dc.push(val()),
            "--cfproxy-worker-domain" | "--cf-worker" => workers.push(val()),
            "--cfproxy-domain" | "--cf-domain" => domains.push(val()),
            "--pool-size" | "--pool" => pool = val().parse().unwrap_or_else(|_| usage()),
            "--log-file" => log_file = val(),
            "--log-max-mb" => log_mb = val().parse().unwrap_or_else(|_| usage()),
            "--log-backups" => log_backups = val().parse().unwrap_or_else(|_| usage()),
            "--buf-kb" => {
                let _ = val();
            }
            "--no-cfproxy" => cf = false,
            "--no-h2" => h2 = false,
            "--no-secure" => no_secure = true,
            "--force-test-dc" => test_dc = true,
            "--proxy-protocol" => pp = true,
            "--fake-tls-domain" | "--fake-tls" => fake = val(),
            "-v" | "--verbose" => verbose = true,
            _ => usage(),
        }
    }
    if secret.is_empty() {
        let mut b = [0u8; 16];
        rand::thread_rng().fill_bytes(&mut b);
        secret = b.iter().map(|x| format!("{x:02x}")).collect();
    }
    if dc.is_empty() {
        dc = vec!["2:149.154.167.220".into(), "4:149.154.167.220".into()];
    }
    let cfg = json!({
        "host": host, "port": port, "secret": secret, "dc_ip": dc,
        "cfproxy": cf, "cfproxy_worker_domain": workers, "cfproxy_user_domain": domains,
        "pool_size": pool, "verbose": verbose, "fake_tls_domain": fake, "h2_media": h2,
        "no_secure": no_secure, "force_test_dc": test_dc, "proxy_protocol": pp,
    })
    .to_string();

    tgws_core::logbuf::set_echo(true);
    if !log_file.is_empty() {
        if let Err(e) = tgws_core::logbuf::set_file(&log_file, log_mb, log_backups) {
            eprintln!("Cannot open log file {log_file}: {e}");
            std::process::exit(1);
        }
    }
    let err = tgws_core::proxy::start(&cfg);
    if !err.is_empty() {
        eprintln!("Error: {err}");
        std::process::exit(1);
    }
    let link_host = if host == "0.0.0.0" { "127.0.0.1" } else { host.as_str() };
    let sec = if fake.is_empty() {
        format!("dd{secret}")
    } else {
        format!("ee{secret}{}", fake.bytes().map(|b| format!("{b:02x}")).collect::<String>())
    };
    println!("\nAdd to Telegram:\n  tg://proxy?server={link_host}&port={port}&secret={sec}\n\nCtrl+C to stop.");
    // periodic stats line, like the old core
    loop {
        std::thread::sleep(std::time::Duration::from_secs(60));
        tgws_core::logbuf::log("INFO", format!("stats: {}", tgws_core::stats::json()));
    }
}
