#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$MODULE_DIR/src/main/assets/devtools_frontend"
CHROMIUM_SRC="${CHROMIUM_SRC:-$HOME/vpbrowser/src/chromium/src}"
BUILT_DIR="$CHROMIUM_SRC/out/Release/gen/third_party/devtools-frontend/src/front_end"

if [[ ! -f "$BUILT_DIR/devtools_app.html" ]]; then
  echo "ERROR: Built Chromium DevTools frontend not found: $BUILT_DIR" >&2
  echo "Set CHROMIUM_SRC to a Chromium checkout with out/Release/gen/third_party/devtools-frontend/src/front_end." >&2
  exit 1
fi

# Replace only this module's DevTools frontend assets; no browser app files are touched.
rm -rf "$ASSETS_DIR"
mkdir -p "$(dirname "$ASSETS_DIR")"
cp -a "$BUILT_DIR" "$ASSETS_DIR"
COUNT=$(find "$ASSETS_DIR" -type f | wc -l | tr -d ' ')
echo "Imported $COUNT files into $ASSETS_DIR"
