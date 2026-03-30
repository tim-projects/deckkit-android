FROM docker.io/cimg/android:2024.01.1

ARG WEBSITE_URL=https://example.com
ARG APP_NAME=MyApp
ARG PACKAGE_NAME=com.example.app
ARG FAVICON_URL=
ARG BUILD_TYPE=release
ARG CACHE_DATE=1

ENV WEBSITE_URL=$WEBSITE_URL
ENV APP_NAME=$APP_NAME
ENV PACKAGE_NAME=$PACKAGE_NAME
ENV FAVICON_URL=$FAVICON_URL
ENV BUILD_TYPE=$BUILD_TYPE

WORKDIR /project

RUN sudo apt-get update && sudo apt-get install -y wget imagemagick

RUN if [ -f "keystore.jks" ]; then cp keystore.jks /project/keystore.jks; fi

RUN PACKAGE_PATH=$(echo "$PACKAGE_NAME" | tr '.' '/') && \
    mkdir -p app/src/main/java/${PACKAGE_PATH} && \
    mkdir -p app/src/main/res/layout && \
    mkdir -p app/src/main/res/values && \
    mkdir -p app/src/main/res/values-night && \
    mkdir -p gradle/wrapper

RUN if [ -n "$FAVICON_URL" ]; then \
        wget -qO favicon.png "$FAVICON_URL" || \
        wget -qO favicon.png "https://placehold.co/512x512/000000/FFFFFF.png?text=APP"; \
    else \
        wget -qO favicon.png "https://placehold.co/512x512/000000/FFFFFF.png?text=APP"; \
    fi

RUN mkdir -p app/src/main/res/mipmap-hdpi app/src/main/res/mipmap-mdpi app/src/main/res/mipmap-xhdpi app/src/main/res/mipmap-xxhdpi app/src/main/res/mipmap-xxxhdpi && \
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
        <activity android:name="androidx.browser.customtabs.CustomTabsActivity" android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="https" />
            </intent-filter>
        </activity>
    </application>
    <queries>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="https" />
        </intent>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="firebaseauth" />
        </intent>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="googleusercontent" />
        </intent>
    </queries>
</manifest>
MANIFEST_EOF

RUN PACKAGE_PATH=$(echo "$PACKAGE_NAME" | tr '.' '/') && \
    mkdir -p app/src/main/java/${PACKAGE_PATH}

RUN cat <<JAVA_EOF > app/src/main/java/${PACKAGE_PATH}/MainActivity.java
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
import androidx.browser.customtabs.CustomTabsIntent;

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
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webSettings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    Uri uri = Uri.parse(url);
                    String host = uri.getHost();
                    if (host != null && (host.equals(Uri.parse(BASE_URL).getHost()) || 
                        host.contains("googleapis.com") ||
                        host.contains("google.com") ||
                        host.contains("firebaseapp.com") ||
                        host.contains("firebase.com") ||
                        host.contains("accounts.google.com") ||
                        host.contains("ssl.gstatic.com") ||
                        host.contains("gstatic.com"))) {
                        return false;
                    } else {
                        openInCustomTab(url);
                        return true;
                    }
                } else if (url.startsWith("intent://")) {
                    try {
                        Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        Intent chooser = Intent.createChooser(intent, "Open with");
                        startActivity(chooser);
                    } catch (Exception e) {}
                    return true;
                } else if (url.startsWith("firebaseauth://") || url.startsWith("chrome://")) {
                    return false;
                } else {
                    openInCustomTab(url);
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
                if (newProgress == 100) progressBar.setVisibility(View.GONE);
                else { progressBar.setVisibility(View.VISIBLE); progressBar.setProgress(newProgress); }
            }
        });

        webView.loadUrl(BASE_URL);
    }

    private void openInCustomTab(String url) {
        try {
            CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder();
            CustomTabsIntent customTabsIntent = builder.build();
            customTabsIntent.launchUrl(this, Uri.parse(url));
        } catch (Exception e) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
JAVA_EOF

RUN cat <<LAYOUT_EOF > app/src/main/res/layout/activity_main.xml
<?xml version="1.0" encoding="utf-8"?>
<RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <ProgressBar android:id="@+id/progressBar" style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="match_parent" android:layout_height="4dp" android:layout_alignParentTop="true"
        android:indeterminate="false" android:visibility="gone" android:max="100" />
    <WebView android:id="@+id/webview" android:layout_width="match_parent" android:layout_height="match_parent" />
</RelativeLayout>
LAYOUT_EOF

RUN echo "<resources><string name=\"app_name\">$APP_NAME</string></resources>" > app/src/main/res/values/strings.xml
RUN echo '<?xml version="1.0" encoding="utf-8"?><resources><color name="progressBarColor">@android:color/black</color></resources>' > app/src/main/res/values/colors.xml
RUN echo '<?xml version="1.0" encoding="utf-8"?><resources><style name="AppTheme" parent="Theme.AppCompat.Light.NoActionBar"><item name="android:progressBarStyleHorizontal">@style/CustomProgressBar</item></style><style name="CustomProgressBar" parent="@android:style/Widget.ProgressBar.Horizontal"><item name="android:progressTint">@color/progressBarColor</item></style></resources>' > app/src/main/res/values/styles.xml
RUN echo '<?xml version="1.0" encoding="utf-8"?><resources><color name="progressBarColor">@android:color/white</color></resources>' > app/src/main/res/values-night/colors.xml

RUN cat <<'GRADLE_EOF' > app/build.gradle
apply plugin: 'com.android.application'

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
                    storePassword System.getenv("KEYSTORE_PASSWORD")
                    keyAlias System.getenv("KEY_ALIAS")
                    keyPassword System.getenv("KEY_PASSWORD")
                }
                signingConfig signingConfigs.release
            }
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
        debug {
            if (System.getenv("KEYSTORE_PASSWORD")) signingConfig signingConfigs.release
        }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    namespace System.getenv("PACKAGE_NAME")
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.4.2'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.3'
    implementation 'androidx.browser:browser:1.4.0'
}
GRADLE_EOF

RUN cat <<'ROOT_EOF' > build.gradle
buildscript {
    repositories { google(); mavenCentral() }
    dependencies { classpath 'com.android.tools.build:gradle:8.5.2' }
}
allprojects {
    repositories { google(); mavenCentral() }
}
task clean(type: Delete) { delete rootProject.buildDir }
ROOT_EOF

RUN echo "android.useAndroidX=true" > gradle.properties
RUN echo "android.enableJetifier=true" >> gradle.properties
RUN echo "org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8" >> gradle.properties

RUN echo "distributionBase=GRADLE_USER_HOME" > gradle/wrapper/gradle-wrapper.properties
RUN echo "distributionUrl=https\://services.gradle.org/distributions/gradle-8.8-bin.zip" >> gradle/wrapper/gradle-wrapper.properties
RUN echo "distributionPath=wrapper/dists" >> gradle/wrapper/gradle-wrapper.properties
RUN echo "zipStoreBase=GRADLE_USER_HOME" >> gradle/wrapper/gradle-wrapper.properties
RUN echo "zipStorePath=wrapper/dists" >> gradle/wrapper/gradle-wrapper.properties

RUN wget -q https://raw.githubusercontent.com/gradle/gradle/v8.8.0/gradle/wrapper/gradle-wrapper.jar -O gradle/wrapper/gradle-wrapper.jar

RUN cat <<'GW' > gradlew
#!/bin/bash
JAVA_HOME=${JAVA_HOME:-$(dirname $(dirname $(readlink -f $(which java))))}
exec $JAVA_HOME/bin/java -Xmx2048m -Dfile.encoding=UTF-8 -classpath $(dirname $0)/gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain "$@"
GW

RUN chmod +x gradlew && ls -la /project/ && ls -la /project/gradlew

RUN ./gradlew clean assembleDebug 2>&1 | tail -50

CMD ["tail", "-f", "/dev/null"]