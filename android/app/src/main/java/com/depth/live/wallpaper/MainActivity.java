package com.depth.live.wallpaper;

import android.annotation.SuppressLint;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends BridgeActivity {

    private DepthBridge depthBridge;

    /**
     * The interface has to be attached BEFORE the WebView evaluates the page:
     * addJavascriptInterface() only becomes visible to JS on the next page load,
     * so registering it in onStart() (after Capacitor already loaded index.html)
     * left window.DepthAndroid undefined -> "Apply" silently fell back to saving
     * a PNG and the "Set as Live Wallpaper" button stayed hidden.
     */
    @SuppressLint("JavascriptInterface")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        attachDepthBridge();
    }

    @Override
    public void onStart() {
        super.onStart();
        attachDepthBridge(); // safety net if the WebView was not ready yet
    }

    @SuppressLint("JavascriptInterface")
    private void attachDepthBridge() {
        if (depthBridge != null) return;
        if (getBridge() == null || getBridge().getWebView() == null) return;
        WebView webView = getBridge().getWebView();
        depthBridge = new DepthBridge(this);
        webView.addJavascriptInterface(depthBridge, "DepthAndroid");
    }

    /**
     * Native bridge consumed by the web editor:
     *   window.DepthAndroid.apply(base64Jpeg, settingsJson) -> applies wallpaper
     *   window.DepthAndroid.startLive()                     -> opens live-wallpaper picker
     * Reports back via window.__depthApplied(targets) in the WebView.
     */
    public static class DepthBridge {
        private final MainActivity activity;

        DepthBridge(MainActivity a) { this.activity = a; }

        @JavascriptInterface
        public String apply(String base64Image, String settingsJson) {
            try {
                byte[] data = Base64.decode(base64Image, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bmp == null) {
                    notifyWebError("could not decode the exported image");
                    return "decode-failed";
                }

                FileOutputStream out = activity.openFileOutput("depth-wallpaper.jpg", Context.MODE_PRIVATE);
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out);
                out.close();

                if (settingsJson != null) {
                    FileOutputStream so = activity.openFileOutput("depth-settings.json", Context.MODE_PRIVATE);
                    so.write(settingsJson.getBytes("UTF-8"));
                    so.close();
                }

                String targets = "both";
                if (settingsJson != null) {
                    try {
                        targets = new JSONObject(settingsJson).optString("targets", "both");
                    } catch (Exception parseError) {
                        targets = "both";
                    }
                }
                if (!"lock".equals(targets) && !"home".equals(targets)) targets = "both";

                WallpaperManager wm = WallpaperManager.getInstance(activity);
                boolean homeDone = false, lockDone = false;
                if (targets.equals("home") || targets.equals("both")) {
                    wm.setBitmap(bmp);
                    homeDone = true;
                }
                if (targets.equals("lock") || targets.equals("both")) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK);
                    } else {
                        wm.setBitmap(bmp);
                    }
                    lockDone = true;
                }
                bmp.recycle();
                notifyWeb(targets);
                return "ok:" + (homeDone ? "home;" : "") + (lockDone ? "lock" : "");
            } catch (Exception e) {
                notifyWebError(e.getMessage());
                return "error:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public void startLive() {
            try {
                Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
                i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        new ComponentName(activity, DepthLiveWallpaperService.class));
                activity.startActivity(i);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public boolean hasWallpaper() {
            return new File(activity.getFilesDir(), "depth-wallpaper.jpg").exists();
        }

        private void notifyWeb(final String targets) {
            eval("window.__depthApplied&&window.__depthApplied('" + esc(targets) + "');");
        }

        private void notifyWebError(final String message) {
            eval("window.__depthFailed&&window.__depthFailed('" + esc(message == null ? "unknown error" : message) + "');");
        }

        private String esc(String s) {
            return s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
        }

        private void eval(final String js) {
            activity.runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (activity.getBridge() != null && activity.getBridge().getWebView() != null) {
                        activity.getBridge().getWebView().evaluateJavascript(js, null);
                    }
                }
            });
        }
    }
}
