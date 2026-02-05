"""
Re-encode Android launcher PNGs so AAPT can compile them.
Keeps the same image; strips metadata and writes clean PNGs.
Run from My-Cafe directory: python scripts/fix_ic_launcher_png.py
"""
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    print("Install Pillow: pip install Pillow")
    raise SystemExit(1)

RES_BASE = Path(__file__).resolve().parent.parent / "android" / "app" / "src" / "main" / "res"
MIPMAP_DIRS = ["mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"]
ICON_NAME = "ic_launcher.png"

def main():
    for d in MIPMAP_DIRS:
        path = RES_BASE / d / ICON_NAME
        if not path.exists():
            print(f"Skip (missing): {path}")
            continue
        try:
            img = Image.open(path).convert("RGBA")
            # Write to temp then replace to avoid partial failure
            tmp = path.with_suffix(".tmp.png")
            img.save(tmp, format="PNG", optimize=False)
            tmp.replace(path)
            print(f"OK: {path}")
        except Exception as e:
            print(f"Error {path}: {e}")
            raise
    print("Done.")

if __name__ == "__main__":
    main()
