package com.github.peco2282

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
fun WifiChannelGraph(
  wifiResults: List<WifiResult>,
  is5GHz: Boolean
) {
  // 帯域ごとのフィルタリング
  val targetResults = wifiResults.filter {
    if (is5GHz) it.freq > 4000 else it.freq < 3000
  }

  // 表示するチャンネル範囲の定義
  val channels = if (is5GHz) {
    listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140)
  } else {
    (1..14).toList()
  }

  Column(modifier = Modifier.fillMaxWidth().height(250.dp).padding(16.dp)) {
    Text(text = if (is5GHz) "5GHz Band Occupancy" else "2.4GHz Band Occupancy", fontSize = 14.sp)

    Canvas(modifier = Modifier.fillMaxSize()) {
      val widthPerCh = size.width / channels.size
      val height = size.height
      val maxRssi = -30f // グラフの上限 (強い)
      val minRssi = -100f // グラフの下限 (弱い)
      val rssiRange = abs(maxRssi - minRssi)

      // 枠線の描画
      drawLine(Color.Gray, Offset(0f, height), Offset(size.width, height), 2f) // X軸

      channels.forEachIndexed { index, ch ->
        val x = index * widthPerCh + (widthPerCh / 2)

        // チャンネル番号の描画 (簡易的)
        // note: drawContext.canvas.nativeCanvas.drawText 等が必要ですが、
        // 簡易化のためここではグリッド線のみ引きます
        drawLine(Color.LightGray, Offset(x, 0f), Offset(x, height), 1f)

        // このチャンネルに該当するAPを探して描画
        val apsOnCh = targetResults.filter { it.ch == ch }

        apsOnCh.forEach { ap ->
          // RSSIを高さに変換 (高いほど強い)
          val normalizedRssi = (ap.rssi.toFloat() - minRssi).coerceIn(0f, rssiRange)
          val barHeight = (normalizedRssi / rssiRange) * height

          // 山なり（放物線）を描画してスペクトラムアナライザっぽくする
          val path = Path().apply {
            moveTo(x - (widthPerCh / 1.5f), height)
            quadraticBezierTo(
              x, height - barHeight, // 頂点
              x + (widthPerCh / 1.5f), height
            )
          }

          // 色分け（SSIDのハッシュで色を変えるなど）
          val color = Color(ap.ssid.hashCode().toLong() or 0xFF000000)
          drawPath(path, color.copy(alpha = 0.5f))
          drawPath(path, color, style = Stroke(width = 3f))
        }
      }
    }
  }
}
