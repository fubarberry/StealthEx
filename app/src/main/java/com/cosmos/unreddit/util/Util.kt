package com.cosmos.unreddit.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object Util {

    fun <T1, T2> let(p1: T1?, p2: T2?, block: (T1, T2) -> Unit) {
        if (p1 != null && p2 != null) {
            block.invoke(p1, p2)
        }
    }

    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
