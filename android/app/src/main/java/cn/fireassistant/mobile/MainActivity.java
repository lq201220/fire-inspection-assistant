package cn.fireassistant.mobile;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.File;
import java.io.FileOutputStream;

/** Online application host. Only the configured site can navigate inside this WebView. */
public class MainActivity extends AppCompatActivity {
    private WebView web;
    private LinearLayout offline;
    private volatile boolean pageReady;
    private boolean wasPaused;
    private ValueCallback<Uri[]> fileResult;
    private final Uri appOrigin = Uri.parse(BuildConfig.APP_WEB_URL);
    private final ActivityResultLauncher<Intent> picker = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            ValueCallback<Uri[]> callback = fileResult;
            fileResult = null;
            if (callback != null) callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.getResultCode(), result.getData()));
        }
    );

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(246, 247, 249));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            return insets;
        });
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " FireAssistant/" + BuildConfig.VERSION_NAME);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        web.addJavascriptInterface(new OnlineActions(), "FireAssistantNative");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                if (trusted(uri)) return false;
                String scheme = uri.getScheme();
                if ("https".equals(scheme) || "http".equals(scheme) || "mailto".equals(scheme) || "tel".equals(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                }
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                pageReady = false;
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showOffline();
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) showOffline();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileResult != null) fileResult.onReceiveValue(null);
                fileResult = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                    picker.launch(intent);
                } catch (Exception e) {
                    fileResult = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "暂时无法选择文件", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        offline = new LinearLayout(this);
        offline.setOrientation(LinearLayout.VERTICAL);
        offline.setGravity(Gravity.CENTER);
        offline.setPadding(32, 32, 32, 32);
        offline.setBackgroundColor(Color.rgb(246, 247, 249));
        TextView text = new TextView(this);
        text.setText("暂时无法连接，请检查网络后重试");
        text.setTextColor(Color.rgb(30, 50, 70));
        text.setTextSize(16);
        offline.addView(text);
        Button retry = new Button(this);
        retry.setText("重试");
        retry.setOnClickListener(v -> openApp());
        offline.addView(retry);
        offline.setVisibility(View.GONE);
        root.addView(offline, new FrameLayout.LayoutParams(-1, -1));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (pageReady && offline.getVisibility() != View.VISIBLE) {
                    web.evaluateJavascript("window.dispatchEvent(new Event('fire-assistant-back'))", null);
                } else moveTaskToBack(true);
            }
        });
        openApp();
    }

    private boolean trusted(Uri uri) {
        return appOrigin.getScheme().equals(uri.getScheme()) && appOrigin.getHost().equals(uri.getHost()) && appOrigin.getPort() == uri.getPort();
    }
    private void openApp() {
        offline.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.loadUrl(BuildConfig.APP_WEB_URL);
    }
    private void showOffline() {
        pageReady = false;
        web.setVisibility(View.GONE);
        offline.setVisibility(View.VISIBLE);
    }
    @Override protected void onPause() { super.onPause(); wasPaused = true; }
    @Override protected void onResume() {
        super.onResume();
        if (wasPaused && pageReady && web != null) web.evaluateJavascript("window.dispatchEvent(new Event('fire-assistant-resume'))", null);
        wasPaused = false;
    }
    @Override protected void onDestroy() {
        if (fileResult != null) fileResult.onReceiveValue(null);
        if (web != null) { web.removeJavascriptInterface("FireAssistantNative"); web.destroy(); }
        super.onDestroy();
    }

    public class OnlineActions {
        @JavascriptInterface public void ready() { pageReady = true; }
        @JavascriptInterface public void minimize() { runOnUiThread(() -> moveTaskToBack(true)); }
        @JavascriptInterface public boolean shareFile(String data, String name, String type) {
            if (data == null || data.length() > 15 * 1024 * 1024 || name == null || !name.matches("[A-Za-z0-9._-]{1,160}")) return false;
            if (!("image/png".equals(type) || "image/jpeg".equals(type) || "application/pdf".equals(type))) return false;
            try {
                byte[] bytes = Base64.decode(data, Base64.DEFAULT);
                if (bytes.length > 10 * 1024 * 1024) return false;
                File directory = new File(getCacheDir(), "attachments");
                if (!directory.isDirectory() && !directory.mkdirs()) return false;
                File file = new File(directory, name);
                try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
                Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", file);
                runOnUiThread(() -> {
                    try {
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType(type);
                        share.putExtra(Intent.EXTRA_STREAM, uri);
                        share.setClipData(ClipData.newRawUri("附件", uri));
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(share, "保存或分享附件"));
                    } catch (Exception e) { Toast.makeText(MainActivity.this, "暂时无法打开附件", Toast.LENGTH_SHORT).show(); }
                });
                return true;
            } catch (Exception e) { return false; }
        }
    }
}
