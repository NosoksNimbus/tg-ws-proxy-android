use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::util::sanitize_log;

static LINES: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());
static VERBOSE: AtomicBool = AtomicBool::new(false);
const CAP: usize = 400;
static ECHO: AtomicBool = AtomicBool::new(false);

struct FileSink {
    path: String,
    max: u64,
    backups: u32,
    written: u64,
    f: Option<std::fs::File>,
}
static FILE: Mutex<Option<FileSink>> = Mutex::new(None);

/// Desktop CLI: also append log lines to `path`, rotating at `max_mb` and keeping `backups` old files.
pub fn set_file(path: &str, max_mb: f64, backups: u32) -> std::io::Result<()> {
    let f = std::fs::OpenOptions::new().create(true).append(true).open(path)?;
    let written = f.metadata().map(|m| m.len()).unwrap_or(0);
    if let Ok(mut g) = FILE.lock() {
        *g = Some(FileSink { path: path.to_string(), max: (max_mb * 1024.0 * 1024.0) as u64, backups: backups.max(1), written, f: Some(f) });
    }
    Ok(())
}

fn write_file(line: &str) {
    use std::io::Write;
    let Ok(mut g) = FILE.lock() else { return };
    let Some(s) = g.as_mut() else { return };
    if s.written + line.len() as u64 + 1 > s.max {
        s.f = None;
        for i in (1..s.backups).rev() {
            let _ = std::fs::rename(format!("{}.{}", s.path, i), format!("{}.{}", s.path, i + 1));
        }
        let _ = std::fs::rename(&s.path, format!("{}.1", s.path));
        s.f = std::fs::OpenOptions::new().create(true).append(true).open(&s.path).ok();
        s.written = 0;
    }
    if let Some(f) = s.f.as_mut() {
        if writeln!(f, "{line}").is_ok() {
            s.written += line.len() as u64 + 1;
        }
    }
}

/// Desktop CLI: also print every log line to stderr.
pub fn set_echo(v: bool) {
    ECHO.store(v, Ordering::Relaxed);
}

pub fn set_verbose(v: bool) {
    VERBOSE.store(v, Ordering::Relaxed);
}

pub fn verbose() -> bool {
    VERBOSE.load(Ordering::Relaxed)
}

pub fn log(level: &str, msg: String) {
    if level == "DEBUG" && !verbose() {
        return;
    }
    let secs = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    let t = format!("{:02}:{:02}:{:02}", (secs / 3600) % 24, (secs / 60) % 60, secs % 60);
    let line = format!("{} {:<5} {}", t, level, sanitize_log(&msg));
    if ECHO.load(Ordering::Relaxed) {
        eprintln!("{line}");
    }
    write_file(&line);
    if let Ok(mut q) = LINES.lock() {
        if q.len() >= CAP {
            q.pop_front();
        }
        q.push_back(line);
    }
}

pub fn dump() -> String {
    LINES.lock().map(|q| q.iter().cloned().collect::<Vec<_>>().join("\n")).unwrap_or_default()
}

pub fn clear() {
    if let Ok(mut q) = LINES.lock() {
        q.clear();
    }
}
