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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.peco2282.ui.theme.PowerAcquisitionTheme
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class MainActivity : ComponentActivity() {
  var wifiManager: WifiManager? = null
  private var monitorServiceIntent: Intent? = null
  private val listServiceIntent by lazy { Intent(this, WifiRssiListService::class.java) }
//    private var isStarted = false

  companion object {
    const val PERMISSION_REQUEST_CODE: Int = 1001

    private var _instance: MainActivity? = null
    fun getInstance() = _instance!!
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
    enableEdgeToEdge()
    _instance = synchronized(this) {
      _instance ?: this
    }
    bindService(listServiceIntent, listConnection, BIND_AUTO_CREATE)

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

  override fun onDestroy() {
    super.onDestroy()
    unbindService(listConnection)
  }

  private var wifiRssiMonitoringService: WifiRssiMonitoringService? = null
  private lateinit var wifiRssiListService: WifiRssiListService
  private var isBound = false

  private val monitorConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      val binder = service as WifiRssiMonitoringService.WifiRssiServiceBinder
      wifiRssiMonitoringService = binder.service
      isBound = true
      // サービス接続後、UIの更新を開始
      updateUiContent(true)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onServiceDisconnected(name: ComponentName?) {
      wifiRssiMonitoringService = null
      isBound = false
      updateUiContent(false)
    }
  }

  private val listConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      val binder = service as WifiRssiListService.RSSIBinder
      wifiRssiListService = binder.service
      updateUiContent(isBound)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      updateUiContent(isBound)
    }
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun updateUiContent(isServiceRunning: Boolean) {
    setContent {
      AppContent(
        ::startRssiMonitoringService,
        ::stopRssiMonitoringService,
        if (isServiceRunning) wifiRssiMonitoringService else null,
//        wifiRssiListService,
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
    if (monitorServiceIntent == null)
      monitorServiceIntent = Intent(this, WifiRssiMonitoringService::class.java)
    ContextCompat.startForegroundService(this, monitorServiceIntent!!)

    if (!isBound)
      bindService(monitorServiceIntent!!, monitorConnection, BIND_AUTO_CREATE)
    Toast.makeText(this, "RSSI監視を開始しました", Toast.LENGTH_SHORT).show()
    updateUiContent(true)
  }

  private fun stopRssiMonitoringService() {
    if (isBound) {
      unbindService(monitorConnection)
      isBound = false
      wifiRssiMonitoringService = null // Clear the service reference
    }

    if (monitorServiceIntent != null) {
      stopService(monitorServiceIntent)
      Toast.makeText(this, "RSSI監視を停止しました", Toast.LENGTH_SHORT).show()
      monitorServiceIntent = null
    } else {
      Toast.makeText(this, "サービスが開始していません", Toast.LENGTH_SHORT).show()
    }

    updateUiContent(false)
  }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent(
//    rssiStateFlow: StateFlow<Int>?,
  onStartServiceClick: () -> Unit,
  onStopServiceClick: () -> Unit,
  monitorService: WifiRssiMonitoringService?,
//  listService: WifiRssiListService?,
  isStarted: Boolean,
) {
  val context by monitorService?.currentContext?.collectAsStateWithLifecycle()
    ?: remember { mutableStateOf(UNRESOLVED_WIFI_CONTEXT) }

  val tabs = listOf("監視", "情報")
  val pagerState = rememberPagerState(pageCount = { tabs.size })
  val scope = rememberCoroutineScope()

  PowerAcquisitionTheme {
    Scaffold(
      modifier = Modifier.fillMaxSize(),
      topBar = {
        Text(tabs[pagerState.currentPage], modifier = Modifier.padding(30.dp))
      },
      bottomBar = {
      TabRow(pagerState.currentPage, modifier = Modifier.fillMaxWidth().padding(bottom = 50.dp)) {
        tabs.fastForEachIndexed { index, it ->
          Tab(
            selected = pagerState.currentPage == index,
            text = { Text(it) },
            onClick = {
              scope.launch {
                pagerState.animateScrollToPage(index)
              }
            }
          )
        }
      }
    }) { innerPadding ->
      HorizontalPager(
        state = pagerState,
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
        ) { page ->

        when (page) {
          0 -> MonitoringScreen(
            innerPadding,
            onStartServiceClick,
            onStopServiceClick,
            context,
            isStarted
          )
          1 -> WifiListScreen(
            innerPadding,
          )
        }
      }
    }
  }
}

@Composable
fun MonitoringScreen(
  innerPadding: PaddingValues,
  onStartServiceClick: () -> Unit,
  onStopServiceClick: () -> Unit,
  context: WifiContext,
  isStarted: Boolean,
) {
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

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun WifiListScreen(
  innerPadding: PaddingValues,
) {
  val listService = WifiRssiListService.getInstance()
  Log.i("WWW", listService.toString())
  if (listService != null) {
    listService.buildWifiCard()
    val card = WifiScanRepository.getInstance().getWifiResults()
    Log.i("WWW", card.toString())
    WifiChannelGraph(wifiResults = card, is5GHz = false)
    WifiChannelGraph(wifiResults = card, is5GHz = true)
    card.forEach {

      WifiCard(it)
    }
  }
}

@Composable
fun WifiCard(info: WifiResult) {
  Text(info.toString())
}

fun connectionInfo() = MainActivity.getInstance().wifiManager?.connectionInfo
