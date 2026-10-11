package com.pbb.ringtonecutter

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

// WebView wrapper for "חיתוך רינגטונים חכם" — kept intentionally minimal (no Capacitor/Cordova)
// so the APK stays small, same philosophy as the lightweight Windows build (Go + WebView2).
//
// Four gaps a plain WebView has vs. a desktop/browser build, each bridged here:
// 1) <input type=file> needs onShowFileChooser to open the system picker.
// 2) A persisted API key needs a JS bridge (SharedPreferences), exposed under the SAME
//    window.desktopSaveApiKey/desktopLoadApiKey names the HTML already feature-detects
//    for the Windows build — so the HTML itself needed no changes, just a small shim
//    (see assets/app.html's injected <head> script) that forwards to this bridge.
// 3) Saving a generated file (MP3 / log / API key .txt) via a Blob + <a download> click
//    does not trigger a real download in WebView — the same injected shim intercepts
//    that click and hands the data to AndroidBridge.saveBlob() instead, which opens the
//    system "save as" picker (Storage Access Framework) so the person chooses where on
//    the device the file goes, instead of it being silently written to Downloads.
// 4) Without useWideViewPort/loadWithOverviewMode, WebView ignores the page's own
//    <meta name="viewport"> and renders at a fixed ~980px desktop-width canvas, zoomed
//    out to fit — text and layout look shrunk/"floating" instead of filling the real
//    (narrow) screen width. Both settings below make it honor the real device width.
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSaveBytes: ByteArray? = null

    private val fileChooserLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            if (callback == null) return@registerForActivityResult
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data?.data == null) {
                callback.onReceiveValue(null)
            } else {
                callback.onReceiveValue(arrayOf(data.data!!))
            }
        }

    private val createDocumentLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
            val bytes = pendingSaveBytes
            pendingSaveBytes = null
            if (uri != null && bytes != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                } catch (_: Exception) {
                    // best-effort, same as before
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        val s: WebSettings = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true

        webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback = callback
                val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT)
                intent.type = "audio/*"
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }

        webView.loadUrl("file:///android_asset/app.html")
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    /** Exposed to the page as window.AndroidBridge.* — see assets/app.html's shim for the
     * window.desktopSaveApiKey / desktopLoadApiKey / blob-download forwarding. */
    inner class AndroidBridge(private val activity: MainActivity) {
        private val prefs = activity.getSharedPreferences("ringtone_cutter", MODE_PRIVATE)

        @JavascriptInterface
        fun saveApiKey(key: String) {
            prefs.edit().putString("api_key", key).apply()
        }

        @JavascriptInterface
        fun loadApiKey(): String = prefs.getString("api_key", "") ?: ""

        /** data is base64 (no data: prefix), filename includes extension, mime is the content type.
         * Opens the system "save as" picker so the person chooses the destination folder,
         * instead of always writing silently to Downloads. JavascriptInterface callbacks run on
         * a background thread, so the picker launch is posted to the UI thread. */
        @JavascriptInterface
        fun saveBlob(base64Data: String, filename: String, mime: String) {
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                activity.runOnUiThread {
                    activity.pendingSaveBytes = bytes
                    try {
                        activity.createDocumentLauncher.launch(filename)
                    } catch (_: Exception) {
                        activity.pendingSaveBytes = null
                    }
                }
            } catch (_: Exception) {
                // best-effort — a failed save shouldn't crash the app; the page's own
                // error banner already surfaces unexpected errors to the user.
            }
        }
    }
}
