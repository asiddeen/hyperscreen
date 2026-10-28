# Contributing to HyperScreen

Thank you for your interest in contributing to **HyperScreen**! HyperScreen is a high-performance, low-latency second display and audio streaming system bridging macOS and Android.

To maintain sub-8ms presentation latency, rock-solid stability across various hardware decoders (Samsung Exynos, Qualcomm Snapdragon), and seamless USB/Wi-Fi transport, we adhere to strict engineering standards.

Please review this guide before submitting issues or pull requests.

---

## Table of Contents
1. [Code of Conduct](#code-of-conduct)
2. [Ways to Contribute](#ways-to-contribute)
3. [Prerequisites & Environment Setup](#prerequisites--environment-setup)
4. [Monorepo Structure](#monorepo-structure)
5. [Development & Git Workflow](#development--git-workflow)
6. [Conventional Commits & Automatic Versioning](#conventional-commits--automatic-versioning)
7. [Hardware & Pipeline Invariants (Must Follow)](#hardware--pipeline-invariants-must-follow)
8. [Testing & Verification](#testing--verification)
9. [Submitting a Pull Request](#submitting-a-pull-request)
10. [Release Workflow](#release-workflow)

---

## Code of Conduct

We are committed to providing a friendly, safe, and welcoming environment for all contributors, regardless of experience level, background, or identity. Please be respectful, constructive, and collaborative in all discussions, issues, and code reviews.

---

## Ways to Contribute

- **Report Bugs**: Open a detailed GitHub issue if you identify crashes, frame drops, audio crackling, or decoder initialization failures.
- **Improve Documentation**: Enhance setup instructions, architecture guides, or API docstrings.
- **Submit Optimizations**: Profile and reduce CPU/GPU overhead, memory allocations in hot paths, or transport jitter.
- **Add Device Support**: Validate and tune MediaCodec configurations for additional Android tablet models and display refresh rates (e.g. 90Hz, 120Hz, 144Hz).

---

## Prerequisites & Environment Setup

### 1. Core Protocol (`core/`)
- C++20 compatible compiler: Clang (LLVM 16+) or GCC 13+
- CMake 3.25 or newer

### 2. macOS Host (`macos-host/`)
- Apple Silicon or Intel Mac running macOS 14.0+ (Sonoma or Sequoia)
- Xcode 15 or 16 (Command Line Tools: `xcode-select --install`)
- Swift 6 toolchain

### 3. Android Client (`android-client/`)
- Android Studio Ladybug / Meerkat or standalone Android SDK
- JDK 21 (configured via `JAVA_HOME`)
- Android NDK (r26 or r27 recommended)
- Physical Android tablet or phone running Android 11+ (API 30+) with USB Debugging enabled

### 4. Clone and Install Git Hooks
```bash
git clone https://github.com/asiddeen/hyperscreen.git
cd hyperscreen

# Activate automated Conventional Commit hooks
zsh scripts/install_git_hooks.sh
```

---

## Monorepo Structure

```
hyperscreen/
├── core/                # Shared C++20 protocol, CRC-16, GF(2^8) FEC, 1-Euro cursor filter
├── macos-host/          # macOS menu bar app, ScreenCaptureKit, VideoToolbox encoder
├── android-client/      # Modular Android app (MediaCodec, AudioTrack, AOA transport)
├── docs/                # Static GitHub Pages landing page and documentation
├── scripts/             # Release automation, packaging, and version bumping scripts
├── assets/              # Vector logos, app icons (.icns), and Android adaptive mipmaps
└── .github/workflows/   # Multi-platform CI/CD pipeline (Core, macOS, Android, Release)
```

---

## Development & Git Workflow

1. **Create a Topic Branch**:
   Always branch off `main`:
   ```bash
   git checkout main
   git pull origin main
   git checkout -b feat/your-feature-name
   # or
   git checkout -b fix/your-bugfix-name
   ```

2. **Keep Commits Atomic**:
   Each commit should represent a single logical change with clear intent.

3. **Rebase, Don't Merge**:
   Keep git history linear by rebasing onto `main`:
   ```bash
   git fetch origin
   git rebase origin/main
   ```

---

## Conventional Commits & Automatic Versioning

HyperScreen uses [Conventional Commits](https://www.conventionalcommits.org/) to automatically calculate semantic versions (`MAJOR.MINOR.PATCH`) and strictly monotonic build codes:

### Format
```
<type>(<scope>): <short description>

[optional body]

[optional footer(s)]
```

### Commit Types
| Type | Semantic Bump | Description |
| :--- | :--- | :--- |
| `feat:` | **Minor** (`1.5.0` $\to$ `1.6.0`) | A new feature or major capability |
| `fix:` | **Patch** (`1.5.0` $\to$ `1.5.1`) | A bug fix or crash resolution |
| `perf:` | **Patch** | A code change that improves execution speed or memory |
| `refactor:` | **Patch** | Code refactoring without behavior changes |
| `docs:` | **Patch** | Documentation, README, or guides |
| `test:` | None / Patch | Adding or updating tests |
| `ci:` / `chore:`| None / Patch | CI workflows, build scripts, tool configuration |
| `BREAKING CHANGE:` | **Major** (`1.5.0` $\to$ `2.0.0`) | Breaking API or protocol wire format change |

### Examples
* `feat(audio): implement 48kHz stereo system audio capture via ScreenCaptureKit`
* `fix(codec): prevent NO_MEMORY error on Exynos MediaCodec initialization`
* `perf(transport): reduce memory copying in UDP MTU slicing loop`

---

## Hardware & Pipeline Invariants (Must Follow)

Any contribution touching video, audio, or transport must respect the following architectural guardrails:

### 1. Android MediaCodec Decoder Safety
* **NEVER** set `MediaFormat.KEY_OPERATING_RATE` to `Short.MAX_VALUE (32767)` on Samsung Exynos (`c2.exynos.hevc.decoder`) or Qualcomm hardware decoders. It triggers an unrecoverable `0xfffffff4 (NO_MEMORY)` failure in decoder state `STARTING`.
* Always provide multi-stage fallback when configuring decoder surfaces:
  `1920x1200 @ 90/120Hz` $\to$ standard `1920x1200` $\to$ `1280x800`.

### 2. Android 15+ 16KB Page Size Compatibility
* All NDK shared libraries must include `-Wl,-z,max-page-size=16384` in `target_link_options` in [`CMakeLists.txt`](android-client/app/src/main/cpp/CMakeLists.txt) to run without crashing on Android 15+ devices with 16KB page size kernels.

### 3. macOS VideoToolbox Hardware Pipeline
* Presentation timestamps fed to `VTCompressionSessionEncodeFrame` must be **strictly monotonic `CMTime` values**.
* Keep maximum keyframe interval duration capped at 1.0 second (`kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration = 1.0`) with an active static-desktop re-encode timer (350ms) so connecting tablets immediately receive IDR/SPS/PPS parameter sets.

### 4. Zero-GC Hot Paths
* Hot loops (rendering frames at 120Hz/144Hz, audio PCM playback at 48kHz, and UDP packet parsing) must **never allocate heap memory or trigger Garbage Collection** in Kotlin or Swift. Use pre-allocated ring buffers and direct byte buffers.

### 5. USB AOA v2 & ADB Coexistence
* `HSUSBAOAManager` on macOS must open pure AOA interfaces (`0x2D00` / `0x2D04`) and must **never reset or claim composite ADB interfaces** (`0x2D01`), ensuring that `adb reverse tcp:9453 tcp:9453` loopback tunnels remain uninterrupted.

---

## Testing & Verification

Before submitting a pull request, verify all test suites locally:

### 1. Core C++20 Unit Tests (Protocol, FEC, Filter)
```bash
cmake -B build -S core -DBUILD_TESTING=ON
cmake --build build --config Release
ctest --test-dir build --output-on-failure
```

### 2. Hardware VideoToolbox Loopback Benchmark
```bash
# Verify sub-8ms encode and zero-copy loopback pipeline
build/HyperScreen.app/Contents/MacOS/HyperScreenHost --benchmark-loopback
```

### 3. macOS Host App Build
```bash
zsh scripts/package_macos_app.sh
```

### 4. Android Client APK Assembly
```bash
cd android-client
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

---

## Submitting a Pull Request

1. **Push your branch**:
   ```bash
   git push origin feat/your-feature-name
   ```
2. **Open a Pull Request**:
   Go to [GitHub Pull Requests](https://github.com/asiddeen/hyperscreen/pulls) and click **"New pull request"**.
3. **Fill out the PR Description**:
   - Clearly explain the problem being solved or the feature added.
   - Reference any linked issues (e.g., `Closes #12`).
   - Note the hardware tested (Mac model, macOS version, Android tablet model, Android OS version).
4. **CI Matrix Checks**:
   Ensure all GitHub Actions checks pass:
   - `core-tests` (Ubuntu & macOS runners)
   - `macos-host` (Swift 6 + VideoToolbox compilation)
   - `android-client` (Gradle APK assembly)

---

## Release Workflow

Releases are strictly gated behind annotated Git tags (`v*`) and are not triggered by standard commits:

* To run release pre-flight checks, bump versions, tag, and publish to GitHub in a single command:
  ```bash
  zsh scripts/release.sh auto
  ```
* See [`.github/workflows/ci.yml`](.github/workflows/ci.yml) for details on GitHub Actions automated DMG and APK asset packaging.

---

Thank you for helping make HyperScreen the best low-latency display engine! 🚀
