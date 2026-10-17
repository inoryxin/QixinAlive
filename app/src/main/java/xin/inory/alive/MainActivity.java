package xin.inory.alive;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 整个 app 的界面就是一个 WebView，没有任何工具栏和设置入口。
 *
 * 两件事：
 *   1. 显示固定网页（状态页）
 *   2. 页面里的探针把两行内容回传，交给常驻通知
 *
 * 所有地址、设备 key、模板都写死在 Prefs 里，别人改不了。
 */
public class MainActivity extends Activity {

    private static final String TAG = "QixinAlive/UI";
    private static final int REQ_NOTIF = 7001;

    private WebView webView;
    private String currentBaseUrl = "";

    static void applyLinesFromPage(android.content.Context ctx, List<String> lines) {
        if (lines == null || lines.size() < 2) return;
        String status = AliveService.lastStatus();
        if (status == null || status.isEmpty()) status = ctx.getString(R.string.status_from_page);
        AliveService.updateLast(lines, status);
        Notifier.post(ctx, lines, status);
        AliveWidget.updateAll(ctx);
        Log.i(TAG, "from page: " + lines.get(0) + " | " + lines.get(1));
    }

    /** 从 WebView 传回来的 ["第一行","第二行"] 里取两行 */
    static List<String> parseLinesJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JSONArray arr = new JSONArray(raw);
            if (arr.length() < 2) return null;
            List<String> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i, ""));
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ 生命周期

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        currentBaseUrl = StatusRepository.baseUrl(Prefs.hosts().get(0));

        setupSystemBars();
        setupWebView();
        webView.addJavascriptInterface(new Bridge(), "Android");

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(currentBaseUrl);
        }

        ensureNotificationPermission();
        AliveService.start(this);
        requestWidgetPinIfNeeded();
    }

    /**
     * 主动发起「把小组件固定到桌面」的系统请求。
     *
     * 为什么要这么做：各家桌面的「长按图标 → 添加小部件」不是 Android 的强制要求，
     * 小米 HyperOS 上就没有（长按图标只有应用信息/卸载等）。而
     * {@link AppWidgetManager#requestPinAppWidget} 是**标准 API**，
     * 桌面对它的支持由框架保证，会在桌面上弹一个「要添加吗」的确认框，
     * 完全绕开长按菜单那条路。
     *
     * 只在「桌面上还没有本组件」时请求，所以如果用户不想要、点了取消，
     * 就不会每次打开都烦人；哪天删掉了再打开 app 还能重新加回来。
     */
    private void requestWidgetPinIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(this);
            ComponentName provider = new ComponentName(this, AliveWidget.class);

            if (!mgr.isRequestPinAppWidgetSupported()) {
                Log.i(TAG, "桌面不支持 requestPinAppWidget（只能走桌面自己的添加入口）");
                return;
            }
            if (mgr.getAppWidgetIds(provider).length > 0) {
                Log.i(TAG, "桌面上已经有本组件，不重复请求");
                return;
            }

            // 等界面起来一点再弹，免得和启动动画打架
            webView.postDelayed(() -> {
                try {
                    boolean asked = mgr.requestPinAppWidget(provider, null, null);
                    Log.i(TAG, "requestPinAppWidget -> " + asked);
                } catch (Throwable t) {
                    Log.w(TAG, "requestPinAppWidget failed: " + t.getMessage());
                }
            }, 1200);
        } catch (Throwable t) {
            Log.w(TAG, "requestWidgetPinIfNeeded failed: " + t.getMessage());
        }
    }

    /**
     * 让网页避开状态栏和导航栏。
     *
     * 不依赖「系统是否边到边」这个前提：Android 15（targetSdk 35）会强制边到边，
     * 低版本不会。这里直接读 WindowInsets 把系统栏的高度做成根布局的内边距，
     * 两种情况都正好留出空间。
     */
    private void setupSystemBars() {
        // 两栏配色跟页面一致，避免出现突兀色块
        getWindow().setStatusBarColor(Color.parseColor("#FFD9E8"));
        getWindow().setNavigationBarColor(Color.parseColor("#FFF5FA"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        // 刘海区域只用「窄边」模式，内容不会顶进刘海
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        }

        final View root = findViewById(R.id.root);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：直接从 insets 拿系统栏尺寸
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars =
                        insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        } else {
            // Android 8~10：本来就不是边到边，交给布局的 fitsSystemWindows 即可
            root.setFitsSystemWindows(true);
        }
        root.requestApplyInsets();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        webView.setBackgroundColor(0xFFFFF5FA);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request == null || request.getUrl() == null
                        ? null : request.getUrl().toString();
                if (url == null) return false;
                String host = Uri.parse(url).getHost();
                String baseHost = Uri.parse(currentBaseUrl).getHost();
                // 站内跳转留在 WebView，站外跳系统浏览器
                if (host != null && baseHost != null && !host.equals(baseHost)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectProbe();
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // 内网自签证书 / 用 IP 访问导致证书主机名不匹配，直接放行（自用 app）
                Log.w(TAG, "SSL error, proceed: " + (error == null ? "?" : error.getPrimaryError()));
                if (handler != null) handler.proceed();
            }
        });

        WebViewHolder.attach(webView);
    }

    /** 往页面里注入预览条样式 + 探针脚本。 */
    private void injectProbe() {
        String js = "(function(){try{\n"
                + "var st=document.getElementById('qx-probe-style');\n"
                + "if(!st){st=document.createElement('style');st.id='qx-probe-style';\n"
                + "st.textContent=" + JSONObject.quote(ProbeScript.BOX_CSS) + ";\n"
                + "(document.head||document.documentElement).appendChild(st);}\n"
                + "}catch(e){}\n"
                + ProbeScript.build()
                + "\n})()";
        try {
            webView.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w(TAG, "injectProbe failed: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------ 权限

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            webView.postDelayed(this::askBatteryExemption, 2500);
        }
    }

    /** 只提示一次，让常驻通知不被系统后台限制掐掉。 */
    private void askBatteryExemption() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.batt_title)
                .setMessage(R.string.batt_msg)
                .setPositiveButton(R.string.batt_go, (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:" + getPackageName())));
                    } catch (Throwable t) {
                        try {
                            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        } catch (Throwable ignored) {
                        }
                    }
                })
                .setNegativeButton(R.string.later, null)
                .show();
    }

    // ------------------------------------------------------------ JS 桥

    private class Bridge {
        /** 页面探针算好的两行，直接进常驻通知 */
        @JavascriptInterface
        public void setLines(String json) {
            final List<String> lines = parseLinesJson(json);
            if (lines == null) return;
            runOnUiThread(() -> applyLinesFromPage(MainActivity.this, lines));
        }

        @JavascriptInterface
        public void onProbe(String title, boolean ok) {
            Log.i(TAG, "probe title=" + title + " ok=" + ok);
        }
    }

    // ------------------------------------------------------------ 收尾

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) WebViewHolder.attach(webView);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            WebViewHolder.detach(webView);
            try {
                webView.removeJavascriptInterface("Android");
                ViewGroup parent = (ViewGroup) webView.getParent();
                if (parent != null) parent.removeView(webView);
                webView.destroy();
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }
}
