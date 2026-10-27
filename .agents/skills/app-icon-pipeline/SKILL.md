---
name: app-icon-pipeline
description: Generate comprehensive app icons for macOS (.icns, menu bar) and Android (adaptive mipmaps, play store) from vector or master artwork.
---

# App Icon Pipeline for macOS & Android

Use this skill when tasked with generating, packaging, or sizing app icons for macOS and Android applications.

## Prerequisites
- macOS environment with `sips`, `iconutil`, and `swiftc`.
- Master 1024×1024 PNG artwork (or vector SVG).

## 1. macOS Iconset & `.icns` Bundle

Create an `AppIcon.iconset` directory containing all 10 standard Apple Human Interface Guidelines resolutions:

```bash
mkdir -p AppIcon.iconset
sips -z 16 16     master_1024.png --out AppIcon.iconset/icon_16x16.png
sips -z 32 32     master_1024.png --out AppIcon.iconset/icon_16x16@2x.png
sips -z 32 32     master_1024.png --out AppIcon.iconset/icon_32x32.png
sips -z 64 64     master_1024.png --out AppIcon.iconset/icon_32x32@2x.png
sips -z 128 128   master_1024.png --out AppIcon.iconset/icon_128x128.png
sips -z 256 256   master_1024.png --out AppIcon.iconset/icon_128x128@2x.png
sips -z 256 256   master_1024.png --out AppIcon.iconset/icon_256x256.png
sips -z 512 512   master_1024.png --out AppIcon.iconset/icon_256x256@2x.png
sips -z 512 512   master_1024.png --out AppIcon.iconset/icon_512x512.png
sips -z 1024 1024 master_1024.png --out AppIcon.iconset/icon_512x512@2x.png

# Compile into .icns bundle
iconutil -c icns AppIcon.iconset -o AppIcon.icns
```

## 2. macOS Menu Bar Template Icon

Render monochromatic template icons (black with transparent background) for dark and light menu bar adaptation:
- `menu_bar_icon.png` (16×16)
- `menu_bar_icon@2x.png` (32×32)

## 3. Android Adaptive Mipmap Suite

For Android 8.0+ (API 26+) adaptive icons and legacy launchers:

### Density Buckets
Generate `ic_launcher.png`, `ic_launcher_round.png` (circular clipped), and `ic_launcher_foreground.png`:
- `mipmap-mdpi`: icon 48×48, foreground 108×108
- `mipmap-hdpi`: icon 72×72, foreground 162×162
- `mipmap-xhdpi`: icon 96×96, foreground 216×216
- `mipmap-xxhdpi`: icon 144×144, foreground 324×324
- `mipmap-xxxhdpi`: icon 192×192, foreground 432×432
- Google Play Store: 512×512 PNG (`ic_launcher-playstore.png`)

### Adaptive XML Configuration
Place in `res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
```

Define `@color/ic_launcher_background` in `res/values/colors.xml`, and link in `AndroidManifest.xml`:
```xml
<application
    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round" ... >
```
