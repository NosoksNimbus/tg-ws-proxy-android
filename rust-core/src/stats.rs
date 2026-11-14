use std::sync::atomic::{AtomicU64, Ordering::Relaxed};

pub static UP: AtomicU64 = AtomicU64::new(0);
pub static DOWN: AtomicU64 = AtomicU64::new(0);
pub static ACTIVE: AtomicU64 = AtomicU64::new(0);
pub static TOTAL: AtomicU64 = AtomicU64::new(0);
pub static WS: AtomicU64 = AtomicU64::new(0);
pub static CF: AtomicU64 = AtomicU64::new(0);
pub static TCP: AtomicU64 = AtomicU64::new(0);
pub static ERRORS: AtomicU64 = AtomicU64::new(0);
pub static BAD: AtomicU64 = AtomicU64::new(0);
pub static POOL_HITS: AtomicU64 = AtomicU64::new(0);
pub static POOL_MISSES: AtomicU64 = AtomicU64::new(0);

pub fn inc(c: &AtomicU64) {
    c.fetch_add(1, Relaxed);
}

pub fn add(c: &AtomicU64, n: u64) {
    c.fetch_add(n, Relaxed);
}

pub fn sub1(c: &AtomicU64) {
    let _ = c.fetch_update(Relaxed, Relaxed, |v| Some(v.saturating_sub(1)));
}

pub fn reset() {
    for c in [&UP, &DOWN, &ACTIVE, &TOTAL, &WS, &CF, &TCP, &ERRORS, &BAD, &POOL_HITS, &POOL_MISSES] {
        c.store(0, Relaxed);
    }
}

/// Same JSON keys the Python core reports.
pub fn json() -> String {
    let h = POOL_HITS.load(Relaxed);
    let m = POOL_MISSES.load(Relaxed);
    format!(
        "{{\"up\":{},\"down\":{},\"active\":{},\"total\":{},\"ws\":{},\"cf\":{},\"tcp\":{},\"errors\":{},\"pool_hits\":{},\"pool_total\":{}}}",
        UP.load(Relaxed), DOWN.load(Relaxed), ACTIVE.load(Relaxed), TOTAL.load(Relaxed),
        WS.load(Relaxed), CF.load(Relaxed), TCP.load(Relaxed), ERRORS.load(Relaxed), h, h + m
    )
}
