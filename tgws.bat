@echo off
rem Windows launcher: builds (once) and runs the Rust proxy. Needs Rust: https://rustup.rs
cd /d "%~dp0rust-core"
cargo run --release --bin tgws -- %*
