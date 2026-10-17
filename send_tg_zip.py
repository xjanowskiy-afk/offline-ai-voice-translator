import urllib.request
import os

token = os.environ.get("TELEGRAM_BOT_TOKEN")
if not token:
    raise SystemExit("ERROR: TELEGRAM_BOT_TOKEN environment variable is not set.")
chat_id = os.environ.get("TELEGRAM_CHAT_ID")
if not chat_id:
    raise SystemExit("ERROR: TELEGRAM_CHAT_ID environment variable is not set.")
file_path = r"E:\переводчик на телефон\app-release.zip"

size_mb = os.path.getsize(file_path) / (1024 * 1024)
print(f"Uploading {size_mb:.2f} MB to Telegram...")

boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
body = []

body.append(f"--{boundary}".encode())
body.append(f'Content-Disposition: form-data; name="chat_id"'.encode())
body.append(b"")
body.append(chat_id.encode())

body.append(f"--{boundary}".encode())
body.append(f'Content-Disposition: form-data; name="caption"'.encode())
body.append(b"")
body.append("📦 Готовый релиз Offline AI Voice Translator v1.0.3\nВнутри архива: app-release.apk (распакуйте и установите)".encode("utf-8"))

body.append(f"--{boundary}".encode())
body.append(f'Content-Disposition: form-data; name="document"; filename="Offline_Translator_v1.0.3.zip"'.encode())
body.append(b"Content-Type: application/zip")
body.append(b"")
with open(file_path, "rb") as f:
    body.append(f.read())

body.append(f"--{boundary}--".encode())
body.append(b"")

data = b"\r\n".join(body)

req = urllib.request.Request(
    f"https://api.telegram.org/bot{token}/sendDocument",
    data=data,
    headers={"Content-Type": f"multipart/form-data; boundary={boundary}"}
)

with urllib.request.urlopen(req) as resp:
    print("Telegram response:", resp.read().decode())
