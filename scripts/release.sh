#!/usr/bin/env zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Colors for terminal output
BOLD="\033[1m"
GREEN="\033[32m"
YELLOW="\033[33m"
CYAN="\033[36m"
RED="\033[31m"
RESET="\033[0m"

echo "${BOLD}${CYAN}======================================================${RESET}"
echo "${BOLD}${CYAN}     HyperScreen Official Local Release Engine        ${RESET}"
echo "${BOLD}${CYAN}======================================================${RESET}"

BUMP_TYPE="auto"
DRY_RUN=false
SKIP_TESTS=false
SKIP_BUILD=false
DO_PUSH=true
FORCE=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run|-n)
            DRY_RUN=true
            shift
            ;;
        --skip-tests)
            SKIP_TESTS=true
            shift
            ;;
        --skip-build)
            SKIP_BUILD=true
            shift
            ;;
        --no-push)
            DO_PUSH=false
            shift
            ;;
        --force|-f)
            FORCE=true
            shift
            ;;
        patch|minor|major|auto|[0-9]*.[0-9]*.[0-9]*)
            BUMP_TYPE="$1"
            shift
            ;;
        -h|--help)
            echo "Usage: $0 [patch|minor|major|auto|<x.y.z>] [--dry-run] [--skip-tests] [--skip-build] [--no-push] [--force]"
            echo ""
            echo "Options:"
            echo "  auto           (Default) Determine version bump automatically from Conventional Commits"
            echo "  patch          Increment patch version (e.g. 1.5.6 -> 1.5.7)"
            echo "  minor          Increment minor version (e.g. 1.5.6 -> 1.6.0)"
            echo "  major          Increment major version (e.g. 1.5.6 -> 2.0.0)"
            echo "  <x.y.z>        Explicit semantic version string"
            echo ""
            echo "Flags:"
            echo "  --dry-run, -n  Preview calculations without touching git, building, or tagging"
            echo "  --skip-tests   Bypass local C++20 core unit test suite"
            echo "  --skip-build   Bypass macOS DMG and Android APK local packaging"
            echo "  --no-push      Build binaries and tag locally without pushing to GitHub"
            echo "  --force, -f    Bypass branch check and uncommitted changes warnings"
            exit 0
            ;;
        *)
            echo "${RED}Error: Unknown argument '$1'${RESET}"
            echo "Run '$0 --help' for usage."
            exit 1
            ;;
    esac
done

# Step 1: Pre-flight Git Checks
echo "\n${BOLD}[1/6] Checking Git repository status...${RESET}"
if [ "$FORCE" = false ]; then
    CURRENT_BRANCH="$(git -C "$ROOT_DIR" rev-parse --abbrev-ref HEAD)"
    if [ "$CURRENT_BRANCH" != "main" ]; then
        echo "${RED}Error: Releases must be triggered from the 'main' branch (currently on '$CURRENT_BRANCH').${RESET}"
        echo "Use --force to override if intentional."
        exit 1
    fi

    if ! git -C "$ROOT_DIR" diff --quiet || ! git -C "$ROOT_DIR" diff --cached --quiet; then
        echo "${RED}Error: Working directory has uncommitted changes.${RESET}"
        echo "Please commit or stash your changes before creating a release."
        exit 1
    fi
fi
echo "${GREEN}  ✓ Git working tree is clean on 'main'.${RESET}"

# Step 2: Run Core Unit Tests
if [ "$SKIP_TESTS" = false ]; then
    echo "\n${BOLD}[2/6] Running Core C++20 Unit Tests & 0ms FEC Reassembler...${RESET}"
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
    echo "${GREEN}  ✓ Core unit tests passed cleanly.${RESET}"
else
    echo "\n${YELLOW}[2/6] Skipping local unit tests (--skip-tests).${RESET}"
fi

# Step 3: Determine and Preview Version Bump
echo "\n${BOLD}[3/6] Calculating version bump (${BUMP_TYPE})...${RESET}"
if [ "$DRY_RUN" = true ]; then
    zsh "$ROOT_DIR/scripts/bump_version.sh" "$BUMP_TYPE" --dry-run
    echo "\n${GREEN}Dry-run complete. No files modified, no builds executed.${RESET}"
    exit 0
fi

# Perform version bump, commit, and tag
zsh "$ROOT_DIR/scripts/bump_version.sh" "$BUMP_TYPE" --commit --tag

NEW_VERSION="$(cat "$ROOT_DIR/VERSION" | tr -d '[:space:]')"
TAG_NAME="v${NEW_VERSION}"
DMG_PATH="$ROOT_DIR/build/HyperScreen-${NEW_VERSION}-macOS.dmg"
APK_SOURCE="$ROOT_DIR/android-client/app/build/outputs/apk/debug/app-debug.apk"
APK_TARGET="$ROOT_DIR/build/HyperScreen-${NEW_VERSION}-Android.apk"

echo "${GREEN}  ✓ Created release commit and Git tag ${TAG_NAME}.${RESET}"

# Step 4: Build Local Release Binaries (macOS DMG + Android APK)
if [ "$SKIP_BUILD" = false ]; then
    echo "\n${BOLD}[4/6] Building macOS Host Bundle & DMG Installer...${RESET}"
    zsh "$ROOT_DIR/scripts/package_macos_app.sh"
    echo "${GREEN}  ✓ macOS DMG built: ${DMG_PATH}${RESET}"

    echo "\n${BOLD}[5/6] Assembling Android Client APK...${RESET}"
    JAVA_HOME_PATH="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    if [ ! -d "$JAVA_HOME_PATH" ]; then
        JAVA_HOME_PATH="$(/usr/libexec/java_home -v 21 2>/dev/null || echo "")"
    fi

    (
        cd "$ROOT_DIR/android-client"
        if [ -n "$JAVA_HOME_PATH" ]; then
            JAVA_HOME="$JAVA_HOME_PATH" ./gradlew assembleDebug
        else
            ./gradlew assembleDebug
        fi
    )

    if [ -f "$APK_SOURCE" ]; then
        mkdir -p "$ROOT_DIR/build"
        cp "$APK_SOURCE" "$APK_TARGET"
        echo "${GREEN}  ✓ Android APK built: ${APK_TARGET}${RESET}"
    else
        echo "${RED}Warning: Android APK was not found at $APK_SOURCE${RESET}"
    fi
else
    echo "\n${YELLOW}[4/6 & 5/6] Skipping binary compilation (--skip-build).${RESET}"
fi

# Step 6: Push to GitHub and Publish Release
if [ "$DO_PUSH" = true ]; then
    echo "\n${BOLD}[6/6] Pushing release commit and tag to GitHub origin...${RESET}"
    git -C "$ROOT_DIR" push origin main
    git -C "$ROOT_DIR" push origin "$TAG_NAME"
    echo "${GREEN}  ✓ Pushed commit and tag '${TAG_NAME}' to GitHub origin!${RESET}"

    echo "\n${BOLD}Publishing Release Assets...${RESET}"
    if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
        echo "Creating official GitHub release via GitHub CLI..."
        RELEASE_FILES=()
        [ -f "$DMG_PATH" ] && RELEASE_FILES+=("$DMG_PATH")
        [ -f "$APK_TARGET" ] && RELEASE_FILES+=("$APK_TARGET")

        gh release create "$TAG_NAME" "${RELEASE_FILES[@]}" \
            --title "$TAG_NAME" \
            --generate-notes
        echo "${GREEN}  ✓ GitHub Release ${TAG_NAME} published with attached binaries!${RESET}"
    else
        echo "${YELLOW}GitHub CLI ('gh') is not installed or authenticated.${RESET}"
        echo "Your git tag '${TAG_NAME}' is live. You can publish the release via browser:"
        echo "  🌐 URL: https://github.com/asiddeen/hyperscreen/releases/new?tag=${TAG_NAME}"
        echo "Attach the built local binaries:"
        [ -f "$DMG_PATH" ] && echo "  📦 macOS DMG:   $DMG_PATH"
        [ -f "$APK_TARGET" ] && echo "  📱 Android APK: $APK_TARGET"
    fi

    echo "\n${BOLD}${GREEN}======================================================${RESET}"
    echo "${BOLD}${GREEN}   🚀 Release ${TAG_NAME} Completed Successfully!    ${RESET}"
    echo "${BOLD}${GREEN}======================================================${RESET}"
    echo "  • GitHub Releases: https://github.com/asiddeen/hyperscreen/releases"
    echo "  • GitHub Actions:  https://github.com/asiddeen/hyperscreen/actions"
    echo "${BOLD}${GREEN}======================================================${RESET}"
else
    echo "\n${YELLOW}[6/6] Skipped remote push (--no-push).${RESET}"
    echo "To push manually, run:"
    echo "  git push origin main"
    echo "  git push origin ${TAG_NAME}"
fi
