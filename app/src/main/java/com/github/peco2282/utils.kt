package com.github.peco2282

import android.net.wifi.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

data class WifiContext(
  val rssi: Int,
  val ssid: String,
  val freq: Int,
  val ch: Int,
)

data class WifiResult(
  val ssid: String,
  val bssid: String,
  val rssi: Int,
  val level: Int,
  val freq: Int,
  val ch: Int,
  val distanceMm: Int,
  val standard: String,
  val timestamp: Long,
  val capabilities: String,
  val channelWidth: Int,
  val macAddress: String,
) {
  fun asContext(): WifiContext = WifiContext(rssi = rssi, ssid = ssid, freq = freq, ch = ch)
}

data class PingResult(
    val success: Boolean, // Pingが成功したか
    val targetHost: String,
    val packetLossPercentage: Double, // パケットロス率 (%)
    val avgRtt: Double, // 平均応答時間 (ms)
    val minRtt: Double, // 最小応答時間 (ms)
    val maxRtt: Double, // 最大応答時間 (ms)
    val jittterMdev: Double, // ジッター/標準偏差 (ms)
    val rawOutput: String, // 解析前のpingコマンドの全出力
    val individualRtts: List<Double> // 各パケットのRTT (グラフ表示用)
)

val UNRESOLVED_WIFI_CONTEXT = WifiContext(-127, "<unresolved>", 0, -1)


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

fun getChannel(freq: Int): Int = when (freq) {
  in 4901..<5900 -> {
    channel5GHz(freq)
  }

  in 2001..<3000 -> {
    channel2400MHz(freq)
  }

  else -> {
    -1
  }
}

fun wifiStandardToString(standard: Int): String {
  when (standard) {
    ScanResult.WIFI_STANDARD_LEGACY -> return "legacy"
    ScanResult.WIFI_STANDARD_11N -> return "11n"
    ScanResult.WIFI_STANDARD_11AC -> return "11ac"
    ScanResult.WIFI_STANDARD_11AX -> return "11ax"
    ScanResult.WIFI_STANDARD_11AD -> return "11ad"
    ScanResult.WIFI_STANDARD_11BE -> return "11be"
    ScanResult.WIFI_STANDARD_UNKNOWN -> return "unknown"
  }
  return ""
}

fun wifiChannelWidth(width: Int) = when (width) {
  ScanResult.CHANNEL_WIDTH_20MHZ -> 20
  ScanResult.CHANNEL_WIDTH_40MHZ -> 40
  ScanResult.CHANNEL_WIDTH_80MHZ -> 80
  ScanResult.CHANNEL_WIDTH_160MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
  ScanResult.CHANNEL_WIDTH_320MHZ -> 320
  else -> width
}

suspend fun getPing(hostNameOrIp: String): PingResult = withContext(Dispatchers.IO) {
  val command = "ping -c 15 -W 30 $hostNameOrIp"
  var process: Process? = null
  val fullOutput = StringBuilder()
  val individualRtts = mutableListOf<Double>()

  try {
    process = Runtime.getRuntime().exec(command)
    val reader = BufferedReader(InputStreamReader(process.inputStream))
    var line: String?

    while (reader.readLine().also { line = it } != null) {
      fullOutput.append(line).append("\n")
      // 各パケットのRTTを抽出（例: time=2.5 ms）
      line?.let { parseIndividualRtt(it, individualRtts) }
    }

    // プロセスが終了するのを待つ
    process.waitFor()

    // サマリー行を解析してPingResultを作成
    return@withContext parseSummary(fullOutput.toString(), hostNameOrIp, individualRtts)

  } catch (e: Exception) {
    // エラーハンドリング (権限エラー、ホストが見つからないなど)
    e.printStackTrace()
    return@withContext PingResult(false, hostNameOrIp, 100.0, 0.0, 0.0, 0.0, 0.0, fullOutput.toString(), emptyList())
  } finally {
    process?.destroy()
  }
}

// 各パケットのRTTを抽出する簡易関数
private fun parseIndividualRtt(line: String, rttList: MutableList<Double>) {
  val rttMatch = Regex("time=(\\d+\\.?\\d+) ms").find(line)
  rttMatch?.groupValues?.get(1)?.toDoubleOrNull()?.let { rttList.add(it) }
}

// 最終的なサマリー行を解析する（この部分は最も複雑で、正規表現での厳密なパースが必要です）
private fun parseSummary(output: String, hostName: String, rttList: List<Double>): PingResult {
  // --- 1. パケットロス率の抽出 ---
  val lossMatch = Regex("(\\d+)% packet loss").find(output)
  val loss = lossMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 100.0

  // --- 2. RTT統計の抽出 ---
  // rtt min/avg/max/mdev = 0.536/0.793/1.121/0.244 ms のような行を探す
  val rttStatsMatch = Regex("rtt min/avg/max/mdev = (\\d+\\.?\\d+)/(\\d+\\.?\\d+)/(\\d+\\.?\\d+)/(\\d+\\.?\\d+) ms").find(output)

  val minRtt = rttStatsMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
  val avgRtt = rttStatsMatch?.groupValues?.get(2)?.toDoubleOrNull() ?: 0.0
  val maxRtt = rttStatsMatch?.groupValues?.get(3)?.toDoubleOrNull() ?: 0.0
  val mdev = rttStatsMatch?.groupValues?.get(4)?.toDoubleOrNull() ?: 0.0

  // 解析結果をまとめる
  val success = loss < 100.0 && avgRtt > 0.0

  return PingResult(success, hostName, loss, avgRtt, minRtt, maxRtt, mdev, output, rttList)
}
