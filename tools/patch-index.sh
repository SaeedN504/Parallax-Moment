#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

INDEX="www/index.html"
HOOK="www/depth-cutout-hook.js"
RAW_INDEX="https://raw.githubusercontent.com/SaeedN504/Parallax-Moment/main/www/index.html"
RAW_HOOK="https://raw.githubusercontent.com/SaeedN504/Parallax-Moment/main/www/depth-cutout-hook.js"
STAMP="$(date +%Y%m%d-%H%M%S)"
TMP_DIR="$(mktemp -d)"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

command -v curl >/dev/null || { echo "curl is required"; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required"; exit 1; }

echo "Downloading files..."
curl --fail --location --silent --show-error "$RAW_INDEX" -o "$TMP_DIR/index.html"
curl --fail --location --silent --show-error "$RAW_HOOK" -o "$TMP_DIR/depth-cutout-hook.js"

echo "Validating download..."
grep -qi '<html' "$TMP_DIR/index.html" || {
  echo "Downloaded index.html does not look like HTML. Nothing changed."
  exit 1
}
grep -q '</body>' "$TMP_DIR/index.html" || {
  echo "Could not find </body>. Nothing changed."
  exit 1
}
grep -q 'depth-cutout-hook.js' "$TMP_DIR/index.html" && {
  echo "Hook already exists. Nothing changed."
  exit 0
}

echo "Creating backup..."
cp "$INDEX" "$INDEX.backup.$STAMP"

echo "Installing hook..."
cp "$TMP_DIR/depth-cutout-hook.js" "$HOOK"

python3 - "$TMP_DIR/index.html" "$INDEX" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1])
target = Path(sys.argv[2])
html = source.read_text(encoding="utf-8")

tag = '    <script src="depth-cutout-hook.js"></script>\n'
needle = "</body>"

if html.count(needle) != 1:
    raise SystemExit("Expected exactly one </body> tag. Nothing changed.")

html = html.replace(needle, tag + needle, 1)
target.write_text(html, encoding="utf-8")
PY

echo
echo "Patched successfully."
echo "Backup: $INDEX.backup.$STAMP"
echo
echo "Review the change:"
git diff --stat
git diff -- "$INDEX" "$HOOK"
echo
echo "Important: this installs the hook, but the segmentation result still must set:"
echo 'window.__depthCutoutCanvas = YOUR_CUTOUT_CANVAS;'
