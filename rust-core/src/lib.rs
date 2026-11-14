//! TG WS Proxy core in Rust: local MTProto proxy that tunnels Telegram over WebSockets.

macro_rules! linfo { ($($a:tt)*) => { $crate::logbuf::log("INFO", format!($($a)*)) } }
macro_rules! lwarn { ($($a:tt)*) => { $crate::logbuf::log("WARN", format!($($a)*)) } }
macro_rules! lerror { ($($a:tt)*) => { $crate::logbuf::log("ERROR", format!($($a)*)) } }
macro_rules! ldebug { ($($a:tt)*) => { $crate::logbuf::log("DEBUG", format!($($a)*)) } }

pub mod util;
pub mod config;
pub mod logbuf;
pub mod stats;
pub mod obf;
pub mod ws;
pub mod proxy;
pub mod diag;
mod jni_api;
