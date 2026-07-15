#!/bin/bash
set -e
chmod +x ./android-web-app-builder.sh
touch keystore.jks 2>/dev/null || true
ARGS=(
    --url "https://deckk.it"
    --app-name "deckk.it"
    --package-name "com.deckk.it"
)
if [ -n "${VERSION:-}" ]; then
    ARGS+=(--version "$VERSION")
fi
if [ -n "${VERSION_CODE:-}" ]; then
    ARGS+=(--version-code "$VERSION_CODE")
fi
./android-web-app-builder.sh "${ARGS[@]}"
