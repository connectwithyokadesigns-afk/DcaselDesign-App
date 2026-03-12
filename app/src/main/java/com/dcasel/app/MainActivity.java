package com.dcasel.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    // ─── File picker launcher ─────────────────────────────────────────────────
    private final ActivityResultLauncher<Intent> filePickerLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) results = new Uri[]{ uri };
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        });

    // ─── Permission launcher ──────────────────────────────────────────────────
    private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), perms -> {
            // Permissions handled silently; operations retry on next user action
        });

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        View decorView = getWindow().getDecorView();
        WindowInsetsControllerCompat controller =
            new WindowInsetsControllerCompat(getWindow(), decorView);
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);

        setupWebView();
        requestRequiredPermissions();

        // FIX: Replace deprecated onBackPressed() override with OnBackPressedCallback
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        webView.loadUrl("file:///android_asset/www/index.html");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        // FIX: Use LOAD_DEFAULT (not LOAD_NO_CACHE) so assets load correctly from file://
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // FIX: Enable cross-file access required for local asset loading
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setDatabaseEnabled(true);
        s.setOffscreenPreRaster(true);
        // FIX: User agent — helps external URLs identify the app correctly
        s.setUserAgentString(s.getUserAgentString() + " DcaselApp/1.0");

        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view,
                    android.webkit.WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (url.startsWith("file://") || url.startsWith("about:")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                    String description, String failingUrl) {
                // Silently handle load errors (supports offline use)
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // FIX: Inject Android bridge availability flag into JS context
                view.evaluateJavascript(
                    "window._androidReady = true; " +
                    "window.dispatchEvent(new Event('androidready'));",
                    null
                );
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> fileCb,
                    FileChooserParams params) {
                // FIX: Cancel any pending callback before setting a new one
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
                filePathCallback = fileCb;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                try {
                    filePickerLauncher.launch(intent);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        AppBridge bridge = new AppBridge();
        // FIX: Register both names — new and legacy — for JS backward compatibility
        webView.addJavascriptInterface(bridge, "AppBridge");
        webView.addJavascriptInterface(bridge, "AndroidBridge");
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES
                });
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                });
            }
        }
    }

    // FIX: Release WebView resources to prevent memory leaks
    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    // ─── JavaScript Bridge ────────────────────────────────────────────────────
    public class AppBridge {

        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() ->
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        /**
         * Save a base64-encoded PNG to the device gallery.
         * Works on API 24–35 (Android 7 – Android 15).
         * Calls window._onSaveResult(success, message) on completion.
         */
        @JavascriptInterface
        public void saveImageToGallery(String base64Data, String filename) {
            try {
                // Strip data URL prefix if present (e.g. "data:image/png;base64,...")
                String pureBase64 = base64Data.contains(",")
                    ? base64Data.split(",")[1] : base64Data;

                // FIX: Ensure valid base64 padding
                int pad = pureBase64.length() % 4;
                if (pad != 0) pureBase64 += "====".substring(pad);

                byte[] bytes = Base64.decode(pureBase64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

                if (bmp == null) {
                    notifyJS(false, "Could not decode image. Please try again.");
                    return;
                }

                // Ensure filename ends with .png
                String safeFilename = filename.toLowerCase().endsWith(".png")
                    ? filename : filename + ".png";

                String savedPath = null;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ — MediaStore (no legacy storage permission needed)
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, safeFilename);
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/Dcasel");
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);

                    Uri uri = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

                    if (uri != null) {
                        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                            if (out != null) {
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                                out.flush();
                            }
                        }
                        values.clear();
                        values.put(MediaStore.Images.Media.IS_PENDING, 0);
                        getContentResolver().update(uri, values, null, null);
                        savedPath = Environment.DIRECTORY_PICTURES + "/Dcasel/" + safeFilename;
                    }
                } else {
                    // Android 7–9 — legacy file path
                    File dir = new File(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_PICTURES), "Dcasel");
                    if (!dir.exists() && !dir.mkdirs()) {
                        notifyJS(false, "Cannot create save directory.");
                        return;
                    }
                    File imageFile = new File(dir, safeFilename);
                    try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                        fos.flush();
                    }
                    // Trigger gallery scan
                    Intent scan = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                    scan.setData(Uri.fromFile(imageFile));
                    sendBroadcast(scan);
                    savedPath = imageFile.getAbsolutePath();
                }

                bmp.recycle();

                if (savedPath != null) {
                    notifyJS(true, "Saved to Pictures/Dcasel");
                } else {
                    notifyJS(false, "Save failed. Please check storage permissions.");
                }

            } catch (SecurityException e) {
                notifyJS(false, "Permission denied. Grant storage access in Settings.");
            } catch (Exception e) {
                e.printStackTrace();
                notifyJS(false, "Export error: " + e.getMessage());
            }
        }

        /**
         * Share image via system share sheet (WhatsApp, Drive, Gmail, etc.)
         */
        @JavascriptInterface
        public void shareImage(String base64Data, String filename) {
            try {
                String pureBase64 = base64Data.contains(",")
                    ? base64Data.split(",")[1] : base64Data;
                byte[] bytes = Base64.decode(pureBase64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp == null) {
                    notifyJS(false, "Cannot decode image.");
                    return;
                }

                String safeFilename = filename.toLowerCase().endsWith(".png")
                    ? filename : filename + ".png";

                // Write to app cache directory for sharing via FileProvider
                File cacheDir = new File(getCacheDir(), "shared");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                File shareFile = new File(cacheDir, safeFilename);
                try (FileOutputStream fos = new FileOutputStream(shareFile)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.flush();
                }
                bmp.recycle();

                // FIX: Use FileProvider — required for sharing files outside app on API 24+
                Uri uri = FileProvider.getUriForFile(
                    MainActivity.this,
                    getPackageName() + ".fileprovider",
                    shareFile);

                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("image/png");
                share.putExtra(Intent.EXTRA_STREAM, uri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(share, "Share Image"));

            } catch (Exception e) {
                e.printStackTrace();
                notifyJS(false, "Share error: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public boolean isAndroid() { return true; }

        @JavascriptInterface
        public String getAppVersion() { return BuildConfig.VERSION_NAME; }

        @JavascriptInterface
        public int getApiLevel() { return Build.VERSION.SDK_INT; }

        // FIX: Sanitise message before injecting into JS to prevent script injection
        private void notifyJS(boolean success, String message) {
            String escaped = message
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n");
            runOnUiThread(() ->
                webView.evaluateJavascript(
                    "window._onSaveResult && window._onSaveResult("
                        + success + ", '" + escaped + "');",
                    null
                )
            );
        }
    }
}
