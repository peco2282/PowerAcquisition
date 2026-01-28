import requests
import time

# Android端末のIPアドレス（アプリ画面に表示されるもの）
ANDROID_IP = "172.16.198.199"  # ここを実際のIPに書き換えてください
BASE_URL = f"http://{ANDROID_IP}:8080"

def start_monitoring():
    print("Starting monitoring...")
    response = requests.get(f"{BASE_URL}/start")
    print(response.json())

def stop_monitoring():
    print("Stopping monitoring...")
    response = requests.get(f"{BASE_URL}/stop")
    print(response.json())

def get_status():
    response = requests.get(f"{BASE_URL}/status")
    data = response.json()
    if data['isStarted']:
        ctx = data['wifiContext']
        print(f"Status: Running, RSSI: {ctx['rssi']} dBm, SSID: {ctx['ssid']}, Ch: {ctx['ch']}")
    else:
        print("Status: Stopped")

if __name__ == "__main__":
    try:
        start_monitoring()
        for _ in range(5):
            time.sleep(3)
            get_status()
        stop_monitoring()
    except Exception as e:
        print(f"Error: {e}")
