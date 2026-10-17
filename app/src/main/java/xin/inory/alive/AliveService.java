package xin.inory.alive;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 常驻前台服务：把状态页的数据刷进常驻通知 + 桌面小组件。
 *
 * 第一行 = 手机 show_name + app_name + 电量
 * 第二行 = 电脑 show_name + app_name
 *
 * 两种取数方式：
 *   1. SSE（默认）—— 挂着 /events 长连接，服务端一有变化就推过来，**实时**
 *   2. 轮询（兜底）—— 每 N 秒 GET 一次 /query
 * SSE 连不上或断流时自动退回轮询，轮询期间也会不断尝试重连 SSE。
 *
 * ── 关于前台服务类型（踩过的坑）──
 * 之前用的是 dataSync，在 Android 15+ 上被系统的「每 24 小时约 6 小时」配额卡死：
 *   ForegroundServiceStartNotAllowedException:
 *           Time limit already exhausted for foreground service type dataSync
 * 额度一用完，startForeground() 就抛异常 → 服务创建失败 → START_STICKY 又把它拉起来
 * → 再抛 → 无限「屡次停止运行」。
 *
 * 现在改用 specialUse（没有时长配额），并且把 startForeground 包进 try/catch：
 * 万一还是被拒绝，就退化成「普通后台服务 + 手动发通知」，功能照跑，绝不崩。
 */
public class AliveService extends Service {

    private static final String TAG = "QixinAlive/Svc";

    public static final String ACTION_REFRESH = "xin.inory.alive.action.REFRESH";
    public static final String ACTION_STOP = "xin.inory.alive.action.STOP";

    /** SSE 多久没有任何事件就认为连接死了，重连 */
    private static final long SSE_SILENT_TIMEOUT_MS = 75_000;

    private static volatile List<String> lastLines = Collections.emptyList();
    private static volatile String lastStatus = "";
    private static volatile String lastUpdatedAt = "";
    private static volatile boolean sseLive = false;

    /** 真的当上前台服务了吗（没当上就靠手动通知兜着，功能照跑） */
    private static volatile boolean foregroundOk = false;

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qixin-alive-fetch");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = false;
    private volatile SseClient sse;

    public static List<String> lastLines() {
        return lastLines;
    }

    public static String lastStatus() {
        return lastStatus;
    }

    /** 当前是不是 SSE 长连接在推（给 UI 显示） */
    public static boolean sseLive() {
        return sseLive;
    }

    /** 这两行是什么时候算出来的（桌面小组件标题用） */
    public static String lastUpdatedAt() {
        return lastUpdatedAt;
    }

    /** 是不是正常的前台服务（false 表示被系统拒绝了，走的是降级模式） */
    public static boolean foregroundOk() {
        return foregroundOk;
    }

    static void updateLast(List<String> lines, String status) {
        lastLines = lines == null ? Collections.emptyList() : new ArrayList<>(lines);
        lastStatus = status == null ? "" : status;
        lastUpdatedAt = Time.nowStamp();
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, AliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            // 后台启动前台服务受限时，退回普通启动（服务内部自己再兜一次）
            Log.w(TAG, "startForegroundService refused, 退回 startService: " + t.getMessage());
            try {
                ctx.startService(i);
            } catch (Throwable t2) {
                Log.w(TAG, "startService 也不行: " + t2.getMessage());
            }
        }
    }

    public static void refresh(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, AliveService.class).setAction(ACTION_REFRESH));
        } catch (Throwable ignored) {
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, AliveService.class));
    }

    /**
     * Android 14 以下不认识 specialUse，按版本挑一个合法的类型，
     * 免得传了不支持的位导致 IllegalArgumentException。
     */
    private static int fgsType() {
        if (Build.VERSION.SDK_INT >= 34) return ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        }
        return 0;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notifier.ensureChannels(getSystemService(NotificationManager.class));
        String placeholder = getString(R.string.status_starting);
        List<String> two = new ArrayList<>(2);
        two.add(placeholder);
        two.add(placeholder);
        startForegroundCompat(two, placeholder);
        running = true;
        // 先立刻抓一次，别等 SSE 连上才有内容
        io.execute(() -> {
            tick(null);
            loop();
        });
        Log.i(TAG, "service created, foregroundOk=" + foregroundOk);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_REFRESH.equals(action)) {
            ensureRunning();
            io.execute(() -> tick(null));
        } else {
            ensureRunning();
        }
        return START_STICKY;
    }

    private void ensureRunning() {
        if (!running) {
            running = true;
            io.execute(this::loop);
        }
    }

    private long pollIntervalMs() {
        return Math.max(5, Prefs.intervalSec()) * 1000L;
    }

    // ------------------------------------------------------------ 主循环

    private void loop() {
        while (running) {
            if (Prefs.useSse()) {
                runSseOnce();
            } else {
                tick(null);
                sleepQuietly(pollIntervalMs());
            }
        }
    }

    /**
     * 挂着 SSE。只要这条连接活着，通知就是实时的；
     * 断了就先轮询一次补上，再重连（失败则按轮询间隔重试）。
     */
    private void runSseOnce() {
        String host = pickHost();
        if (host == null) {
            // 一个地址都没试通，先轮询兜底
            tick(null);
            sleepQuietly(pollIntervalMs());
            return;
        }

        final String eventsUrl = SseClient.eventsUrl(host);
        final long[] lastEventAt = {System.currentTimeMillis()};

        SseClient client = new SseClient(eventsUrl);
        sse = client;

        String reason = client.stream(new SseClient.Listener() {
            @Override
            public void onEvent(String event, String data) {
                lastEventAt[0] = System.currentTimeMillis();
                if ("open".equals(event)) {
                    sseLive = true;
                    Log.i(TAG, "SSE open: " + eventsUrl);
                    tick(null);
                    return;
                }
                if (!"update".equals(event)) {
                    return; // heartbeat 只是证明连接还活着
                }
                StatusSnapshot snap = StatusParser.parse(data, eventsUrl, Time.nowStamp());
                if (snap != null) {
                    apply(snap);
                } else {
                    // 推过来的东西解不了，退回自己的 /query
                    Log.w(TAG, "SSE update 解析失败，退回轮询");
                    tick(null);
                }
            }

            @Override
            public boolean isStopped() {
                if (!running) return true;
                // 看门狗：服务端每 30 秒心跳，超过 75 秒没动静就是死连接
                return System.currentTimeMillis() - lastEventAt[0] > SSE_SILENT_TIMEOUT_MS;
            }
        });

        sse = null;
        sseLive = false;
        client.close();
        if (!running) return;

        Log.i(TAG, "SSE 断开(" + reason + ")，轮询一次后重连");
        // 断流了，用轮询保证通知仍然是最新的
        tick(null);
        sleepQuietly(3_000L);
    }

    /** 挑一个上次成功的地址；没有就先抓一次让 StatusRepository 探出来。 */
    private String pickHost() {
        String preferred = Prefs.preferredHost();
        if (preferred != null) return preferred;
        StatusRepository.FetchReport report = StatusRepository.fetch(this, 8000);
        if (report.snapshot != null) {
            apply(report.snapshot);
        }
        return Prefs.preferredHost();
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            // 退出
        }
    }

    // ------------------------------------------------------------ 刷一次

    /** snapshot 为 null 时自己 GET /query。 */
    private void tick(StatusSnapshot snapshot) {
        if (!running) return;
        try {
            StatusSnapshot snap = snapshot;
            if (snap == null) {
                StatusRepository.FetchReport report = StatusRepository.fetch(this, 9000);
                snap = report.snapshot;
                if (snap == null) {
                    List<String> lines = lastLines.isEmpty()
                            ? Arrays.asList(LineBuilder.MISSING, LineBuilder.MISSING)
                            : lastLines;
                    String status = getString(R.string.status_failed, report.firstNote());
                    publish(lines, status);
                    WebViewHolder.requestProbe();
                    return;
                }
            }
            apply(snap);
        } catch (Throwable t) {
            Log.e(TAG, "tick failed", t);
        }
    }

    private void apply(StatusSnapshot snap) {
        List<String> lines = LineBuilder.build(snap);
        publish(lines, getString(R.string.status_ok, snap.fetchedAt));
        Log.i(TAG, "lines = " + lines.get(0) + " | " + lines.get(1));
    }

    /** 两行算好了：更新常驻通知 + 桌面小组件 + 页面里的预览条。 */
    private void publish(List<String> lines, String status) {
        updateLast(lines, status);
        Notifier.post(this, lines, status);
        AliveWidget.updateAll(this);
        WebViewHolder.pushLines(lines);
    }

    // ------------------------------------------------------------ 生命周期

    /**
     * 尝试成为前台服务。**这里就是之前崩溃的地方**：
     * dataSync 配额用尽时 startForeground() 抛 ForegroundServiceStartNotAllowedException
     * → 服务创建失败 → 系统按 START_STICKY 反复重启 → 无限「屡次停止运行」。
     *
     * 现在无论抛什么，都退化成「普通后台服务 + 手动发通知」，功能继续跑。
     */
    private void startForegroundCompat(List<String> lines, String status) {
        boolean ok = false;
        try {
            Notification n = Notifier.build(this, lines, status);
            int type = fgsType();
            if (type != 0) {
                startForeground(Notifier.ID_ONGOING, n, type);
            } else {
                startForeground(Notifier.ID_ONGOING, n);
            }
            ok = true;
        } catch (Throwable t) {
            Log.w(TAG, "startForeground 被系统拒绝，改用降级模式（通知照样发）: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        foregroundOk = ok;
        if (!ok) {
            // 没当成前台服务，但通知还得显示，只是少了前台优先级保护
            Notifier.post(this, lines, status);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // 从最近任务里划掉也让它继续常驻
        if (!running) return;
        try {
            Intent restart = new Intent(getApplicationContext(), AliveService.class);
            int flags = PendingIntent.FLAG_ONE_SHOT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getService(this, 1, restart, flags);
            AlarmManager am = getSystemService(AlarmManager.class);
            if (am != null) am.set(AlarmManager.RTC, System.currentTimeMillis() + 1000, pi);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        sseLive = false;
        SseClient c = sse;
        if (c != null) c.close();
        io.shutdownNow();
        Log.i(TAG, "service destroyed");
        super.onDestroy();
    }
}
