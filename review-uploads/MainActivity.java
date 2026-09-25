package com.parallaxmoment;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int FILECHOOSER_RESULTCODE = 10001;
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                startActivityForResult(intent, FILECHOOSER_RESULTCODE);
                return true;
            }
        });

        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILECHOOSER_RESULTCODE) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                results = new Uri[]{data.getData()};
            }
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        }
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void apply(String bgBase64, String cutoutBase64, String depthBase64, String settingsJson, String target) {
            try {
                File dir = getFilesDir();

                saveBase64(bgBase64, new File(dir, "bg.jpg"));
                saveBase64(cutoutBase64, new File(dir, "cutout.png"));
                saveBase64(depthBase64, new File(dir, "depth.png"));
                writeString(settingsJson, new File(dir, "settings.json"));

                if (!"live".equalsIgnoreCase(target)) {
                    File bgFile = new File(dir, "bg.jpg");
                    Bitmap bitmap = BitmapFactory.decodeFile(bgFile.getAbsolutePath());
                    if (bitmap != null) {
                        WallpaperManager wm = WallpaperManager.getInstance(MainActivity.this);
                        int flags = 0;
                        if ("home".equalsIgnoreCase(target)) {
                            flags = WallpaperManager.FLAG_SYSTEM;
                        } else if ("lock".equalsIgnoreCase(target)) {
                            flags = WallpaperManager.FLAG_LOCK;
                        } else if ("both".equalsIgnoreCase(target)) {
                            flags = WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && flags != 0) {
                            wm.setBitmap(bitmap, null, true, flags);
                        } else {
                            wm.setBitmap(bitmap);
                        }
                    }
                }

                if ("live".equalsIgnoreCase(target) || "both".equalsIgnoreCase(target)) {
                    Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
                    intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                            new ComponentName(MainActivity.this, DepthLiveWallpaperService.class));
                    startActivity(intent);
                }

            } catch (Exception e) {
                Log.e(TAG, "apply() failed", e);
            }
        }
    }

    private void saveBase64(String base64Data, File outFile) throws IOException {
        if (base64Data == null || base64Data.isEmpty()) return;
        int comma = base64Data.indexOf(',');
        if (comma > 0) {
            base64Data = base64Data.substring(comma + 1);
        }
        byte[] bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(bytes);
        }
    }

    private void writeString(String data, File outFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(data.getBytes(StandardCharsets.UTF_8));
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
}
