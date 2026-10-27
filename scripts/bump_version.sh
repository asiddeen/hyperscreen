#!/usr/bin/env zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
VERSION_FILE="$ROOT_DIR/VERSION"

if [ ! -f "$VERSION_FILE" ]; then
    echo "1.0.0" > "$VERSION_FILE"
fi

CURRENT_VERSION="$(cat "$VERSION_FILE" | tr -d '[:space:]')"

if [ -z "$CURRENT_VERSION" ]; then
    CURRENT_VERSION="1.0.0"
fi

# Parse current version components
IFS='.' read -r MAJ MIN PAT <<< "$CURRENT_VERSION"
MAJ="${MAJ:-1}"
MIN="${MIN:-0}"
PAT="${PAT:-0}"

BUMP_TYPE="${1:-patch}"

case "$BUMP_TYPE" in
    patch)
        NEW_PAT=$((PAT + 1))
        NEW_VERSION="${MAJ}.${MIN}.${NEW_PAT}"
        ;;
    minor)
        NEW_MIN=$((MIN + 1))
        NEW_VERSION="${MAJ}.${NEW_MIN}.0"
        ;;
    major)
        NEW_MAJ=$((MAJ + 1))
        NEW_VERSION="${NEW_MAJ}.0.0"
        ;;
    [0-9]*.[0-9]*.[0-9]*)
        NEW_VERSION="$BUMP_TYPE"
        ;;
    *)
        echo "Usage: $0 [patch|minor|major|<x.y.z>]"
        echo "Current version: $CURRENT_VERSION"
        exit 1
        ;;
esac

# Calculate integer version code for Android (e.g. 1.0.1 -> 10001)
IFS='.' read -r N_MAJ N_MIN N_PAT <<< "$NEW_VERSION"
VERSION_CODE=$(( ${N_MAJ:-1} * 10000 + ${N_MIN:-0} * 100 + ${N_PAT:-0} ))

# Write new version to VERSION file
echo "$NEW_VERSION" > "$VERSION_FILE"

echo "================================================="
echo "  HyperScreen Version Bump: ${CURRENT_VERSION} -> ${NEW_VERSION}"
echo "================================================="
echo "  • VERSION File:       $VERSION_FILE"
echo "  • Version String:     $NEW_VERSION"
echo "  • Integer Code:       $VERSION_CODE"
echo "  • Target macOS App:   HyperScreen-${NEW_VERSION}-macOS.dmg"
echo "  • Target Android APK: app-debug.apk (v${NEW_VERSION}, code=${VERSION_CODE})"
echo "================================================="
echo "Next steps:"
echo "  1) Package macOS Host:    zsh scripts/package_macos_app.sh"
echo "  2) Assemble Android APK:  cd android-client && ./gradlew assembleDebug"
echo "================================================="
