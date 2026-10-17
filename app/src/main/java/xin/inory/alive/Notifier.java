package xin.inory.alive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 常驻通知。通知正文用 "\n" 分成两行：
 *   第一行 = 手机 show_name + app_name + 电量
 *   第二行 = 电脑 show_name + app_name
 * Android 会把 \n 当换行渲染，所以两行都会显示出来。
 */
final class Notifier {

    static final String CHANNEL_ONGOING = "qixin_alive_status";
    static final String CHANNEL_ALERT = "qixin_alive_alert";
    static final int ID_ONGOING = 1001;

    private static final String TAG = "QixinAlive/Notify";

    private Notifier() {
    }

    static void ensureChannels(NotificationManager nm) {
        if (nm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel ongoing = new NotificationChannel(
                CHANNEL_ONGOING, "状态常驻通知", NotificationManager.IMPORTANCE_LOW);
        ongoing.setDescription("常驻显示祈歆的手机电量与电脑当前应用");
        ongoing.setShowBadge(false);
        ongoing.enableLights(false);
        ongoing.enableVibration(false);
        ongoing.setSound(null, null);
        nm.createNotificationChannel(ongoing);

        NotificationChannel alert = new NotificationChannel(
                CHANNEL_ALERT, "状态提醒", NotificationManager.IMPORTANCE_DEFAULT);
        alert.setDescription("抓取失败等提醒（低频）");
        alert.setShowBadge(false);
        nm.createNotificationChannel(alert);
    }

    static PendingIntent contentIntent(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(ctx, 0, i, flags);
    }

    static Notification build(Context ctx, List<String> lines, String status) {
        String first = (lines.isEmpty() || lines.get(0) == null) ? status : lines.get(0);
        StringBuilder body = new StringBuilder(status == null ? "" : status);
        for (String l : lines) body.append('\n').append(l == null ? "" : l);

        Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ONGOING)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.app_name))
                // 折叠态显示第一行；展开态用 bigText 显示完整的 status + 两行
                .setContentText(first)
                .setStyle(new Notification.BigTextStyle().bigText(body.toString()))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(contentIntent(ctx));
        return b.build();
    }

    /** 刷新常驻通知（服务可能在后台线程调用）。 */
    static void post(Context ctx, List<String> lines, String status) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        try {
            nm.notify(ID_ONGOING, build(ctx, lines, status));
        } catch (Throwable t) {
            Log.w(TAG, "notify failed: " + t.getMessage());
        }
    }
}

/**
 * 页面自身也会请求 /query，所以页面里有同一份数据。
 * 这里存一个弱引用：服务抓不到时可以退回用页面里的那份。
 */
final class WebViewHolder {

    private static WeakReference<WebView> ref;

    private WebViewHolder() {
    }

    static synchronized void attach(WebView wv) {
        ref = new WeakReference<>(wv);
    }

    static synchronized void detach(WebView wv) {
        WebView cur = ref == null ? null : ref.get();
        if (cur == wv) ref = null;
    }

    static synchronized WebView alive() {
        return ref == null ? null : ref.get();
    }

    /** 让页面里的 JS 自己重新算一次两行并回传（服务抓取失败时的兜底）。 */
    static void requestProbe() {
        final WebView wv = alive();
        if (wv == null) return;
        wv.post(() -> {
            try {
                wv.evaluateJavascript("window.__qxProbe && window.__qxProbe()", null);
            } catch (Throwable ignored) {
            }
        });
    }

    /** 把服务算好的两行推给页面里的预览条。 */
    static void pushLines(final List<String> lines) {
        final WebView wv = alive();
        if (wv == null) return;
        // 参数用 JSON 字符串字面量，避免内容里的引号破坏 JS 语法
        final String payload = JSONObject.quote(new JSONArray(lines).toString());
        wv.post(() -> {
            try {
                wv.evaluateJavascript("window.__qxSetLines && window.__qxSetLines(" + payload + ")", null);
            } catch (Throwable ignored) {
            }
        });
    }
}
