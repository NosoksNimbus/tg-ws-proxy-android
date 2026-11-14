//! JNI entry points for org.flowseal.tgwsproxy.RustEngine.
use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

fn to_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn arg(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|j| j.into()).unwrap_or_default()
}

fn guarded<T>(default: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or_else(|_| {
        lerror!("internal error (panic) in the Rust core");
        default
    })
}

#[no_mangle]
pub extern "system" fn Java_org_flowseal_tgwsproxy_RustEngine_nStart(
    mut env: JNIEnv, _this: JObject, cfg: JString,
) -> jstring {
    let c = arg(&mut env, &cfg);
    let r = guarded("Rust core panicked while starting".to_string(), || crate::proxy::start(&c));
    to_jstring(&mut env, &r)
}

#[no_mangle]
pub extern "system" fn Java_org_flowseal_tgwsproxy_RustEngine_nStop(_env: JNIEnv, _this: JObject) {
    guarded((), crate::proxy::stop);
}

#[no_mangle]
pub extern "system" fn Java_org_flowseal_tgwsproxy_RustEngine_nIsRunning(_env: JNIEnv, _this: JObject) -> jboolean {
    if guarded(false, crate::proxy::is_running) { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_org_flowseal_tgwsproxy_RustEngine_nNetworkChanged(
    _env: JNIEnv, _this: JObject, hard: jboolean,
) {
    guarded((), || crate::proxy::network_changed(hard != 0));
}

#[no_mangle]
pub extern "system" fn Java_org_flowseal_tgwsproxy_RustEngine_nSetTuning(_env: JNIEnv, _this: JObject, pool: jint) {
    guarded((), || crate::proxy::set_tuning(pool.max(1) as usize));
}

#[no_mangle]
pub extern "system" fn Java_org_flowseal_tgwsproxy_RustEngine_nStats(mut env: JNIEnv, _this: JObject) -> jstring {
    to_jstring(&mut env, &crate::stats::json())
}

#[no_mangle]
pub extern "system" fn Java_org_flowseal_tgwsproxy_RustEngine_nLogs(mut env: JNIEnv, _this: JObject) -> jstring {
    to_jstring(&mut env, &crate::logbuf::dump())
}

#[no_mangle]
pub extern "system" fn Java_org_flowseal_tgwsproxy_RustEngine_nDiagnose(
    mut env: JNIEnv, _this: JObject, cfg: JString,
) -> jstring {
    let c = arg(&mut env, &cfg);
    let r = guarded("{\"results\":[],\"cf_needs_proxy\":false}".to_string(), || crate::diag::diagnose(&c));
    to_jstring(&mut env, &r)
}

#[no_mangle]
pub extern "system" fn Java_org_flowseal_tgwsproxy_RustEngine_nVersion(mut env: JNIEnv, _this: JObject) -> jstring {
    to_jstring(&mut env, env!("CARGO_PKG_VERSION"))
}
