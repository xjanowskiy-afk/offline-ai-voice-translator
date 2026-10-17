import zipfile
import os

project_root = r"E:\переводчик на телефон"
archive_path = r"E:\переводчик на телефон\PROJECT_FULL_HANDOVER_BUNDLE.zip"

# Исключаем служебные кэши и локальную историю git (сам репозиторий привязан к GitHub)
EXCLUDE_DIRS = {".git", ".gradle", "build", ".idea", "video_frames"}
EXCLUDE_FILES = {
    "app-release.apk", 
    "app-release.zip", 
    "test.zip", 
    "video_2026-10-16_18-27-28.mp4",
    "PROJECT_FULL_HANDOVER_BUNDLE.zip"
}

print("Формирование супер-компактного архива (до 1 МБ)...")

with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zipf:
    for root, dirs, files in os.walk(project_root):
        # Исключаем лишние папки
        dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS and not d.endswith(".gradle")]

        for file in files:
            if file in EXCLUDE_FILES or file.endswith(".pyc"):
                continue
            
            full_path = os.path.join(root, file)
            rel_path = os.path.relpath(full_path, project_root)
            zipf.write(full_path, arcname=rel_path)

size_bytes = os.path.getsize(archive_path)
size_mb = size_bytes / (1024 * 1024)
size_kb = size_bytes / 1024
print(f"Готово! Размер архива: {size_kb:.1f} КБ ({size_mb:.2f} МБ)")
