# HyperScreen Brand Assets & Design System

This directory houses the official visual identity, vector logos, macOS app icons, Android adaptive mipmaps, and marketing banners for **HyperScreen**.

---

## 🎨 Color Palette & Design Tokens

| Token | Hex | Role |
| :--- | :--- | :--- |
| **Hyper Cyan** | `#00F2FE` / `#38BDF8` | Primary speed & optical bridge pulse |
| **Electric Indigo** | `#818CF8` / `#6366F1` | Inter-device synchronization & wireless beam |
| **Hyper Violet** | `#A855F7` / `#C084FC` | Android client display & high-refresh 120Hz badge |
| **Titanium Void** | `#0A0E1A` / `#06080F` | Deep space squircle background & adaptive icon base |
| **Display Bezel Slate** | `#1E293B` / `#334155` | Hardware frame outlines & glass specular highlights |
| **Text-Sharp White** | `#FFFFFF` / `#E0F2FE` | Filament core & typography highlights |

---

## 📁 Directory Structure

```text
assets/
├── banners/
│   ├── hyperscreen_hero_banner.jpg    # 16:9 Hero cinematic banner for README & Web
│   └── hyperscreen_studio_banner.jpg  # 16:9 Studio desktop rendering
├── logo/
│   ├── hyperscreen_logo.svg           # Primary horizontal vector logo (Dark mode default)
│   ├── hyperscreen_logo_dark.svg      # Horizontal vector logo for dark backgrounds
│   ├── hyperscreen_logo_light.svg     # Horizontal vector logo for light backgrounds
│   ├── hyperscreen_mark.svg           # Square standalone vector logomark (512x512)
│   └── hyperscreen_mark.png           # Master 1024x1024 transparent PNG mark
└── icons/
    ├── AppIcon_1024.png               # Master 1024x1024 raster icon
    ├── macos/
    │   ├── AppIcon.icns               # Universal compiled macOS bundle icon (16x16 → 1024x1024)
    │   ├── AppIcon.iconset/           # Complete Apple HIG iconset source files
    │   ├── menu_bar_icon.png          # 16x16 macOS Menu Bar status icon
    │   └── menu_bar_icon@2x.png       # 32x32 Retina macOS Menu Bar status icon
    └── android/
        ├── ic_launcher-playstore.png  # 512x512 Google Play Store high-res icon
        ├── mipmap-anydpi-v26/         # Android 8.0+ Adaptive Icon XML wrappers
        ├── mipmap-mdpi/               # 48x48 icon, 108x108 foreground
        ├── mipmap-hdpi/               # 72x72 icon, 162x162 foreground
        ├── mipmap-xhdpi/              # 96x96 icon, 216x216 foreground
        ├── mipmap-xxhdpi/             # 144x144 icon, 324x324 foreground
        └── mipmap-xxxhdpi/            # 192x192 icon, 432x432 foreground
```

---

## 🚀 Integration Details

- **macOS Host (`macos-host/Resources/`)**:
  - `AppIcon.icns` embedded into the app bundle.
  - `menu_bar_icon.png` & `@2x` available for `NSStatusItem` in the macOS menu bar.
- **Android Client (`android-client/app/src/main/res/`)**:
  - All mipmap density buckets (`mdpi`, `hdpi`, `xhdpi`, `xxhdpi`, `xxxhdpi`) populated with both standard squircle (`ic_launcher.png`) and round (`ic_launcher_round.png`).
  - Adaptive XML (`mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`) referencing `@color/ic_launcher_background` and `@mipmap/ic_launcher_foreground`.
  - Configured in [AndroidManifest.xml](file:///Users/asiddeenmuhammad/Projects/hyperscreen/android-client/app/src/main/AndroidManifest.xml) with `android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher_round"`.
