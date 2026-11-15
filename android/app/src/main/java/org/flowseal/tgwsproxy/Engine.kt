package org.flowseal.tgwsproxy

import android.content.Context

/** What the app needs from the proxy core. */
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

/** The core is the Rust library libtgws_core.so (see /rust-core). */
object Engine {
    @Volatile private var impl: CoreEngine? = null

    fun get(@Suppress("UNUSED_PARAMETER") ctx: Context): CoreEngine {
        impl?.let { return it }
        synchronized(this) {
            impl?.let { return it }
            val e = RustEngine.create()
            impl = e
            return e
        }
    }

    /** Non-null once the core has been loaded. */
    fun peek(): CoreEngine? = impl
}

/** JNI bridge to libtgws_core.so. */
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
