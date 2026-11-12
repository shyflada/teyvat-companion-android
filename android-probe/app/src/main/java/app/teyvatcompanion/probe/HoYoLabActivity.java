package app.teyvatcompanion.probe;

import android.app.Activity;
import android.content.Context;
import android.graphics.Insets;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URISyntaxException;
import org.json.JSONException;
import org.json.JSONObject;

public final class HoYoLabActivity extends Activity {
    private static final String PATH = "/ys/app/interactive-map/index.html";
    private static final String MAP = "https://act.hoyolab.com" + PATH + "?lang=en-us#/map/2?shown_types=17,44,45,46,269";
    private final Handler main = new Handler(Looper.getMainLooper());
    private Handler worker;
    private WebView browser;
    private TextView status;
    private String adapter;
    private long session; // Owned by worker.
    private boolean initialized;
    private boolean busy;
    private boolean closed;
    private boolean resumed;
    private int page;
    private int operation;

    private final Runnable update = new Runnable() {
        @Override public void run() {
            if (!resumed || closed) { return; }
            if (initialized && !busy && focusedMap()) { poll(); }
            main.postDelayed(this, 1800);
        }
    };

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(NavigationPreferences.localized(base));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        CaptureService.setAccountPageVisible(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets padding = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(padding.left, padding.top, padding.right, padding.bottom);
                return WindowInsets.CONSUMED;
            }
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        status = new TextView(this);
        int margin = Math.round(12 * getResources().getDisplayMetrics().density);
        status.setPadding(margin, margin, margin, margin);
        status.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setText(R.string.sync_working);
        content.addView(status);
        browser = new WebView(this);
        content.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        Button back = new Button(this);
        back.setText(R.string.return_navigation);
        back.setAllCaps(false);
        back.setOnClickListener(view -> finish());
        content.addView(back, new LinearLayout.LayoutParams(-1, -2));
        setContentView(content);
        try (InputStream input = getAssets().open("hoyolab_site.js")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) { bytes.write(buffer, 0, count); }
            adapter = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            status.setText(R.string.sync_unavailable);
            return;
        }
        browser.getSettings().setJavaScriptEnabled(true);
        browser.getSettings().setDomStorageEnabled(true);
        browser.getSettings().setAllowFileAccess(false);
        browser.getSettings().setAllowContentAccess(false);
        browser.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        browser.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request.isForMainFrame() && official(request.getUrl().toString()) == null;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                ++page;
                if (official(url) == null) {
                    view.stopLoading();
                    status.setText(R.string.sync_unavailable);
                }
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                ++page;
                status.setText(R.string.sync_unavailable);
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                ++page;
                if (view.getParent() instanceof ViewGroup) { ((ViewGroup) view.getParent()).removeView(view); }
                view.destroy();
                browser = null;
                status.setText(R.string.sync_unavailable);
                return true;
            }
        });
        browser.loadUrl(MAP);
        browser.requestFocus();
        HandlerThread thread = new HandlerThread("HoYoLAB progress");
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(() -> {
            try {
                session = openSync(getFilesDir().getAbsolutePath());
                boolean ready = session != 0;
                main.post(() -> { if (!closed) { initialized = ready; } });
            } catch (RuntimeException | LinkageError error) {
                main.post(() -> { if (!closed) { status.setText(R.string.sync_unavailable); } });
            }
        });
    }

    private void poll() {
        busy = true;
        int current = ++operation;
        int originPage = page;
        worker.post(() -> {
            String request = syncRequest(session);
            main.post(() -> {
                if (closed || current != operation) { return; }
                if (page != originPage || !focusedMap()) { busy = false; return; }
                try {
                    if (request == null || new JSONObject(request).optInt("map") != 2) {
                        busy = false;
                        status.setText(R.string.sync_unavailable);
                        return;
                    }
                    // The desktop adapter performs the official map's normal marker action.
                    String expression = "(document.visibilityState === 'visible' && document.hasFocus()) ? ("
                            + adapter + ")(" + request + ") : ({state:'paused'})";
                    browser.evaluateJavascript(expression, result -> receive(current, originPage, result));
                } catch (JSONException | RuntimeException error) {
                    busy = false;
                    status.setText(R.string.sync_unavailable);
                }
            });
        });
    }

    private void receive(int current, int originPage, String result) {
        if (closed || current != operation) { return; }
        if (page != originPage || !focusedMap()) { busy = false; return; }
        if (result == null || result.length() > 262_144) { busy = false; status.setText(R.string.sync_unavailable); return; }
        worker.post(() -> {
            String response = syncResponse(session, result);
            main.post(() -> {
                if (closed || current != operation) { return; }
                busy = false;
                try {
                    JSONObject data = new JSONObject(response == null ? "{}" : response);
                    status.setText(statusText(data.optString("state")));
                } catch (JSONException | RuntimeException error) { status.setText(R.string.sync_unavailable); }
            });
        });
    }

    private static int statusText(String state) {
        switch (state) {
            case "login_required": return R.string.sync_login;
            case "ready": return R.string.sync_ready;
            case "ack": return R.string.sync_saved;
            case "pin_unavailable":
            case "wrong_pin":
            case "site_changed":
            case "not_acknowledged": return R.string.sync_pin_pending;
            case "loading":
            case "paused":
            case "pending":
            case "selecting":
            case "busy": return R.string.sync_working;
            default: return R.string.sync_unavailable;
        }
    }

    private static URI official(String address) {
        if (address == null) { return null; }
        try {
            URI url = new URI(address);
            if (!"https".equals(url.getScheme()) || url.getPort() != -1 || url.getRawUserInfo() != null) { return null; }
            String host = url.getHost();
            return "act.hoyolab.com".equals(host) || "www.hoyolab.com".equals(host) || "account.hoyoverse.com".equals(host) ? url : null;
        } catch (URISyntaxException error) { return null; }
    }

    private boolean focusedMap() {
        if (!resumed || browser == null || !browser.hasWindowFocus()) { return false; }
        URI url = official(browser.getUrl());
        if (url == null || !"act.hoyolab.com".equals(url.getHost()) || !PATH.equals(url.getRawPath())) { return false; }
        String route = url.getRawFragment();
        return route != null && (route.equals("/map/2") || route.startsWith("/map/2?") || route.startsWith("/map/2/"));
    }

    @Override protected void onStart() {
        super.onStart();
        CaptureService.setAccountPageVisible(true);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (browser != null) { browser.onResume(); }
        main.post(update);
    }

    @Override protected void onPause() {
        resumed = false;
        ++operation;
        busy = false;
        main.removeCallbacks(update);
        if (browser != null) { browser.onPause(); }
        super.onPause();
    }

    @Override protected void onStop() {
        CaptureService.setAccountPageVisible(false);
        super.onStop();
    }

    @Override protected void onDestroy() {
        closed = true;
        CaptureService.setAccountPageVisible(false);
        main.removeCallbacks(update);
        if (worker != null) {
            worker.post(() -> { if (session != 0) { closeSync(session); session = 0; } });
            worker.getLooper().quitSafely();
        }
        if (browser != null) { browser.destroy(); }
        super.onDestroy();
    }

    private static native long openSync(String directory);
    private static native String syncRequest(long session);
    private static native String syncResponse(long session, String response);
    private static native void closeSync(long session);
}
