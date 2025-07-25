package com.github.peco2282

//noinspection SuspiciousImport
import android.R
import android.app.*
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class WifiRssiMonitoringService : Service() {
    @RequiresApi(Build.VERSION_CODES.O)
    companion object {
        const val CHANNEL_ID: String = "WifiRssiMonitoringChannel"

        const val NOTIFICATION_ID: Int = 123

        const val UPDATE_INTERVAL: Long = 2000

        val FNAME_FORMATTER: DateFormat = SimpleDateFormat("'wifi_'yyyyMMdd_HHmmss.'csv'", Locale.JAPAN)

        val DATE_FORMATTER: DateFormat = SimpleDateFormat("yyyy-MM-dd_HH:mm:ss.SSS", Locale.JAPAN)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private var currentFName: String? = FNAME_FORMATTER.format(Date())

    private var handler: Handler? = null
    private var updateRssiRunnable: Runnable? = null
    private var isStarted = false

    // RSSI値を公開するためのStateFlow
    private val _currentRssi = MutableStateFlow(-1000) // 初期値は無効なRSSI
    val currentRssi: StateFlow<Int>? = _currentRssi

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        handler = Handler(Looper.myLooper()!!)

        updateRssiRunnable = object : Runnable {
            override fun run() {
                if (!isStarted) return
                updateRssi()
                handler!!.postDelayed(this, UPDATE_INTERVAL)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
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

    @RequiresApi(Build.VERSION_CODES.O)
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

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onDestroy() {
        super.onDestroy()
        handler!!.removeCallbacks(updateRssiRunnable!!)
        Log.i("WifiRssiService", "Service destroyed")
        _currentRssi.value = -1000
        currentFName = null
        isStarted = false
    }

    override fun onBind(intent: Intent?): IBinder {
        return WifiRssiServiceBinder()
    }


    @RequiresApi(Build.VERSION_CODES.O)
    private fun updateRssi() {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager?
        if (wifiManager != null) {
            val wifiInfo = wifiManager.connectionInfo
            if (wifiInfo != null) {
                _currentRssi.value = wifiInfo.rssi
                val rssi = wifiInfo.rssi

                val contentText = "RSSI: $rssi dBm\nSSID: ${MainActivity.getCurrentSSID().value}"
                saveToExternalFilesDir(DATE_FORMATTER.format(Date()) + "," + rssi.toString())
                Log.d("WifiRssiService", contentText)

                val notification = buildNotification(contentText)
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager?
                notificationManager?.notify(NOTIFICATION_ID, notification)
            } else {
                Log.w("WifiRssiService", "WifiInfo is null")
                _currentRssi.value = -1000 // Wi-Fi未接続などで無効な値
                val notification = buildNotification("Wi-Fi未接続")
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager?
                notificationManager?.notify(NOTIFICATION_ID, notification)
            }
        } else {
            Log.e("WifiRssiService", "WifiManager is null")
            _currentRssi.value = -1000
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
            .build()
    }

    inner class WifiRssiServiceBinder : Binder() {
        val service: WifiRssiMonitoringService
            get() = this@WifiRssiMonitoringService
    }
}
