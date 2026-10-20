package com.local.cloneui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String PAGE = "file:///android_asset/ui.html";
    // App icons for the page: <img src="https://cloneui.icon/<package>">.
    // Answered from shouldInterceptRequest; nothing goes to the network.
    private static final String ICON_HOST = "cloneui.icon";
    private static final int ICON_PX = 144;
    // Page background (ui.html --bg), so the system bars match the page.
    private static final int BG_LIGHT = 0xfff3f4f8;
    private static final int BG_DARK = 0xff0b0b0d;

    private RootShell root;
    private WebView web;
    // One request at a time, in order; root calls must not run on the
    // page's thread or a two-minute "create" freezes the page.
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, byte[]> icons = new ConcurrentHashMap<>();
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int bg = night ? BG_DARK : BG_LIGHT;
        getWindow().setBackgroundDrawable(new ColorDrawable(bg));

        root = new RootShell(getApplicationInfo().sourceDir);
        web = new WebView(this);
        web.setBackgroundColor(bg);
        web.setFitsSystemWindows(true);
        // The bridge reaches a root process: this WebView shows the bundled
        // page and nothing else.
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !PAGE.equals(request.getUrl().toString());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (!"https".equals(url.getScheme()) || !ICON_HOST.equals(url.getHost())) {
                    return null;
                }
                byte[] png = icon(url.getLastPathSegment());
                if (png == null) {
                    return new WebResourceResponse("image/png", null, 404, "Not Found",
                            Collections.<String, String>emptyMap(), new ByteArrayInputStream(new byte[0]));
                }
                return new WebResourceResponse("image/png", null, 200, "OK",
                        Collections.singletonMap("Cache-Control", "max-age=86400"),
                        new ByteArrayInputStream(png));
            }
        });
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowContentAccess(false);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.addJavascriptInterface(new Bridge(), "CloneUI");
        FrameLayout frame = new FrameLayout(this);
        frame.setFitsSystemWindows(true);
        frame.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            bars.setSystemBarsAppearance(night ? 0 : light, light);
        }
        web.loadUrl(PAGE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Icons may have been moved or renamed on the desktop meanwhile.
        web.evaluateJavascript("window.onPageResume && window.onPageResume()", null);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // The page closes its own dialog or picker first.
        web.evaluateJavascript("window.onBack ? window.onBack() : false", handled -> {
            if (!"true".equals(handled)) {
                super.onBackPressed();
            }
        });
    }

    @Override
    protected void onDestroy() {
        // Closing its stdin ends the root process. Queue it behind a running
        // request instead of waiting here: that request (say a create) then
        // finishes in the background.
        destroyed = true;
        worker.execute(root::stop);
        worker.shutdown();
        web.destroy();
        super.onDestroy();
    }

    /** PNG of the app's icon as the main user sees it, or null. */
    private byte[] icon(String pkg) {
        if (pkg == null) {
            return null;
        }
        byte[] cached = icons.get(pkg);
        if (cached != null) {
            return cached;
        }
        try {
            Drawable d = getPackageManager().getApplicationIcon(pkg);
            Bitmap bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, ICON_PX, ICON_PX);
            d.draw(new Canvas(bitmap));
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
            bitmap.recycle();
            byte[] bytes = png.toByteArray();
            icons.put(pkg, bytes);
            return bytes;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    public class Bridge {
        /** Send one request; the reply comes back as window.__reply(id, json). */
        @JavascriptInterface
        public void post(final int id, final String line) {
            try {
                worker.execute(() -> {
                    String reply = root.call(line);
                    String js = "window.__reply(" + id + "," + JSONObject.quote(reply) + ")";
                    web.post(() -> {
                        if (!destroyed) {
                            web.evaluateJavascript(js, null);
                        }
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // The page is closing.
            }
        }

        @JavascriptInterface
        public String labels() {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            try {
                PackageManager pm = getPackageManager();
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> infos = pm.queryIntentActivities(intent, 0);
                Map<String, String> seen = new LinkedHashMap<String, String>();
                if (infos != null) {
                    for (ResolveInfo info : infos) {
                        if (info == null || info.activityInfo == null) {
                            continue;
                        }
                        String pkg = info.activityInfo.packageName;
                        if (pkg == null || seen.containsKey(pkg)) {
                            continue;
                        }
                        CharSequence label = info.loadLabel(pm);
                        String text = label == null ? pkg : label.toString();
                        seen.put(pkg, text);
                    }
                }
                boolean first = true;
                for (Map.Entry<String, String> entry : seen.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append('"').append(escape(entry.getKey())).append('"');
                    sb.append(':');
                    sb.append('"').append(escape(entry.getValue())).append('"');
                }
            } catch (Exception ex) {
                return "{}";
            }
            sb.append('}');
            return sb.toString();
        }

        private String escape(String value) {
            return value
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", " ")
                    .replace("\r", " ");
        }
    }
}
