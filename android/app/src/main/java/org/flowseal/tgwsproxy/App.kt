package org.flowseal.tgwsproxy

import android.app.Application
import kotlin.concurrent.thread

/**
 * Starts the embedded Python runtime in the background as soon as the process is created,
 * so pressing the power button doesn't have to wait for the interpreter and the
 * proxy modules to load.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        thread(name = "python-warmup", priority = Thread.MIN_PRIORITY) {
            try {
                (Engine.get(this) as? PythonEngine)?.warmup()
            } catch (_: Throwable) {
                // The service reports real errors when it actually starts the core.
            }
        }
    }
}
