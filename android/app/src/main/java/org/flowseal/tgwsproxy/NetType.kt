package org.flowseal.tgwsproxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Kind of the current default network, used for per-network profiles and traffic stats. */
enum class Net {
    WIFI, MOBILE, OTHER;

    companion object {
        fun current(ctx: Context): Net {
            return try {
                val cm = ctx.getSystemService(ConnectivityManager::class.java)
                val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return OTHER
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> MOBILE
                    else -> OTHER
                }
            } catch (_: Exception) {
                OTHER
            }
        }
    }
}
