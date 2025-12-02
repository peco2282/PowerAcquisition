package com.github.peco2282

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.peco2282.ui.theme.PowerAcquisitionTheme

class MainActivity : ComponentActivity() {
  var wifiManager: WifiManager? = null
  private var serviceIntent: Intent? = null
//    private var isStarted = false

  companion object {
    const val PERMISSION_REQUEST_CODE: Int = 1001

    private var _instance: MainActivity? = null
    fun getInstance() = _instance!!
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
    enableEdgeToEdge()
    _instance = synchronized(this) {
      _instance ?: this
    }
    // サービスにバインドする前に、初期のUIを表示
    setContent {
      AppContent(
        ::startRssiMonitoringService,
        ::stopRssiMonitoringService,
        null,
        false
      )
    }

    checkPermission()
  }

  private var wifiRssiMonitoringService: WifiRssiMonitoringService? = null
  private var isBound = false

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      val binder = service as WifiRssiMonitoringService.WifiRssiServiceBinder
      wifiRssiMonitoringService = binder.service
      isBound = true
      // サービス接続後、UIの更新を開始
      updateUiContent(true)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      wifiRssiMonitoringService = null
      isBound = false
      updateUiContent(false)
    }
  }

  private fun updateUiContent(isServiceRunning: Boolean) {
    setContent {
      AppContent(
        ::startRssiMonitoringService,
        ::stopRssiMonitoringService,
        service = if (isServiceRunning) wifiRssiMonitoringService else null,
        isStarted = isServiceRunning
      )
    }
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

  private fun startRssiMonitoringService() {
    if (serviceIntent == null)
      serviceIntent = Intent(this, WifiRssiMonitoringService::class.java)
    ContextCompat.startForegroundService(this, serviceIntent!!)

    if (!isBound)
      bindService(serviceIntent!!, connection, BIND_AUTO_CREATE)
    Toast.makeText(this, "RSSI監視を開始しました", Toast.LENGTH_SHORT).show()
    updateUiContent(true)
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

    updateUiContent(false)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent(
//    rssiStateFlow: StateFlow<Int>?,
  onStartServiceClick: () -> Unit,
  onStopServiceClick: () -> Unit,
  service: WifiRssiMonitoringService?,
  isStarted: Boolean,
) {
  val context by service?.currentContext?.collectAsStateWithLifecycle()
    ?: remember { mutableStateOf(UNRESOLVED_WIFI_CONTEXT) }
  PowerAcquisitionTheme {
    Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
      TopAppBar(title = { Text("Wi-Fi") })
    }) { innerPadding ->
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Spacer(modifier = Modifier.height(24.dp))

        ShowContext(context) // SSID, Channel, Freqを表示

        Spacer(modifier = Modifier.height(24.dp))

        RssiDisplay(context) // RSSI値を大きく表示

        Spacer(modifier = Modifier.height(48.dp))
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
fun RssiDisplay(
  context: WifiContext,
) {

  val displayRssiText = when (val rssi = context.rssi) {
    -127 -> {
      val rssi = connectionInfo()?.rssi
      "RSSI: ${if (rssi != null) ("$rssi dBm") else "測定待機中 / サービス未開始"}"
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
    modifier = Modifier.padding(20.dp)
  )
}

@Composable
fun ShowContext(
  context: WifiContext
) {
  // SSID
  val ssidText = (connectionInfo()?.ssid?.replace("\"", "") ?: context.ssid).ifEmpty { "<Hidden SSID>" }
  Text(
    "SSID: $ssidText",
    modifier = Modifier.padding(top = 8.dp),
    style = TextStyle(fontSize = 18.sp)
  )

  // Frequency and Channel
  val freqText = if (context.freq > 0) "${context.freq} MHz" else {
    val freq = connectionInfo()?.frequency
    if (freq == null) "未取得"
    else "$freq MHz"
  }
  val chText = if (context.ch != -1) context.ch.toString() else {
    val freq = connectionInfo()?.frequency ?: 0
    getChannel(freq).toString()
  }

  Text(
    "Frequency: $freqText / Channel: $chText",
    modifier = Modifier.padding(bottom = 8.dp),
    style = TextStyle(fontSize = 18.sp)
  )
}

fun connectionInfo() = MainActivity.getInstance().wifiManager?.connectionInfo
