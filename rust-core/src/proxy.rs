//! Listener, upstream pool, fallback chain and the re-encrypting bridges.
use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering::Relaxed};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use rand::seq::SliceRandom;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::runtime::Runtime;
use tokio::sync::watch;
use tokio::time::{sleep, timeout};

use crate::config::{self, Cfg};
use crate::obf::{self, CryptoCtx};
use crate::stats;
use crate::util::*;
use crate::ws::{self, WsConn, WsError};

type Key = (u32, bool, bool); // (dc, media, test)

const POOL_MAX_AGE: Duration = Duration::from_secs(60);
const TCP_BACKOFF_INITIAL: u64 = 30;
const TCP_BACKOFF_MAX: u64 = 3600;
const FRONT_SNI: &str = "sprinthost.ru";

#[derive(Default)]
struct PoolInner {
    idle: HashMap<Key, VecDeque<(WsConn, Instant)>>,
    pending: HashMap<Key, usize>,
    backoff: HashMap<Key, (u32, Instant)>,
}

pub struct Shared {
    pub cfg: Cfg,
    pool_size: AtomicUsize,
    pool: Mutex<PoolInner>,
    epoch: watch::Sender<u64>,
    tcp_backoff: Mutex<HashMap<String, (u32, Instant)>>,
    cf_current: Mutex<HashMap<u32, String>>,
    front_first: AtomicBool,
    plain_until: AtomicU64,
}

fn now_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

impl Shared {
    fn new(cfg: Cfg) -> Arc<Shared> {
        let (epoch, _) = watch::channel(0u64);
        Arc::new(Shared {
            pool_size: AtomicUsize::new(cfg.pool_size),
            cfg,
            pool: Mutex::new(PoolInner::default()),
            epoch,
            tcp_backoff: Mutex::new(HashMap::new()),
            cf_current: Mutex::new(HashMap::new()),
            front_first: AtomicBool::new(false),
            plain_until: AtomicU64::new(0),
        })
    }

    // ---- Cloudflare transport selection ----------------------------------------------------

    /// Order in which TLS:443 (true) and plain:80 (false) are tried for CF routes.
    fn cf_modes(&self) -> Vec<bool> {
        if self.cfg.no_secure || self.cfg.cf_mode == "plain" {
            return vec![false];
        }
        if self.cfg.cf_mode == "tls" {
            return vec![true];
        }
        if self.plain_until.load(Relaxed) > now_secs() { vec![false, true] } else { vec![true, false] }
    }

    async fn cf_connect(&self, host: &str, path: &str) -> Result<WsConn, WsError> {
        let modes = self.cf_modes();
        let mut last = WsError("no transport".into());
        for (i, secure) in modes.iter().enumerate() {
            match ws::connect(host, host, Duration::from_secs(10), path, None, *secure).await {
                Ok(c) => {
                    if !*secure && i > 0 && self.cfg.cf_mode == "auto" {
                        self.plain_until.store(now_secs() + 600, Relaxed);
                        ldebug!("CF: TLS failed, using plain :80 for 10 min");
                    }
                    return Ok(c);
                }
                Err(e) => last = e,
            }
        }
        Err(last)
    }

    fn cf_domains_for(&self, dc: u32) -> Vec<String> {
        let base = if self.cfg.user_domains.is_empty() { default_cf_domains() } else { self.cfg.user_domains.clone() };
        let current = self.cf_current.lock().ok().and_then(|m| m.get(&dc).cloned());
        let mut rest: Vec<String> = base.into_iter().filter(|d| Some(d) != current.as_ref()).collect();
        rest.shuffle(&mut rand::thread_rng());
        let mut out = Vec::new();
        if let Some(c) = current {
            out.push(c);
        }
        out.extend(rest);
        out
    }

    // ---- direct WebSocket pool -------------------------------------------------------------

    async fn connect_one(&self, ip: &str, domains: &[String], path: &str) -> Option<WsConn> {
        for d in domains {
            let first = self.front_first.load(Relaxed);
            for fronted in [first, !first] {
                let tmo = Duration::from_secs(if fronted { 7 } else { 8 });
                let sni = if fronted { Some(FRONT_SNI) } else { None };
                match ws::connect(ip, d, tmo, path, sni, true).await {
                    Ok(c) => {
                        self.front_first.store(fronted, Relaxed);
                        ldebug!("WS pool connected {d}{path} via {ip} (fronting={fronted})");
                        return Some(c);
                    }
                    Err(e) => {
                        stats::inc(&stats::ERRORS);
                        ldebug!("WS pool connect {d}{path} via {ip} (fronting={fronted}): {e}");
                    }
                }
            }
        }
        None
    }

    fn pool_get(self: &Arc<Self>, key: Key) -> Option<WsConn> {
        if !self.cfg.dc.contains_key(&key.0) || self.pool_size.load(Relaxed) == 0 {
            return None;
        }
        let mut found = None;
        if let Ok(mut p) = self.pool.lock() {
            if let Some(q) = p.idle.get_mut(&key) {
                while let Some((c, t)) = q.pop_front() {
                    if t.elapsed() < POOL_MAX_AGE {
                        found = Some(c);
                        break;
                    }
                }
            }
        }
        if found.is_some() { stats::inc(&stats::POOL_HITS) } else { stats::inc(&stats::POOL_MISSES) }
        self.refill(key);
        found
    }

    fn refill(self: &Arc<Self>, key: Key) {
        let Some(ip) = self.cfg.dc.get(&key.0).cloned() else { return };
        let target = self.pool_size.load(Relaxed);
        if target == 0 {
            return;
        }
        let need = {
            let Ok(mut p) = self.pool.lock() else { return };
            if let Some((_, until)) = p.backoff.get(&key) {
                if Instant::now() < *until {
                    return;
                }
            }
            let have = p.idle.get(&key).map_or(0, |q| q.len());
            let pend = p.pending.get(&key).copied().unwrap_or(0);
            let need = target.saturating_sub(have + pend);
            if need == 0 {
                return;
            }
            *p.pending.entry(key).or_insert(0) += need;
            need
        };
        for _ in 0..need {
            let sh = self.clone();
            let ip = ip.clone();
            tokio::spawn(async move {
                let path = if key.2 { "/apiws_test" } else { "/apiws" };
                let r = sh.connect_one(&ip, &ws_domains(key.0, key.1), path).await;
                let target = sh.pool_size.load(Relaxed);
                if let Ok(mut p) = sh.pool.lock() {
                    if let Some(n) = p.pending.get_mut(&key) {
                        *n = n.saturating_sub(1);
                    }
                    match r {
                        Some(c) => {
                            p.backoff.remove(&key);
                            let q = p.idle.entry(key).or_default();
                            if q.len() < target {
                                q.push_back((c, Instant::now()));
                            }
                        }
                        None => {
                            let f = p.backoff.get(&key).map_or(0, |x| x.0) + 1;
                            let secs = (5u64 * 2u64.pow((f - 1).min(6))).min(300);
                            p.backoff.insert(key, (f, Instant::now() + Duration::from_secs(secs)));
                        }
                    }
                }
            });
        }
    }

    fn warmup(self: &Arc<Self>) {
        for dc in self.cfg.dc.keys() {
            for media in [false, true] {
                self.refill((*dc, media, false));
            }
        }
    }

    fn reset_pools(&self) {
        if let Ok(mut p) = self.pool.lock() {
            p.idle.clear();
            p.backoff.clear();
        }
        if let Ok(mut t) = self.tcp_backoff.lock() {
            t.clear();
        }
    }

    fn drop_stale(&self) {
        if let Ok(mut p) = self.pool.lock() {
            for q in p.idle.values_mut() {
                q.retain(|(_, t)| t.elapsed() < POOL_MAX_AGE);
            }
        }
    }
}

// ---- upstream selection ---------------------------------------------------------------------

enum Upstream {
    Ws { conn: WsConn, splitter: bool },
    Tcp(TcpStream),
}

enum Method {
    Worker,
    Cf,
    Tcp,
}

async fn worker_ws(sh: &Arc<Shared>, label: &str, dc: u32, media: bool, dst: &str) -> Option<WsConn> {
    let path = format!("/apiws?dst={dst}&dc={dc}");
    let tag = if media { " media" } else { "" };
    for wd in &sh.cfg.workers {
        linfo!("[{label}] DC{dc}{tag} -> trying CF worker {wd} for {dst}");
        match sh.cf_connect(wd, &path).await {
            Ok(c) => return Some(c),
            Err(e) => lwarn!("[{label}] DC{dc}{tag} CF worker {wd} failed: {e}"),
        }
    }
    None
}

async fn cf_ws(sh: &Arc<Shared>, label: &str, dc: u32, media: bool) -> Option<WsConn> {
    let tag = if media { " media" } else { "" };
    linfo!("[{label}] DC{dc}{tag} -> trying CF proxy");
    for base in sh.cf_domains_for(dc) {
        let host = format!("kws{dc}.{base}");
        match sh.cf_connect(&host, "/apiws").await {
            Ok(c) => {
                if let Ok(mut m) = sh.cf_current.lock() {
                    m.insert(dc, base);
                }
                return Some(c);
            }
            Err(e) => lwarn!("[{label}] DC{dc}{tag} CF proxy failed: {e}"),
        }
    }
    None
}

async fn tcp_up(sh: &Arc<Shared>, label: &str, dst: &str) -> Option<TcpStream> {
    let key = format!("{dst}:443");
    if let Ok(m) = sh.tcp_backoff.lock() {
        if let Some((_, until)) = m.get(&key) {
            if Instant::now() < *until {
                ldebug!("[{label}] TCP fallback to {key} skipped (backoff)");
                return None;
            }
        }
    }
    linfo!("[{label}] TCP fallback to {key}");
    let r = timeout(Duration::from_secs(10), TcpStream::connect((dst, 443u16))).await;
    match r {
        Ok(Ok(s)) => {
            let _ = s.set_nodelay(true);
            if let Ok(mut m) = sh.tcp_backoff.lock() {
                m.remove(&key);
            }
            Some(s)
        }
        other => {
            let why = match other {
                Ok(Err(e)) => WsError::io(e).0,
                _ => "timeout".into(),
            };
            if let Ok(mut m) = sh.tcp_backoff.lock() {
                let f = m.get(&key).map_or(0, |x| x.0) + 1;
                let secs = (TCP_BACKOFF_INITIAL * 2u64.pow((f - 1).min(7))).min(TCP_BACKOFF_MAX);
                m.insert(key.clone(), (f, Instant::now() + Duration::from_secs(secs)));
                lwarn!("[{label}] TCP fallback to {key} failed: {why}; retry in {secs}s");
            }
            None
        }
    }
}

// ---- client handling ------------------------------------------------------------------------

struct ActiveGuard;
impl ActiveGuard {
    fn new() -> Self {
        stats::inc(&stats::TOTAL);
        stats::inc(&stats::ACTIVE);
        ActiveGuard
    }
}
impl Drop for ActiveGuard {
    fn drop(&mut self) {
        stats::sub1(&stats::ACTIVE);
    }
}

async fn run_client(sh: Arc<Shared>, mut sock: TcpStream, label: String) {
    let _g = ActiveGuard::new();
    let _ = sock.set_nodelay(true);

    let mut hs = [0u8; HANDSHAKE_LEN];
    match timeout(Duration::from_secs(10), sock.read_exact(&mut hs)).await {
        Ok(Ok(_)) => {}
        Ok(Err(_)) => {
            ldebug!("[{label}] client disconnected before handshake");
            return;
        }
        Err(_) => {
            lwarn!("[{label}] timeout during handshake");
            return;
        }
    }
    let Some(h) = obf::try_handshake(&hs, &sh.cfg.secret) else {
        stats::inc(&stats::BAD);
        lwarn!("[{label}] bad handshake (wrong secret or proto)");
        let _ = timeout(Duration::from_secs(10), async {
            let mut left = 64 * 1024usize;
            let mut buf = [0u8; 4096];
            while left > 0 {
                match sock.read(&mut buf[..left.min(4096)]).await {
                    Ok(0) | Err(_) => break,
                    Ok(n) => left -= n,
                }
            }
        })
        .await;
        return;
    };

    let mut dc = h.dc;
    let is_test = sh.cfg.force_test_dc || dc >= 10000;
    if dc >= 10000 {
        linfo!("[{label}] test DC{dc} -> DC{}", dc - 10000);
        dc -= 10000;
    }
    let proto = if h.tag == obf::TAG_ABRIDGED {
        PROTO_ABRIDGED
    } else if h.tag == obf::TAG_INTERMEDIATE {
        PROTO_INTERMEDIATE
    } else {
        PROTO_PADDED
    };
    let media = h.media;
    let dc_idx: i16 = if media { -(dc as i16) } else { dc as i16 };
    let relay_init = obf::generate_relay_init(h.tag, dc_idx);
    let ctx = obf::build_ctx(&h.prekey_iv, &sh.cfg.secret, &relay_init);
    let tag = if media { " media" } else { "" };
    ldebug!("[{label}] handshake ok: DC{dc}{tag} proto=0x{proto:08X}");

    // 1) direct WebSocket from the pool
    if let Some(conn) = sh.pool_get((dc, media, is_test)) {
        if conn.w.send(&relay_init).await.is_ok() {
            linfo!("[{label}] DC{dc}{tag} -> WS pool hit");
            stats::inc(&stats::WS);
            bridge_ws(sock, conn, ctx, Some(MsgSplitter::new(proto)), &label, dc, media).await;
            return;
        }
    }

    // 2) fallbacks
    linfo!("[{label}] DC{dc}{tag} WS pool unavailable -> fallback");
    let dst = dc_default_ip(dc, is_test);
    let mut methods = Vec::new();
    if !sh.cfg.workers.is_empty() && dst.is_some() {
        methods.push(Method::Worker);
    }
    if sh.cfg.cfproxy && !is_test {
        methods.push(Method::Cf);
    }
    if dst.is_some() {
        methods.push(Method::Tcp);
    }
    for m in methods {
        let up = match m {
            Method::Worker => worker_ws(&sh, &label, dc, media, dst.unwrap_or("")).await
                .map(|c| (Upstream::Ws { conn: c, splitter: false }, &stats::CF)),
            Method::Cf => cf_ws(&sh, &label, dc, media).await
                .map(|c| (Upstream::Ws { conn: c, splitter: true }, &stats::CF)),
            Method::Tcp => tcp_up(&sh, &label, dst.unwrap_or("")).await
                .map(|s| (Upstream::Tcp(s), &stats::TCP)),
        };
        let Some((up, counter)) = up else { continue };
        match up {
            Upstream::Ws { conn, splitter } => {
                if conn.w.send(&relay_init).await.is_err() {
                    continue;
                }
                stats::inc(counter);
                let sp = if splitter { Some(MsgSplitter::new(proto)) } else { None };
                bridge_ws(sock, conn, ctx, sp, &label, dc, media).await;
            }
            Upstream::Tcp(mut s) => {
                if s.write_all(&relay_init).await.is_err() {
                    continue;
                }
                stats::inc(counter);
                bridge_tcp(sock, s, ctx, &label).await;
            }
        }
        linfo!("[{label}] DC{dc}{tag} fallback closed");
        return;
    }
    lwarn!("[{label}] DC{dc}{tag} no fallback available");
}

async fn bridge_ws(
    client: TcpStream, conn: WsConn, ctx: CryptoCtx, mut splitter: Option<MsgSplitter>,
    label: &str, dc: u32, media: bool,
) {
    let started = Instant::now();
    let (mut cr, mut cw) = client.into_split();
    let WsConn { r: mut wr, w: ww } = conn;
    let CryptoCtx { mut clt_dec, mut clt_enc, mut tg_enc, mut tg_dec } = ctx;
    let ww_up = ww.clone();

    let up_bytes = Arc::new(AtomicU64::new(0));
    let down_bytes = Arc::new(AtomicU64::new(0));
    let (ub, db) = (up_bytes.clone(), down_bytes.clone());

    let up = async move {
        let mut buf = vec![0u8; 65536];
        loop {
            let n = match cr.read(&mut buf).await {
                Ok(0) | Err(_) => {
                    if let Some(sp) = splitter.as_mut() {
                        let tail = sp.flush();
                        if !tail.is_empty() {
                            let _ = ww_up.send(&tail[0]).await;
                        }
                    }
                    break;
                }
                Ok(n) => n,
            };
            stats::add(&stats::UP, n as u64);
            ub.fetch_add(n as u64, Relaxed);
            let mut plain = buf[..n].to_vec();
            clt_dec.apply(&mut plain);
            let mut chunk = plain.clone();
            tg_enc.apply(&mut chunk);
            let res = match splitter.as_mut() {
                Some(sp) => {
                    let parts = sp.split(&chunk, &plain);
                    if parts.is_empty() { Ok(()) } else { ww_up.send_batch(&parts).await }
                }
                None => ww_up.send(&chunk).await,
            };
            if res.is_err() {
                break;
            }
        }
    };
    let down = async move {
        loop {
            match wr.recv().await {
                Ok(Some(mut data)) => {
                    let n = data.len() as u64;
                    stats::add(&stats::DOWN, n);
                    db.fetch_add(n, Relaxed);
                    tg_dec.apply(&mut data);
                    clt_enc.apply(&mut data);
                    if cw.write_all(&data).await.is_err() {
                        break;
                    }
                }
                _ => break,
            }
        }
    };
    tokio::select! { _ = up => {}, _ = down => {} }
    ww.close().await;
    linfo!(
        "[{label}] DC{dc}{} WS session closed: ^{} v{} in {:.1}s",
        if media { "m" } else { "" },
        human_bytes(up_bytes.load(Relaxed)),
        human_bytes(down_bytes.load(Relaxed)),
        started.elapsed().as_secs_f32()
    );
}

async fn bridge_tcp(client: TcpStream, remote: TcpStream, ctx: CryptoCtx, label: &str) {
    let (mut cr, mut cw) = client.into_split();
    let (mut rr, mut rw) = remote.into_split();
    let CryptoCtx { mut clt_dec, mut clt_enc, mut tg_enc, mut tg_dec } = ctx;
    let up = async move {
        let mut buf = vec![0u8; 65536];
        loop {
            match cr.read(&mut buf).await {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    stats::add(&stats::UP, n as u64);
                    clt_dec.apply(&mut buf[..n]);
                    tg_enc.apply(&mut buf[..n]);
                    if rw.write_all(&buf[..n]).await.is_err() {
                        break;
                    }
                }
            }
        }
    };
    let down = async move {
        let mut buf = vec![0u8; 65536];
        loop {
            match rr.read(&mut buf).await {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    stats::add(&stats::DOWN, n as u64);
                    tg_dec.apply(&mut buf[..n]);
                    clt_enc.apply(&mut buf[..n]);
                    if cw.write_all(&buf[..n]).await.is_err() {
                        break;
                    }
                }
            }
        }
    };
    tokio::select! { _ = up => {}, _ = down => {} }
    ldebug!("[{label}] TCP session closed");
}

fn human_bytes(n: u64) -> String {
    let mut v = n as f64;
    for u in ["B", "KB", "MB", "GB"] {
        if v < 1024.0 {
            return format!("{v:.1}{u}");
        }
        v /= 1024.0;
    }
    format!("{v:.1}TB")
}

// ---- lifecycle ------------------------------------------------------------------------------

struct Running {
    rt: Runtime,
    sh: Arc<Shared>,
    stop: watch::Sender<bool>,
}

static CORE: Mutex<Option<Running>> = Mutex::new(None);
static RUNNING: AtomicBool = AtomicBool::new(false);

async fn accept_loop(listener: TcpListener, sh: Arc<Shared>, mut stop: watch::Receiver<bool>) {
    let mut tick = tokio::time::interval(Duration::from_secs(15));
    loop {
        tokio::select! {
            _ = stop.changed() => break,
            _ = tick.tick() => {
                sh.drop_stale();
                sh.warmup();
            }
            r = listener.accept() => match r {
                Ok((s, peer)) => {
                    let sh2 = sh.clone();
                    let label = format!("{}:{}", peer.ip(), peer.port());
                    let mut ep = sh.epoch.subscribe();
                    tokio::spawn(async move {
                        tokio::select! {
                            _ = run_client(sh2, s, label.clone()) => {}
                            _ = ep.changed() => ldebug!("[{label}] dropped (network changed)"),
                        }
                    });
                }
                Err(e) => {
                    lwarn!("accept failed: {e}");
                    sleep(Duration::from_millis(500)).await;
                }
            }
        }
    }
}

pub fn start(cfg_json: &str) -> String {
    let mut guard = match CORE.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    if guard.is_some() && RUNNING.load(Relaxed) {
        return String::new();
    }
    let cfg = match config::parse(cfg_json) {
        Ok(c) => c,
        Err(e) => return e,
    };
    let verbose = serde_json::from_str::<serde_json::Value>(cfg_json)
        .ok()
        .and_then(|v| v["verbose"].as_bool())
        .unwrap_or(false);
    crate::logbuf::set_verbose(verbose);

    let listener = match std::net::TcpListener::bind((cfg.host.as_str(), cfg.port)) {
        Ok(l) => l,
        Err(e) => return format!("Cannot listen on {}:{}: {}", cfg.host, cfg.port, WsError::io(e)),
    };
    if let Err(e) = listener.set_nonblocking(true) {
        return format!("listener: {e}");
    }
    let rt = match tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build() {
        Ok(r) => r,
        Err(e) => return format!("runtime: {e}"),
    };

    linfo!("tg-ws-proxy rust core {} starting on {}:{}", env!("CARGO_PKG_VERSION"), cfg.host, cfg.port);
    for (dc, ip) in &cfg.dc {
        linfo!("  DC{dc}: {ip}");
    }
    if cfg.cfproxy {
        linfo!("  CF proxy: enabled (mode {})", cfg.cf_mode);
    }
    if !cfg.workers.is_empty() {
        linfo!("  CF worker: {}", cfg.workers.join(", "));
    }

    let sh = Shared::new(cfg);
    let (stop_tx, stop_rx) = watch::channel(false);
    {
        let _enter = rt.enter();
        let l = match TcpListener::from_std(listener) {
            Ok(l) => l,
            Err(e) => return format!("listener: {e}"),
        };
        sh.warmup();
        tokio::spawn(accept_loop(l, sh.clone(), stop_rx));
    }
    RUNNING.store(true, Relaxed);
    *guard = Some(Running { rt, sh, stop: stop_tx });
    String::new()
}

pub fn stop() {
    let r = {
        let mut guard = match CORE.lock() {
            Ok(g) => g,
            Err(p) => p.into_inner(),
        };
        guard.take()
    };
    RUNNING.store(false, Relaxed);
    if let Some(r) = r {
        let _ = r.stop.send(true);
        r.rt.shutdown_timeout(Duration::from_secs(2));
        linfo!("Proxy stopped");
    }
}

pub fn is_running() -> bool {
    RUNNING.load(Relaxed)
}

pub fn network_changed(hard: bool) {
    let guard = match CORE.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    let Some(r) = guard.as_ref() else { return };
    let sh = r.sh.clone();
    r.rt.spawn(async move {
        sh.reset_pools();
        if hard {
            let next = *sh.epoch.borrow() + 1;
            let _ = sh.epoch.send(next);
        }
        sh.warmup();
        linfo!("Network {}: pools reset{}", if hard { "changed" } else { "blip" },
            if hard { ", sessions dropped" } else { "" });
    });
}

pub fn set_tuning(pool: usize) {
    let guard = match CORE.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    let Some(r) = guard.as_ref() else { return };
    r.sh.pool_size.store(pool.clamp(1, 64), Relaxed);
    let sh = r.sh.clone();
    r.rt.spawn(async move {
        sh.drop_stale();
        sh.warmup();
    });
}
