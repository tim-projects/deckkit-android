FROM docker.io/cimg/android:2024.01.1

ARG WEBSITE_URL=https://example.com
ARG APP_NAME=MyApp
ARG PACKAGE_NAME=com.example.app
ARG FAVICON_URL=
ARG BUILD_TYPE=release

ENV WEBSITE_URL=$WEBSITE_URL
ENV APP_NAME=$APP_NAME
ENV PACKAGE_NAME=$PACKAGE_NAME
ENV FAVICON_URL=$FAVICON_URL
ENV BUILD_TYPE=$BUILD_TYPE

WORKDIR /project

RUN sudo apt-get update && sudo apt-get install -y wget imagemagick

RUN ls -la

RUN if [ -f "keystore.jks" ]; then \
    cp keystore.jks /project/keystore.jks; \
    fi

RUN PACKAGE_PATH=$(echo "$PACKAGE_NAME" | tr '.' '/') && \
    mkdir -p app/src/main/java/${PACKAGE_PATH} && \
    mkdir -p app/src/main/res/layout && \
    mkdir -p app/src/main/res/values && \
    mkdir -p app/src/main/res/values-night && \
    mkdir -p gradle/wrapper

RUN if [ -n "$FAVICON_URL" ]; then \
        wget -qO favicon.png "$FAVICON_URL" || \
        (echo "Could not download favicon from $FAVICON_URL. Using default." && \
        wget -qO favicon.png "https://placehold.co/512x512/000000/FFFFFF.png?text=APP"); \
    else \
        echo "No favicon URL provided. Using default." && \
        wget -qO favicon.png "https://placehold.co/512x512/000000/FFFFFF.png?text=APP"; \
    fi

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
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private ProgressBar progressBar;
    private static final String BASE_URL = "$WEBSITE_URL";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progressBar);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    if (Uri.parse(url).getHost().equals(Uri.parse(BASE_URL).getHost())) {
                        return false;
                    } else {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(intent);
                        return true;
                    }
                } else {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(intent);
                    } catch (Exception e) {
                    }
                    return true;
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                }
            }
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
}
JAVA_EOF

RUN cat <<LAYOUT_EOF > app/src/main/res/layout/activity_main.xml
<?xml version="1.0" encoding="utf-8"?>
<RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <ProgressBar
        android:id="@+id/progressBar"
        style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="match_parent"
        android:layout_height="4dp"
        android:layout_alignParentTop="true"
        android:indeterminate="false"
        android:visibility="gone"
        android:max="100" />
    <WebView
        android:id="@+id/webview"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />
</RelativeLayout>
LAYOUT_EOF

RUN echo "<resources><string name=\"app_name\">$APP_NAME</string></resources>" > app/src/main/res/values/strings.xml
RUN cat <<'COLORS_EOF' > app/src/main/res/values/colors.xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="progressBarColor">@android:color/black</color>
</resources>
COLORS_EOF
RUN cat <<'STYLES_EOF' > app/src/main/res/values/styles.xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="AppTheme" parent="Theme.AppCompat.Light.NoActionBar">
        <item name="android:progressBarStyleHorizontal">@style/CustomProgressBar</item>
    </style>
    <style name="CustomProgressBar" parent="@android:style/Widget.ProgressBar.Horizontal">
        <item name="android:progressTint">@color/progressBarColor</item>
    </style>
</resources>
STYLES_EOF
RUN cat <<'DARK_STYLES_EOF' > app/src/main/res/values-night/colors.xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="progressBarColor">@android:color/white</color>
</resources>
DARK_STYLES_EOF

RUN cat <<'APP_GRADLE_EOF' > app/build.gradle
apply plugin: 'com.android.application'

def keystorePass = System.getenv("KEYSTORE_PASSWORD") ?: "dummy"
def keyAlias = System.getenv("KEY_ALIAS") ?: "dummy"
def keyPass = System.getenv("KEY_PASSWORD") ?: "dummy"

android {
    compileSdk 34
    
    defaultConfig {
        applicationId System.getenv("PACKAGE_NAME")
        minSdk 24
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }
    
    buildTypes {
        release {
            minifyEnabled false
            if (System.getenv("KEYSTORE_PASSWORD")) {
                signingConfigs.release {
                    storeFile file("keystore.jks")
                    storePassword keystorePass
                    keyAlias keyAlias
                    keyPassword keyPass
                }
                signingConfig signingConfigs.release
            }
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
        debug {
            if (System.getenv("KEYSTORE_PASSWORD")) {
                signingConfig signingConfigs.release
            }
        }
    }
    
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    
    namespace System.getenv("PACKAGE_NAME")
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
        fi; \
    fi && \
    java -version && \
    if [ "$BUILD_TYPE" = "release" ]; then \
        ./gradlew assembleRelease; \
    else \
        ./gradlew assembleDebug; \
    fi 2>&1 | tail -100

RUN find /project/app/build/outputs -name "*.apk"

CMD ["/bin/bash", "-c", "echo 'Build complete.' && sleep infinity"]