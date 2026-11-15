package org.flowseal.tgwsproxy

import android.app.Application
import kotlin.concurrent.thread

/** Loads the native proxy core in the background so the first start is instant. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        thread(name = "core-warmup", priority = Thread.MIN_PRIORITY) {
            try {
                Engine.get(this)
            } catch (_: Throwable) {
                // The service reports the real error when it starts the core.
            }
        }
    }
}
