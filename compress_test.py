import zipfile
import os

apk_path = r"E:\переводчик на телефон\app-release.apk"
zip_path = r"E:\переводчик на телефон\app-release.zip"

with zipfile.ZipFile(zip_path, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    z.write(apk_path, arcname="app-release.apk")

size_mb = os.path.getsize(zip_path) / (1024 * 1024)
print(f"Compressed size: {size_mb:.2f} MB")
