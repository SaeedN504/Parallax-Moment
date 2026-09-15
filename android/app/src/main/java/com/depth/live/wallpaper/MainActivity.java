package com.depth.live.wallpaper;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap.CompressFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 42;
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(android.graphics.Color.BLACK);
        getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        webView.setBackgroundColor(android.graphics.Color.BLACK);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true); s.setAllowContentAccess(true); s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true); s.setUseWideViewPort(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) { s.setAllowFileAccessFromFileURLs(true); s.setAllowUniversalAccessFromFileURLs(true); }
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null); fileCallback = callback;
                try { startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST); }
                catch (Exception e) { Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT); pick.addCategory(Intent.CATEGORY_OPENABLE); pick.setType("image/*"); startActivityForResult(pick, FILE_CHOOSER_REQUEST); }
                return true;
            }
        });
        webView.addJavascriptInterface(new DepthBridge(this, webView), "DepthAndroid");
        setContentView(webView); webView.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileCallback != null) { fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)); fileCallback = null; }
    }
    @Override public void onBackPressed() { if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() { if (webView != null) { webView.removeJavascriptInterface("DepthAndroid"); webView.destroy(); } super.onDestroy(); }

    public static class DepthBridge {
        private final MainActivity activity; private final WebView webView;
        DepthBridge(MainActivity activity, WebView webView) { this.activity = activity; this.webView = webView; }

        @JavascriptInterface public String apply(String base64Image, String settingsJson) {
            Bitmap bitmap = null;
            try {
                byte[] data = Base64.decode(base64Image, Base64.DEFAULT);
                bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap == null) return fail("could not decode the exported image");
                try (FileOutputStream out = activity.openFileOutput("depth-wallpaper.jpg", Context.MODE_PRIVATE)) { bitmap.compress(CompressFormat.JPEG, 92, out); }
                if (settingsJson != null) try (FileOutputStream out = activity.openFileOutput("depth-settings.json", Context.MODE_PRIVATE)) { out.write(settingsJson.getBytes(StandardCharsets.UTF_8)); }
                String depthWarning = generateDepthMap(bitmap);
                String targets = "both";
                if (settingsJson != null) try { targets = new JSONObject(settingsJson).optString("targets", "both"); } catch (Exception ignored) {}
                if (!"home".equals(targets) && !"lock".equals(targets)) targets = "both";
                WallpaperManager manager = WallpaperManager.getInstance(activity); boolean home = false, lock = false;
                if ("home".equals(targets) || "both".equals(targets)) { if (Build.VERSION.SDK_INT >= 24) manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM); else manager.setBitmap(bitmap); home = true; }
                if ("lock".equals(targets) || "both".equals(targets)) { if (Build.VERSION.SDK_INT >= 24) manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK); else manager.setBitmap(bitmap); lock = true; }
                notifyWeb("window.__depthApplied&&window.__depthApplied('" + escape(targets) + "');");
                if (depthWarning != null) {
                    final String message = depthWarning;
                    notifyWeb("window.__depthWarning&&window.__depthWarning('" + escape(message) + "');");
                    activity.runOnUiThread(() -> Toast.makeText(activity, "Wallpaper applied in safe mode: " + message, Toast.LENGTH_LONG).show());
                }
                return "ok:" + (home ? "home;" : "") + (lock ? "lock" : "") + (depthWarning == null ? ";depth" : ";safe-mode");
            } catch (Exception e) {
                return fail(e.getMessage() == null ? "wallpaper apply failed" : e.getMessage());
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            }
        }

        /** Runs packaged MiDaS and returns null on success or a user-facing safe-mode reason. */
        private String generateDepthMap(Bitmap bitmap) {
            try (MidasDepthEstimator estimator = new MidasDepthEstimator(activity)) {
                MidasDepthEstimator.DepthResult result = estimator.estimate(bitmap);
                Bitmap map = Bitmap.createBitmap(result.getWidth(), result.getWidth(), Bitmap.Config.ARGB_8888);
                try {
                    float[][] values = result.getNormalized();
                    for (int y = 0; y < result.getWidth(); y++) for (int x = 0; x < result.getWidth(); x++) {
                        int v = Math.max(0, Math.min(255, Math.round(values[y][x] * 255f)));
                        map.setPixel(x, y, android.graphics.Color.rgb(v, v, v));
                    }
                    try (FileOutputStream out = activity.openFileOutput("depth-map.png", Context.MODE_PRIVATE)) { map.compress(CompressFormat.PNG, 100, out); }
                } finally {
                    map.recycle();
                }
                return null;
            } catch (Exception error) {
                new File(activity.getFilesDir(), "depth-map.png").delete();
                String message = error.getMessage();
                return message == null || message.trim().isEmpty() ? "depth generation failed" : message;
            }
        }

        @JavascriptInterface public void startLive() { activity.runOnUiThread(() -> { try { Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER); i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(activity, DepthLiveWallpaperService.class)); activity.startActivity(i); } catch (Exception e) { activity.startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)); } }); }
        @JavascriptInterface public boolean hasWallpaper() { return new File(activity.getFilesDir(), "depth-wallpaper.jpg").exists(); }
        @JavascriptInterface public void openAppSettings() { activity.runOnUiThread(() -> activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.getPackageName())))); }
        private String fail(String message) { notifyWeb("window.__depthFailed&&window.__depthFailed('" + escape(message) + "');"); return "error:" + message; }
        private void notifyWeb(String script) { activity.runOnUiThread(() -> webView.evaluateJavascript(script, null)); }
        private String escape(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " "); }
    }
}
