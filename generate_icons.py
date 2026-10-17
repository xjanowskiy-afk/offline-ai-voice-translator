import os
import sys
from PIL import Image

src_img_path = r"C:\Users\555\.gemini\antigravity\brain\7f19311a-07e9-4a37-ab1e-5edf9a5dd929\translator_app_icon_1792094526020.jpg"
res_dir = r"E:\переводчик на телефон\app\src\main\res"

if not os.path.exists(src_img_path):
    print(f"Error: {src_img_path} not found")
    sys.exit(1)

img = Image.open(src_img_path).convert("RGBA")

# Android standard icon sizes
# mdpi: 48x48
# hdpi: 72x72
# xhdpi: 96x96
# xxhdpi: 144x144
# xxxhdpi: 192x192
densities = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

for folder, size in densities.items():
    out_dir = os.path.join(res_dir, folder)
    os.makedirs(out_dir, exist_ok=True)
    
    # Save standard launcher icon
    resized_img = img.resize((size, size), Image.Resampling.LANCZOS)
    launcher_path = os.path.join(out_dir, "ic_launcher.png")
    round_path = os.path.join(out_dir, "ic_launcher_round.png")
    
    resized_img.save(launcher_path, "PNG")
    resized_img.save(round_path, "PNG")
    print(f"Saved {launcher_path} ({size}x{size})")

# Also save high-res store icon in res root
store_icon_path = os.path.join(res_dir, "ic_launcher-web.png")
img.resize((512, 512), Image.Resampling.LANCZOS).save(store_icon_path, "PNG")
print(f"Saved {store_icon_path} (512x512)")
print("Icon generation completed successfully!")
