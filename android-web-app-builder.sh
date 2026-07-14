#!/bin/bash

usage() {
    echo "Usage: $0 --url <website_url> --app-name <app_name> --package-name <package_name> [--favicon-url <favicon_url>]" 1>&2
    echo ""
    echo "This script builds a single-website Android APK using a Podman-based build environment."
    echo ""
    echo "Mandatory flags:"
    echo "  --url <website_url>       The full URL of the website (e.g., https://google.com)"
    echo "  --app-name <app_name>     The name of the app (e.g., Google Search)"
    echo "  --package-name <package_name>  The package name for the app (e.g., com.google.search)"
    echo ""
    echo "Optional flag:"
    echo "  --favicon-url <favicon_url>  The direct URL for the app's icon (e.g., https://example.com/icon.png)"
    exit 1
}

TEMP=$(getopt -o "" -l url:,app-name:,package-name:,favicon-url: -- "$@")
if [ $? != 0 ]; then
    usage
fi
eval set -- "$TEMP"

WEBSITE_URL=""
APP_NAME=""
PACKAGE_NAME=""
FAVICON_URL=""

while true; do
    case "$1" in
        --url) WEBSITE_URL="$2"; shift 2 ;;
        --app-name) APP_NAME="$2"; shift 2 ;;
        --package-name) PACKAGE_NAME="$2"; shift 2 ;;
        --favicon-url) FAVICON_URL="$2"; shift 2 ;;
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

if ! echo "$PACKAGE_NAME" | grep -qE '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'; then
    echo "Error: Package name must be in format com.example.app (lowercase, dots separated)" >&2
    exit 1
fi

if ! podman info >/dev/null 2>&1; then
    echo "Error: Podman is not installed or not working correctly."
    exit 1
fi

echo "--- Building Android WebView App ---"
echo "URL: $WEBSITE_URL"
echo "App Name: $APP_NAME"
echo "Package Name: $PACKAGE_NAME"
[ -n "$FAVICON_URL" ] && echo "Favicon URL: $FAVICON_URL"
echo "-------------------------------------"

TEMP_BUILD_CONTEXT=$(mktemp -d)
echo "Creating temporary Podman build context at $TEMP_BUILD_CONTEXT..."

cat << 'PODMANFILE_END' > "$TEMP_BUILD_CONTEXT/Dockerfile"
FROM docker.io/cimg/android:2024.01.1

ARG WEBSITE_URL
ARG APP_NAME
ARG PACKAGE_NAME
ARG FAVICON_URL

WORKDIR /project

RUN sudo apt-get update && sudo apt-get install -y wget imagemagick librsvg2-bin

RUN PACKAGE_PATH=$(echo "$PACKAGE_NAME" | tr '.' '/') && \
    mkdir -p app/src/main/java/${PACKAGE_PATH} && \
    mkdir -p app/src/main/res/layout && \
    mkdir -p app/src/main/res/values && \
    mkdir -p app/src/main/res/values-night && \
    mkdir -p gradle/wrapper

RUN FAV="${FAVICON_URL:-https://deckk.it/images/favicon.svg}" && \
    ( wget -qO favicon.png "$FAV" && convert favicon.png -resize 512x512 favicon_tmp.png && mv favicon_tmp.png favicon.png ) || \
    wget -qO favicon.png "https://placehold.co/512x512/000000/FFFFFF.png?text=deckk.it"

RUN mkdir -p app/src/main/res/mipmap-hdpi && \
    mkdir -p app/src/main/res/mipmap-mdpi && \
    mkdir -p app/src/main/res/mipmap-xhdpi && \
    mkdir -p app/src/main/res/mipmap-xxhdpi && \
    mkdir -p app/src/main/res/mipmap-xxxhdpi && \
    convert favicon.png -resize 48x48 app/src/main/res/mipmap-mdpi/ic_launcher.png && \
    convert favicon.png -resize 72x72 app/src/main/res/mipmap-hdpi/ic_launcher.png && \
    convert favicon.png -resize 96x96 app/src/main/res/mipmap-xhdpi/ic_launcher.png && \
    convert favicon.png -resize 144x144 app/src/main/res/mipmap-xxhdpi/ic_launcher.png && \
    convert favicon.png -resize 192x192 app/src/main/res/mipmap-xxxhdpi/ic_launcher.png

RUN echo "include ':app'" > settings.gradle

RUN cat <<MANIFEST_EOF > app/src/main/AndroidManifest.xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$PACKAGE_NAME">
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:allowBackup="true"
        android:label="$APP_NAME"
        android:theme="@style/AppTheme"
        android:supportsRtl="true"
        android:usesCleartextTraffic="false"
        android:hardwareAccelerated="true"
        android:icon="@mipmap/ic_launcher">
        <activity android:name=".MainActivity" android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden|uiMode"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
MANIFEST_EOF

RUN PACKAGE_PATH=$(echo "$PACKAGE_NAME" | tr '.' '/') && \
    cat <<JAVA_EOF > app/src/main/java/${PACKAGE_PATH}/MainActivity.java
package $PACKAGE_NAME;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.graphics.Color;
import android.content.res.Configuration;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private static final String BASE_URL = "$WEBSITE_URL";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Make it fullscreen for kiosk mode
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        
        // Hide system UI for immersive experience
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decorView.setSystemUiVisibility(uiOptions);
        
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setSaveFormData(true);
        webSettings.setSavePassword(true);
        webSettings.setSupportZoom(false);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webSettings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        
        // Avoid a white flash before the page paints: let the themed window background show through.
        webView.setBackgroundColor(Color.TRANSPARENT);
        applyDarkMode(webSettings);
        
        // Disable context menu and zoom controls for kiosk mode
        webView.setOnLongClickListener(v -> true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Handle special URL schemes
                if (url.startsWith("mailto:") || url.startsWith("tel:") || 
                    url.startsWith("sms:") || url.startsWith("geo:")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(intent);
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                }
                
                // Allow all http/https URLs to load in the WebView
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false; // Load in WebView
                }
                
                // For everything else, try to open with an intent
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            // Empty implementation - we don't need progress bar or other UI elements
        });

        webView.loadUrl(BASE_URL);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            View decorView = getWindow().getDecorView();
            int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            decorView.setSystemUiVisibility(uiOptions);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        webView.onResume();
    }

    private boolean isNightMode() {
        int nightModeFlags = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyDarkMode(WebSettings webSettings) {
        boolean night = isNightMode();
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(webSettings,
                    night ? WebSettingsCompat.FORCE_DARK_AUTO : WebSettingsCompat.FORCE_DARK_OFF);
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            WebSettingsCompat.setForceDarkStrategy(webSettings,
                    WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyDarkMode(webView.getSettings());
    }
}
JAVA_EOF

RUN cat <<LAYOUT_EOF > app/src/main/res/layout/activity_main.xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <WebView
        android:id="@+id/webview"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />
</FrameLayout>
LAYOUT_EOF

RUN echo "<resources><string name=\"app_name\">$APP_NAME</string></resources>" > app/src/main/res/values/strings.xml
RUN echo '<resources><color name="windowBackground">#FFFFFF</color></resources>' > app/src/main/res/values/colors.xml
RUN cat <<'STYLES_EOF' > app/src/main/res/values/styles.xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="AppTheme" parent="Theme.AppCompat.DayNight.NoActionBar">
        <item name="android:windowBackground">@color/windowBackground</item>
    </style>
</resources>
STYLES_EOF
RUN cat <<'DARK_STYLES_EOF' > app/src/main/res/values-night/colors.xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="windowBackground">#121212</color>
</resources>
DARK_STYLES_EOF

RUN cat <<APP_GRADLE_EOF > app/build.gradle
apply plugin: 'com.android.application'

android {
    compileSdk 34
    
    defaultConfig {
        applicationId "$PACKAGE_NAME"
        minSdk 24
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }
    
    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    
    namespace '$PACKAGE_NAME'
}

configurations.all {
    resolutionStrategy {
        force 'org.jetbrains.kotlin:kotlin-stdlib:1.9.0'
        force 'org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.0'
        force 'org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0'
        force 'org.jetbrains.kotlin:kotlin-stdlib-common:1.9.0'
    }
    
    exclude group: 'org.jetbrains.kotlin', module: 'kotlin-stdlib-jdk7'
    exclude group: 'org.jetbrains.kotlin', module: 'kotlin-stdlib-jdk8'
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.4.2'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.3'
    implementation 'androidx.webkit:webkit:1.9.0'
}
APP_GRADLE_EOF

RUN cat <<ROOT_GRADLE_EOF > build.gradle
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:8.5.2'
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

task clean(type: Delete) {
    delete rootProject.buildDir
}
ROOT_GRADLE_EOF

RUN cat <<PROPERTIES_EOF > gradle.properties
android.useAndroidX=true
android.enableJetifier=true
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.daemon=true
org.gradle.configureondemand=true
android.javaCompile.suppressSourceTargetDeprecationWarning=true
PROPERTIES_EOF

RUN cat <<WRAPPER_PROPERTIES_EOF > gradle/wrapper/gradle-wrapper.properties
distributionBase=GRADLE_USER_HOME
distributionUrl=https\://services.gradle.org/distributions/gradle-8.8-bin.zip
distributionPath=wrapper/dists
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
WRAPPER_PROPERTIES_EOF

RUN wget -q https://raw.githubusercontent.com/gradle/gradle/v8.8.0/gradle/wrapper/gradle-wrapper.jar -O gradle/wrapper/gradle-wrapper.jar

RUN cat <<'GRADLEW_EOF' > gradlew && chmod +x gradlew
#!/bin/bash
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    if [ -x "/usr/lib/jvm/java-17-openjdk-amd64/bin/java" ]; then
        JAVACMD="/usr/lib/jvm/java-17-openjdk-amd64/bin/java"
    elif [ -x "/usr/lib/jvm/java-11-openjdk-amd64/bin/java" ]; then
        JAVACMD="/usr/lib/jvm/java-11-openjdk-amd64/bin/java"
    elif command -v java >/dev/null 2>&1; then
        JAVACMD="java"
    else
        echo "Error: Could not find Java executable"
        exit 1
    fi
fi
if [ ! -x "$JAVACMD" ] && [ "$JAVACMD" != "java" ]; then
    echo "Error: Java executable not found at $JAVACMD"
    exit 1
fi
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GRADLE_WRAPPER_JAR="$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$GRADLE_WRAPPER_JAR" ]; then
    echo "Error: Gradle wrapper JAR not found at $GRADLE_WRAPPER_JAR"
    exit 1
fi
DEFAULT_JVM_OPTS="-Xmx2048m -Dfile.encoding=UTF-8"
exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS -classpath "$GRADLE_WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
GRADLEW_EOF

RUN if [ -z "$JAVA_HOME" ]; then \
        if [ -d "/usr/lib/jvm/java-17-openjdk-amd64" ]; then \
            export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"; \
        elif [ -d "/usr/lib/jvm/java-11-openjdk-amd64" ]; then \
            export JAVA_HOME="/usr/lib/jvm/java-11-openjdk-amd64"; \
        fi; \
    fi && \
    ./gradlew assembleDebug

CMD ["/bin/bash", "-c", "echo 'Build complete. The APK is at /project/app/build/outputs/apk/debug/app-debug.apk' && sleep infinity"]
PODMANFILE_END

APK_OUTPUT_DIR="$(pwd)/apks"
IMAGE_NAME="webview-builder-$(date +%s)"
podman build \
    --build-arg WEBSITE_URL="$WEBSITE_URL" \
    --build-arg APP_NAME="$APP_NAME" \
    --build-arg PACKAGE_NAME="$PACKAGE_NAME" \
    --build-arg FAVICON_URL="$FAVICON_URL" \
    -t "$IMAGE_NAME" \
    "$TEMP_BUILD_CONTEXT"

if [ $? -eq 0 ]; then
    CONTAINER_NAME="webview-app-$(date +%s)"
    podman create --name "$CONTAINER_NAME" "$IMAGE_NAME"
    mkdir -p "$APK_OUTPUT_DIR"
    APK_PATH_IN_CONTAINER="/project/app/build/outputs/apk/debug/app-debug.apk"
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

echo "Removing temporary build context..."
rm -rf "$TEMP_BUILD_CONTEXT"

echo ""
echo "Success! Your Android WebView app has been created."
echo "APK location: $FINAL_APK_PATH"
echo ""
echo "Note: This is a DEBUG APK, which is easier to install and test."
echo "Build process completed."

