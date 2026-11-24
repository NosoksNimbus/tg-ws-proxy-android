//! Listener, upstream pool, fallback chain and the re-encrypting bridges.
use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, AtomicUsize, Ordering::Relaxed};
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
use crate::ws::{self, Stream, WsConn, WsError};

type Key = (u32, bool, bool); // (dc, media, test)

const POOL_MAX_AGE: Duration = Duration::from_secs(60);
const TCP_BACKOFF_INITIAL: u64 = 30;
const TCP_BACKOFF_MAX: u64 = 3600;
const FRONT_SNI: &str = "sprinthost.ru";
const CF_LIST_HOST: &str = "raw.githubusercontent.com";
const CF_LIST_PATH: &str = "/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt";
const CF_MIN_VALID: usize = 3;

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
    cf_pool: Mutex<Vec<String>>,
    h2: Arc<crate::h2lane::H2Pool>,
    sticky: Mutex<HashMap<u32, (Method, Instant)>>,
    front_first: AtomicBool,
    /// (domain, tls) -> do not retry before: CF handshake timed out recently.
    cf_timeout_until: Mutex<HashMap<(String, bool), Instant>>,
    cf_tls_ok: AtomicU32,
    cf_plain_ok: AtomicU32,
    /// Worker domain -> disabled until (daily limit, 5xx, network errors).
    worker_until: Mutex<HashMap<String, Instant>>,
    /// Prewarmed CF Worker sockets per DC.
    wpool: Mutex<WPool>,
}

#[derive(Default)]
struct WPool {
    idle: HashMap<u32, VecDeque<(WsConn, Instant)>>,
    pending: HashMap<u32, usize>,
    backoff: HashMap<u32, (u32, Instant)>,
}

const WPOOL_MAX_AGE: Duration = Duration::from_secs(100);
const WPOOL_PER_DC: usize = 1;
const WPOOL_BACKOFF_MAX: u64 = 3600;
const WORKER_INIT_TIMEOUT: Duration = Duration::from_secs(10);

const CF_TIMEOUT_COOLDOWN: Duration = Duration::from_secs(30);
const CF_CONNECT_TIMEOUTS: [u64; 5] = [3, 3, 5, 5, 10];
const WORKER_FAILURE_COOLDOWN: Duration = Duration::from_secs(60);

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
            cf_pool: Mutex::new(Vec::new()),
            h2: Arc::new(crate::h2lane::H2Pool::new()),
            sticky: Mutex::new(HashMap::new()),
            front_first: AtomicBool::new(false),
            cf_timeout_until: Mutex::new(HashMap::new()),
            cf_tls_ok: AtomicU32::new(0),
            cf_plain_ok: AtomicU32::new(0),
            wpool: Mutex::new(WPool::default()),
            worker_until: Mutex::new(HashMap::new()),
        })
    }

    /// Downloads the community list of Cloudflare-proxied domains (unless the user set their own).
    async fn refresh_cf_domains(&self) {
        if !self.cfg.user_domains.is_empty() {
            return;
        }
        let path = format!("{CF_LIST_PATH}?{}", rand::random::<u32>());
        match ws::https_get(CF_LIST_HOST, &path, Duration::from_secs(10)).await {
            Ok(text) => {
                let items: Vec<String> = text
                    .lines()
                    .map(str::trim)
                    .filter(|l| !l.is_empty() && !l.starts_with('#'))
                    .map(decode_domain)
                    .collect();
                let pool = coerce_domains(&items);
                if pool.len() >= CF_MIN_VALID {
                    linfo!("CF proxy domain pool updated from GitHub ({} domains)", pool.len());
                    if let Ok(mut g) = self.cf_pool.lock() {
                        *g = pool;
                    }
                } else {
                    lwarn!("Ignoring fetched CF proxy domains (total={}, valid={}, need>={}); keeping current pool",
                        items.len(), pool.len(), CF_MIN_VALID);
                }
            }
            Err(e) => lwarn!("CF proxy domain refresh failed ({e}); keeping current pool"),
        }
    }

    // ---- Cloudflare transport selection ----------------------------------------------------

    /// TLS has worked at least as often as plain :80 on this network.
    fn cf_prefers_tls(&self) -> bool {
        self.cf_tls_ok.load(Relaxed) >= self.cf_plain_ok.load(Relaxed)
    }

    fn cf_cooling(&self, name: &str, tls: bool) -> bool {
        self.cf_timeout_until
            .lock()
            .ok()
            .and_then(|m| m.get(&(name.to_string(), tls)).cloned())
            .map_or(false, |t| Instant::now() < t)
    }

    /// Connects to a Cloudflare host over TLS:443 or plain:80. A timed-out variant is paused for
    /// 30 s and the other one is tried; successes decide which variant goes first next time.
    async fn cf_connect(&self, name: &str, host: &str, path: &str) -> Result<WsConn, WsError> {
        let (allow_tls, allow_plain) = if self.cfg.no_secure || self.cfg.cf_mode == "plain" {
            (false, true)
        } else if self.cfg.cf_mode == "tls" {
            (true, false)
        } else {
            (true, true)
        };
        let order: Vec<bool> = match (allow_tls, allow_plain) {
            (true, true) => {
                let first = self.cf_prefers_tls();
                vec![first, !first]
            }
            (true, false) => vec![true],
            _ => vec![false],
        };
        let mut last = WsError("cooling down after timeout".into());
        let mut timeouts = 0usize;
        for secure in order {
            if self.cf_cooling(name, secure) {
                continue;
            }
            let tmo = Duration::from_secs(CF_CONNECT_TIMEOUTS[timeouts.min(CF_CONNECT_TIMEOUTS.len() - 1)]);
            match ws::connect(host, host, tmo, path, None, secure).await {
                Ok(c) => {
                    if let Ok(mut m) = self.cf_timeout_until.lock() {
                        m.remove(&(name.to_string(), secure));
                    }
                    if secure { &self.cf_tls_ok } else { &self.cf_plain_ok }.fetch_add(1, Relaxed);
                    return Ok(c);
                }
                Err(e) => {
                    if e.0 == "timeout" {
                        timeouts += 1;
                        if let Ok(mut m) = self.cf_timeout_until.lock() {
                            let now = Instant::now();
                            m.retain(|_, t| *t + CF_TIMEOUT_COOLDOWN > now);
                            m.insert((name.to_string(), secure), now + CF_TIMEOUT_COOLDOWN);
                        }
                        lwarn!("CF WebSocket timed out (host={host} tls={secure}); retry in 30s");
                    }
                    last = e;
                }
            }
        }
        Err(last)
    }

    /// Connects to one of the available worker domains (shuffled).
    async fn worker_connect(&self, dc: u32, dst: &str) -> Option<WsConn> {
        let mut ds = self.workers_available();
        ds.shuffle(&mut rand::thread_rng());
        let path = format!("/apiws?dst={dst}&dc={dc}");
        for wd in &ds {
            match self.cf_connect(wd, wd, &path).await {
                Ok(c) => return Some(c),
                Err(e) => {
                    lwarn!("CF worker {wd} failed: {e}");
                    self.worker_failed(wd, &e);
                }
            }
        }
        None
    }

    fn wpool_get(self: &Arc<Self>, dc: u32) -> Option<WsConn> {
        let mut hit = None;
        if let Ok(mut p) = self.wpool.lock() {
            if let Some(q) = p.idle.get_mut(&dc) {
                while let Some((c, t)) = q.pop_front() {
                    if t.elapsed() < WPOOL_MAX_AGE {
                        hit = Some(c);
                        break;
                    }
                }
            }
        }
        self.wrefill(dc);
        hit
    }

    fn wrefill(self: &Arc<Self>, dc: u32) {
        if self.cfg.workers.is_empty() || self.pool_size.load(Relaxed) == 0 {
            return;
        }
        let Some(dst) = dc_default_ip(dc, false) else { return };
        if self.workers_available().is_empty() {
            return;
        }
        {
            let Ok(mut p) = self.wpool.lock() else { return };
            let have = p.idle.get(&dc).map_or(0, |q| q.len()) + p.pending.get(&dc).copied().unwrap_or(0);
            if have >= WPOOL_PER_DC {
                return;
            }
            if p.backoff.get(&dc).map_or(false, |(_, t)| Instant::now() < *t) {
                return;
            }
            *p.pending.entry(dc).or_insert(0) += 1;
        }
        let sh = self.clone();
        let dst = dst.to_string();
        tokio::spawn(async move {
            let r = tokio::time::timeout(Duration::from_secs(20), sh.worker_connect(dc, &dst)).await.ok().flatten();
            if let Ok(mut p) = sh.wpool.lock() {
                if let Some(n) = p.pending.get_mut(&dc) {
                    *n = n.saturating_sub(1);
                }
                match r {
                    Some(c) => {
                        p.backoff.remove(&dc);
                        p.idle.entry(dc).or_default().push_back((c, Instant::now()));
                    }
                    None => {
                        let n = p.backoff.get(&dc).map_or(0, |b| b.0) + 1;
                        let secs = (1u64 << (n - 1).min(12)).min(WPOOL_BACKOFF_MAX);
                        p.backoff.insert(dc, (n, Instant::now() + Duration::from_secs(secs)));
                    }
                }
            }
        });
    }

    /// Drops stale worker sockets and keeps one warm per DC that has no direct route.
    fn wpool_rotate(self: &Arc<Self>) {
        if let Ok(mut p) = self.wpool.lock() {
            for q in p.idle.values_mut() {
                q.retain(|(_, t)| t.elapsed() < WPOOL_MAX_AGE);
            }
        }
        for dc in 1..=5u32 {
            if !self.cfg.dc.contains_key(&dc) {
                self.wrefill(dc);
            }
        }
    }

    fn workers_available(&self) -> Vec<String> {
        let now = Instant::now();
        let m = self.worker_until.lock().ok();
        let mut out: Vec<String> = Vec::new();
        for d in &self.cfg.workers {
            if out.contains(d) {
                continue;
            }
            if m.as_ref().and_then(|m| m.get(d)).map_or(false, |t| *t > now) {
                continue;
            }
            out.push(d.clone());
        }
        out
    }

    /// Pauses a worker domain after a daily-limit (429), 5xx or network failure.
    fn worker_failed(&self, domain: &str, e: &WsError) {
        let now = Instant::now();
        let (until, why) = if e.0.starts_with("HTTP 429") {
            (now + Duration::from_secs(86400 - now_secs() % 86400), "daily request limit".to_string())
        } else if e.0.starts_with("HTTP 5") {
            (now + WORKER_FAILURE_COOLDOWN, e.0.chars().take(40).collect())
        } else if e.0.starts_with("HTTP ") {
            return; // challenge / misconfigured route: not a reason to disable the whole domain
        } else {
            (now + WORKER_FAILURE_COOLDOWN, e.0.chars().take(40).collect())
        };
        if let Ok(mut m) = self.worker_until.lock() {
            if m.get(domain).map_or(false, |t| *t > now) {
                return;
            }
            m.insert(domain.to_string(), until);
            lwarn!("CF worker {domain} disabled for {}s: {why}", (until - now).as_secs().max(1));
        }
    }

    fn cf_domains_for(&self, dc: u32) -> Vec<String> {
        let base = if !self.cfg.user_domains.is_empty() {
            self.cfg.user_domains.clone()
        } else {
            let p = self.cf_pool.lock().map(|g| g.clone()).unwrap_or_default();
            if p.is_empty() { default_cf_domains() } else { p }
        };
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

    async fn connect_one(self: &Arc<Self>, ip: &str, domains: &[String], path: &str) -> Option<WsConn> {
        let first = self.front_first.load(Relaxed);
        let mut combos: Vec<(String, bool)> = Vec::new();
        for d in domains {
            combos.push((d.clone(), first));
            combos.push((d.clone(), !first));
        }
        let n = combos.len();
        let (sh, ip, path) = (self.clone(), ip.to_string(), path.to_string());
        let combos = Arc::new(combos);
        // Happy-eyeballs style: the next variant starts 400 ms after the previous one.
        race(n, Duration::from_millis(400), move |i| {
            let (sh, ip, path, combos) = (sh.clone(), ip.clone(), path.clone(), combos.clone());
            async move {
                let (d, fronted) = &combos[i];
                let sni = if *fronted { Some(FRONT_SNI) } else { None };
                match ws::connect(&ip, d, Duration::from_secs(5), &path, sni, true).await {
                    Ok(c) => {
                        sh.front_first.store(*fronted, Relaxed);
                        ldebug!("WS pool connected {d}{path} via {ip} (fronting={fronted})");
                        Some(c)
                    }
                    Err(e) => {
                        stats::inc(&stats::ERRORS);
                        ldebug!("WS pool connect {d}{path} via {ip} (fronting={fronted}): {e}");
                        None
                    }
                }
            }
        })
        .await
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
        self.wpool_rotate();
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
        if let Ok(mut p) = self.wpool.lock() {
            p.idle.clear();
            p.backoff.clear();
        }
        if let Ok(mut t) = self.tcp_backoff.lock() {
            t.clear();
        }
        if let Ok(mut t) = self.sticky.lock() {
            t.clear();
        }
        if let Ok(mut t) = self.cf_timeout_until.lock() {
            t.clear();
        }
        self.cf_tls_ok.store(0, Relaxed);
        self.cf_plain_ok.store(0, Relaxed);
    }

    fn note_ok(&self, dc: u32, m: Method) {
        if let Ok(mut s) = self.sticky.lock() {
            s.insert(dc, (m, Instant::now()));
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

/// Starts `n` attempts staggered by `stagger` and returns the first success (rest are cancelled).
async fn race<T, F, Fut>(n: usize, stagger: Duration, f: F) -> Option<T>
where
    T: Send + 'static,
    F: Fn(usize) -> Fut,
    Fut: std::future::Future<Output = Option<T>> + Send + 'static,
{
    let mut set = tokio::task::JoinSet::new();
    for i in 0..n {
        let fut = f(i);
        set.spawn(async move {
            if i > 0 {
                sleep(stagger * i as u32).await;
            }
            fut.await
        });
    }
    while let Some(r) = set.join_next().await {
        if let Ok(Some(v)) = r {
            return Some(v);
        }
    }
    None
}

// ---- upstream selection ---------------------------------------------------------------------

enum Upstream {
    Ws { conn: WsConn, splitter: bool },
    Tcp(TcpStream),
}

#[derive(Clone, Copy, PartialEq)]
enum Method {
    Worker,
    Cf,
    Tcp,
}

async fn worker_ws(sh: &Arc<Shared>, label: &str, dc: u32, media: bool, dst: &str, is_test: bool) -> Option<WsConn> {
    let tag = if media { " media" } else { "" };
    if !is_test {
        if let Some(c) = sh.wpool_get(dc) {
            linfo!("[{label}] DC{dc}{tag} -> CF worker pool hit");
            return Some(c);
        }
    }
    linfo!("[{label}] DC{dc}{tag} -> trying CF worker for {dst}");
    tokio::time::timeout(Duration::from_secs(10), sh.worker_connect(dc, dst)).await.ok().flatten()
}

async fn cf_ws(sh: &Arc<Shared>, label: &str, dc: u32, media: bool) -> Option<WsConn> {
    let tag = if media { " media" } else { "" };
    linfo!("[{label}] DC{dc}{tag} -> trying CF proxy");
    let domains: Vec<String> = sh.cf_domains_for(dc).into_iter().take(4).collect();
    let n = domains.len();
    let domains = Arc::new(domains);
    let (shc, label_s) = (sh.clone(), label.to_string());
    race(n, Duration::from_millis(600), move |i| {
        let (sh, domains, label) = (shc.clone(), domains.clone(), label_s.clone());
        async move {
            let base = domains[i].clone();
            let host = format!("kws{dc}.{base}");
            match sh.cf_connect(&base, &host, "/apiws").await {
                Ok(c) => {
                    if let Ok(mut m) = sh.cf_current.lock() {
                        m.insert(dc, base);
                    }
                    Some(c)
                }
                Err(e) => {
                    lwarn!("[{label}] DC{dc} CF proxy failed: {e}");
                    None
                }
            }
        }
    })
    .await
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
    linfo!("[{label}] TCP fallback to {dst} (ports 443/80/5222)");
    let dsts = dst.to_string();
    let r: Result<Result<TcpStream, std::io::Error>, ()> = match race(TG_TCP_PORTS.len(), Duration::from_millis(300), move |i| {
        let d = dsts.clone();
        async move {
            let port = TG_TCP_PORTS[i];
            match timeout(Duration::from_secs(5), TcpStream::connect((d.as_str(), port))).await {
                Ok(Ok(s)) => Some(s),
                _ => None,
            }
        }
    })
    .await
    {
        Some(s) => Ok(Ok(s)),
        None => Err(()),
    };
    match r {
        Ok(Ok(s)) => {
            let _ = s.set_nodelay(true);
            if let Ok(mut m) = sh.tcp_backoff.lock() {
                m.remove(&key);
            }
            Some(s)
        }
        _ => {
            let why = "blocked on 443/80/5222";
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

    if sh.cfg.proxy_protocol {
        // PROXY protocol v1: "PROXY TCP4 src dst sport dport\r\n" (max 107 bytes)
        let mut line = Vec::with_capacity(108);
        loop {
            let mut b = [0u8; 1];
            match timeout(Duration::from_secs(10), sock.read_exact(&mut b)).await {
                Ok(Ok(_)) => line.push(b[0]),
                _ => return,
            }
            if line.ends_with(b"\r\n") {
                break;
            }
            if line.len() > 107 {
                lwarn!("[{label}] invalid PROXY protocol header");
                return;
            }
        }
        if !line.starts_with(b"PROXY ") {
            lwarn!("[{label}] missing PROXY protocol header");
            return;
        }
    }

    let mut hs = [0u8; HANDSHAKE_LEN];
    let fake = !sh.cfg.fake_tls_domain.is_empty();
    let client: Stream;
    if fake {
        let mut first = [0u8; 1];
        match timeout(Duration::from_secs(10), sock.read_exact(&mut first)).await {
            Ok(Ok(_)) => {}
            _ => {
                ldebug!("[{label}] client disconnected before handshake");
                return;
            }
        }
        match crate::fake_tls::accept(sock, first[0], &sh.cfg.secret, &sh.cfg.fake_tls_domain, &label).await {
            crate::fake_tls::Accepted::Tls(init, st) => {
                hs = init;
                client = Box::new(st);
            }
            crate::fake_tls::Accepted::Done => return,
        }
    } else {
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
        client = Box::new(sock);
    }
    let mut sock = client;
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
    // try first whatever worked last time for this DC (a blocked direct path costs seconds)
    let sticky = sh.sticky.lock().ok().and_then(|m| m.get(&dc).cloned())
        .filter(|(_, t)| t.elapsed() < Duration::from_secs(600)).map(|(m, _)| m);
    methods.sort_by_key(|m| if Some(*m) == sticky { 0 } else { 1 });
    for m in methods {
        // fast lane for media: HTTP/2 POSTs through Cloudflare instead of a WebSocket
        if matches!(m, Method::Cf) && media && sh.cfg.h2_media && sh.cf_prefers_tls() {
            let domains: Vec<String> = sh.cf_domains_for(dc).into_iter().filter(|d| !sh.cf_cooling(d, true)).collect();
            if let Some(ch) = sh.h2.open(dc, &domains, &label).await {
                stats::inc(&stats::CF);
                sh.note_ok(dc, Method::Cf);
                crate::h2lane::bridge(sock, ch, ctx, h.tag, sh.h2.clone(), &label).await;
                linfo!("[{label}] DC{dc}{tag} fallback closed");
                return;
            }
        }
        let up = match m {
            Method::Worker => worker_ws(&sh, &label, dc, media, dst.unwrap_or(""), is_test).await
                .map(|c| (Upstream::Ws { conn: c, splitter: false }, &stats::CF)),
            Method::Cf => cf_ws(&sh, &label, dc, media).await
                .map(|c| (Upstream::Ws { conn: c, splitter: true }, &stats::CF)),
            Method::Tcp => tcp_up(&sh, &label, dst.unwrap_or("")).await
                .map(|s| (Upstream::Tcp(s), &stats::TCP)),
        };
        let Some((up, counter)) = up else { continue };
        sh.note_ok(dc, m);
        match up {
            Upstream::Ws { conn, splitter } => {
                let sent = tokio::time::timeout(WORKER_INIT_TIMEOUT, conn.w.send(&relay_init)).await;
                if !matches!(sent, Ok(Ok(()))) {
                    lwarn!("[{label}] DC{dc}{tag} init frame failed");
                    conn.w.close().await;
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
    client: Stream, conn: WsConn, ctx: CryptoCtx, mut splitter: Option<MsgSplitter>,
    label: &str, dc: u32, media: bool,
) {
    let started = Instant::now();
    let (mut cr, mut cw) = tokio::io::split(client);
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

async fn bridge_tcp(client: Stream, remote: TcpStream, ctx: CryptoCtx, label: &str) {
    let (mut cr, mut cw) = tokio::io::split(client);
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
        if sh.cfg.user_domains.is_empty() && sh.cfg.cfproxy {
            let shr = sh.clone();
            tokio::spawn(async move {
                loop {
                    shr.refresh_cf_domains().await;
                    sleep(Duration::from_secs(3600)).await;
                }
            });
        }
        crate::fake_tls::set_profiled_len(None);
        let shl = sh.clone();
        tokio::spawn(async move {
            // measure the masking site before accepting clients, like the Python core
            if !shl.cfg.fake_tls_domain.is_empty() {
                let d = shl.cfg.fake_tls_domain.clone();
                linfo!("  Fake TLS: {d}");
                let len = crate::fake_tls::probe_appdata_len(&d).await;
                crate::fake_tls::set_profiled_len(len);
                match len {
                    Some(l) => linfo!("Fake TLS profile: first encrypted record {l} bytes"),
                    None => lwarn!("Fake TLS profile unavailable, ServerHello uses random record sizes"),
                }
            }
            accept_loop(l, shl, stop_rx).await;
        });
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
