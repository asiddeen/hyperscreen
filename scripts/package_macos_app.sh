#!/usr/bin/env zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MACOS_DIR="$ROOT_DIR/macos-host"
APP_BUNDLE="$ROOT_DIR/build/HyperScreen.app"

echo "==> [1/4] Running C/C++20 Core Unit Tests (with 0ms FEC Reassembler)..."
clang++ -std=c++20 -O3 \
    -I"$ROOT_DIR/core/include" \
    "$ROOT_DIR/core/src/hs_protocol.cpp" \
    "$ROOT_DIR/core/src/hs_fec.cpp" \
    "$ROOT_DIR/core/src/hs_bitstream.cpp" \
    "$ROOT_DIR/core/src/hs_filter.cpp" \
    "$ROOT_DIR/core/src/hs_ringbuffer.cpp" \
    "$ROOT_DIR/core/tests/test_core.cpp" \
    -o /tmp/test_hyperscreen_core
/tmp/test_hyperscreen_core

echo "==> [2/4] Building HyperScreenHost (Swift 6 + Objective-C + C++20)..."
swift build -c release --package-path "$MACOS_DIR"

BIN_PATH="$MACOS_DIR/.build/release/HyperScreenHost"

echo "==> [3/4] Packaging Signed macOS Application Bundle ($APP_BUNDLE)..."
killall HyperScreenHost 2>/dev/null || true
rm -rf "$APP_BUNDLE"
mkdir -p "$APP_BUNDLE/Contents/MacOS"
mkdir -p "$APP_BUNDLE/Contents/Resources"

cp "$BIN_PATH" "$APP_BUNDLE/Contents/MacOS/HyperScreenHost"
chmod +x "$APP_BUNDLE/Contents/MacOS/HyperScreenHost"

# Copy Brand Icons & Retina Menu Bar Assets into Bundle Resources
if [ -d "$MACOS_DIR/Resources" ]; then
    cp -R "$MACOS_DIR/Resources/"* "$APP_BUNDLE/Contents/Resources/"
fi
if [ -f "$ROOT_DIR/assets/logo/hyperscreen_mark.png" ]; then
    cp "$ROOT_DIR/assets/logo/hyperscreen_mark.png" "$APP_BUNDLE/Contents/Resources/hyperscreen_mark.png"
fi

VERSION_FILE="$ROOT_DIR/VERSION"
if [ -f "$VERSION_FILE" ]; then
    APP_VERSION="$(cat "$VERSION_FILE" | tr -d '[:space:]')"
else
    APP_VERSION="1.0.0"
fi
GIT_COMMIT_COUNT=$(git -C "$ROOT_DIR" rev-list --count HEAD 2>/dev/null || echo 1)
GIT_COMMIT_HASH=$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo "unknown")
IFS='.' read -r V_MAJ V_MIN V_PAT <<< "$APP_VERSION"
BUILD_NUMBER=$(( ${V_MAJ:-1} * 100000 + ${V_MIN:-0} * 10000 + ${GIT_COMMIT_COUNT:-1} ))

echo "    • CFBundleShortVersionString: $APP_VERSION"
echo "    • CFBundleVersion:            $BUILD_NUMBER (commit #$GIT_COMMIT_COUNT, hash: $GIT_COMMIT_HASH)"

cat > "$APP_BUNDLE/Contents/Info.plist" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>HyperScreenHost</string>
    <key>CFBundleIdentifier</key>
    <string>dev.hyperscreen.host</string>
    <key>CFBundleName</key>
    <string>HyperScreen</string>
    <key>CFBundleDisplayName</key>
    <string>HyperScreen Pro</string>
    <key>CFBundleIconFile</key>
    <string>AppIcon</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>${APP_VERSION}</string>
    <key>CFBundleVersion</key>
    <string>${BUILD_NUMBER}</string>
    <key>HyperScreenGitCommit</key>
    <string>${GIT_COMMIT_HASH}</string>
    <key>HyperScreenCommitCount</key>
    <integer>${GIT_COMMIT_COUNT}</integer>
    <key>LSMinimumSystemVersion</key>
    <string>14.0</string>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>NSScreenCaptureUsageDescription</key>
    <string>HyperScreen captures the virtual extended display to stream it at up to 120Hz to your Android tablet.</string>
</dict>
</plist>
EOF

codesign --force --deep --identifier "dev.hyperscreen.host" -r='designated => identifier "dev.hyperscreen.host"' --sign - "$APP_BUNDLE"

# Copy to /Applications/HyperScreen.app so macOS TCC tracks a single canonical path
rm -rf "/Applications/HyperScreen.app" 2>/dev/null || true
cp -R "$APP_BUNDLE" "/Applications/HyperScreen.app" 2>/dev/null || true

echo "==> [4/5] Building macOS DMG Installer & Homebrew Cask Formula..."
DMG_STAGING="/tmp/hyperscreen_dmg_staging"
DMG_PATH="$ROOT_DIR/build/HyperScreen-${APP_VERSION}-macOS.dmg"
CASK_PATH="$ROOT_DIR/build/hyperscreen.rb"
rm -rf "$DMG_STAGING" "$DMG_PATH"
mkdir -p "$DMG_STAGING"
cp -R "$APP_BUNDLE" "$DMG_STAGING/HyperScreen.app"
ln -s /Applications "$DMG_STAGING/Applications"
hdiutil create -volname "HyperScreen Pro" -srcfolder "$DMG_STAGING" -ov -format UDZO "$DMG_PATH" >/dev/null
rm -rf "$DMG_STAGING"

DMG_SHA256="$(shasum -a 256 "$DMG_PATH" | awk '{print $1}')"
cat > "$CASK_PATH" << EOF
cask "hyperscreen" do
  version "${APP_VERSION}"
  sha256 "$DMG_SHA256"

  url "https://github.com/asiddeen/hyperscreen/releases/download/v#{version}/HyperScreen-#{version}-macOS.dmg"
  name "HyperScreen Pro"
  desc "Ultra-low-latency 120Hz USB-C & Wireless second display for macOS and Android"
  homepage "https://github.com/asiddeen/hyperscreen"

  depends_on macos: ">= :sonoma"

  app "HyperScreen.app"

  zap trash: [
    "~/Library/Preferences/dev.hyperscreen.host.plist",
    "~/Library/Application Support/HyperScreen"
  ]
end
EOF
echo "    [OK] DMG Installer: $DMG_PATH (sha256: $DMG_SHA256)"
echo "    [OK] Homebrew Cask: $CASK_PATH"

echo "==> [5/5] Running Hardware VideoToolbox + Zero-Copy IOSurface + 0ms FEC Loopback Benchmark..."
"$APP_BUNDLE/Contents/MacOS/HyperScreenHost" --benchmark-loopback
echo "==> Launching HyperScreen.app..."
if [ -d "/Applications/HyperScreen.app" ]; then
    open -n "/Applications/HyperScreen.app" || true
else
    open -n "$APP_BUNDLE" || true
fi
echo "==> Done! HyperScreen.app is running and ready at: $APP_BUNDLE"
