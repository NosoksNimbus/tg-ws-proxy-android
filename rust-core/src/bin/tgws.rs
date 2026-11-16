//! Desktop command-line proxy (Windows / Linux / macOS). No Python needed.
//!
//!   tgws [--host 127.0.0.1] [--port 1443] [--secret <32 hex>] [--dc-ip 2:149.154.167.220]...
//!        [--cf-worker domain]... [--cf-domain domain]... [--no-cfproxy] [--pool 8] [--verbose]
use rand::RngCore;
use serde_json::json;

fn usage() -> ! {
    eprintln!(
        "tgws {} - Telegram MTProto -> WebSocket proxy\n\n\
         Options:\n  --host H            listen address (default 127.0.0.1)\n  --port N            listen port (default 1443)\n  \
         --secret HEX        32 hex chars (random if omitted)\n  --dc-ip N:IP        DC address, repeatable (default 2 and 4 -> 149.154.167.220)\n  \
         --cf-worker DOMAIN  own Cloudflare Worker domain, repeatable\n  --cf-domain DOMAIN  own Cloudflare-proxied domain, repeatable\n  \
         --no-cfproxy        disable the Cloudflare fallback\n  --pool N            connections per DC (default 8)\n  --verbose           debug log\n  -h, --help",
        env!("CARGO_PKG_VERSION")
    );
    std::process::exit(2)
}

fn main() {
    let mut host = "127.0.0.1".to_string();
    let mut port = 1443u64;
    let mut secret = String::new();
    let (mut dc, mut workers, mut domains) = (Vec::<String>::new(), Vec::<String>::new(), Vec::<String>::new());
    let (mut cf, mut pool, mut verbose) = (true, 8u64, false);

    let mut it = std::env::args().skip(1);
    while let Some(a) = it.next() {
        let mut val = || it.next().unwrap_or_else(|| usage());
        match a.as_str() {
            "--host" => host = val(),
            "--port" => port = val().parse().unwrap_or_else(|_| usage()),
            "--secret" => secret = val(),
            "--dc-ip" => dc.push(val()),
            "--cf-worker" => workers.push(val()),
            "--cf-domain" => domains.push(val()),
            "--pool" => pool = val().parse().unwrap_or_else(|_| usage()),
            "--no-cfproxy" => cf = false,
            "--verbose" => verbose = true,
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
        "pool_size": pool, "verbose": verbose,
    })
    .to_string();

    tgws_core::logbuf::set_echo(true);
    let err = tgws_core::proxy::start(&cfg);
    if !err.is_empty() {
        eprintln!("Error: {err}");
        std::process::exit(1);
    }
    let link_host = if host == "0.0.0.0" { "127.0.0.1" } else { host.as_str() };
    println!("\nAdd to Telegram:\n  tg://proxy?server={link_host}&port={port}&secret=dd{secret}\n\nCtrl+C to stop.");
    loop {
        std::thread::sleep(std::time::Duration::from_secs(3600));
    }
}
