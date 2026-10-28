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
echo "${BOLD}${CYAN}        HyperScreen Official Release Script           ${RESET}"
echo "${BOLD}${CYAN}======================================================${RESET}"

BUMP_TYPE="auto"
DRY_RUN=false
SKIP_TESTS=false
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
            echo "Usage: $0 [patch|minor|major|auto|<x.y.z>] [--dry-run] [--skip-tests] [--no-push] [--force]"
            echo ""
            echo "Options:"
            echo "  auto           (Default) Determine version bump automatically from Conventional Commits"
            echo "  patch          Increment patch version (e.g. 1.5.3 -> 1.5.4)"
            echo "  minor          Increment minor version (e.g. 1.5.3 -> 1.6.0)"
            echo "  major          Increment major version (e.g. 1.5.3 -> 2.0.0)"
            echo "  <x.y.z>        Explicit semantic version string"
            echo ""
            echo "Flags:"
            echo "  --dry-run, -n  Preview calculations without touching git or creating tags"
            echo "  --skip-tests   Bypass local C++20 core unit test suite"
            echo "  --no-push      Create release commit and tag locally, but do not push to GitHub"
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
echo "\n${BOLD}[1/5] Checking Git repository status...${RESET}"
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
echo "${GREEN}  ✓ Git working tree is clean.${RESET}"

# Step 2: Run Core Unit Tests
if [ "$SKIP_TESTS" = false ]; then
    echo "\n${BOLD}[2/5] Running Core C++20 Unit Tests & FEC Reassembler Verification...${RESET}"
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
    echo "\n${YELLOW}[2/5] Skipping local unit tests (--skip-tests).${RESET}"
fi

# Step 3: Determine and Preview Version
echo "\n${BOLD}[3/5] Calculating version bump (${BUMP_TYPE})...${RESET}"
if [ "$DRY_RUN" = true ]; then
    zsh "$ROOT_DIR/scripts/bump_version.sh" "$BUMP_TYPE" --dry-run
    echo "\n${GREEN}Dry-run complete. No changes made.${RESET}"
    exit 0
fi

# Step 4: Perform Version Bump, Commit, and Tag
echo "\n${BOLD}[4/5] Updating VERSION, creating commit, and tagging...${RESET}"
zsh "$ROOT_DIR/scripts/bump_version.sh" "$BUMP_TYPE" --commit --tag

NEW_VERSION="$(cat "$ROOT_DIR/VERSION" | tr -d '[:space:]')"
TAG_NAME="v${NEW_VERSION}"

echo "${GREEN}  ✓ Successfully tagged ${TAG_NAME}${RESET}"

# Step 5: Push to GitHub to trigger CI Release
if [ "$DO_PUSH" = true ]; then
    echo "\n${BOLD}[5/5] Pushing release commit and tag to GitHub origin...${RESET}"
    git -C "$ROOT_DIR" push origin main
    git -C "$ROOT_DIR" push origin "$TAG_NAME"
    echo "${GREEN}  ✓ Pushed commit and tag '$TAG_NAME' to origin!${RESET}"
    echo ""
    echo "${BOLD}${GREEN}======================================================${RESET}"
    echo "${BOLD}${GREEN}   🚀 Release ${TAG_NAME} Triggered Successfully!    ${RESET}"
    echo "${BOLD}${GREEN}======================================================${RESET}"
    echo "GitHub Actions is now executing the release pipeline:"
    echo "  • Action Run: https://github.com/asiddeen/hyperscreen/actions"
    echo "  • Releases:   https://github.com/asiddeen/hyperscreen/releases"
    echo ""
    echo "When complete, GitHub will automatically publish:"
    echo "  📦 HyperScreen-${NEW_VERSION}-macOS.dmg"
    echo "  📱 app-debug.apk (v${NEW_VERSION})"
    echo "${BOLD}${GREEN}======================================================${RESET}"
else
    echo "\n${YELLOW}[5/5] Skipped push (--no-push).${RESET}"
    echo "To publish this release to GitHub manually, run:"
    echo "  git push origin main"
    echo "  git push origin ${TAG_NAME}"
fi
