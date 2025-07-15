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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.peco2282.ui.theme.PowerAcquisitionTheme
import kotlinx.coroutines.flow.StateFlow


class MainActivity : ComponentActivity() {
    private var wifiManager: WifiManager? = null
    private var serviceIntent: Intent? = null

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
                null, // サービスがまだ接続されていないのでnull
                ::startRssiMonitoringService,
                ::stopRssiMonitoringService
            )
        }

        checkLocationPermission()
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                PERMISSION_REQUEST_CODE
            )
        }
    }

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

    val PERMISSION_REQUEST_CODE: Int = 1001

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
                    wifiRssiMonitoringService?.currentRssi as StateFlow<Int>,
                    ::startRssiMonitoringService,
                    ::stopRssiMonitoringService
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            wifiRssiMonitoringService = null
            isBound = false
            setContent {
                AppContent(
                    null,
                    ::startRssiMonitoringService,
                    ::stopRssiMonitoringService
                )
            }
        }
    }

    private fun startRssiMonitoringService() {
        serviceIntent = Intent(this, WifiRssiMonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent!!)
        } else {
            startService(serviceIntent!!)
        }
        bindService(serviceIntent!!, connection, BIND_AUTO_CREATE)
        Toast.makeText(this, "RSSI監視を開始しました", Toast.LENGTH_SHORT).show()
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
    }
}

@Composable
fun AppContent(
    rssiStateFlow: StateFlow<Int>?,
    onStartServiceClick: () -> Unit,
    onStopServiceClick: () -> Unit
) {
    PowerAcquisitionTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val isStarted = (rssiStateFlow?.collectAsStateWithLifecycle() ?: remember { mutableIntStateOf(-1000) }).value != -1000
                // RSSIを表示するComposable
                RssiDisplay(rssiStateFlow)

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
            }
        }
    }
}

@Composable
fun RssiDisplay(rssiStateFlow: StateFlow<Int>?) {
    // StateFlowをComposeのStateとして収集
    // `collectAsStateWithLifecycle` を使用すると、ライフサイクルに安全に収集できる
    val rssi by rssiStateFlow?.collectAsStateWithLifecycle() ?: remember { mutableIntStateOf(-1000) }

    val displayRssiText = when (rssi) {
        -1000 -> {
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
