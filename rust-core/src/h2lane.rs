//! HTTP/2 media lane: instead of a WebSocket, every MTProto packet of a media connection is sent
//! as `POST https://kws<dc>.<cf-domain>/api` over one multiplexed HTTP/2 connection per host, and
//! the response body is the reply packet. This is the fast path for big media downloads.
use std::collections::HashMap;
use std::collections::{HashSet, VecDeque};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use bytes::Bytes;
use h2::client::{self, SendRequest};
use http::{Method, Request};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, Semaphore};
use tokio::time::{sleep, timeout};
use tokio_rustls::rustls::pki_types::ServerName;
use tokio_rustls::rustls::{ClientConfig, RootCertStore};
use tokio_rustls::TlsConnector;

use crate::obf::{Ctr, CryptoCtx, TAG_ABRIDGED, TAG_SECURE};
use crate::stats;
use crate::ws::Stream;

const MAX_PACKET: usize = 4 * 1024 * 1024;
const MAX_PLAIN_PADDING: usize = 256;
const MAX_CHANNEL_REQUESTS: usize = 8;
const DOMAIN_COOLDOWN: Duration = Duration::from_secs(30);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(65);
const SETUP_TIMEOUT: Duration = Duration::from_secs(5);

struct Lane {
    send: SendRequest<Bytes>,
}

#[derive(Default)]
pub struct H2Pool {
    lanes: tokio::sync::Mutex<HashMap<String, Lane>>,
    failed_until: Mutex<HashMap<String, Instant>>,
    setup_retry_after: Mutex<HashMap<u32, Instant>>,
}

pub struct Channel {
    send: SendRequest<Bytes>,
    host: String,
}

fn tls_h2() -> Arc<ClientConfig> {
    static C: OnceLock<Arc<ClientConfig>> = OnceLock::new();
    C.get_or_init(|| {
        let mut roots = RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        let mut c = ClientConfig::builder_with_provider(Arc::new(tokio_rustls::rustls::crypto::ring::default_provider()))
            .with_safe_default_protocol_versions()
            .expect("tls versions")
            .with_root_certificates(roots)
            .with_no_client_auth();
        c.alpn_protocols = vec![b"h2".to_vec()];
        Arc::new(c)
    })
    .clone()
}

#[derive(Debug)]
enum ReqErr {
    /// Error code the native client must receive (e.g. -404).
    Transport(i32),
    Other(String),
}

impl H2Pool {
    pub fn new() -> H2Pool {
        H2Pool::default()
    }

    async fn connect(host: &str) -> Result<SendRequest<Bytes>, String> {
        let tcp = TcpStream::connect((host, 443)).await.map_err(|e| e.to_string())?;
        let _ = tcp.set_nodelay(true);
        let name = ServerName::try_from(host.to_string()).map_err(|e| e.to_string())?;
        let tls = TlsConnector::from(tls_h2()).connect(name, tcp).await.map_err(|e| e.to_string())?;
        if tls.get_ref().1.alpn_protocol() != Some(b"h2") {
            return Err("server did not negotiate HTTP/2".into());
        }
        let (mut send, conn) = client::Builder::new()
            .initial_window_size(4 * 1024 * 1024)
            .initial_connection_window_size(16 * 1024 * 1024)
            .handshake::<_, Bytes>(tls)
            .await
            .map_err(|e| e.to_string())?;
        tokio::spawn(async move {
            let _ = conn.await;
        });
        // preflight: HEAD /api must answer 2xx/405/501 over HTTP/2
        let req = Request::builder().method(Method::HEAD).uri(format!("https://{host}/api")).body(()).map_err(|e| e.to_string())?;
        let ready = send.clone().ready().await.map_err(|e| e.to_string())?;
        let mut ready = ready;
        let (resp, _) = ready.send_request(req, true).map_err(|e| e.to_string())?;
        let resp = resp.await.map_err(|e| e.to_string())?;
        let st = resp.status().as_u16();
        if !((200..300).contains(&st) || st == 405 || st == 501) {
            return Err(format!("preflight HTTP {st}"));
        }
        Ok(send)
    }

    /// Opens a channel for DC media through the first Cloudflare domain that works.
    pub async fn open(&self, dc: u32, domains: &[String], label: &str) -> Option<Channel> {
        if let Some(t) = self.setup_retry_after.lock().ok().and_then(|m| m.get(&dc).cloned()) {
            if Instant::now() < t {
                return None;
            }
        }
        match timeout(SETUP_TIMEOUT, self.open_inner(dc, domains, label)).await {
            Ok(c) => c,
            Err(_) => {
                if let Ok(mut m) = self.setup_retry_after.lock() {
                    m.insert(dc, Instant::now() + DOMAIN_COOLDOWN);
                }
                stats::inc(&stats::ERRORS);
                lwarn!("[{label}] H2 DC{dc} setup timed out; using WS for 30s");
                None
            }
        }
    }

    async fn open_inner(&self, dc: u32, domains: &[String], label: &str) -> Option<Channel> {
        for d in domains {
            let host = format!("kws{dc}.{d}");
            let cooling = self.failed_until.lock().ok().and_then(|m| m.get(&host).cloned()).map_or(false, |t| Instant::now() < t);
            if cooling {
                continue;
            }
            let mut lanes = self.lanes.lock().await;
            if let Some(l) = lanes.get(&host) {
                let mut s = l.send.clone();
                if futures_ready(&mut s).await {
                    linfo!("[{label}] ROUTE DC{dc} media -> H2 host={host}");
                    return Some(Channel { send: l.send.clone(), host });
                }
                lanes.remove(&host);
            }
            match Self::connect(&host).await {
                Ok(send) => {
                    linfo!("[{label}] ROUTE DC{dc} media -> H2 host={host} (new lane)");
                    lanes.insert(host.clone(), Lane { send: send.clone() });
                    return Some(Channel { send, host });
                }
                Err(e) => {
                    stats::inc(&stats::ERRORS);
                    if let Ok(mut m) = self.failed_until.lock() {
                        m.insert(host.clone(), Instant::now() + DOMAIN_COOLDOWN);
                    }
                    lwarn!("[{label}] H2 preflight failed host={host}: {e}");
                }
            }
        }
        ldebug!("[{label}] H2 DC{dc} unavailable; using WS");
        None
    }

    pub fn forget(&self, host: &str) {
        if let Ok(mut m) = self.failed_until.lock() {
            m.insert(host.to_string(), Instant::now() + DOMAIN_COOLDOWN);
        }
    }
}

async fn futures_ready(s: &mut SendRequest<Bytes>) -> bool {
    matches!(timeout(Duration::from_secs(2), std::future::poll_fn(|cx| s.poll_ready(cx))).await, Ok(Ok(())))
}

async fn post(send: &SendRequest<Bytes>, host: &str, body: Vec<u8>, rec: &AtomicBool, progress: &Mutex<Instant>) -> Result<Vec<u8>, ReqErr> {
    let run = async {
        let req = Request::builder()
            .method(Method::POST)
            .uri(format!("https://{host}/api"))
            .header("content-type", "application/octet-stream")
            .header("accept-encoding", "identity")
            .body(())
            .map_err(|e| ReqErr::Other(e.to_string()))?;
        let mut s = send.clone().ready().await.map_err(|e| ReqErr::Other(e.to_string()))?;
        let (resp, mut stream) = s.send_request(req, false).map_err(|e| ReqErr::Other(e.to_string()))?;
        stream.send_data(Bytes::from(body), true).map_err(|e| ReqErr::Other(e.to_string()))?;
        let resp = resp.await.map_err(|e| ReqErr::Other(e.to_string()))?;
        let st = resp.status().as_u16();
        if matches!(st, 403 | 404 | 429 | 444) {
            return Err(ReqErr::Transport(-(st as i32)));
        }
        if st != 200 {
            return Err(ReqErr::Other(format!("CF /api HTTP {st}")));
        }
        if resp.headers().get("content-type").and_then(|v| v.to_str().ok()).map_or(false, |v| v.to_ascii_lowercase().contains("text/html")) {
            return Err(ReqErr::Other("CF /api returned HTML instead of MTProto".into()));
        }
        rec.store(true, Ordering::Relaxed);
        touch(progress);
        let mut body = resp.into_body();
        let mut out: Vec<u8> = Vec::new();
        while let Some(chunk) = body.data().await {
            let chunk = chunk.map_err(|e| ReqErr::Other(e.to_string()))?;
            let _ = body.flow_control().release_capacity(chunk.len());
            if out.len() + chunk.len() > MAX_PACKET {
                return Err(ReqErr::Other("H2 response exceeds native packet bound".into()));
            }
            out.extend_from_slice(&chunk);
            touch(progress);
        }
        if !out.is_empty() && out.len() % 4 != 0 {
            return Err(ReqErr::Other("CF /api response is not an aligned MTProto packet".into()));
        }
        if out.len() == 4 {
            let code = i32::from_le_bytes([out[0], out[1], out[2], out[3]]);
            if code < 0 {
                return Err(ReqErr::Transport(code));
            }
        }
        Ok(out)
    };
    timeout(REQUEST_TIMEOUT, run).await.unwrap_or(Err(ReqErr::Other("timeout".into())))
}

fn encode_reply(body: &[u8], tag: [u8; 4]) -> Option<Vec<u8>> {
    if body.is_empty() || body.len() % 4 != 0 || body.len() > MAX_PACKET {
        return None;
    }
    if tag == TAG_ABRIDGED {
        let words = body.len() / 4;
        let mut v = if words < 0x7f { vec![words as u8] } else { let mut p = vec![0x7f]; p.extend_from_slice(&(words as u32).to_le_bytes()[..3]); p };
        v.extend_from_slice(body);
        return Some(v);
    }
    let pad = if tag == TAG_SECURE { (rand::random::<u8>() % 4) as usize } else { 0 };
    let mut v = ((body.len() + pad) as u32).to_le_bytes().to_vec();
    v.extend_from_slice(body);
    v.extend(std::iter::repeat_with(rand::random::<u8>).take(pad));
    Some(v)
}

/// Reads one native MTProto packet from the (obfuscated) client stream.
async fn read_packet<R: AsyncReadExt + Unpin>(r: &mut R, ctx: &mut Ctr, tag: [u8; 4]) -> Result<Vec<u8>, String> {
    async fn plain<R: AsyncReadExt + Unpin>(r: &mut R, ctx: &mut Ctr, n: usize) -> Result<Vec<u8>, String> {
        let mut b = vec![0u8; n];
        r.read_exact(&mut b).await.map_err(|e| e.to_string())?;
        ctx.apply(&mut b);
        Ok(b)
    }
    let length;
    if tag == TAG_ABRIDGED {
        let first = plain(r, ctx, 1).await?[0];
        let mut words = (first & 0x7f) as usize;
        if words == 0x7f {
            let b = plain(r, ctx, 3).await?;
            words = u32::from_le_bytes([b[0], b[1], b[2], 0]) as usize;
        }
        length = words * 4;
    } else {
        let b = plain(r, ctx, 4).await?;
        length = (u32::from_le_bytes([b[0], b[1], b[2], b[3]]) & 0x7fff_ffff) as usize;
    }
    let max = MAX_PACKET + if tag == TAG_SECURE { MAX_PLAIN_PADDING } else { 0 };
    if !(24..=max).contains(&length) {
        return Err(format!("native packet length outside supported range: {length}"));
    }
    let mut body = plain(r, ctx, length).await?;
    if tag == TAG_SECURE {
        let (packet_len, max_pad) = if body[..8] == [0u8; 8] {
            (20 + u32::from_le_bytes([body[16], body[17], body[18], body[19]]) as usize, MAX_PLAIN_PADDING)
        } else {
            (24 + ((length - 24) / 16) * 16, 15)
        };
        if packet_len < 24 || packet_len > length || length - packet_len > max_pad {
            return Err("invalid padded native packet".into());
        }
        body.truncate(packet_len);
    }
    if body.len() % 4 != 0 || body.len() > MAX_PACKET {
        return Err("invalid native packet alignment or size".into());
    }
    Ok(body)
}

fn touch(p: &Mutex<Instant>) {
    if let Ok(mut g) = p.lock() {
        *g = Instant::now();
    }
}

// ---- replay recovery ----------------------------------------------------------------------
// MTProto over HTTP only receives data as the response to a request. When the server has
// something to push and the client has nothing in flight, the last encrypted packet is re-sent
// as a poll (the server de-duplicates by msg_id). Same timings as the old Python core.

const REPLAY_IDLE: Duration = Duration::from_secs(1);
const REPLAY_REQUEST: Duration = Duration::from_secs(3);
const REPLAY_CHECK: Duration = Duration::from_millis(250);
const REPLAY_MAX_AGE: Duration = Duration::from_secs(30);
const REPLAY_HISTORY_BYTES: usize = 64 * 1024;
const REPLAY_HISTORY_PACKETS: usize = 16;
const MAX_CHANNEL_REPLAYS: usize = 2;
const REPLAY_RETRY: Duration = Duration::from_secs(2);
const REPLAY_MAX_ATTEMPTS: u32 = 3;

struct Packet {
    body: Arc<Vec<u8>>,
    sent_at: Instant,
    attempts: u32,
    last_attempt: Option<Instant>,
    originals: HashSet<u64>,
    pending: Option<u64>,
    reply_id: Option<[u8; 24]>,
}

struct Pend {
    sent: Instant,
    replay: bool,
    receiving: Arc<AtomicBool>,
    packet: Option<u64>, // packet sequence number
}

#[derive(Default)]
struct State {
    history: VecDeque<(u64, Packet)>,
    key: [u8; 8],
    pend: HashMap<u64, Pend>,
    next_id: u64,
    seq: u64,
}

impl State {
    fn packet_mut(&mut self, seq: u64) -> Option<&mut Packet> {
        self.history.iter_mut().find(|(s, _)| *s == seq).map(|(_, p)| p)
    }

    fn remember(&mut self, body: &Arc<Vec<u8>>, now: Instant) -> Option<u64> {
        let key: [u8; 8] = body[..8].try_into().ok()?;
        if key == [0u8; 8] {
            return None;
        }
        if self.key != key {
            self.history.clear();
            self.key = key;
        }
        if body.len() > REPLAY_HISTORY_BYTES {
            return None;
        }
        if let Some((s, _)) = self.history.iter().find(|(_, p)| *p.body == **body) {
            return Some(*s);
        }
        self.seq += 1;
        let seq = self.seq;
        self.history.push_back((seq, Packet {
            body: body.clone(), sent_at: now, attempts: 0, last_attempt: None,
            originals: HashSet::new(), pending: None, reply_id: None,
        }));
        // trim: oldest packets without live requests first
        loop {
            let bytes: usize = self.history.iter().map(|(_, p)| p.body.len()).sum();
            if self.history.len() <= REPLAY_HISTORY_PACKETS && bytes <= REPLAY_HISTORY_BYTES {
                break;
            }
            let idx = self.history.iter().position(|(_, p)| p.originals.is_empty() && p.pending.is_none()).unwrap_or(0);
            self.history.remove(idx);
        }
        Some(seq)
    }

    fn overdue(&self, p: &Packet, now: Instant) -> bool {
        p.originals.iter().any(|id| {
            self.pend.get(id).map_or(false, |r| !r.receiving.load(Ordering::Relaxed) && now - r.sent >= REPLAY_REQUEST)
        })
    }

    /// Picks the packet to poll with, following the Python core's blockers.
    fn replay_candidate(&self, now: Instant, last_progress: Instant) -> Option<u64> {
        if self.pend.values().any(|r| r.receiving.load(Ordering::Relaxed)) {
            return None; // a body is arriving
        }
        let replays = self.pend.values().filter(|r| r.replay).count();
        let last_seq = self.history.back().map(|(s, _)| *s);
        let mut cands: Vec<(bool, i64, u64)> = Vec::new();
        for (seq, p) in &self.history {
            if now - p.sent_at > REPLAY_MAX_AGE {
                continue;
            }
            let overdue = self.overdue(p, now);
            let limit = if !p.originals.is_empty() || Some(*seq) == last_seq { REPLAY_MAX_ATTEMPTS } else { 0 };
            if p.attempts >= limit {
                continue;
            }
            if p.last_attempt.map_or(false, |t| now - t < REPLAY_RETRY) {
                continue;
            }
            if !overdue {
                let others = self.pend.iter().any(|(id, _)| Some(*id) != p.pending);
                if Some(*seq) != last_seq || others {
                    continue;
                }
                if p.last_attempt.is_some() && now - last_progress < REPLAY_IDLE {
                    continue;
                }
            }
            if p.pending.is_none() && replays >= MAX_CHANNEL_REPLAYS {
                continue;
            }
            cands.push((!overdue, if overdue { *seq as i64 } else { -(*seq as i64) }, *seq));
        }
        cands.sort();
        cands.first().map(|c| c.2)
    }
}

/// Bridges a native client to the H2 lane until either side ends.
pub async fn bridge(client: Stream, ch: Channel, ctx: CryptoCtx, tag: [u8; 4], pool: Arc<H2Pool>, label: &str) {
    let CryptoCtx { clt_dec: mut dec_ctx, mut clt_enc, .. } = ctx;
    let (mut cr, mut cw) = tokio::io::split(client);
    let (tx, mut rx) = mpsc::channel::<(u64, Result<Vec<u8>, ReqErr>)>(16);
    let sem = Arc::new(Semaphore::new(MAX_CHANNEL_REQUESTS));
    let st = Arc::new(Mutex::new(State::default()));
    let progress = Arc::new(Mutex::new(Instant::now()));
    let delivering = Arc::new(AtomicBool::new(false));

    // starts one POST (original or replay) and registers it in the state
    let spawn_post = {
        let (send, host, tx, st, progress) = (ch.send.clone(), ch.host.clone(), tx.clone(), st.clone(), progress.clone());
        Arc::new(move |body: Arc<Vec<u8>>, replay: bool, seq: Option<u64>, permit: Option<tokio::sync::OwnedSemaphorePermit>| {
            let rec = Arc::new(AtomicBool::new(false));
            let now = Instant::now();
            let id = {
                let mut g = st.lock().unwrap_or_else(|p| p.into_inner());
                g.next_id += 1;
                let id = g.next_id;
                g.pend.insert(id, Pend { sent: now, replay, receiving: rec.clone(), packet: seq });
                if let Some(sq) = seq {
                    if let Some(p) = g.packet_mut(sq) {
                        if replay {
                            p.attempts += 1;
                            p.last_attempt = Some(now);
                            p.pending = Some(id);
                        } else {
                            p.originals.insert(id);
                        }
                    }
                }
                id
            };
            let (send, host, tx, progress) = (send.clone(), host.clone(), tx.clone(), progress.clone());
            tokio::spawn(async move {
                let r = post(&send, &host, (*body).clone(), &rec, &progress).await;
                drop(permit);
                let _ = tx.send((id, r)).await;
            });
        })
    };
    drop(tx);

    let up_spawn = spawn_post.clone();
    let up_st = st.clone();
    let upload = async move {
        loop {
            let body = match read_packet(&mut cr, &mut dec_ctx, tag).await {
                Ok(b) => Arc::new(b),
                Err(_) => break, // client gone
            };
            stats::add(&stats::UP, body.len() as u64);
            let Ok(permit) = sem.clone().acquire_owned().await else { break };
            let seq = up_st.lock().unwrap_or_else(|p| p.into_inner()).remember(&body, Instant::now());
            (*up_spawn)(body, false, seq, Some(permit));
        }
    };

    let (st_d, prog_d, deliv_d) = (st.clone(), progress.clone(), delivering.clone());
    let down = async {
        while let Some((id, r)) = rx.recv().await {
            // bookkeeping for the finished request
            let mut reply_changed = false;
            {
                let mut g = st_d.lock().unwrap_or_else(|p| p.into_inner());
                if let Some(pd) = g.pend.remove(&id) {
                    if let Some(sq) = pd.packet {
                        if let Some(p) = g.packet_mut(sq) {
                            p.originals.remove(&id);
                            if p.pending == Some(id) {
                                p.pending = None;
                            }
                            if let Ok(rep) = &r {
                                if rep.len() >= 24 {
                                    let rid: [u8; 24] = rep[..24].try_into().unwrap_or([0; 24]);
                                    if p.reply_id != Some(rid) {
                                        p.reply_id = Some(rid);
                                        p.attempts = 0;
                                        reply_changed = true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            let _ = reply_changed;
            match r {
                Ok(reply) if reply.is_empty() => {}
                Ok(reply) => {
                    stats::add(&stats::DOWN, reply.len() as u64);
                    let Some(mut enc) = encode_reply(&reply, tag) else { break };
                    clt_enc.apply(&mut enc);
                    deliv_d.store(true, Ordering::Relaxed);
                    let ok = cw.write_all(&enc).await.is_ok();
                    deliv_d.store(false, Ordering::Relaxed);
                    touch(&prog_d);
                    if !ok {
                        break;
                    }
                }
                Err(ReqErr::Transport(code)) => {
                    lwarn!("[{label}] H2 MTProto transport error {code}; reporting to client");
                    if let Some(mut enc) = encode_reply(&code.to_le_bytes(), tag) {
                        clt_enc.apply(&mut enc);
                        let _ = cw.write_all(&enc).await;
                    }
                    break;
                }
                Err(ReqErr::Other(e)) => {
                    stats::inc(&stats::ERRORS);
                    lwarn!("[{label}] H2 request failed: {e}; closing, reconnect may select another domain");
                    pool.forget(&ch.host);
                    break;
                }
            }
        }
    };

    let recover_st = st.clone();
    let recover = async move {
        loop {
            sleep(REPLAY_CHECK).await;
            if delivering.load(Ordering::Relaxed) {
                continue;
            }
            let last = *progress.lock().unwrap_or_else(|p| p.into_inner());
            let now = Instant::now();
            let pick = {
                let g = recover_st.lock().unwrap_or_else(|p| p.into_inner());
                g.replay_candidate(now, last).and_then(|seq| {
                    g.history.iter().find(|(s, _)| *s == seq).map(|(s, p)| (*s, p.body.clone()))
                })
            };
            if let Some((seq, body)) = pick {
                (*spawn_post)(body, true, Some(seq), None);
            }
        }
    };

    tokio::select! { _ = upload => {}, _ = down => {}, _ = recover => {} }
    let _ = cw.shutdown().await;
    ldebug!("[{label}] H2 channel closed host={}", ch.host);
}
