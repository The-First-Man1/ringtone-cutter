package com.generated.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final int REQUEST_CODE_FILE_CHOOSER = 1001;
    private static final int REQUEST_CODE_CREATE_DOCUMENT = 1002;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private byte[] pendingSaveBytes;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setDefaultTextEncodingName("utf-8");

        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                Intent intent = null;
                if (params != null) {
                    intent = params.createIntent();
                }
                if (intent == null) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("audio/*");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                }
                try {
                    startActivityForResult(intent, REQUEST_CODE_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
            }
        });

        webView.loadUrl("file:///android_asset/app.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_FILE_CHOOSER) {
            if (filePathCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Uri dataUri = data.getData();
                    if (dataUri != null) {
                        results = new Uri[]{ dataUri };
                    } else if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        } else if (requestCode == REQUEST_CODE_CREATE_DOCUMENT) {
            byte[] bytes = pendingSaveBytes;
            pendingSaveBytes = null;
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null && bytes != null) {
                Uri uri = data.getData();
                try {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        try {
                            os.write(bytes);
                            os.flush();
                        } finally {
                            os.close();
                        }
                        Toast.makeText(this, "הרינגטון נשמר בהצלחה!", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "שגיאה בשמירת הקובץ", Toast.LENGTH_SHORT).show();
                }
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

    public class AndroidBridge {
        private final Activity activity;
        private final SharedPreferences prefs;

        public AndroidBridge(Activity activity) {
            this.activity = activity;
            this.prefs = activity.getSharedPreferences("ringtone_cutter", MODE_PRIVATE);
        }

        @JavascriptInterface
        public void saveApiKey(String key) {
            prefs.edit().putString("api_key", key).apply();
        }

        @JavascriptInterface
        public String loadApiKey() {
            return prefs.getString("api_key", "");
        }

        @JavascriptInterface
        public void saveBlob(final String base64Data, final String filename, final String mime) {
            try {
                final byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        pendingSaveBytes = bytes;
                        try {
                            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            intent.setType(mime != null && !mime.isEmpty() ? mime : "audio/*");
                            intent.putExtra(Intent.EXTRA_TITLE, filename);
                            startActivityForResult(intent, REQUEST_CODE_CREATE_DOCUMENT);
                        } catch (Exception e) {
                            pendingSaveBytes = null;
                            Toast.makeText(activity, "שגיאה בפתיחת מנהל הקבצים", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            } catch (Exception ignored) {
            }
        }
    }
}
