package com.github.peco2282

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log


class LocationChangeReceiver: BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
      // 位置情報サービスの現在の状態を再確認
      val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager


      // isProviderEnabled()を使ってGPSまたはネットワークの状態を確認
      val isLocationOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
          || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

      if (isLocationOn) {
        Log.d("LocationMonitor", "位置情報サービスがONになりました。")
        // ここでSSIDの取得を試みるなど、必要な処理を実行
      } else {
        Log.d("LocationMonitor", "位置情報サービスがOFFになりました。")
        // ユーザーにONにするよう促す通知やダイアログを表示（アクティビティがフォアグラウンドの場合）
      }
      // listener.onStateChange(isLocationOn); // インターフェース経由でActivityに通知
    }
  }
}
