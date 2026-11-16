//! HTTP/2 media lane: instead of a WebSocket, every MTProto packet of a media connection is sent
//! as `POST https://kws<dc>.<cf-domain>/api` over one multiplexed HTTP/2 connection per host, and
//! the response body is the reply packet. This is the fast path for big media downloads.
use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use bytes::Bytes;
use h2::client::{self, SendRequest};
use http::{Method, Request};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, Semaphore};
use tokio::time::timeout;
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
const SETUP_TIMEOUT: Duration = Duration::from_secs(8);

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

async fn post(send: &SendRequest<Bytes>, host: &str, body: Vec<u8>) -> Result<Vec<u8>, ReqErr> {
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
        let mut body = resp.into_body();
        let mut out: Vec<u8> = Vec::new();
        while let Some(chunk) = body.data().await {
            let chunk = chunk.map_err(|e| ReqErr::Other(e.to_string()))?;
            let _ = body.flow_control().release_capacity(chunk.len());
            if out.len() + chunk.len() > MAX_PACKET {
                return Err(ReqErr::Other("H2 response exceeds native packet bound".into()));
            }
            out.extend_from_slice(&chunk);
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

/// Bridges a native client to the H2 lane until either side ends.
pub async fn bridge(client: Stream, ch: Channel, ctx: CryptoCtx, tag: [u8; 4], pool: Arc<H2Pool>, label: &str) {
    let CryptoCtx { clt_dec: mut dec_ctx, mut clt_enc, .. } = ctx;
    let (mut cr, mut cw) = tokio::io::split(client);
    let (tx, mut rx) = mpsc::channel::<Result<Vec<u8>, ReqErr>>(16);
    let sem = Arc::new(Semaphore::new(MAX_CHANNEL_REQUESTS));
    let send = ch.send.clone();
    let host = ch.host.clone();
    let tx_up = tx.clone();

    let upload = async move {
        loop {
            let body = match read_packet(&mut cr, &mut dec_ctx, tag).await {
                Ok(b) => b,
                Err(_) => break, // client gone
            };
            stats::add(&stats::UP, body.len() as u64);
            let Ok(permit) = sem.clone().acquire_owned().await else { break };
            let (send, host, tx) = (send.clone(), host.clone(), tx_up.clone());
            tokio::spawn(async move {
                let r = post(&send, &host, body).await;
                drop(permit);
                let _ = tx.send(r).await;
            });
        }
    };
    drop(tx);
    let down = async {
        while let Some(r) = rx.recv().await {
            match r {
                Ok(reply) if reply.is_empty() => {}
                Ok(reply) => {
                    stats::add(&stats::DOWN, reply.len() as u64);
                    let Some(mut enc) = encode_reply(&reply, tag) else { break };
                    clt_enc.apply(&mut enc);
                    if cw.write_all(&enc).await.is_err() {
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
    tokio::select! { _ = upload => {}, _ = down => {} }
    let _ = cw.shutdown().await;
    ldebug!("[{label}] H2 channel closed host={}", ch.host);
}
