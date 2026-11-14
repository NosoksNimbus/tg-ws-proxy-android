use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::util::sanitize_log;

static LINES: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());
static VERBOSE: AtomicBool = AtomicBool::new(false);
const CAP: usize = 400;

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
