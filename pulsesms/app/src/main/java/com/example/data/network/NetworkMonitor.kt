package com.example.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkSnapshot(
  val isOnline: Boolean = false,
  val transportLabel: String = "Offline",
  val isCellular: Boolean = false,
  val isMetered: Boolean = true,
)

class NetworkMonitor(context: Context) {
  private val cm =
    context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  private val _snapshot = MutableStateFlow(readCurrentSnapshot())
  val snapshot: StateFlow<NetworkSnapshot> = _snapshot.asStateFlow()

  private val callback =
    object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        _snapshot.value = readCurrentSnapshot()
      }

      override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        _snapshot.value = snapshotFromCapabilities(caps)
      }

      override fun onLost(network: Network) {
        _snapshot.value = readCurrentSnapshot()
      }
    }

  init {
    runCatching { cm.registerDefaultNetworkCallback(callback) }
  }

  fun currentIsOnline(): Boolean = _snapshot.value.isOnline

  fun refresh() {
    _snapshot.value = readCurrentSnapshot()
  }

  private fun readCurrentSnapshot(): NetworkSnapshot {
    val active = cm.activeNetwork ?: return NetworkSnapshot(isOnline = false, transportLabel = "Offline")
    val caps =
      cm.getNetworkCapabilities(active)
        ?: return NetworkSnapshot(isOnline = false, transportLabel = "Offline")
    return snapshotFromCapabilities(caps)
  }

  private fun snapshotFromCapabilities(caps: NetworkCapabilities): NetworkSnapshot {
    val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    val isEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

    val label =
      when {
        !hasInternet -> "No Signal"
        isCellular -> "Mobile Data"
        isWifi -> "Wi-Fi"
        isEthernet -> "Ethernet"
        else -> "Data On"
      }
    return NetworkSnapshot(
      isOnline = hasInternet,
      transportLabel = label,
      isCellular = isCellular,
      isMetered = isMetered,
    )
  }
}
