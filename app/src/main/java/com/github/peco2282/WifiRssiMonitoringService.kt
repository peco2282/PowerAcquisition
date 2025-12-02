package com.github.peco2282

//noinspection SuspiciousImport
import android.Manifest
import android.R
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*


class WifiRssiMonitoringService : Service() {
  companion object {
    const val CHANNEL_ID: String = "WifiRssiMonitoringChannel"

    const val NOTIFICATION_ID: Int = 123

    const val UPDATE_INTERVAL: Long = 2000

    val FNAME_FORMATTER: DateFormat = SimpleDateFormat("'wifi_'yyyyMMdd_HHmmss.'csv'", Locale.JAPAN)

    val DATE_FORMATTER: DateFormat = SimpleDateFormat("yyyy-MM-dd_HH:mm:ss.SSS", Locale.JAPAN)
  }

  private var currentFName: String? = FNAME_FORMATTER.format(Date())

  private var handler: Handler? = Handler(Looper.getMainLooper()!!)
  private var updateRssiRunnable: Runnable? = null
  private var isStarted = false

  // RSSI値を公開するためのStateFlow
  private val _currentRssi = MutableStateFlow(-127) // 初期値は無効なRSSI

  private val _currentContext = MutableStateFlow(
    UNRESOLVED_WIFI_CONTEXT.copy()
  )

  val currentContext: StateFlow<WifiContext>
    get() = _currentContext

  var rssi = -1000

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()

    updateRssiRunnable = object : Runnable {
      override fun run() {
        if (!isStarted) return
        updateRssi()
        handler!!.postDelayed(this, UPDATE_INTERVAL)
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val notification = buildNotification("RSSI: 測定中...")
    startForeground(NOTIFICATION_ID, notification)

    handler!!.post(updateRssiRunnable!!)
    val data = "Date,RSSI"

    val txt = "保存先: ${saveToExternalFilesDir(data)?.absolutePath}"
    Toast.makeText(this, txt, Toast.LENGTH_LONG).show()
    isStarted = true
    Log.i("WifiRssiService", "Service started")
    return START_STICKY
  }

  private fun saveToExternalFilesDir(data: String): File? {
    val appSpecificDir = getExternalFilesDir(null)
    if (appSpecificDir == null) {
      println("Service: 外部ストレージのアプリ専用ディレクトリが見つかりません。")
      return null
    }

    val file = File(appSpecificDir, currentFName!!)
    try {
      FileOutputStream(file, true).use { outputStream ->
        OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
          writer.write(data)
          writer.appendLine()
          writer.flush()
        }
      }
      return file
    } catch (e: Exception) {
      e.printStackTrace()
      println("Service: データ保存に失敗しました。エラー: ${e.message}")
      return null
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    handler!!.removeCallbacks(updateRssiRunnable!!)
    Log.i("WifiRssiService", "Service destroyed")
    _currentRssi.value = -127
    currentFName = null
    isStarted = false
  }

  override fun onBind(intent: Intent?): IBinder {
    return WifiRssiServiceBinder()
  }

  private fun updateRssi() {
    val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager? ?: return
    val wifiInfo = wifiManager.connectionInfo
    if (wifiInfo == null) {
      val notification = buildNotification("Wi-Fi未接続")
      (getSystemService(NOTIFICATION_SERVICE) as NotificationManager?)?.notify(NOTIFICATION_ID, notification)
      return
    }
    _currentRssi.value = wifiInfo.rssi
    val rssi = wifiInfo.rssi
    this.rssi = rssi
    val ssid = wifiInfo.ssid.replace("\"", "")

    _currentContext.value = UNRESOLVED_WIFI_CONTEXT.copy(rssi = rssi, ssid = ssid)

    if (ActivityCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION
      ) != PackageManager.PERMISSION_GRANTED
    ) {
      Toast.makeText(
        this,
        "アプリが正確な位置情報を取得できません",
        Toast.LENGTH_LONG
      ).show()
      return
    }
    val result = wifiManager.scanResults.find { it.SSID == ssid }
    val sb = StringBuilder("RSSI: $rssi dBm")
      .appendLine()
      .append("SSID: ")
      .append(ssid)
    if (result != null) {
      val freq = result.frequency
      Log.d("WifiRssiService", "Frequency: $freq, Channel: ${channel5GHz(freq)} ${channel2400MHz(freq)}")
      val ch = getChannel(freq)

      if (ch != -1) {
        sb
          .appendLine()
          .append("Channel: ")
          .append(ch)
          .append(" , Frequencies: ")
          .append(freq)
          .append(" MHz")
      }
      _currentContext.update {
        it.copy(freq = freq, ch = ch)
      }
      saveToExternalFilesDir(DATE_FORMATTER.format(Date()) + "," + rssi.toString())
      Log.d("WifiRssiService", sb.toString())
      Log.d("WifiRssiService", "---------")


      val notification = buildNotification(sb.toString())
      val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager?
      notificationManager?.notify(NOTIFICATION_ID, notification)
    } else {
      Log.w("WifiRssiService", "ScanResult for SSID $ssid not found.")
      _currentContext.update { current ->
        current.copy(freq = 0, ch = -127)
      }
      val notification = buildNotification("RSSI: $rssi dBm, SSID: $ssid (詳細不明)")
      (getSystemService(NOTIFICATION_SERVICE) as NotificationManager?)?.notify(NOTIFICATION_ID, notification)
    }
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val name: CharSequence = "Wi-Fi RSSI Monitor"
      val description = "Wi-FiのRSSIを監視します"
      val importance = NotificationManager.IMPORTANCE_LOW
      val channel = NotificationChannel(CHANNEL_ID, name, importance)
      channel.description = description

      val notificationManager = getSystemService(NotificationManager::class.java)
      notificationManager?.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(contentText: String): Notification {
    val notificationIntent = Intent(this, MainActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(
      this,
      0,
      notificationIntent,
      PendingIntent.FLAG_IMMUTABLE
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Wi-Fi RSSI Monitor")
      .setContentText(contentText)
      .setSmallIcon(R.drawable.ic_dialog_info)
      .setContentIntent(pendingIntent)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setOngoing(true)
      .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
      .build()
  }

  inner class WifiRssiServiceBinder : Binder() {
    val service: WifiRssiMonitoringService
      get() = this@WifiRssiMonitoringService
  }
}
