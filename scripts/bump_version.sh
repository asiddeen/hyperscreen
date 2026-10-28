#!/usr/bin/env zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
VERSION_FILE="$ROOT_DIR/VERSION"

# Ensure VERSION file exists
if [ ! -f "$VERSION_FILE" ]; then
    echo "1.0.0" > "$VERSION_FILE"
fi

CURRENT_VERSION="$(cat "$VERSION_FILE" | tr -d '[:space:]')"
if [ -z "$CURRENT_VERSION" ]; then
    CURRENT_VERSION="1.0.0"
fi

# Parse arguments & flags
DO_COMMIT=false
DO_TAG=false
DRY_RUN=false
IS_HOOK=false
BUMP_ARG=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --commit|-c)
            DO_COMMIT=true
            shift
            ;;
        --tag|-t)
            DO_TAG=true
            shift
            ;;
        --dry-run|-n)
            DRY_RUN=true
            shift
            ;;
        --hook)
            IS_HOOK=true
            shift
            ;;
        auto|--auto)
            BUMP_ARG="auto"
            shift
            ;;
        patch|minor|major|[0-9]*.[0-9]*.[0-9]*)
            BUMP_ARG="$1"
            shift
            ;;
        -h|--help)
            echo "Usage: $0 [patch|minor|major|auto|<x.y.z>] [--commit] [--tag] [--dry-run]"
            echo ""
            echo "Arguments:"
            echo "  auto           Analyze Conventional Commits to automatically bump version"
            echo "  patch          Increment patch version (e.g. 1.0.1 -> 1.0.2)"
            echo "  minor          Increment minor version (e.g. 1.0.1 -> 1.1.0)"
            echo "  major          Increment major version (e.g. 1.0.1 -> 2.0.0)"
            echo "  <x.y.z>        Set explicit semantic version (e.g. 1.2.0)"
            echo ""
            echo "Flags:"
            echo "  --commit, -c   Create git commit for the version bump"
            echo "  --tag, -t      Create git tag v<NEW_VERSION>"
            echo "  --dry-run, -n  Preview calculated version without modifying files"
            echo "  --hook         Internal mode for post-commit Git hook"
            echo ""
            echo "Current version: $CURRENT_VERSION"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Run '$0 --help' for usage."
            exit 1
            ;;
    esac
done

# Default to auto if inside git repo, otherwise patch
if [ -z "$BUMP_ARG" ]; then
    if git -C "$ROOT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        BUMP_ARG="auto"
    else
        BUMP_ARG="patch"
    fi
fi

# In hook mode, perform safety checks before amending
if [ "$IS_HOOK" = true ]; then
    # Skip if loop prevention variable is set
    if [ "${HYPERSCREEN_AUTO_BUMP_SKIP:-0}" = "1" ]; then
        exit 0
    fi

    # Skip during interactive rebase, cherry-pick, or merge resolution
    if [ -d "$ROOT_DIR/.git/rebase-merge" ] || [ -d "$ROOT_DIR/.git/rebase-apply" ] || \
       [ -f "$ROOT_DIR/.git/MERGE_HEAD" ] || [ -f "$ROOT_DIR/.git/CHERRY_PICK_HEAD" ]; then
        exit 0
    fi

    # Skip merge commits (2+ parents)
    if git -C "$ROOT_DIR" rev-parse -q --verify HEAD^2 >/dev/null 2>&1; then
        exit 0
    fi

    # Skip if current commit already modified VERSION manually
    if git -C "$ROOT_DIR" diff-tree --no-commit-id --name-only -r HEAD 2>/dev/null | grep -q "^VERSION$"; then
        exit 0
    fi

    # Read the commit message of HEAD
    LATEST_MSG="$(git -C "$ROOT_DIR" log -1 --pretty=format:"%s%n%b" 2>/dev/null || true)"
    if [[ "$LATEST_MSG" == *"[skip-bump]"* ]] || [[ "$LATEST_MSG" == *"chore(release)"* ]]; then
        exit 0
    fi
fi

# Detect Conventional Commit bump type
detect_bump_type() {
    local COMMIT_TEXT=""
    if [ "$IS_HOOK" = true ]; then
        # In hook mode, evaluate ONLY the latest commit that just triggered the hook
        COMMIT_TEXT="$(git -C "$ROOT_DIR" log -1 --pretty=format:"%s%n%b" 2>/dev/null || true)"
    else
        # Find latest release tag if present
        local LAST_TAG
        LAST_TAG="$(git -C "$ROOT_DIR" describe --tags --abbrev=0 --match "v[0-9]*" 2>/dev/null || true)"
        if [ -n "$LAST_TAG" ]; then
            COMMIT_TEXT="$(git -C "$ROOT_DIR" log "${LAST_TAG}..HEAD" --pretty=format:"%s%n%b" 2>/dev/null || true)"
        fi
        # If no tag or no commits between tag and HEAD, check recent commits
        if [ -z "$COMMIT_TEXT" ]; then
            COMMIT_TEXT="$(git -C "$ROOT_DIR" log -n 10 --pretty=format:"%s%n%b" 2>/dev/null || true)"
        fi
    fi

    if [ -z "$COMMIT_TEXT" ]; then
        echo "patch"
        return
    fi

    # Check for Breaking Changes (Major)
    if echo "$COMMIT_TEXT" | grep -E -q "(BREAKING CHANGE:|BREAKING-CHANGE:|^[a-zA-Z]+(\([a-zA-Z0-9_.-]+\))?!:)"; then
        echo "major"
        return
    fi

    # Check for Features (Minor)
    if echo "$COMMIT_TEXT" | grep -E -q "^feat(\([a-zA-Z0-9_.-]+\))?:"; then
        echo "minor"
        return
    fi

    # Default to patch for fixes, refactors, perf, docs, chore, etc.
    echo "patch"
}

# Resolve bump type
if [ "$BUMP_ARG" = "auto" ]; then
    BUMP_TYPE="$(detect_bump_type)"
else
    BUMP_TYPE="$BUMP_ARG"
fi

# Parse current version components
IFS='.' read -r MAJ MIN PAT <<< "$CURRENT_VERSION"
MAJ="${MAJ:-1}"
MIN="${MIN:-0}"
PAT="${PAT:-0}"

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
        echo "Error: Unrecognized bump type '$BUMP_TYPE'."
        exit 1
        ;;
esac

# Calculate strictly monotonic build code / versionCode
# Uses git commit count so every commit guarantees a strictly greater build number
GIT_COMMIT_COUNT=$(git -C "$ROOT_DIR" rev-list --count HEAD 2>/dev/null || echo 1)
GIT_COMMIT_HASH=$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo "unknown")

IFS='.' read -r N_MAJ N_MIN N_PAT <<< "$NEW_VERSION"
VERSION_CODE=$(( ${N_MAJ:-1} * 100000 + ${N_MIN:-0} * 10000 + ${GIT_COMMIT_COUNT:-1} ))

if [ "$DRY_RUN" = true ]; then
    echo "--- [Dry-Run] Calculated Version Bump ---"
    echo "  Current Version:  $CURRENT_VERSION"
    echo "  Bump Type:        $BUMP_TYPE"
    echo "  New Version:      $NEW_VERSION"
    echo "  Build/Code:       $VERSION_CODE (commit #$GIT_COMMIT_COUNT, hash: $GIT_COMMIT_HASH)"
    exit 0
fi

# Write new version to VERSION file
echo "$NEW_VERSION" > "$VERSION_FILE"

# Handle hook mode: seamlessly amend VERSION into current commit
if [ "$IS_HOOK" = true ]; then
    git -C "$ROOT_DIR" add "$VERSION_FILE"
    HYPERSCREEN_AUTO_BUMP_SKIP=1 git -C "$ROOT_DIR" commit --amend -C HEAD --no-verify >/dev/null 2>&1
    echo "⚡ [HyperScreen Auto-Bump] Monotonically bumped version: v${CURRENT_VERSION} -> v${NEW_VERSION} (${BUMP_TYPE}, build ${VERSION_CODE})"
    exit 0
fi

echo "================================================="
echo "  HyperScreen Version Bump: ${CURRENT_VERSION} -> ${NEW_VERSION} (${BUMP_TYPE})"
echo "================================================="
echo "  • VERSION File:       $VERSION_FILE"
echo "  • Version String:     $NEW_VERSION"
echo "  • Git Commit:         #$GIT_COMMIT_COUNT ($GIT_COMMIT_HASH)"
echo "  • Build/VersionCode:  $VERSION_CODE"
echo "  • macOS Host Target:  HyperScreen-${NEW_VERSION}-macOS.dmg"
echo "  • Android APK Target: app-debug.apk (v${NEW_VERSION}, code=${VERSION_CODE})"
echo "================================================="

# Optional Git commit
if [ "$DO_COMMIT" = true ]; then
    git -C "$ROOT_DIR" add "$VERSION_FILE"
    HYPERSCREEN_AUTO_BUMP_SKIP=1 git -C "$ROOT_DIR" commit -m "chore(release): bump version to v${NEW_VERSION} [skip-bump]"
    echo "  [OK] Created release commit for v${NEW_VERSION}"
fi

# Optional Git tag
if [ "$DO_TAG" = true ]; then
    git -C "$ROOT_DIR" tag -a "v${NEW_VERSION}" -m "Release v${NEW_VERSION}"
    echo "  [OK] Created Git tag v${NEW_VERSION}"
fi

echo "Next steps:"
echo "  1) Package macOS Host:    zsh scripts/package_macos_app.sh"
echo "  2) Assemble Android APK:  cd android-client && ./gradlew assembleDebug"
echo "================================================="
