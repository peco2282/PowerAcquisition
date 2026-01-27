@file:SuppressLint("MissingPermission")
package com.github.peco2282

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi

class WifiRssiListService : Service() {
  companion object {
    const val ACTION_SCAN_RESULTS: String = "com.peco2282.android.wifi.SCAN_RESULTS"
    const val EXTRA_RESULTS: String = "com.peco2282.android.wifi.EXTRA_RESULTS"
    const val ACTION_SCAN_ERROR: String = "com.peco2282.android.wifi.SCAN_ERROR"
    const val EXTRA_ERROR_MESSAGE: String = "com.peco2282.android.wifi.EXTRA_ERROR_MESSAGE"
    val TAG: String = WifiRssiListService::class.java.simpleName

    private var _instance: WifiRssiListService? = null

    fun getInstance() = _instance
  }

  private val wifiManager by lazy { applicationContext.getSystemService(WIFI_SERVICE) as WifiManager? }
  private val repository = WifiScanRepository.getInstance()
  private var isStarted = false

  val wifiScanReceiver = WifiScanReceiver()

  fun startScan() {
    if (wifiManager == null) Log.w(TAG, "WifiManager is null")
    if (isStarted) return
    wifiManager?.startScan()
    registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
    isStarted = true
  }

  fun stopScan() {
    isStarted = false
    unregisterReceiver(wifiScanReceiver)
  }

  override fun onCreate() {
    super.onCreate()
    _instance = this
    startScan()
  }

  override fun onDestroy() {
    super.onDestroy()
    _instance = null
    stopScan()
  }

  private fun broadcastResults(results: MutableList<ScanResult>) {
    val intent = Intent(ACTION_SCAN_RESULTS)
    intent.putParcelableArrayListExtra(EXTRA_RESULTS, results as ArrayList<ScanResult>)

  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  fun buildWifiCard() {
    repository.scan()
    val results = repository.getScanResults()
    val cache: Map<String, ScanResult> = results.associateBy { it.apMldMacAddress.toString() }
    Log.i(TAG, "buildWifiCard: $results")

    val request = RangingRequest.Builder().addAccessPoints(results.subList(0, RangingRequest.getMaxPeers() - 1)).build()
    val wifiRangingManager = applicationContext.getSystemService(WIFI_RTT_RANGING_SERVICE) as WifiRttManager?
    wifiRangingManager?.startRanging(
      request,
      applicationContext.mainExecutor,
      object : RangingResultCallback() {
        override fun onRangingFailure(code: Int) {
          Log.w(TAG, "onRangingFailure: $code")
        }

        override fun onRangingResults(results: List<RangingResult>) {
          val answer = mutableListOf<WifiResult>()

          Log.i(TAG, "onRangingResults: $results")
          for (result in results.filter { it.status == RangingResult.STATUS_SUCCESS }) {
            Log.d(TAG, result.toString())
            val mac = result.macAddress!!.toString()
            val scanResult = cache[mac] ?: continue
            val rssi = result.rssi
            val level = WifiManager.calculateSignalLevel(rssi, 5)
            val freq = scanResult.frequency
            val ch = getChannel(freq)
            val distanceMm = result.distanceMm
            val standard = wifiStandardToString(scanResult.wifiStandard)
            val bssid = scanResult.BSSID
            val ssid = scanResult.SSID
            val timestamp = scanResult.timestamp
            val capabilities = scanResult.capabilities
            val channelWidth = wifiChannelWidth(scanResult.channelWidth)

            answer.add(WifiResult(ssid, bssid, rssi, level, freq, ch, distanceMm, standard, timestamp, capabilities, channelWidth, mac))
          }
          repository.updateWifiResults(answer)
        }
      }
    )
  }

  override fun onBind(intent: Intent?): IBinder = RSSIBinder()
  inner class RSSIBinder : Binder() {
    val service: WifiRssiListService
      get() = this@WifiRssiListService
  }

  inner class WifiScanReceiver(private val andThen: () -> Unit = {}) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION != intent?.action) return
      val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)

      if (success) {
        val results = wifiManager?.scanResults ?: emptyList()
        repository.updateScanResults(results)
        Log.d("WifiScanReceiver", "Wi-Fiスキャン結果が更新されました。")
        andThen()
      } else {
        repository.updateScanError("スキャン結果の更新に失敗しました。")
        Log.d("WifiScanReceiver", "Wi-Fiスキャンに失敗したか、結果が更新されていません。")
      }
    }

  }
}
