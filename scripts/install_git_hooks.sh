#!/usr/bin/env zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"

echo "==> Configuring Git Hooks for HyperScreen..."
git -C "$ROOT_DIR" config core.hooksPath .githooks
chmod +x "$ROOT_DIR/.githooks/post-commit"

# Also link into .git/hooks as fallback for tools that don't respect core.hooksPath
if [ -d "$ROOT_DIR/.git/hooks" ]; then
    cp "$ROOT_DIR/.githooks/post-commit" "$ROOT_DIR/.git/hooks/post-commit"
    chmod +x "$ROOT_DIR/.git/hooks/post-commit"
fi

echo "  [OK] Git hooks active! Commits will now automatically bump versions for Android & macOS based on Conventional Commits."
