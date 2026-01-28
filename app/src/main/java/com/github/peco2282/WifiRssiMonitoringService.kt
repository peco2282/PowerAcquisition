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
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
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
        @Serializable
        data class WifiContext(
            val rssi: Int,
            val ssid: String,
            val freq: Int,
            val ch: Int,
        )

        @Serializable
        data class StatusResponse(
            val isStarted: Boolean,
            val wifiContext: WifiContext?
        )

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
    private var currentFName: String? = null

    private var handler: Handler? = null
    private var updateRssiRunnable: Runnable? = null
    private val _isStarted = MutableStateFlow(false)
    val isMonitoringStarted: StateFlow<Boolean> get() = _isStarted

    private var serverJob: Job? = null
    private val serverScope = CoroutineScope(Dispatchers.IO)

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
                if (!_isStarted.value) return
                updateRssi()
                handler!!.postDelayed(this, UPDATE_INTERVAL)
            }
        }
        startHttpServer()
    }

    private fun startHttpServer() {
        Log.i("WifiRssiService", "Starting HTTP Server on port 8080...")
        serverJob = serverScope.launch {
            try {
                embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
                    install(ContentNegotiation) {
                        json()
                    }
                    routing {
                        get("/") {
                            call.respond(mapOf("status" to "ok", "service" to "WifiRssiMonitoringService"))
                        }
                        get("/start") {
                            if (!_isStarted.value) {
                                handler?.post {
                                    startMonitoring()
                                }
                                call.respond(mapOf("message" to "Monitoring started"))
                            } else {
                                call.respond(mapOf("message" to "Monitoring already running"))
                            }
                        }
                        get("/stop") {
                            if (_isStarted.value) {
                                handler?.post {
                                    stopMonitoring()
                                }
                                call.respond(mapOf("message" to "Monitoring stopped"))
                            } else {
                                call.respond(mapOf("message" to "Monitoring not running"))
                            }
                        }
                        get("/status") {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                call.respond(StatusResponse(_isStarted.value, _currentContext.value))
                            } else {
                                call.respond(mapOf("isStarted" to _isStarted.value))
                            }
                        }
                    }
                }.start(wait = true)
            } catch (e: Exception) {
                Log.e("WifiRssiService", "Failed to start HTTP Server", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun startMonitoring() {
        if (_isStarted.value) return
        currentFName = FNAME_FORMATTER.format(Date())
        val data = "Date,RSSI"
        val file = saveToExternalFilesDir(data)
        val txt = "保存先: ${file?.absolutePath}"
        Toast.makeText(this, txt, Toast.LENGTH_LONG).show()

        val notification = buildNotification("RSSI: 測定中...")
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)

        handler?.post(updateRssiRunnable!!)
        _isStarted.value = true
        Log.i("WifiRssiService", "Monitoring started")
    }

    fun stopMonitoring() {
        if (!_isStarted.value) return
        handler?.removeCallbacks(updateRssiRunnable!!)
        _isStarted.value = false
        // 計測停止時もフォアグラウンドサービスを維持するため、通知を更新する
        val notification = buildNotification("サーバー待機中...")
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        Log.i("WifiRssiService", "Monitoring stopped")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("サーバー待機中...")
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun saveToExternalFilesDir(data: String): File? {
        val fName = currentFName ?: return null
        val appSpecificDir = getExternalFilesDir(null)
        if (appSpecificDir == null) {
            println("Service: 外部ストレージのアプリ専用ディレクトリが見つかりません。")
            return null
        }

        val file = File(appSpecificDir, fName)
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
        stopMonitoring()
        serverJob?.cancel()
        _currentRssi.value = -1000
        currentFName = null
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

                _currentContext.value = _currentContext.value.copy(rssi = rssi, ssid = ssid)

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
                    _currentContext.value = _currentContext.value.copy(
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

val CHANNEL_2400MHz = mapOf(
    2412 to 1,
    2417 to 2,
    2422 to 3,
    2427 to 4,
    2432 to 5,
    2437 to 6,
    2442 to 7,
    2447 to 8,
    2452 to 9,
    2457 to 10,
    2462 to 11,
    2467 to 12,
    2472 to 13,
    2484 to 14
)

val CHANNEL_5000MHz = mapOf(
    5180 to 36,
    5200 to 40,
    5220 to 44,
    5240 to 48,
    5260 to 52,
    5280 to 56,
    5300 to 60,
    5320 to 64,
    5500 to 100,
    5520 to 104,
    5540 to 108,
    5560 to 112,
    5580 to 116,
    5600 to 120,
    5620 to 124,
    5640 to 128,
    5660 to 132,
    5680 to 136,
)

fun channel2400MHz(value: Int): Int = CHANNEL_2400MHz.getOrDefault(value, -1)

fun channel5GHz(value: Int): Int = CHANNEL_5000MHz.getOrDefault(value, -1)
