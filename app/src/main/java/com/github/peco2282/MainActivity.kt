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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        enableEdgeToEdge()
//        setContent {
//            PowerAcquisitionTheme {
//                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
//                    Greeting(
//                        name = "Android",
//                        modifier = Modifier.padding(innerPadding)
//                    )
//                }
//            }
//        }
        // サービスにバインドする前に、初期のUIを表示
        setContent {
            AppContent(
                ::startRssiMonitoringService,
                ::stopRssiMonitoringService,
                ::getSSID,
                null,
                currentSSID,
                currentChannel,
                false
            )
        }

        checkPermission()
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

    private var wifiRssiMonitoringService: WifiRssiMonitoringService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WifiRssiMonitoringService.WifiRssiServiceBinder
            wifiRssiMonitoringService = binder.service
            isBound = true
            // サービス接続後、UIの更新を開始
            setContent {
                AppContent(
//                    wifiRssiMonitoringService?.currentRssi as StateFlow<Int>,
                    ::startRssiMonitoringService,
                    ::stopRssiMonitoringService,
                    ::getSSID,
                    //                    wifiRssiMonitoringService?.currentSSID as StateFlow<String>
                    wifiRssiMonitoringService,
                    currentSSID,
                    currentChannel,
                    true
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            wifiRssiMonitoringService = null
            isBound = false
            setContent {
                AppContent(
//                    null,
                    ::startRssiMonitoringService,
                    ::stopRssiMonitoringService,
                    ::getSSID,
                    wifiRssiMonitoringService,
                    currentSSID,
                    currentChannel,
                    false
                )
            }
        }
    }

    private fun startRssiMonitoringService() {
        serviceIntent = Intent(this, WifiRssiMonitoringService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent!!)

        bindService(serviceIntent!!, connection, BIND_AUTO_CREATE)
        Toast.makeText(this, "RSSI監視を開始しました", Toast.LENGTH_SHORT).show()
        setContent {
            AppContent(
                ::startRssiMonitoringService,
                ::stopRssiMonitoringService,
                ::getSSID,
                wifiRssiMonitoringService,
                currentSSID,
                currentChannel,
                true
            )
        }
    }

    private fun stopRssiMonitoringService() {
        if (isBound) {
            unbindService(connection)
            isBound = false
            wifiRssiMonitoringService = null // Clear the service reference
        }

        if (serviceIntent != null) {
            stopService(serviceIntent)
            Toast.makeText(this, "RSSI監視を停止しました", Toast.LENGTH_SHORT).show()
            serviceIntent = null
        } else {
            Toast.makeText(this, "サービスが開始していません", Toast.LENGTH_SHORT).show()
        }

        setContent {
            AppContent(
                ::startRssiMonitoringService,
                ::stopRssiMonitoringService,
                ::getSSID,
                wifiRssiMonitoringService,
                currentSSID,
                currentChannel,
                false
            )
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
//                val isStarted = context.rssi != WifiRssiMonitoringService.CONTEXT_INSTANCE.rssi
//                    (rssiStateFlow?.collectAsStateWithLifecycle()
//                    ?: remember { mutableIntStateOf(-1000) }).value != -1000


//                ShowSSID(ssidStateFlow)
                ShowChannel(context, ssidStateFlow, channelStateFlow)

                // RSSIを表示するComposable
                RssiDisplay(context)
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
