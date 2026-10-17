import urllib.request
import json
import os

token = os.environ.get("TELEGRAM_BOT_TOKEN")
if not token:
    raise SystemExit("ERROR: TELEGRAM_BOT_TOKEN environment variable is not set.")
chat_id = os.environ.get("TELEGRAM_CHAT_ID")
if not chat_id:
    raise SystemExit("ERROR: TELEGRAM_CHAT_ID environment variable is not set.")
# NB: keep in sync with versionName in app/build.gradle.kts
download_url = "https://github.com/xjanowskiy-afk/offline-ai-voice-translator/releases/download/v1.0.4/app-release.apk"

apk_path = r"E:\переводчик на телефон\app-release.apk"
size_mb = round(os.path.getsize(apk_path) / (1024 * 1024), 2) if os.path.exists(apk_path) else 52.3

text = (
    f"🎉 <b>Offline AI Voice Translator v1.0.4</b>\n\n"
    f"📦 <b>Файл:</b> app-release.apk\n"
    f"📊 <b>Размер:</b> {size_mb} МБ\n"
    f"📱 <b>Для:</b> Samsung Galaxy Z Fold 4 (Android 14 / One UI 6)\n\n"
    f"⬇️ <b>Прямая ссылка для установки на телефон:</b>\n"
    f"{download_url}\n\n"
    f"<i>Нажмите на ссылку выше прямо с телефона — файл скачается и сразу предложит установку поверх старой версии!</i>"
)

url = f"https://api.telegram.org/bot{token}/sendMessage"
data = json.dumps({
    "chat_id": chat_id,
    "text": text,
    "parse_mode": "HTML"
}).encode("utf-8")

req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
with urllib.request.urlopen(req) as resp:
    res = json.loads(resp.read().decode())
    print("Telegram response:", res.get("ok"))
