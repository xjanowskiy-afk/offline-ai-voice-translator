import urllib.request
import os

token = os.environ.get("TELEGRAM_BOT_TOKEN")
if not token:
    raise SystemExit("ERROR: TELEGRAM_BOT_TOKEN environment variable is not set.")
chat_id = os.environ.get("TELEGRAM_CHAT_ID")
if not chat_id:
    raise SystemExit("ERROR: TELEGRAM_CHAT_ID environment variable is not set.")
file_path = r"E:\переводчик на телефон\PROJECT_FULL_HANDOVER_BUNDLE.zip"

size_mb = os.path.getsize(file_path) / (1024 * 1024)
print(f"Uploading Project Handover Bundle ({size_mb:.2f} MB) to Telegram...")

boundary = "----WebKitFormBoundaryHandoverZip777"
body = []

body.append(f"--{boundary}".encode())
body.append(f'Content-Disposition: form-data; name="chat_id"'.encode())
body.append(b"")
body.append(chat_id.encode())

body.append(f"--{boundary}".encode())
body.append(f'Content-Disposition: form-data; name="caption"'.encode())
body.append(b"")
body.append("📂 Полный архив исходного кода проекта Offline AI Voice Translator для передачи другому агенту/программисту.\nВнутри: полный код Kotlin/C++, CI/CD, ключи и файл PROJECT_HANDOVER_DOSSIER.md".encode("utf-8"))

body.append(f"--{boundary}".encode())
body.append(f'Content-Disposition: form-data; name="document"; filename="PROJECT_FULL_HANDOVER_BUNDLE.zip"'.encode())
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
