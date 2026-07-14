# AGENTS.md

Project: deckk.it Android WebView app (single-site wrapper).

## Build is done by a GitHub worker, not locally

The APK is produced by a **GitHub Actions workflow** (`.github/workflows/*.yml`, "Build APK"), triggered manually (`workflow_dispatch`). There is no local build step to run for normal changes.

Key facts:
- The worker builds from the **root `Dockerfile`** (NOT `android-web-app-builder.sh`, which is an alternative local/podman builder with the same result).
- Build args fed to the Dockerfile: `WEBSITE_URL`, `APP_NAME`, `PACKAGE_NAME`, `FAVICON_URL`, `BUILD_TYPE`, `VERSION`, `VERSION_CODE`.
- Signing uses repo secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
- The workflow extracts `*.apk` from the built container, names it `<app_name>-<version>.apk`, and publishes a GitHub Release + tag `v<version>`.

### How to change app behavior
- All app source (Java, manifest, resources, Gradle) is **generated inside the Dockerfile via heredocs**. Edit the `Dockerfile` to change the app (e.g. `MainActivity.java`, `AndroidManifest.xml`, `res/values/styles.xml`).
- `android-web-app-builder.sh` embeds an equivalent Dockerfile for local podman builds; keep it in sync with the root `Dockerfile` when changing behavior.
- After pushing, trigger the workflow from the Actions tab with the desired inputs.

### Important: the app must follow system dark/light mode
- Theme is `Theme.AppCompat.DayNight.NoActionBar`; the window background adapts via `res/values/colors.xml` (`windowBackground`) and `res/values-night/colors.xml`.
- The WebView is made transparent and dark mode is passed to web pages via `androidx.webkit.WebSettingsCompat.setForceDark` (`FORCE_DARK_AUTO`) + `DARK_STRATEGY_WEB_THEME_DARKENING_ONLY`.
- External links open in Chrome Custom Tabs with `CustomTabsIntent` color scheme set to follow the system (`COLOR_SCHEME_SYSTEM`), so they are not forced into a light theme.
- Do not regress these (white flash on first load / always-light rendering) when editing the Dockerfile.
