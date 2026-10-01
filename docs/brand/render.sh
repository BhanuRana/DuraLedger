#!/usr/bin/env bash
# Renders the README banner and the GitHub social preview from their HTML sources with headless Chrome.
#   ./docs/brand/render.sh     -> docs/banner.png (2560x800), docs/social-preview.png (1280x640)
set -euo pipefail
cd "$(dirname "$0")"
CHROME=${CHROME:-"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"}
shot() { # html, css width, css height, scale, output
  "$CHROME" --headless --disable-gpu --hide-scrollbars --virtual-time-budget=4000 \
    --force-device-scale-factor="$4" --window-size="$2,$3" --screenshot="$5" "file://$PWD/$1" 2>/dev/null
  echo "wrote $5"
}
shot banner.html 1280 400 2 ../banner.png
shot social-preview.html 1280 640 1 ../social-preview.png
