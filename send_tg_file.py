import urllib.request
import os

token = os.environ.get("TELEGRAM_BOT_TOKEN")
if not token:
    raise SystemExit("ERROR: TELEGRAM_BOT_TOKEN environment variable is not set.")
chat_id = os.environ.get("TELEGRAM_CHAT_ID")
if not chat_id:
    raise SystemExit("ERROR: TELEGRAM_CHAT_ID environment variable is not set.")
apk_path = r"E:\переводчик на телефон\app-release.apk"

boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
body = []

body.append(f"--{boundary}".encode())
body.append(f'Content-Disposition: form-data; name="chat_id"'.encode())
body.append(b"")
body.append(chat_id.encode())

body.append(f"--{boundary}".encode())
body.append(f'Content-Disposition: form-data; name="caption"'.encode())
body.append(b"")
body.append("📦 Готовый APK Offline Voice Translator v1.0.3".encode("utf-8"))

body.append(f"--{boundary}".encode())
body.append(f'Content-Disposition: form-data; name="document"; filename="app-release.apk"'.encode())
body.append(b"Content-Type: application/vnd.android.package-archive")
body.append(b"")
with open(apk_path, "rb") as f:
    body.append(f.read())

body.append(f"--{boundary}--".encode())
body.append(b"")

data = b"\r\n".join(body)

req = urllib.request.Request(
    f"https://api.telegram.org/bot{token}/sendDocument",
    data=data,
    headers={"Content-Type": f"multipart/form-data; boundary={boundary}"}
)

try:
    with urllib.request.urlopen(req) as resp:
        print("Telegram file sent:", resp.read().decode())
except Exception as e:
    print("Direct file upload error (expected if >50MB):", e)
