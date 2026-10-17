package xin.inory.alive;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.widget.RemoteViews;

import java.util.List;

/**
 * 桌面小组件（2x4）：显示和常驻通知一模一样的「两行」。
 *
 *   第一行 = 手机 show_name + app_name + 电量
 *   第二行 = 电脑 show_name + app_name
 *
 * 数据不自己抓，直接用 AliveService 已经算好的那份（它走 SSE 实时推送），
 * 所以组件和通知永远同步，也不会多耗一分电。
 *
 * 交互：整块点一下打开 app，右上角 ↻ 立刻刷新一次。
 */
public class AliveWidget extends AppWidgetProvider {

    private static final String TAG = "QixinAlive/Widget";

    /** 组件里「刷新」按钮的广播 */
    private static final String ACTION_REFRESH = "xin.inory.alive.widget.REFRESH";

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        render(context, mgr, ids);
        // 服务没在跑就拉起来（正在使用时被添加，前台服务可以正常启动）
        try {
            AliveService.start(context);
        } catch (Throwable t) {
            Log.w(TAG, "start service failed: " + t.getMessage());
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) {
            try {
                AliveService.refresh(context);
            } catch (Throwable ignored) {
            }
            return;
        }
        if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(intent.getAction())) {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
            if (ids == null) {
                ids = idsOf(context, mgr);
            }
            render(context, mgr, ids);
        }
    }

    /** 所有实例一起重画（服务刷新后调用）。 */
    static void updateAll(Context context) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = idsOf(context, mgr);
            if (ids.length == 0) return;
            render(context, mgr, ids);
        } catch (Throwable t) {
            Log.w(TAG, "updateAll failed: " + t.getMessage());
        }
    }

    private static int[] idsOf(Context context, AppWidgetManager mgr) {
        try {
            return mgr.getAppWidgetIds(new ComponentName(context, AliveWidget.class));
        } catch (Throwable t) {
            return new int[0];
        }
    }

    private static void render(Context context, AppWidgetManager mgr, int[] ids) {
        if (ids == null || ids.length == 0) return;

        // 直接用服务已经算好的那份数据；服务还没跑过就先显示「等待数据」
        List<String> lines = AliveService.lastLines();
        String title = AliveService.lastUpdatedAt();
        if (title == null || title.isEmpty()) title = context.getString(R.string.widget_waiting);

        String l1 = LineBuilder.MISSING;
        String l2 = LineBuilder.MISSING;
        if (lines.size() >= 2) {
            l1 = lines.get(0);
            l2 = lines.get(1);
        }

        for (int id : ids) {
            RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_alive);
            rv.setTextViewText(R.id.widget_title, title);
            rv.setTextViewText(R.id.widget_line1, l1);
            rv.setTextViewText(R.id.widget_line2, l2);

            // 点整块 → 打开 app
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            rv.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                    context, 0, new Intent(context, MainActivity.class), flags));

            // 点 ↻ → 让服务立刻抓一次
            Intent refresh = new Intent(context, AliveWidget.class).setAction(ACTION_REFRESH);
            rv.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(
                    context, 1, refresh, flags));

            try {
                mgr.updateAppWidget(id, rv);
            } catch (Throwable t) {
                Log.w(TAG, "updateAppWidget failed: " + t.getMessage());
            }
        }
        Log.i(TAG, "rendered " + ids.length + " widget(s): " + l1 + " | " + l2);
    }

    /** 给日志/调试用：当前有几个实例。 */
    static int count(Context context) {
        return idsOf(context, AppWidgetManager.getInstance(context)).length;
    }
}
