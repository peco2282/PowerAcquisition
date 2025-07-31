package com.github.peco2282

//noinspection SuspiciousImport
import android.R
import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
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
        data class WifiContext(
            val rssi: Int,
            val ssid: String,
            val freq: Int,
            val ch: Int,
//            val bssid: String,
//            val timestamp: Long,
        ) {
            fun `override`(
                mutableRssi: MutableStateFlow<WifiContext>,
                rssi: Int = this.rssi,
                ssid: String = this.ssid,
                freq: Int = this.freq,
                ch: Int = this.ch,
            ) = WifiContext(rssi, ssid, freq, ch).also { mutableRssi.value = it }
        }

        @RequiresApi(Build.VERSION_CODES.O)
        val CONTEXT_INSTANCE = WifiContext(
            -127,
            "<unresolved>",
            0,
            -1
        )

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

    @RequiresApi(Build.VERSION_CODES.O)
    private val _currentContext = MutableStateFlow(
        CONTEXT_INSTANCE.copy()
    )

    val currentContext: StateFlow<WifiContext>
        @RequiresApi(Build.VERSION_CODES.O)
        get() = _currentContext

    var rssi = -1000

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
                this.rssi = rssi
                val ssid = wifiInfo.ssid.replace("\"", "")

                _currentContext.value.override(_currentContext, rssi = rssi, ssid = ssid)

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
                    val ch = when {
                        freq > 4900 && freq < 5900 -> {
                            channel5GHz(freq)
                        }

                        2000 < freq && freq < 3000 -> {
                            channel2400MHz(freq)
                        }

                        else -> {
                            Log.w("WifiRssiService", "Frequency out of range: $freq")
                            Log.w("WifiRssiService", "ScanResults: ${wifiManager.scanResults}")
                            Log.w("WifiRssiService", "ConnectionInfo: $wifiInfo")
                            Log.w("WifiRssiService", "SSID: $ssid")
                            Toast.makeText(this, "Frequency out of range: $freq", Toast.LENGTH_LONG).show()

                            -1
                        }
                    }
                    if (ch != -1) {
                        sb
                            .appendLine()
                            .append("Channel: ")
                            .append(ch)
                            .append(" , Frequencies: ")
                            .append(freq)
                            .append(" MHz")
                    }
                    _currentContext.value.override(
                        _currentContext,
                        freq = freq,
                        ch = ch
                    )
                }
                saveToExternalFilesDir(DATE_FORMATTER.format(Date()) + "," + rssi.toString())
                Log.d("WifiRssiService", sb.toString())
                Log.d("WifiRssiService", "---------")


                val notification = buildNotification(sb.toString())
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


fun channel2400MHz(value: Int): Int = when (value) {
    2412 -> 1
    2417 -> 2
    2422 -> 3
    2427 -> 4
    2432 -> 5
    2437 -> 6
    2442 -> 7
    2447 -> 8
    2452 -> 9
    2457 -> 10
    2462 -> 11
    2467 -> 12
    2472 -> 13
    2484 -> 14
    else -> -1
}

fun channel5GHz(value: Int): Int = when (value) {
    5180 -> 36
    5200 -> 40
    5220 -> 44
    5240 -> 48
    5260 -> 52
    5280 -> 56
    5300 -> 60
    5320 -> 64
    5500 -> 100
    5520 -> 104
    5540 -> 108
    5560 -> 112
    5580 -> 116
    5600 -> 120
    5620 -> 124
    5640 -> 128
    5660 -> 132
    5680 -> 136

    else -> -1
}

