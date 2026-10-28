# HyperScreen Project Rules & Guardrails

## 1. Build, Package & Deployment Commands
- **macOS Host (`HyperScreen.app`)**:
  - Always build, sign, benchmark, and relaunch via:
    `zsh /Users/asiddeenmuhammad/Projects/hyperscreen/scripts/package_macos_app.sh`
  - Menu bar icons (`NSStatusBarButton.image`) MUST keep `isTemplate = true` so macOS automatically inverts them in Dark and Light modes.
- **Android Client (`app-debug.apk`)**:
  - Keep `gradle-wrapper.properties` pinned to a Gradle version compatible with the active AGP (`gradle-9.3.1-bin.zip` with `AGP 9.0.0`). Do NOT hardcode machine-specific paths like `org.gradle.java.home` in `gradle.properties`.
  - Always execute builds with `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug` (requires `BypassSandbox: true` for Gradle daemon loopback IPC).
  - Keep `-Wl,-z,max-page-size=16384` in `CMakeLists.txt` `target_link_options` for Android 15+ 16KB page size compatibility.
  - After installing via `adb install -r`, always run `adb reverse tcp:9453 tcp:9453` so the zero-lag USB-C loopback tunnel (`127.0.0.1:9453`) is active alongside Wireless LAN.
- **Version Bumping & Release Automation**:
  - Root `VERSION` is the single source of truth for marketing versions (`MAJOR.MINOR.PATCH`).
  - Automatically analyze Conventional Commits and bump version via:
    `zsh /Users/asiddeenmuhammad/Projects/hyperscreen/scripts/bump_version.sh auto [--commit] [--tag]`
  - Publish releases strictly and safely via:
    `zsh /Users/asiddeenmuhammad/Projects/hyperscreen/scripts/release.sh [patch|minor|major|auto]`
    (Verifies clean git state, runs core tests, packages macOS DMG and Android APK locally, bumps `VERSION`, creates annotated tag `v*`, and publishes to GitHub).
  - Git hooks in `.githooks/` auto-bump and amend `VERSION` on commit. Activate with `zsh /Users/asiddeenmuhammad/Projects/hyperscreen/scripts/install_git_hooks.sh`.
  - Both Android `versionCode` and macOS `CFBundleVersion` dynamically calculate strictly monotonic build codes from Git commit count: `(major * 100000) + (minor * 10000) + gitCommitCount`.

## 2. Hardware Video Pipeline Invariants (macOS VideoToolbox -> Android MediaCodec)
- **Android `MediaCodec` (`LowLatencyMediaCodec`)**:
  - NEVER set `MediaFormat.KEY_OPERATING_RATE` to `Short.MAX_VALUE (32767)` on Samsung Exynos (`c2.exynos.hevc.decoder`) or Qualcomm hardware decoders—it triggers `0xfffffff4 (NO_MEMORY)` in `state 5/STARTING`.
  - Default stream resolution to `1920x1200 @ 90Hz` (16:10) with multi-stage `configure()` fallback (`KEY_LOW_LATENCY` -> standard `1920x1200` -> `1280x800`).
- **macOS `VideoToolboxEncoder` & `ScreenCaptureKit`**:
  - Always supply a strictly monotonic `CMTime` presentation timestamp to `VTCompressionSessionEncodeFrame` (fallback when `CMSampleBufferGetPresentationTimeStamp` is invalid) and enforce a 1.0-second max keyframe interval (`kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration = 1.0`).
  - Keep the 350ms static-desktop re-encode timer active so clients connecting while the Mac desktop is motionless immediately receive SPS/PPS/VPS + IDR frames.
  - Keep `SCStreamConfiguration.showsCursor = true` and query `CGDisplayBounds(virtualDisplayID)` dynamically (`liveVirtualDisplayBounds()`) rather than caching bounds at `t = 0ms` before `WindowServer` positions the virtual display.

## 3. USB-C & Tablet UX Guardrails
- **USB-C Coexistence with ADB (`CMoreCoreGraphics.m`)**:
  - `HSUSBAOAManager` must only open pure AOA interfaces (`0x2D00`/`0x2D04`) and must NEVER reset or claim composite ADB interfaces (`0x2D01`), preserving `adb reverse` tunnels.
- **Tablet Display Keep-Awake & Camera2 Preview**:
  - `MainActivity` must maintain `FLAG_KEEP_SCREEN_ON`, `FLAG_SHOW_WHEN_LOCKED`, `FLAG_TURN_SCREEN_ON`, `SurfaceView.keepScreenOn = true`, and an active `PowerManager.SCREEN_BRIGHT_WAKE_LOCK` in `onResume()`.
  - Camera2 `TextureView` previews in landscape must use a square container (`1:1`) and apply `configureCameraTransform` (`Matrix.setRectToRect` + `postRotate`) for `Surface.ROTATION_90` / `Surface.ROTATION_270`.

## 4. UI Invariants & Input Guardrails (macOS AppKit & Android Canvas/Views)
- **macOS AppKit / SwiftUI Menu Buttons**:
  - NEVER wrap custom card containers (`HStack`, `ZStack`, `.background`, `.overlay`) inside a `Menu { ... } label: { ... }` with `.menuStyle(.borderlessButton)`. macOS AppKit strips backgrounds, borders, padding, and layout hierarchies. Keep the card outside the `Menu` and position `Menu` strictly as the discrete action button on the right.
  - In step/onboarding cards with visual assets (e.g. QR codes), place title, instructions, and copyable text on the left with a `Spacer()` and align the visual asset to the right for layout consistency across cards.
  - Always format 8-digit Pairing PINs as `XXXX-XXXX` for display and clipboard copying while preserving raw digits for protocol/deep-link payloads.
- **Android Canvas Text Bounding & Auto-Formatted EditText**:
  - NEVER call raw `canvas.drawText()` for variable or telemetry strings (e.g. Bonjour hostnames, IPs, mode statuses). All Canvas text must route through a bounded scaling helper (`drawFittedText`) with proportional `textSize` reduction and `…` ellipsis fallback.
  - When creating auto-hyphenated PIN or code inputs (e.g. `XXXX-XXXX`) in `EditText`:
    - NEVER use `InputType.TYPE_CLASS_NUMBER` alone, as its internal `NumberKeyListener` strips hyphens and triggers `IndexOutOfBoundsException` during cursor positioning.
    - Set `inputType = InputType.TYPE_CLASS_PHONE` and explicitly assign `keyListener = DigitsKeyListener.getInstance("0123456789-")` AFTER setting `inputType`.
    - Always clamp cursor selection using `pinInput.text?.length ?: 0` rather than the unverified formatted string length.
    - Normalize input strings by stripping hyphens (`filter { it.isDigit() }`) before matching against Bonjour discovery or initiating socket connections.
- **Android Theme Adaptation & In-Stream HUD Contrast**:
  - When activities declare `android:configChanges="...|uiMode"`, `onConfigurationChanged(newConfig)` must dynamically update view palettes and request `postInvalidateOnAnimation()` without recreating the activity or disrupting active video/touch pipelines.
  - Dynamically configure `WindowInsetsController.setSystemBarsAppearance` in fullscreen mode to invert status/navigation bar icons to match `palette.isDark`.
  - Floating streaming HUD overlays (top-right control bar and bottom-left modifier bar) must feature dual-layer ambient drop shadows and frosted glass backgrounds so text and buttons remain 100% legible over both bright white and dark Mac desktop streams.
  - Custom dialogs (`AlertDialog`) and input containers must bind theme colors dynamically (e.g. `dialog.setOnShowListener` for action buttons, custom rounded drawables for input backgrounds) to prevent light-on-light or dark-on-dark contrast collisions.
