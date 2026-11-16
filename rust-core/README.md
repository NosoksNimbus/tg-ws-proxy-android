# tgws_core — Rust core (experimental)

Own MIT-licensed implementation of the proxy core, written from the behaviour of the Python core
(`../proxy`). Exposed to Kotlin through JNI (`RustEngine`).

Implemented: MTProto obfuscation (abridged/intermediate/padded), dd-secret clients, per-DC direct
WebSocket pool with fronting, fallback chain Worker -> Cloudflare (TLS:443 then plain :80) -> TCP,
traffic stats, log ring (secrets masked, domains censored), live pool tuning, network-change reset,
connectivity check with the same probes as the Python core.

Not implemented (only existed in the removed Python core): Fake-TLS `ee` secrets, HTTP/2 media lane, remote CF-domain list refresh.

Build Android: Gradle runs `cargo ndk` automatically (`./gradlew assembleDebug`).
Desktop CLI: `cargo run --release --bin tgws -- --help`.
