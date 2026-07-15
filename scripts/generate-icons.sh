#!/bin/bash
# Generates the launcher mipmap icons from a favicon URL (defaults to the deckk.it logo).
# Run from the repo root (or anywhere; it locates the repo root via this script's path).
set -u

FAV="${FAVICON_URL:-https://deckk.it/images/favicon.svg}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

mkdir -p app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}

# Download + normalize to a 512x512 PNG; fall back to a text placeholder if it fails.
( wget -qO favicon.png "$FAV" && convert favicon.png -resize 512x512 favicon_tmp.png && mv favicon_tmp.png favicon.png ) || \
    wget -qO favicon.png "https://placehold.co/512x512/000000/FFFFFF.png?text=deckk.it"

convert favicon.png -resize 48x48  app/src/main/res/mipmap-mdpi/ic_launcher.png
convert favicon.png -resize 72x72  app/src/main/res/mipmap-hdpi/ic_launcher.png
convert favicon.png -resize 96x96  app/src/main/res/mipmap-xhdpi/ic_launcher.png
convert favicon.png -resize 144x144 app/src/main/res/mipmap-xxhdpi/ic_launcher.png
convert favicon.png -resize 192x192 app/src/main/res/mipmap-xxxhdpi/ic_launcher.png

rm -f favicon.png favicon_tmp.png
echo "Launcher icons generated."
