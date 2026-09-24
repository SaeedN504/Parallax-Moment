package com.deepfx.clock

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Hosts the Deep FX Clock editor. The WebView setup mirrors the proven
 * Parallax Moment configuration so the offline imgly cutout engine keeps
 * working from file:// assets.
 */
class MainActivity : Activity() {
    private var webView: WebView? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val view = WebView(this)
        view.layoutParams = ViewGroup.LayoutParams(-1, -1)
        view.setBackgroundColor(Color.BLACK)

        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        // The offline imgly engine fetches its models over file:// URLs, so file access
        // from file URLs must stay enabled. Universal (cross-origin) access is not needed
        // and stays off because this WebView has the JS bridge attached.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = false
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                isOffLimits(request.url)

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                isOffLimits(Uri.parse(url))
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST)
                    true
                } catch (error: Exception) {
                    val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    }
                    startActivityForResult(pick, FILE_CHOOSER_REQUEST)
                    true
                }
            }
        }

        view.addJavascriptInterface(DeepFxBridge(this, view), "DepthAndroid")
        setContentView(view)
        view.loadUrl("file:///android_asset/deepfx.html")
        webView = view
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        }
    }

    override fun onBackPressed() {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView?.let {
            it.removeJavascriptInterface("DepthAndroid")
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    /**
     * Keeps the WebView on the packaged editor. Because the JS bridge is attached to this
     * view, letting it navigate to a remote page would hand `DepthAndroid` to that page.
     */
    private fun isOffLimits(uri: Uri): Boolean = when (uri.scheme) {
        "file" -> !uri.toString().startsWith(ASSET_PREFIX)
        "about", "data", "blob" -> false
        else -> true
    }

    private companion object {
        const val FILE_CHOOSER_REQUEST = 42
        const val ASSET_PREFIX = "file:///android_asset/"
    }
}
