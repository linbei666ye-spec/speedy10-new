
package com.speedy10.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private WebView webView;

    private static final String PREFS = "speedy10_monitor";

    public class AndroidBridge {

        @JavascriptInterface
        public String getPredictions() {

            SharedPreferences prefs = getSharedPreferences(
                    PREFS,
                    MODE_PRIVATE
            );

            return prefs.getString(
                    "predictions_json",
                    "[]"
            );
        }

        @JavascriptInterface
        public String getHistory() {

            SharedPreferences prefs = getSharedPreferences(
                    PREFS,
                    MODE_PRIVATE
            );

            return prefs.getString(
                    "history_json",
                    "[]"
            );
        }

        @JavascriptInterface
        public String getLatestIssue() {

            SharedPreferences prefs = getSharedPreferences(
                    PREFS,
                    MODE_PRIVATE
            );

            return prefs.getString(
                    "latest_issue",
                    ""
            );
        }

        @JavascriptInterface
        public String getLatestCode() {

            SharedPreferences prefs = getSharedPreferences(
                    PREFS,
                    MODE_PRIVATE
            );

            return prefs.getString(
                    "latest_code",
                    ""
            );
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        webView = new WebView(this);

        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);

        settings.setMixedContentMode(
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        );

        webView.addJavascriptInterface(
                new AndroidBridge(),
                "Android"
        );

        webView.setWebViewClient(new WebViewClient());

        webView.setWebChromeClient(new WebChromeClient());

        webView.loadUrl(
                "https://linbei666ye-spec.github.io/speedy10-new/"
        );

        setContentView(webView);

        requestNotificationPermission();

        MonitorService.start(this);
    }

    private void requestNotificationPermission() {

        if (Build.VERSION.SDK_INT >= 33) {

            if (checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {

                requestPermissions(
                        new String[]{
                                Manifest.permission.POST_NOTIFICATIONS
                        },
                        100
                );
            }
        }
    }

    @Override
    public void onBackPressed() {

        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {

        if (webView != null) {
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}
