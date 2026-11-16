# tgws_core — Rust core

MIT-licensed proxy core, exposed to Kotlin through JNI (`RustEngine`).

Implemented: MTProto obfuscation (abridged/intermediate/padded), dd-secret clients, per-DC direct
WebSocket pool with fronting, fallback chain Worker -> Cloudflare (TLS:443 then plain :80) -> TCP,
traffic stats, log ring (secrets masked, domains censored), live pool tuning, network-change reset,
connectivity check. Fake-TLS `ee` secrets (`fake_tls_domain`), HTTP/2 media lane (`h2lane.rs`) and an
hourly Cloudflare-domain list refresh are included as well.

Build Android: Gradle runs `cargo ndk` automatically (`./gradlew assembleDebug`).
Desktop CLI: `cargo run --release --bin tgws -- --help`.
