//! Minimal WebSocket client (RFC 6455, binary frames) over TCP, TLS or fronted TLS.
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadHalf, WriteHalf};
use tokio::net::TcpStream;
use tokio::sync::Mutex;
use tokio::time::timeout;
use tokio_rustls::rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use tokio_rustls::rustls::crypto::{ring, verify_tls12_signature, verify_tls13_signature, CryptoProvider};
use tokio_rustls::rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use tokio_rustls::rustls::{ClientConfig, DigitallySignedStruct, Error as TlsError, RootCertStore, SignatureScheme};
use tokio_rustls::TlsConnector;

use crate::util::{build_frame, mask_in_place};

pub trait Io: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> Io for T {}
pub type Stream = Box<dyn Io>;

const MAX_MESSAGE: u64 = 16 * 1024 * 1024;

/// Short human-readable failure ("timeout", "HTTP 403 ...", OS error text).
#[derive(Debug, Clone)]
pub struct WsError(pub String);

impl std::fmt::Display for WsError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl WsError {
    pub fn timeout() -> Self {
        WsError("timeout".into())
    }
    pub fn io(e: std::io::Error) -> Self {
        let s = e.to_string();
        // "Connection refused (os error 111)" -> "Connection refused"
        WsError(s.split(" (os error").next().unwrap_or(&s).to_string())
    }
}

// ---- TLS ----------------------------------------------------------------------------------

#[derive(Debug)]
struct NoVerify(Arc<CryptoProvider>);

impl ServerCertVerifier for NoVerify {
    fn verify_server_cert(
        &self, _: &CertificateDer<'_>, _: &[CertificateDer<'_>], _: &ServerName<'_>, _: &[u8], _: UnixTime,
    ) -> Result<ServerCertVerified, TlsError> {
        Ok(ServerCertVerified::assertion())
    }
    fn verify_tls12_signature(
        &self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        verify_tls12_signature(m, c, d, &self.0.signature_verification_algorithms)
    }
    fn verify_tls13_signature(
        &self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        verify_tls13_signature(m, c, d, &self.0.signature_verification_algorithms)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.0.signature_verification_algorithms.supported_schemes()
    }
}

fn provider() -> Arc<CryptoProvider> {
    static P: OnceLock<Arc<CryptoProvider>> = OnceLock::new();
    P.get_or_init(|| Arc::new(ring::default_provider())).clone()
}

fn tls_verified() -> Arc<ClientConfig> {
    static C: OnceLock<Arc<ClientConfig>> = OnceLock::new();
    C.get_or_init(|| {
        let mut roots = RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        Arc::new(
            ClientConfig::builder_with_provider(provider())
                .with_safe_default_protocol_versions()
                .expect("tls versions")
                .with_root_certificates(roots)
                .with_no_client_auth(),
        )
    })
    .clone()
}

/// Domain fronting: the SNI differs from the real host, so the certificate cannot match.
fn tls_unverified() -> Arc<ClientConfig> {
    static C: OnceLock<Arc<ClientConfig>> = OnceLock::new();
    C.get_or_init(|| {
        Arc::new(
            ClientConfig::builder_with_provider(provider())
                .with_safe_default_protocol_versions()
                .expect("tls versions")
                .dangerous()
                .with_custom_certificate_verifier(Arc::new(NoVerify(provider())))
                .with_no_client_auth(),
        )
    })
    .clone()
}

// ---- connection -------------------------------------------------------------------------------

#[derive(Clone)]
pub struct WsWriter {
    inner: Arc<Mutex<WriteHalf<Stream>>>,
}

pub struct WsReader {
    r: ReadHalf<Stream>,
    w: WsWriter,
}

pub struct WsConn {
    pub r: WsReader,
    pub w: WsWriter,
}

/// Opens TCP to `host` (name or IP), optionally TLS (`sni` set = fronting, no cert check),
/// and performs the WebSocket upgrade for `path` with `Host: domain`.
pub async fn connect(
    host: &str, domain: &str, tmo: Duration, path: &str, sni: Option<&str>, secure: bool,
) -> Result<WsConn, WsError> {
    let setup = async {
        let port = if secure { 443 } else { 80 };
        let tcp = TcpStream::connect((host, port)).await.map_err(WsError::io)?;
        let _ = tcp.set_nodelay(true);
        let mut stream: Stream = if secure {
            let name = sni.unwrap_or(domain).to_string();
            let sn = ServerName::try_from(name).map_err(|e| WsError(format!("bad server name: {e}")))?;
            let cfg = if sni.is_some() { tls_unverified() } else { tls_verified() };
            let tls = TlsConnector::from(cfg).connect(sn, tcp).await.map_err(WsError::io)?;
            let b: Stream = Box::new(tls);
            b
        } else {
            let b: Stream = Box::new(tcp);
            b
        };

        let key = {
            use base64::Engine;
            let k: [u8; 16] = rand::random();
            base64::engine::general_purpose::STANDARD.encode(k)
        };
        let req = format!(
            "GET {path} HTTP/1.1\r\nHost: {domain}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\
             Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: binary\r\n\r\n"
        );
        stream.write_all(req.as_bytes()).await.map_err(WsError::io)?;
        stream.flush().await.map_err(WsError::io)?;

        let mut head: Vec<u8> = Vec::with_capacity(256);
        loop {
            let b = stream.read_u8().await.map_err(|e| {
                if e.kind() == std::io::ErrorKind::UnexpectedEof { WsError("empty response".into()) } else { WsError::io(e) }
            })?;
            head.push(b);
            if head.ends_with(b"\r\n\r\n") || head.ends_with(b"\n\n") {
                break;
            }
            if head.len() > 16 * 1024 {
                return Err(WsError("response headers too large".into()));
            }
        }
        let text = String::from_utf8_lossy(&head);
        let first = text.lines().next().unwrap_or("").trim().to_string();
        let code: u16 = first.split(' ').nth(1).and_then(|c| c.parse().ok()).unwrap_or(0);
        if code != 101 {
            return Err(WsError(if first.is_empty() { "empty response".into() } else { format!("HTTP {code}: {first}") }));
        }
        Ok::<Stream, WsError>(stream)
    };
    let stream = timeout(tmo.min(Duration::from_secs(10)), setup).await.map_err(|_| WsError::timeout())??;
    let (r, w) = tokio::io::split(stream);
    let w = WsWriter { inner: Arc::new(Mutex::new(w)) };
    Ok(WsConn { r: WsReader { r, w: w.clone() }, w })
}

impl WsWriter {
    pub async fn send(&self, data: &[u8]) -> std::io::Result<()> {
        let f = build_frame(0x2, data, rand::random());
        let mut g = self.inner.lock().await;
        g.write_all(&f).await?;
        g.flush().await
    }

    pub async fn send_batch(&self, parts: &[Vec<u8>]) -> std::io::Result<()> {
        let mut buf = Vec::new();
        for p in parts {
            buf.extend_from_slice(&build_frame(0x2, p, rand::random()));
        }
        let mut g = self.inner.lock().await;
        g.write_all(&buf).await?;
        g.flush().await
    }

    async fn control(&self, op: u8, payload: &[u8]) -> std::io::Result<()> {
        let f = build_frame(op, payload, rand::random());
        let mut g = self.inner.lock().await;
        g.write_all(&f).await?;
        g.flush().await
    }

    pub async fn close(&self) {
        let _ = timeout(Duration::from_secs(2), async {
            let _ = self.control(0x8, &[]).await;
            let mut g = self.inner.lock().await;
            let _ = g.shutdown().await;
        })
        .await;
    }
}

impl WsReader {
    async fn read_frame(&mut self) -> std::io::Result<(u8, Vec<u8>, bool)> {
        let mut h = [0u8; 2];
        self.r.read_exact(&mut h).await?;
        let fin = h[0] & 0x80 != 0;
        let op = h[0] & 0x0f;
        let masked = h[1] & 0x80 != 0;
        let mut len = (h[1] & 0x7f) as u64;
        if len == 126 {
            let mut b = [0u8; 2];
            self.r.read_exact(&mut b).await?;
            len = u16::from_be_bytes(b) as u64;
        } else if len == 127 {
            let mut b = [0u8; 8];
            self.r.read_exact(&mut b).await?;
            len = u64::from_be_bytes(b);
        }
        if len > MAX_MESSAGE {
            return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "frame too large"));
        }
        let mut mask = [0u8; 4];
        if masked {
            self.r.read_exact(&mut mask).await?;
        }
        let mut payload = vec![0u8; len as usize];
        self.r.read_exact(&mut payload).await?;
        if masked {
            mask_in_place(&mut payload, mask);
        }
        Ok((op, payload, fin))
    }

    /// Next complete binary message; Ok(None) once the peer closed the connection.
    pub async fn recv(&mut self) -> std::io::Result<Option<Vec<u8>>> {
        let mut frag: Vec<u8> = Vec::new();
        let mut in_frag = false;
        loop {
            let (op, payload, fin) = self.read_frame().await?;
            match op {
                0x8 => {
                    let n = payload.len().min(2);
                    let _ = self.w.control(0x8, &payload[..n]).await;
                    return Ok(None);
                }
                0x9 => {
                    let _ = self.w.control(0xA, &payload).await;
                }
                0xA => {}
                0x0 => {
                    if in_frag {
                        frag.extend_from_slice(&payload);
                        if frag.len() as u64 > MAX_MESSAGE {
                            return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "message too large"));
                        }
                        if fin {
                            return Ok(Some(frag));
                        }
                    }
                }
                0x1 | 0x2 => {
                    if fin {
                        return Ok(Some(payload));
                    }
                    frag = payload;
                    in_frag = true;
                }
                _ => {}
            }
        }
    }
}
