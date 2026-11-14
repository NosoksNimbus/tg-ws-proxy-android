package org.flowseal.tgwsproxy

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** What the app needs from a proxy core. Implemented by the Python core and the Rust core. */
interface CoreEngine {
    val name: String
    /** Starts the proxy; returns "" on success or an error text. */
    fun start(cfgJson: String): String
    fun stop()
    fun isRunning(): Boolean
    fun networkChanged(hard: Boolean)
    fun setTuning(poolSize: Int)
    /** Cheap JSON snapshot (up, down, active, total, ws, cf, tcp, errors, pool_hits, pool_total). */
    fun stats(): String
    fun logs(): String
    fun diagnose(cfgJson: String): String
    fun version(): String
}

/** Picks the core once per process. Rust is used only if it was built in and its library loads. */
object Engine {
    private const val TAG = "Engine"

    @Volatile private var impl: CoreEngine? = null

    fun get(ctx: Context): CoreEngine {
        impl?.let { return it }
        synchronized(this) {
            impl?.let { return it }
            var e: CoreEngine? = null
            if (BuildConfig.RUST_CORE) {
                e = try {
                    RustEngine.create()
                } catch (t: Throwable) {
                    Log.w(TAG, "Rust core unavailable, using Python: $t")
                    null
                }
            }
            val chosen = e ?: PythonEngine(ctx.applicationContext)
            impl = chosen
            Log.i(TAG, "core: ${chosen.name}")
            return chosen
        }
    }

    /** Non-null only once [get] has run (the app has started a core before). */
    fun peek(): CoreEngine? = impl
}

class PythonEngine(private val ctx: Context) : CoreEngine {
    override val name = "python"

    private fun mod() = run {
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
        Python.getInstance().getModule("android_entry")
    }

    override fun start(cfgJson: String) = mod().callAttr("start", cfgJson).toString()
    override fun stop() { if (Python.isStarted()) mod().callAttr("stop") }
    override fun isRunning() = Python.isStarted() && mod().callAttr("is_running").toBoolean()
    override fun networkChanged(hard: Boolean) { mod().callAttr("network_changed", hard) }
    override fun setTuning(poolSize: Int) { mod().callAttr("set_tuning", poolSize) }
    override fun stats() = mod().callAttr("get_stats").toString()
    override fun logs() = mod().callAttr("get_logs").toString()
    override fun diagnose(cfgJson: String) = mod().callAttr("diagnose", cfgJson).toString()
    override fun version() = mod().callAttr("core_version").toString()

    /** Loads the interpreter ahead of the first start. */
    fun warmup() { mod() }
}

/** JNI bridge to libtgws_core.so (see /rust-core). */
class RustEngine private constructor() : CoreEngine {
    override val name = "rust"

    private external fun nStart(cfg: String): String
    private external fun nStop()
    private external fun nIsRunning(): Boolean
    private external fun nNetworkChanged(hard: Boolean)
    private external fun nSetTuning(pool: Int)
    private external fun nStats(): String
    private external fun nLogs(): String
    private external fun nDiagnose(cfg: String): String
    private external fun nVersion(): String

    override fun start(cfgJson: String) = nStart(cfgJson)
    override fun stop() = nStop()
    override fun isRunning() = nIsRunning()
    override fun networkChanged(hard: Boolean) = nNetworkChanged(hard)
    override fun setTuning(poolSize: Int) = nSetTuning(poolSize)
    override fun stats() = nStats()
    override fun logs() = nLogs()
    override fun diagnose(cfgJson: String) = nDiagnose(cfgJson)
    override fun version() = nVersion()

    companion object {
        fun create(): RustEngine {
            System.loadLibrary("tgws_core")
            return RustEngine()
        }
    }
}
