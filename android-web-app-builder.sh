#!/bin/bash
set -euo pipefail

usage() {
    echo "Usage: $0 --url <website_url> --app-name <app_name> --package-name <package_name> [--favicon-url <favicon_url>] [--version <v>] [--version-code <n>]" 1>&2
    echo ""
    echo "Local/podman alternative to the GitHub Actions 'Build APK' worker."
    echo "Builds the APK from this repo's Dockerfile (which COPYs the project sources)."
    exit 1
}

TEMP=$(getopt -o "" -l url:,app-name:,package-name:,favicon-url:,build-type:,version:,version-code: -- "$@")
if [ $? != 0 ]; then
    usage
fi
eval set -- "$TEMP"

WEBSITE_URL=""
APP_NAME=""
PACKAGE_NAME=""
FAVICON_URL=""
BUILD_TYPE="debug"
VERSION="0.0.1"
VERSION_CODE="1"

while true; do
    case "$1" in
        --url) WEBSITE_URL="$2"; shift 2 ;;
        --app-name) APP_NAME="$2"; shift 2 ;;
        --package-name) PACKAGE_NAME="$2"; shift 2 ;;
        --favicon-url) FAVICON_URL="$2"; shift 2 ;;
        --build-type) BUILD_TYPE="$2"; shift 2 ;;
        --version) VERSION="$2"; shift 2 ;;
        --version-code) VERSION_CODE="$2"; shift 2 ;;
        --) shift; break ;;
        *) usage ;;
    esac
done

if [[ -z "$WEBSITE_URL" || -z "$APP_NAME" || -z "$PACKAGE_NAME" ]]; then
    echo "Error: --url, --app-name, and --package-name are mandatory." >&2
    usage
fi

if [[ ! "$WEBSITE_URL" =~ ^https?:// ]]; then
    echo "URL does not contain a scheme. Prepending https:// to the URL."
    WEBSITE_URL="https://${WEBSITE_URL}"
fi

if ! podman info >/dev/null 2>&1; then
    echo "Error: Podman is not installed or not working correctly."
    exit 1
fi

echo "--- Building Android WebView App ---"
echo "URL: $WEBSITE_URL"
echo "App Name: $APP_NAME"
echo "Package Name: $PACKAGE_NAME"
echo "Build Type: $BUILD_TYPE"
[ -n "$FAVICON_URL" ] && echo "Favicon URL: $FAVICON_URL"
echo "Version: $VERSION ($VERSION_CODE)"
echo "-------------------------------------"

IMAGE_NAME="webview-builder-$(date +%s)"
APK_OUTPUT_DIR="$(pwd)/apks"
mkdir -p "$APK_OUTPUT_DIR"

# Create a dummy keystore in a temp location; it only needs to exist so COPY . includes it.
TEMP_KEYSTORE=$(mktemp)
trap 'rm -f "$TEMP_KEYSTORE" keystore.jks' EXIT
touch "$TEMP_KEYSTORE"
cp "$TEMP_KEYSTORE" keystore.jks

podman build \
    --build-arg WEBSITE_URL="$WEBSITE_URL" \
    --build-arg APP_NAME="$APP_NAME" \
    --build-arg PACKAGE_NAME="$PACKAGE_NAME" \
    --build-arg FAVICON_URL="$FAVICON_URL" \
    --build-arg BUILD_TYPE="$BUILD_TYPE" \
    --build-arg VERSION="$VERSION" \
    --build-arg VERSION_CODE="$VERSION_CODE" \
    -t "$IMAGE_NAME" \
    -f Dockerfile .

if [ $? -eq 0 ]; then
    CONTAINER_NAME="webview-app-$(date +%s)"
    podman create --name "$CONTAINER_NAME" "$IMAGE_NAME"
    APK_PATH_IN_CONTAINER="/project/app/build/outputs/apk/${BUILD_TYPE}/app-${BUILD_TYPE}.apk"
    FINAL_APK_PATH="$APK_OUTPUT_DIR/$APP_NAME.apk"
    echo "Copying APK to: $FINAL_APK_PATH"
    podman cp "$CONTAINER_NAME:$APK_PATH_IN_CONTAINER" "$FINAL_APK_PATH"
    echo "APK moved to: $FINAL_APK_PATH"
    podman rm "$CONTAINER_NAME"
    podman rmi "$IMAGE_NAME"
else
    echo "Error: Podman image build failed." >&2
    exit 1
fi

echo ""
echo "Success! Your Android WebView app has been created."
echo "APK location: $FINAL_APK_PATH"
echo ""
echo "Note: This is a $BUILD_TYPE APK."
echo "Build process completed."
