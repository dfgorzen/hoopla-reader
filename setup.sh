#!/usr/bin/env bash
# One-time: create the GitHub repo, store the signing key as secrets, push, start the build.
# Needs git + GitHub CLI (gh). On Windows run it from Git Bash.
# Usage: ./setup.sh [repo-name] [--public]
set -euo pipefail
cd "$(dirname "$0")"

REPO="hoopla-reader"
VIS="--private"
for a in "$@"; do
  case "$a" in
    --public) VIS="--public" ;;
    *) REPO="$a" ;;
  esac
done

command -v gh >/dev/null || { echo "Install the GitHub CLI first: https://cli.github.com"; exit 1; }
gh auth status >/dev/null 2>&1 || gh auth login
[ -f signing/release.p12 ] && [ -f signing/password.txt ] || { echo "signing/ folder missing"; exit 1; }

if [ ! -d .git ]; then
  git init -q
  git symbolic-ref HEAD refs/heads/main
fi
git add -A
git commit -qm "Read Aloud app" || true

gh repo create "$REPO" $VIS --source . --remote origin
FULL=$(gh repo view --json nameWithOwner -q .nameWithOwner)

base64 < signing/release.p12 | tr -d '\r\n' | gh secret set KEYSTORE_B64 --repo "$FULL"
gh secret set KEYSTORE_PASSWORD --repo "$FULL" --body "$(cat signing/password.txt)"

git push -u origin main

echo
echo "Build started:   https://github.com/$FULL/actions"
echo "APK (≈4 min):    https://github.com/$FULL/releases/latest"
echo
echo "Keep the signing/ folder somewhere safe: every future update must be signed with it."
