package com.github.peco2282

import android.net.wifi.ScanResult
import kotlinx.coroutines.flow.MutableStateFlow

class WifiScanRepository private constructor() {
  companion object {
    @Volatile
    private var instance: WifiScanRepository? = null
    fun getInstance() = instance ?: synchronized(this) {
      instance ?: WifiScanRepository().also { instance = it }
    }
  }

  private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())

  private val _scanError = MutableStateFlow<String?>(null)

  fun updateScanResults(results: List<ScanResult>) {
    _scanResults.value = results
  }

  fun getScanResults(): List<ScanResult> {
    return _scanResults.value
  }

  fun updateScanError(message: String?) {
    _scanError.value = message
  }

  fun getError(): String? {
    return _scanError.value
  }
}
