//! MTProto obfuscation: client handshake parsing, relay init generation, per-direction ciphers.
use aes::Aes256;
use ctr::cipher::{KeyIvInit, StreamCipher};
use rand::RngCore;
use sha2::{Digest, Sha256};

use crate::util::HANDSHAKE_LEN;

type Aes256Ctr = ctr::Ctr128BE<Aes256>;

const SKIP: usize = 8;
const PREKEY: usize = 32;
const IV: usize = 16;
const TAG_POS: usize = 56;
const DC_POS: usize = 60;

pub const TAG_ABRIDGED: [u8; 4] = [0xef; 4];
pub const TAG_INTERMEDIATE: [u8; 4] = [0xee; 4];
pub const TAG_SECURE: [u8; 4] = [0xdd; 4];

pub struct Ctr(Aes256Ctr);

impl Ctr {
    pub fn new(key: &[u8], iv: &[u8]) -> Ctr {
        Ctr(Aes256Ctr::new(key.into(), iv.into()))
    }
    pub fn apply(&mut self, data: &mut [u8]) {
        self.0.apply_keystream(data);
    }
}

pub struct Handshake {
    pub dc: u32,
    pub media: bool,
    pub tag: [u8; 4],
    /// prekey (32) + iv (16) exactly as the client sent them.
    pub prekey_iv: [u8; 48],
}

fn sha(prekey: &[u8], secret: &[u8]) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(prekey);
    h.update(secret);
    h.finalize().into()
}

/// None = wrong secret or unknown protocol.
pub fn try_handshake(hs: &[u8; HANDSHAKE_LEN], secret: &[u8; 16]) -> Option<Handshake> {
    let mut prekey_iv = [0u8; 48];
    prekey_iv.copy_from_slice(&hs[SKIP..SKIP + PREKEY + IV]);
    let key = sha(&prekey_iv[..PREKEY], secret);
    let mut c = Ctr::new(&key, &prekey_iv[PREKEY..]);
    let mut dec = *hs;
    c.apply(&mut dec);
    let mut tag = [0u8; 4];
    tag.copy_from_slice(&dec[TAG_POS..TAG_POS + 4]);
    if tag != TAG_ABRIDGED && tag != TAG_INTERMEDIATE && tag != TAG_SECURE {
        return None;
    }
    let dc_idx = i16::from_le_bytes([dec[DC_POS], dec[DC_POS + 1]]) as i32;
    Some(Handshake { dc: dc_idx.unsigned_abs(), media: dc_idx < 0, tag, prekey_iv })
}

/// The 64-byte obfuscated init we send to Telegram.
pub fn generate_relay_init(tag: [u8; 4], dc_idx: i16) -> [u8; HANDSHAKE_LEN] {
    let mut rng = rand::thread_rng();
    let mut rnd = [0u8; HANDSHAKE_LEN];
    loop {
        rng.fill_bytes(&mut rnd);
        if rnd[0] == 0xef {
            continue;
        }
        let head = [rnd[0], rnd[1], rnd[2], rnd[3]];
        let reserved: [[u8; 4]; 7] = [
            *b"HEAD", *b"POST", *b"GET ", [0xee; 4], [0xdd; 4], [0x16, 0x03, 0x01, 0x02], *b"OPTI",
        ];
        if reserved.contains(&head) {
            continue;
        }
        if rnd[4..8] == [0, 0, 0, 0] {
            continue;
        }
        break;
    }
    // Keystream bytes 56..64 of the cipher keyed by the init itself.
    let mut ks = [0u8; HANDSHAKE_LEN];
    let mut c = Ctr::new(&rnd[SKIP..SKIP + PREKEY], &rnd[SKIP + PREKEY..SKIP + PREKEY + IV]);
    c.apply(&mut ks);

    let mut tail = [0u8; 8];
    tail[..4].copy_from_slice(&tag);
    tail[4..6].copy_from_slice(&dc_idx.to_le_bytes());
    rng.fill_bytes(&mut tail[6..8]);
    for i in 0..8 {
        rnd[TAG_POS + i] = tail[i] ^ ks[TAG_POS + i];
    }
    rnd
}

/// Both directions: client <-> us (secret-derived keys) and us <-> Telegram (raw keys).
pub struct CryptoCtx {
    pub clt_dec: Ctr,
    pub clt_enc: Ctr,
    pub tg_enc: Ctr,
    pub tg_dec: Ctr,
}

pub fn build_ctx(prekey_iv: &[u8; 48], secret: &[u8; 16], relay_init: &[u8; HANDSHAKE_LEN]) -> CryptoCtx {
    let zero = [0u8; HANDSHAKE_LEN];

    let dec_key = sha(&prekey_iv[..PREKEY], secret);
    let mut clt_dec = Ctr::new(&dec_key, &prekey_iv[PREKEY..]);
    clt_dec.apply(&mut zero.clone()); // skip the 64-byte init

    let mut rev = *prekey_iv;
    rev.reverse();
    let enc_key = sha(&rev[..PREKEY], secret);
    let clt_enc = Ctr::new(&enc_key, &rev[PREKEY..]);

    let init_pi = &relay_init[SKIP..SKIP + PREKEY + IV];
    let mut tg_enc = Ctr::new(&init_pi[..PREKEY], &init_pi[PREKEY..]);
    tg_enc.apply(&mut zero.clone());

    let mut rrev = [0u8; 48];
    rrev.copy_from_slice(init_pi);
    rrev.reverse();
    let tg_dec = Ctr::new(&rrev[..PREKEY], &rrev[PREKEY..]);

    CryptoCtx { clt_dec, clt_enc, tg_enc, tg_dec }
}
