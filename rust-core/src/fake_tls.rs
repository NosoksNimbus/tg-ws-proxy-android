//! Fake-TLS (`ee` secrets): the client opens what looks like a TLS 1.3 handshake to a masking
//! site; we answer with a forged ServerHello authenticated by the proxy secret and then carry the
//! MTProto stream inside TLS ApplicationData records. Anything that fails the HMAC check (active
//! probes, replays) is relayed to the real masking site instead.
use std::collections::{HashMap, VecDeque};
use std::io;
use std::pin::Pin;
use std::sync::Mutex;
use std::task::{Context, Poll};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use hmac::{Hmac, Mac};
use rand::{Rng, RngCore};
use sha2::Sha256;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf};
use tokio::net::TcpStream;
use tokio::time::timeout;

type HmacSha256 = Hmac<Sha256>;

pub const RECORD_HANDSHAKE: u8 = 0x16;
const RECORD_CCS: u8 = 0x14;
const RECORD_APPDATA: u8 = 0x17;
const CLIENT_RANDOM_OFF: usize = 11;
const SESSION_ID_OFF: usize = 44;
const TIMESTAMP_TOLERANCE: i64 = 120;
const APPDATA_MAX: usize = 16384;
const EXT_SUPPORTED_VERSIONS: u16 = 0x002b;
const EXT_KEY_SHARE: u16 = 0x0033;
const GROUP_X25519: u16 = 0x001d;
const GROUP_X25519MLKEM768: u16 = 0x11ec;
const MLKEM768_CT_LEN: usize = 1088;
const MLKEM768_CLIENT_SHARE: usize = 1216;
const CIPHER_PREFERENCE: [u16; 3] = [0x1301, 0x1303, 0x1302];
const FALLBACK_APPDATA: (usize, usize) = (64, 160);
const CCS_FRAME: [u8; 6] = [0x14, 0x03, 0x03, 0x00, 0x01, 0x01];
const REPLAY_WINDOW: Duration = Duration::from_secs(2 * 120 + 1);
const REPLAY_CACHE_SIZE: usize = 65536;

// ---- replay cache -------------------------------------------------------------------------

static REPLAY: Mutex<Option<(HashMap<[u8; 32], Instant>, VecDeque<([u8; 32], Instant)>)>> = Mutex::new(None);
static PROFILED_LEN: Mutex<Option<usize>> = Mutex::new(None);

/// True the first time a client random is seen inside the replay window.
fn first_seen(random: &[u8; 32]) -> bool {
    let now = Instant::now();
    let mut g = REPLAY.lock().unwrap_or_else(|p| p.into_inner());
    let (map, order) = g.get_or_insert_with(|| (HashMap::new(), VecDeque::new()));
    while let Some((k, exp)) = order.front().cloned() {
        if exp <= now {
            order.pop_front();
            map.remove(&k);
        } else {
            break;
        }
    }
    if map.contains_key(random) {
        return false;
    }
    if map.len() >= REPLAY_CACHE_SIZE {
        if let Some((k, _)) = order.pop_front() {
            map.remove(&k);
        }
    }
    let exp = now + REPLAY_WINDOW;
    map.insert(*random, exp);
    order.push_back((*random, exp));
    true
}

pub fn set_profiled_len(v: Option<usize>) {
    *PROFILED_LEN.lock().unwrap_or_else(|p| p.into_inner()) = v.map(|l| l.clamp(64, APPDATA_MAX));
}

fn appdata_len() -> usize {
    if let Some(l) = *PROFILED_LEN.lock().unwrap_or_else(|p| p.into_inner()) {
        return l;
    }
    rand::thread_rng().gen_range(FALLBACK_APPDATA.0..=FALLBACK_APPDATA.1)
}

// ---- ClientHello --------------------------------------------------------------------------

pub struct Hello {
    pub random: [u8; 32],
    pub session_id: Vec<u8>,
    pub info: HelloInfo,
}

#[derive(Default)]
pub struct HelloInfo {
    suites: Vec<u16>,
    key_shares: Vec<(u16, usize)>,
}

fn hmac(secret: &[u8], parts: &[&[u8]]) -> [u8; 32] {
    let mut m = <HmacSha256 as Mac>::new_from_slice(secret).expect("hmac key");
    for p in parts {
        m.update(p);
    }
    m.finalize().into_bytes().into()
}

/// Checks the HMAC/timestamp embedded in the ClientHello random. None = not one of ours.
pub fn verify_client_hello(data: &[u8], secret: &[u8; 16]) -> Option<Hello> {
    let n = data.len();
    if n < 43 || data[0] != RECORD_HANDSHAKE || data[5] != 0x01 {
        return None;
    }
    let mut random = [0u8; 32];
    random.copy_from_slice(&data[CLIENT_RANDOM_OFF..CLIENT_RANDOM_OFF + 32]);
    let zero = [0u8; 32];
    let expected = hmac(secret, &[&data[..CLIENT_RANDOM_OFF], &zero, &data[CLIENT_RANDOM_OFF + 32..]]);
    // constant-time compare of the first 28 bytes
    let mut diff = 0u8;
    for i in 0..28 {
        diff |= expected[i] ^ random[i];
    }
    if diff != 0 {
        return None;
    }
    let mut ts = [0u8; 4];
    for i in 0..4 {
        ts[i] = random[28 + i] ^ expected[28 + i];
    }
    let timestamp = u32::from_le_bytes(ts) as i64;
    let now = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
    if (now - timestamp).abs() > TIMESTAMP_TOLERANCE {
        return None;
    }
    let mut session_id = vec![0u8; 32];
    if n >= SESSION_ID_OFF + 32 && data[43] == 0x20 {
        session_id = data[SESSION_ID_OFF..SESSION_ID_OFF + 32].to_vec();
    }
    Some(Hello { random, session_id, info: parse_client_hello(data) })
}

fn be16(d: &[u8], p: usize) -> Option<usize> {
    Some(u16::from_be_bytes([*d.get(p)?, *d.get(p + 1)?]) as usize)
}

fn parse_client_hello(d: &[u8]) -> HelloInfo {
    let parse = || -> Option<HelloInfo> {
        let mut pos = 5 + 4 + 2 + 32;
        pos += 1 + *d.get(pos)? as usize;
        let suites_len = be16(d, pos)?;
        pos += 2;
        let suites_end = pos + suites_len;
        if suites_end > d.len() {
            return None;
        }
        let suites = (pos..suites_end.saturating_sub(1)).step_by(2).filter_map(|o| be16(d, o).map(|v| v as u16)).collect();
        pos = suites_end;
        pos += 1 + *d.get(pos)? as usize;
        let ext_total = be16(d, pos)?;
        pos += 2;
        let ext_end = (pos + ext_total).min(d.len());
        let mut key_shares = Vec::new();
        while pos + 4 <= ext_end {
            let t = be16(d, pos)? as u16;
            let l = be16(d, pos + 2)?;
            pos += 4;
            if t == EXT_KEY_SHARE && pos + 2 <= ext_end {
                let shares_end = (pos + 2 + be16(d, pos)?).min(ext_end);
                let mut off = pos + 2;
                while off + 4 <= shares_end {
                    let group = be16(d, off)? as u16;
                    let klen = be16(d, off + 2)?;
                    off += 4 + klen;
                    if off <= shares_end && !key_shares.iter().any(|(g, _)| *g == group) {
                        key_shares.push((group, klen));
                    }
                }
            }
            pos += l;
        }
        Some(HelloInfo { suites, key_shares })
    };
    parse().unwrap_or_default()
}

fn pick_suite(info: &HelloInfo) -> u16 {
    CIPHER_PREFERENCE.iter().copied().find(|s| info.suites.contains(s)).unwrap_or(CIPHER_PREFERENCE[0])
}

fn x25519_pub() -> [u8; 32] {
    let mut k = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut k);
    k[31] &= 0x7f;
    k
}

fn server_key_share(info: &HelloInfo) -> (u16, Vec<u8>) {
    if info.key_shares.iter().any(|(g, l)| *g == GROUP_X25519MLKEM768 && *l == MLKEM768_CLIENT_SHARE) {
        let mut v = vec![0u8; MLKEM768_CT_LEN];
        rand::thread_rng().fill_bytes(&mut v);
        v.extend_from_slice(&x25519_pub());
        (GROUP_X25519MLKEM768, v)
    } else {
        (GROUP_X25519, x25519_pub().to_vec())
    }
}

/// ServerHello + CCS + one ApplicationData record, authenticated with the secret.
pub fn build_server_hello(secret: &[u8; 16], h: &Hello) -> Vec<u8> {
    let (group, kx) = server_key_share(&h.info);
    let mut ext = Vec::new();
    ext.extend_from_slice(&EXT_SUPPORTED_VERSIONS.to_be_bytes());
    ext.extend_from_slice(&2u16.to_be_bytes());
    ext.extend_from_slice(&0x0304u16.to_be_bytes());
    ext.extend_from_slice(&EXT_KEY_SHARE.to_be_bytes());
    ext.extend_from_slice(&((4 + kx.len()) as u16).to_be_bytes());
    ext.extend_from_slice(&group.to_be_bytes());
    ext.extend_from_slice(&(kx.len() as u16).to_be_bytes());
    ext.extend_from_slice(&kx);

    let mut body = vec![0x03, 0x03];
    body.extend_from_slice(&[0u8; 32]);
    body.push(h.session_id.len() as u8);
    body.extend_from_slice(&h.session_id);
    body.extend_from_slice(&pick_suite(&h.info).to_be_bytes());
    body.push(0);
    body.extend_from_slice(&(ext.len() as u16).to_be_bytes());
    body.extend_from_slice(&ext);

    let mut msg = vec![0x02];
    msg.extend_from_slice(&(body.len() as u32).to_be_bytes()[1..]);
    msg.extend_from_slice(&body);

    let mut resp = vec![RECORD_HANDSHAKE, 0x03, 0x03];
    resp.extend_from_slice(&(msg.len() as u16).to_be_bytes());
    resp.extend_from_slice(&msg);
    resp.extend_from_slice(&CCS_FRAME);
    let alen = appdata_len();
    resp.extend_from_slice(&[RECORD_APPDATA, 0x03, 0x03]);
    resp.extend_from_slice(&(alen as u16).to_be_bytes());
    let mut junk = vec![0u8; alen];
    rand::thread_rng().fill_bytes(&mut junk);
    resp.extend_from_slice(&junk);

    let server_random = hmac(secret, &[&h.random, &resp]);
    resp[11..43].copy_from_slice(&server_random);
    resp
}

// ---- stream wrapper -----------------------------------------------------------------------

/// Reads/writes MTProto bytes wrapped in TLS ApplicationData records.
pub struct FakeTlsStream {
    inner: TcpStream,
    rbuf: Vec<u8>,   // decoded payload not yet handed out
    rpos: usize,
    hdr: [u8; 5],
    hdr_have: usize,
    body_left: usize,
    skip_ccs: bool,
    wbuf: Vec<u8>,   // encoded, pending write
    wpos: usize,
}

impl FakeTlsStream {
    pub fn new(inner: TcpStream) -> Self {
        FakeTlsStream { inner, rbuf: Vec::new(), rpos: 0, hdr: [0; 5], hdr_have: 0, body_left: 0, skip_ccs: false, wbuf: Vec::new(), wpos: 0 }
    }
}

impl AsyncRead for FakeTlsStream {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, out: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let this = &mut *self;
        loop {
            if this.rpos < this.rbuf.len() {
                let n = (this.rbuf.len() - this.rpos).min(out.remaining());
                out.put_slice(&this.rbuf[this.rpos..this.rpos + n]);
                this.rpos += n;
                if this.rpos == this.rbuf.len() {
                    this.rbuf.clear();
                    this.rpos = 0;
                }
                return Poll::Ready(Ok(()));
            }
            if this.body_left == 0 {
                // read the next 5-byte record header
                while this.hdr_have < 5 {
                    let mut rb = ReadBuf::new(&mut this.hdr[this.hdr_have..]);
                    match Pin::new(&mut this.inner).poll_read(cx, &mut rb) {
                        Poll::Ready(Ok(())) => {
                            let n = rb.filled().len();
                            if n == 0 {
                                return Poll::Ready(Ok(())); // EOF
                            }
                            this.hdr_have += n;
                        }
                        other => return other,
                    }
                }
                this.hdr_have = 0;
                let rtype = this.hdr[0];
                let len = u16::from_be_bytes([this.hdr[3], this.hdr[4]]) as usize;
                if rtype != RECORD_APPDATA && rtype != RECORD_CCS {
                    return Poll::Ready(Ok(())); // alert/garbage: treat as EOF
                }
                this.body_left = len;
                if rtype == RECORD_CCS {
                    // swallow it
                    this.rbuf.clear();
                    this.rpos = 0;
                    this.skip_ccs = true;
                }
                if this.body_left == 0 {
                    this.skip_ccs = false;
                    continue;
                }
            }
            // read record body
            let want = this.body_left.min(65536);
            let mut tmp = vec![0u8; want];
            let mut rb = ReadBuf::new(&mut tmp);
            match Pin::new(&mut this.inner).poll_read(cx, &mut rb) {
                Poll::Ready(Ok(())) => {
                    let n = rb.filled().len();
                    if n == 0 {
                        return Poll::Ready(Ok(()));
                    }
                    this.body_left -= n;
                    if this.skip_ccs {
                        if this.body_left == 0 {
                            this.skip_ccs = false;
                        }
                        continue;
                    }
                    this.rbuf.extend_from_slice(&tmp[..n]);
                }
                other => return other,
            }
        }
    }
}

impl AsyncWrite for FakeTlsStream {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, data: &[u8]) -> Poll<io::Result<usize>> {
        let this = &mut *self;
        // flush anything pending first
        while this.wpos < this.wbuf.len() {
            match Pin::new(&mut this.inner).poll_write(cx, &this.wbuf[this.wpos..]) {
                Poll::Ready(Ok(0)) => return Poll::Ready(Err(io::ErrorKind::WriteZero.into())),
                Poll::Ready(Ok(n)) => this.wpos += n,
                other => return other.map(|r| r.map(|_| 0)),
            }
        }
        this.wbuf.clear();
        this.wpos = 0;
        if data.is_empty() {
            return Poll::Ready(Ok(0));
        }
        for chunk in data.chunks(APPDATA_MAX) {
            this.wbuf.extend_from_slice(&[RECORD_APPDATA, 0x03, 0x03]);
            this.wbuf.extend_from_slice(&(chunk.len() as u16).to_be_bytes());
            this.wbuf.extend_from_slice(chunk);
        }
        // data is now owned by wbuf, report it as accepted and push what we can
        while this.wpos < this.wbuf.len() {
            match Pin::new(&mut this.inner).poll_write(cx, &this.wbuf[this.wpos..]) {
                Poll::Ready(Ok(n)) => this.wpos += n,
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Pending => break,
            }
        }
        Poll::Ready(Ok(data.len()))
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = &mut *self;
        while this.wpos < this.wbuf.len() {
            match Pin::new(&mut this.inner).poll_write(cx, &this.wbuf[this.wpos..]) {
                Poll::Ready(Ok(0)) => return Poll::Ready(Err(io::ErrorKind::WriteZero.into())),
                Poll::Ready(Ok(n)) => this.wpos += n,
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Pending => return Poll::Pending,
            }
        }
        this.wbuf.clear();
        this.wpos = 0;
        Pin::new(&mut this.inner).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        match self.as_mut().poll_flush(cx) {
            Poll::Ready(Ok(())) => Pin::new(&mut self.inner).poll_shutdown(cx),
            other => other,
        }
    }
}

// ---- accept side --------------------------------------------------------------------------

pub enum Accepted {
    /// Fake-TLS handshake done: first 64 bytes of the obfuscated init plus the wrapped stream.
    Tls([u8; 64], FakeTlsStream),
    /// Not for us (probe/replay/plain client): already relayed or answered.
    Done,
}

/// Relays a rejected connection to the real masking site so probes see an ordinary web server.
pub async fn mask(mut client: TcpStream, initial: &[u8], domain: &str, label: &str) {
    let up = match timeout(Duration::from_secs(10), TcpStream::connect((domain, 443))).await {
        Ok(Ok(s)) => s,
        e => {
            lwarn!("[{label}] masking: cannot connect to {domain}:443: {}", match e { Ok(Err(x)) => x.to_string(), _ => "timeout".into() });
            return;
        }
    };
    ldebug!("[{label}] masking -> {domain}:443");
    crate::stats::inc(&crate::stats::MASKED);
    let (mut ur, mut uw) = up.into_split();
    if !initial.is_empty() && uw.write_all(initial).await.is_err() {
        return;
    }
    let (mut cr, mut cw) = client.split();
    let a = async { let _ = tokio::io::copy(&mut cr, &mut uw).await; let _ = uw.shutdown().await; };
    let b = async { let _ = tokio::io::copy(&mut ur, &mut cw).await; let _ = cw.shutdown().await; };
    tokio::join!(a, b);
}

/// Handles the start of a connection when Fake-TLS is enabled. `first` is the byte already read.
pub async fn accept(mut sock: TcpStream, first: u8, secret: &[u8; 16], domain: &str, label: &str) -> Accepted {
    if first != RECORD_HANDSHAKE {
        // plain HTTP client / scanner: redirect to the masking site
        ldebug!("[{label}] non-TLS byte 0x{first:02X} -> HTTP redirect");
        let r = format!("HTTP/1.1 301 Moved Permanently\r\nLocation: https://{domain}/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        let _ = sock.write_all(r.as_bytes()).await;
        return Accepted::Done;
    }
    let mut rest = [0u8; 4];
    if timeout(Duration::from_secs(10), sock.read_exact(&mut rest)).await.map_or(true, |r| r.is_err()) {
        ldebug!("[{label}] incomplete TLS record header");
        return Accepted::Done;
    }
    let mut hello = vec![first];
    hello.extend_from_slice(&rest);
    let rec_len = u16::from_be_bytes([rest[2], rest[3]]) as usize;
    let mut body = vec![0u8; rec_len];
    if timeout(Duration::from_secs(10), sock.read_exact(&mut body)).await.map_or(true, |r| r.is_err()) {
        ldebug!("[{label}] incomplete TLS record body");
        return Accepted::Done;
    }
    hello.extend_from_slice(&body);

    let ok = verify_client_hello(&hello, secret).filter(|h| first_seen(&h.random));
    let Some(h) = ok else {
        ldebug!("[{label}] Fake TLS verify failed or replayed -> masking");
        mask(sock, &hello, domain, label).await;
        return Accepted::Done;
    };
    let sh = build_server_hello(secret, &h);
    // Draw the jitter before awaiting: ThreadRng is not Send, so holding it across
    // the await would make this future unusable from tokio::spawn.
    let jitter_ms = rand::thread_rng().gen_range(8..=24);
    tokio::time::sleep(Duration::from_millis(jitter_ms)).await;
    if sock.write_all(&sh).await.is_err() {
        return Accepted::Done;
    }
    let mut st = FakeTlsStream::new(sock);
    let mut init = [0u8; 64];
    match timeout(Duration::from_secs(10), st.read_exact(&mut init)).await {
        Ok(Ok(_)) => Accepted::Tls(init, st),
        _ => {
            ldebug!("[{label}] incomplete obfs2 init inside TLS");
            Accepted::Done
        }
    }
}

// ---- profile probe ------------------------------------------------------------------------

fn first_appdata_len(d: &[u8]) -> Option<usize> {
    let mut pos = 0;
    while pos + 5 <= d.len() {
        let t = d[pos];
        let l = u16::from_be_bytes([d[pos + 3], d[pos + 4]]) as usize;
        if t == RECORD_APPDATA {
            return Some(l);
        }
        if t != RECORD_HANDSHAKE && t != RECORD_CCS {
            return Some(0);
        }
        pos += 5 + l;
    }
    None
}

/// Measures the first encrypted record the masking site sends after its ServerHello.
pub async fn probe_appdata_len(domain: &str) -> Option<usize> {
    use tokio_rustls::rustls::pki_types::ServerName;
    let cfg = crate::ws::tls13_probe_config();
    let name = ServerName::try_from(domain.to_string()).ok()?;
    let mut conn = tokio_rustls::rustls::ClientConnection::new(cfg, name).ok()?;
    let mut hello = Vec::new();
    conn.write_tls(&mut hello).ok()?;
    let run = async {
        let mut s = TcpStream::connect((domain, 443)).await.ok()?;
        s.write_all(&hello).await.ok()?;
        let mut flight = Vec::new();
        let mut buf = vec![0u8; 16384];
        loop {
            let n = s.read(&mut buf).await.ok()?;
            if n == 0 {
                return None;
            }
            flight.extend_from_slice(&buf[..n]);
            if let Some(l) = first_appdata_len(&flight) {
                return if l == 0 { None } else { Some(l) };
            }
        }
    };
    match timeout(Duration::from_secs(5), run).await {
        Ok(v) => v,
        Err(_) => {
            lwarn!("Fake TLS profile: cannot probe {domain}:443: timeout");
            None
        }
    }
}
