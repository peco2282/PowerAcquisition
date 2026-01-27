package com.github.peco2282

import android.annotation.SuppressLint
import android.net.wifi.ScanResult
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

object WifiScanRepository {

  @Volatile
  private var instance: WifiScanRepository? = null
  fun getInstance() = instance ?: synchronized(this) {
    instance ?: WifiScanRepository.also { instance = it }
  }


  private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
  private val _wifiResults = MutableStateFlow<List<WifiResult>>(emptyList())

  private val _scanError = MutableStateFlow<String?>(null)

  fun updateScanResults(results: List<ScanResult>) {
    _scanResults.update { results }
  }

  fun updateWifiResults(results: List<WifiResult>) {
    _wifiResults.update { results }
  }

  @SuppressLint("MissingPermission")
  fun scan() {
    val manager = MainActivity.getInstance()
    val wifiManager = manager.wifiManager ?: return
    wifiManager.startScan()
    updateScanResults(wifiManager.scanResults.also { Log.d("WifiRssiService", "Scan results: $it") })
  }

  fun getScanResults(): List<ScanResult> {
    return _scanResults.value
  }

  fun getWifiResults(): List<WifiResult> {
    return _wifiResults.value.also { Log.i("WIFIR", it.toString()) }
  }

  fun updateScanError(message: String?) {
    _scanError.value = message
  }

  fun getError(): String? {
    return _scanError.value
  }
}
