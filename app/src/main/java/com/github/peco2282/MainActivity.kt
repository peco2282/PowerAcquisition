package com.github.peco2282

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import java.net.InetAddress
import java.net.NetworkInterface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.peco2282.ui.theme.PowerAcquisitionTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@RequiresApi(Build.VERSION_CODES.O)
class MainActivity : ComponentActivity() {
    private var wifiManager: WifiManager? = null
    private var serviceIntent: Intent? = null
//    private var isStarted = false

    companion object {
        const val PERMISSION_REQUEST_CODE: Int = 1001
        private var currentSSID: MutableStateFlow<String> = MutableStateFlow("<unresolved>")
        private var currentChannel: MutableStateFlow<Pair<Int, Int>> = MutableStateFlow(0 to -1)

        fun getCurrentSSID(): StateFlow<String> = currentSSID
    }

    private fun getIPAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is InetAddress) {
                        val host = addr.hostAddress
                        if (host != null && !host.contains(":")) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "Unknown"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        enableEdgeToEdge()

        val ipAddress = getIPAddress()

        setContent {
            val service by _wifiRssiMonitoringService.collectAsStateWithLifecycle()
            val serviceStarted by (service?.isMonitoringStarted ?: MutableStateFlow(false)).collectAsStateWithLifecycle()

            AppContent(
                ::startRssiMonitoringService,
                ::stopRssiMonitoringService,
                ::getSSID,
                service,
                currentSSID,
                currentChannel,
                serviceStarted,
                ipAddress
            )
        }

        checkPermission()
        // サービスを自動開始してHTTPサーバーを立ち上げる
        // 初回起動時は startMonitoring() は呼ばれないように修正済み
        startRssiMonitoringService()
    }

    private fun checkPermission() {
        val perms = getDeniedPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
        )
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun getDeniedPermissions(@Suppress("SameParameterValue") vararg permissions: String) = permissions
        .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String?>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                println("Permission granted")
            } else {
                println("Permission denied!")
            }
        }
    }

    private val _wifiRssiMonitoringService = MutableStateFlow<WifiRssiMonitoringService?>(null)
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WifiRssiMonitoringService.WifiRssiServiceBinder
            _wifiRssiMonitoringService.value = binder.service
            isBound = true
            // isStarted.value = true // Service側で管理
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _wifiRssiMonitoringService.value = null
            isBound = false
            // isStarted.value = false // Service側で管理
        }
    }

    private fun startRssiMonitoringService() {
        if (serviceIntent == null) {
            serviceIntent = Intent(this, WifiRssiMonitoringService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent!!)
            bindService(serviceIntent!!, connection, BIND_AUTO_CREATE)
        } else {
            // サービスが既に起動している場合は、モニタリングのみ開始する
            _wifiRssiMonitoringService.value?.startMonitoring()
        }
        Toast.makeText(this, "RSSI監視を開始しました", Toast.LENGTH_SHORT).show()
        // isStarted.value = true // Service側で管理
    }

    private fun stopRssiMonitoringService() {
        if (serviceIntent != null) {
            // サービスを停止させない（サーバーを維持するため）
            // stopService(serviceIntent)
            _wifiRssiMonitoringService.value?.stopMonitoring()
            Toast.makeText(this, "計測を停止しました", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "サービスが開始していません", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getSSID() {
        val ssid = wifiManager?.connectionInfo?.ssid?.replace("\"", "") ?: "未確認"
        currentSSID.value = ssid
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val scanResult = wifiManager?.scanResults.orEmpty().find { it.SSID == ssid } ?: return
        val ch =
            if (scanResult.frequency > 4000) channel5GHz(scanResult.frequency) else channel2400MHz(scanResult.frequency)
        currentChannel.value = scanResult.frequency to ch
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun AppContent(
//    rssiStateFlow: StateFlow<Int>?,
    onStartServiceClick: () -> Unit,
    onStopServiceClick: () -> Unit,
    onSSIDClick: () -> Unit,
    service: WifiRssiMonitoringService?,
    ssidStateFlow: StateFlow<String>,
    channelStateFlow: StateFlow<Pair<Int, Int>>,
    isStarted: Boolean,
    ipAddress: String
) {
    val channel = service?.currentContext
    val context by channel?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(WifiRssiMonitoringService.CONTEXT_INSTANCE) }
    PowerAcquisitionTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Control via Python: http://$ipAddress:8080/",
                    style = TextStyle(fontSize = TextUnit.Unspecified),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
//                val isStarted = context.rssi != WifiRssiMonitoringService.CONTEXT_INSTANCE.rssi
//                    (rssiStateFlow?.collectAsStateWithLifecycle()
//                    ?: remember { mutableIntStateOf(-1000) }).value != -1000


//                ShowSSID(ssidStateFlow)
                ShowChannel(context, ssidStateFlow, channelStateFlow)

                // RSSIを表示するComposable
                RssiDisplay(context)
                if (isStarted) {
                    Text("計測中...", style = TextStyle(color = androidx.compose.ui.graphics.Color.Red))
                }
                Log.i("MainActivity", "isStarted: $isStarted")

                Button(
                    onClick = onStartServiceClick,
                    modifier = Modifier.padding(top = 16.dp),
                    enabled = !isStarted
                ) {
                    Text("RSSI監視を開始")
                }
                Button(
                    onClick = onStopServiceClick,
                    modifier = Modifier.padding(top = 8.dp),
                    enabled = isStarted
                ) {
                    Text("RSSI監視を停止")
                }

                Button(
                    onClick = onSSIDClick,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("SSIDを取得")
                }
            }
        }
    }
}

@Composable
private fun ShowSSID(ssidStateFlow: StateFlow<String>?) {
    val text =
        "SSID: ${ssidStateFlow?.collectAsStateWithLifecycle()?.value ?: remember { mutableStateOf("未確認") }.value}"

    Text(
        text = text,
        modifier = Modifier.padding(16.dp),
        style = TextStyle(
            fontSize = TextUnit.Unspecified
        )
    )
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun RssiDisplay(
    context: WifiRssiMonitoringService.Companion.WifiContext,
//    rssiStateFlow: StateFlow<WifiRssiMonitoringService.Companion.WifiContext>?
) {
    // StateFlowをComposeのStateとして収集
    // `collectAsStateWithLifecycle` を使用すると、ライフサイクルに安全に収集できる
//    val rssi by rssiStateFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(WifiRssiMonitoringService.Companion.CONTEXT_INSTANCE) }
//    val context by rssiStateFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(WifiRssiMonitoringService.Companion.CONTEXT_INSTANCE) }
    val rssi = context.rssi
    val displayRssiText = when (rssi) {
        -1000, -127 -> {
            "RSSI: 測定待機中 / サービス未開始"
        }

        Integer.MIN_VALUE -> { // サービス内の初期値またはエラー値
            "RSSI: Wi-Fi未接続"
        }

        else -> {
            "現在のRSSI: $rssi dBm"
        }
    }

    Text(
        text = displayRssiText,
        modifier = Modifier.padding(16.dp)
    )
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ShowChannel(
    context: WifiRssiMonitoringService.Companion.WifiContext,
    ssidStateFlow: StateFlow<String>,
    channelStateFlow: StateFlow<Pair<Int, Int>>,
//    contextStateFlow: StateFlow<WifiRssiMonitoringService.Companion.WifiContext>?
) {
    val ssidSF by ssidStateFlow.collectAsStateWithLifecycle()
    val channelSF by channelStateFlow.collectAsStateWithLifecycle()
    val (ssid, freq, ch) = if (context.ssid == "<unresolved>")
        Triple(ssidSF, channelSF.first, channelSF.second) else Triple(context.ssid, context.freq, context.ch)
    ShowSSID(MutableStateFlow(ssid))
    Text(
        "Frequency: $freq Channel: $ch",
        modifier = Modifier.padding(16.dp),
        style = TextStyle(
            fontSize = TextUnit.Unspecified
        )
    )
}
