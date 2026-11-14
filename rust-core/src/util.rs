//! Pure helpers (std only): domain decoding/censoring, DC list parsing, frame masking, MsgSplitter.
use std::collections::BTreeMap;
use std::net::Ipv4Addr;

pub const HANDSHAKE_LEN: usize = 64;
pub const PROTO_ABRIDGED: u32 = 0xEFEF_EFEF;
pub const PROTO_INTERMEDIATE: u32 = 0xEEEE_EEEE;
pub const PROTO_PADDED: u32 = 0xDDDD_DDDD;

pub fn dc_default_ip(dc: u32, test: bool) -> Option<&'static str> {
    if test {
        return match dc {
            1 => Some("149.154.175.10"),
            2 => Some("149.154.167.40"),
            3 => Some("149.154.175.117"),
            _ => None,
        };
    }
    match dc {
        1 => Some("149.154.175.50"),
        2 => Some("149.154.167.51"),
        3 => Some("149.154.175.100"),
        4 => Some("149.154.167.91"),
        5 => Some("149.154.171.5"),
        203 => Some("91.105.192.100"),
        _ => None,
    }
}

pub fn ws_domains(dc: u32, media: bool) -> Vec<String> {
    let dc = if dc == 203 { 2 } else { dc };
    if media {
        vec![format!("kws{dc}-1.web.telegram.org"), format!("kws{dc}.web.telegram.org")]
    } else {
        vec![format!("kws{dc}.web.telegram.org")]
    }
}

const CF_ENC: [&str; 20] = [
    "virkgj.com", "vmmzovy.com", "mkuosckvso.com", "zaewayzmplad.com", "twdmbzcm.com",
    "awzwsldi.com", "clngqrflngqin.com", "tjacxbqtj.com", "bxaxtxmrw.com", "dmohrsgmohcrwb.com",
    "vwbmtmoi.com", "khgrre.com", "ulihssf.com", "tmhqsdqmfpmk.com", "xwuwoqbm.com",
    "orgcnunpj.com", "zhkuldz.com", "zypoljnslxa.com", "efabnxaowuzs.com", "zaftuzsftqdq.com",
];

/// Decodes the obfuscated default Cloudflare domains (same scheme as the Python core).
pub fn decode_domain(s: &str) -> String {
    if !s.ends_with(".com") {
        return s.to_string();
    }
    let p = &s[..s.len() - 4];
    let n = p.chars().filter(|c| c.is_ascii_alphabetic()).count() as i32;
    let mut out: String = p
        .chars()
        .map(|c| {
            if c.is_ascii_alphabetic() {
                let base = if c.is_ascii_lowercase() { 97 } else { 65 };
                let v = ((c as i32 - base - n) % 26 + 26) % 26 + base;
                v as u8 as char
            } else {
                c
            }
        })
        .collect();
    out.push_str(".co.uk");
    out
}

pub fn default_cf_domains() -> Vec<String> {
    CF_ENC.iter().map(|d| decode_domain(d)).collect()
}

pub fn valid_domain(d: &str) -> bool {
    if d.is_empty() || d.len() > 253 || d.starts_with('.') || d.ends_with('.') || !d.contains('.') {
        return false;
    }
    d.split('.').all(|l| {
        !l.is_empty()
            && l.len() <= 63
            && !l.starts_with('-')
            && !l.ends_with('-')
            && l.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
    })
}

/// Splits on commas/newlines/spaces, lowercases, drops invalid and duplicates.
pub fn coerce_domains<S: AsRef<str>>(items: &[S]) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for it in items {
        for part in it.as_ref().split(|c: char| c == ',' || c == '\n' || c.is_whitespace()) {
            let d = part.trim().to_lowercase();
            if valid_domain(&d) && !out.contains(&d) {
                out.push(d);
            }
        }
    }
    out
}

/// "2:149.154.167.220" lines -> map. Err(text) on a bad entry.
pub fn parse_dc_ip<S: AsRef<str>>(items: &[S]) -> Result<BTreeMap<u32, String>, String> {
    let mut m = BTreeMap::new();
    for it in items {
        let t = it.as_ref().trim();
        if t.is_empty() {
            continue;
        }
        let (dc, ip) = t.split_once(':').ok_or_else(|| format!("bad dc_ip entry: {t}"))?;
        let dc: u32 = dc.trim().parse().map_err(|_| format!("bad DC number: {t}"))?;
        let ip = ip.trim();
        ip.parse::<Ipv4Addr>().map_err(|_| format!("bad IP: {t}"))?;
        m.insert(dc, ip.to_string());
    }
    Ok(m)
}

/// Masks hex runs of >= 32 chars (secrets) and half-hides domain names, like the Python log filters.
pub fn sanitize_log(line: &str) -> String {
    let mut out = String::with_capacity(line.len());
    let chars: Vec<char> = line.chars().collect();
    let is_tok = |c: char| c.is_ascii_alphanumeric() || c == '.' || c == '-' || c == '_';
    let mut i = 0;
    while i < chars.len() {
        if is_tok(chars[i]) {
            let mut j = i;
            while j < chars.len() && is_tok(chars[j]) {
                j += 1;
            }
            let tok: String = chars[i..j].iter().collect();
            out.push_str(&sanitize_token(&tok));
            i = j;
        } else {
            out.push(chars[i]);
            i += 1;
        }
    }
    out
}

fn sanitize_token(tok: &str) -> String {
    // secret-like: 32+ hex chars anywhere in the token
    let hex_run = {
        let mut best = 0;
        let mut cur = 0;
        for c in tok.chars() {
            if c.is_ascii_hexdigit() {
                cur += 1;
                best = best.max(cur);
            } else {
                cur = 0;
            }
        }
        best
    };
    if hex_run >= 32 {
        return "<secret>".to_string();
    }
    let t = tok.trim_end_matches('.');
    let trail = &tok[t.len()..];
    let labels: Vec<&str> = t.split('.').collect();
    if labels.len() < 2 {
        return tok.to_string();
    }
    let last = labels[labels.len() - 1];
    if last.len() < 2 || !last.chars().all(|c| c.is_ascii_alphabetic()) {
        return tok.to_string(); // IPs, versions, "1.5s"
    }
    if labels.iter().any(|l| l.is_empty()) {
        return tok.to_string();
    }
    let norm = t.to_lowercase();
    if norm == "telegram.org" || norm.ends_with(".telegram.org") || norm.ends_with(".log") {
        return tok.to_string();
    }
    let mut parts: Vec<String> = Vec::new();
    for (i, l) in labels.iter().enumerate() {
        if i == labels.len() - 1 {
            parts.push((*l).to_string());
        } else {
            let keep = l.chars().count() / 2;
            let head: String = l.chars().take(keep).collect();
            parts.push(format!("{}{}", head, "*".repeat(l.chars().count() - keep)));
        }
    }
    format!("{}{}", parts.join("."), trail)
}

// ---- WebSocket frames ----------------------------------------------------------------------

pub fn mask_in_place(data: &mut [u8], mask: [u8; 4]) {
    for (i, b) in data.iter_mut().enumerate() {
        *b ^= mask[i & 3];
    }
}

/// A client (masked) frame.
pub fn build_frame(op: u8, data: &[u8], mask: [u8; 4]) -> Vec<u8> {
    let n = data.len();
    let mut f = Vec::with_capacity(n + 14);
    f.push(0x80 | op);
    if n < 126 {
        f.push(0x80 | n as u8);
    } else if n < 65536 {
        f.push(0x80 | 126);
        f.extend_from_slice(&(n as u16).to_be_bytes());
    } else {
        f.push(0x80 | 127);
        f.extend_from_slice(&(n as u64).to_be_bytes());
    }
    f.extend_from_slice(&mask);
    let start = f.len();
    f.extend_from_slice(data);
    mask_in_place(&mut f[start..], mask);
    f
}

// ---- MTProto transport packet splitter -----------------------------------------------------

/// Splits a TCP stream into MTProto transport packets so each goes out as its own WS frame.
pub struct MsgSplitter {
    proto: u32,
    cipher: Vec<u8>,
    plain: Vec<u8>,
    disabled: bool,
}

impl MsgSplitter {
    pub fn new(proto: u32) -> Self {
        Self { proto, cipher: Vec::new(), plain: Vec::new(), disabled: false }
    }

    pub fn split(&mut self, chunk: &[u8], plain: &[u8]) -> Vec<Vec<u8>> {
        if chunk.is_empty() {
            return vec![];
        }
        if self.disabled {
            return vec![chunk.to_vec()];
        }
        self.cipher.extend_from_slice(chunk);
        self.plain.extend_from_slice(plain);
        let mut parts = Vec::new();
        let mut offset = 0usize;
        let buf_len = self.cipher.len();
        while offset < buf_len {
            match self.next_len(offset, buf_len - offset) {
                None => break,
                Some(0) => {
                    parts.push(self.cipher[offset..].to_vec());
                    offset = buf_len;
                    self.disabled = true;
                    break;
                }
                Some(n) => {
                    parts.push(self.cipher[offset..offset + n].to_vec());
                    offset += n;
                }
            }
        }
        if offset > 0 {
            self.cipher.drain(..offset);
            self.plain.drain(..offset);
        }
        parts
    }

    pub fn flush(&mut self) -> Vec<Vec<u8>> {
        if self.cipher.is_empty() {
            return vec![];
        }
        let tail = std::mem::take(&mut self.cipher);
        self.plain.clear();
        vec![tail]
    }

    /// Some(n>0) = a full packet of n bytes; None = need more data; Some(0) = give up splitting.
    fn next_len(&self, offset: usize, avail: usize) -> Option<usize> {
        if avail == 0 {
            return None;
        }
        match self.proto {
            PROTO_ABRIDGED => {
                let first = self.plain[offset];
                let (payload, header) = if first == 0x7F || first == 0xFF {
                    if avail < 4 {
                        return None;
                    }
                    let p = &self.plain[offset + 1..offset + 4];
                    ((p[0] as usize | (p[1] as usize) << 8 | (p[2] as usize) << 16) * 4, 4)
                } else {
                    (((first & 0x7F) as usize) * 4, 1)
                };
                if payload == 0 {
                    return Some(0);
                }
                let total = header + payload;
                if avail < total { None } else { Some(total) }
            }
            PROTO_INTERMEDIATE | PROTO_PADDED => {
                if avail < 4 {
                    return None;
                }
                let p = &self.plain[offset..offset + 4];
                let payload = (u32::from_le_bytes([p[0], p[1], p[2], p[3]]) & 0x7FFF_FFFF) as usize;
                if payload == 0 {
                    return Some(0);
                }
                let total = 4 + payload;
                if avail < total { None } else { Some(total) }
            }
            _ => Some(0),
        }
    }
}
