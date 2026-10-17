package xin.inory.alive;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/**
 * 状态页的 SSE 客户端（/events）。
 *
 * 服务端实现（server.py）：
 *   - 数据有变化时  event: update   data: <和 /query 完全一样的 JSON>
 *   - 空闲每 30 秒  event: heartbeat data: <时间戳>
 *   - 已设置 X-Accel-Buffering: no，经 nginx 也不会被缓冲
 *
 * 所以只要保持这条长连接，通知就是**实时**的（服务端 1 秒轮询一次数据变化），
 * 不用等 app 自己那 30 秒的轮询间隔。
 */
final class SseClient implements AutoCloseable {

    private static final String TAG = "QixinAlive/SSE";

    interface Listener {
        /** 只把 update 事件的数据交给上层；heartbeat 只用来更新“还活着”的时间 */
        void onEvent(String event, String data);

        boolean isStopped();
    }

    private final String url;
    private volatile HttpURLConnection conn;
    private volatile boolean closed;

    SseClient(String url) {
        this.url = url;
    }

    /** 地址规则和抓取一致：没写路径就补 /events。 */
    static String eventsUrl(String entry) {
        String q = StatusRepository.queryUrl(entry);
        int idx = q.lastIndexOf('/');
        String base = (idx > q.indexOf("://") + 2) ? q.substring(0, idx) : q;
        return base + "/events";
    }

    /**
     * 阻塞式地读事件流，直到出错、被关闭、或 listener 说停。
     * 返回原因，方便上层决定要不要马上重连。
     */
    String stream(Listener listener) {
        String reason = "closed";
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            conn = c;
            c.setRequestMethod("GET");
            c.setConnectTimeout(10_000);
            // 0 = 不超时，SSE 是长连接。靠心跳 + 上层看门狗判断死没死
            c.setReadTimeout(0);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "text/event-stream");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            c.setRequestProperty("User-Agent", "QixinAlive/1.0 (Android)");
            c.setRequestProperty("Cache-Control", "no-cache");
            if (c instanceof HttpsURLConnection) {
                Tls.relax((HttpsURLConnection) c);
            }

            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                reason = "HTTP " + code;
                Log.w(TAG, url + " → " + reason);
                return reason;
            }
            Log.i(TAG, "connected: " + url);
            // 连上就先按一次“收到事件”处理，让上层立刻取一次当前状态
            listener.onEvent("open", null);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {

                String event = null;
                StringBuilder data = new StringBuilder();
                String line;
                final int maxEventBytes = 256 * 1024;

                while (!closed && !listener.isStopped() && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        // 空行 = 一个事件结束
                        if (event != null) {
                            listener.onEvent(event, data.toString());
                        }
                        event = null;
                        data.setLength(0);
                        continue;
                    }
                    if (line.startsWith(":")) continue; // 注释行
                    int colon = line.indexOf(':');
                    String field = colon < 0 ? line : line.substring(0, colon);
                    String value = colon < 0 ? "" : line.substring(colon + 1);
                    if (value.startsWith(" ")) value = value.substring(1);

                    if ("event".equals(field)) {
                        event = value;
                    } else if ("data".equals(field)) {
                        if (data.length() > 0) data.append('\n');
                        data.append(value);
                        if (data.length() > maxEventBytes) {
                            reason = "event too large";
                            break;
                        }
                    }
                }
                reason = closed || listener.isStopped() ? "closed" : "stream ended";
            }
        } catch (Throwable t) {
            reason = t.getClass().getSimpleName() + ": " + t.getMessage();
            if (!closed && !listener.isStopped()) {
                Log.w(TAG, "stream error: " + reason);
            }
        } finally {
            HttpURLConnection c = conn;
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
            conn = null;
        }
        return reason;
    }

    @Override
    public void close() {
        closed = true;
        HttpURLConnection c = conn;
        if (c != null) {
            try {
                c.disconnect(); // 让阻塞中的 readLine 抛异常退出
            } catch (Throwable ignored) {
            }
        }
    }
}
