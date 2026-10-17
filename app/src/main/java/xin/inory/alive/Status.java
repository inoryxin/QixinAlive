package xin.inory.alive;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// ------------------------------------------------------------------ 数据模型

final class DeviceInfo {

    final String key;
    final String showName;
    final String appName;
    final boolean using;

    DeviceInfo(String key, String showName, String appName, boolean using) {
        this.key = key;
        this.showName = showName;
        this.appName = appName;
        this.using = using;
    }
}

final class StatusSnapshot {

    final LinkedHashMap<String, DeviceInfo> devices;
    final String infoName;
    final String infoDesc;
    final boolean privateMode;
    /** 服务端自己的截断长度（device_status_slice） */
    final int sliceLen;
    /** 这份快照是从哪个地址取到的 */
    final String sourceUrl;
    /** 手机上本次抓取的时间 */
    final String fetchedAt;

    StatusSnapshot(LinkedHashMap<String, DeviceInfo> devices, String infoName, String infoDesc,
                   boolean privateMode, int sliceLen, String sourceUrl, String fetchedAt) {
        this.devices = devices;
        this.infoName = infoName;
        this.infoDesc = infoDesc;
        this.privateMode = privateMode;
        this.sliceLen = sliceLen;
        this.sourceUrl = sourceUrl;
        this.fetchedAt = fetchedAt;
    }

    DeviceInfo device(String key) {
        if (key == null) return null;
        return devices.get(key.trim());
    }
}

// ------------------------------------------------------------------ 解析

final class StatusParser {

    private static final String TAG = "QixinAlive/Parse";

    private StatusParser() {
    }

    /**
     * /query 返回的是合法 JSON；为了容忍反代插进来的空白或说明文字，
     * 这里再做一次「扫描第一个括号平衡的 JSON 对象」。
     */
    static StatusSnapshot parse(String body, String sourceUrl, String fetchedAt) {
        if (body == null) return null;
        String text = body.trim();
        if (text.isEmpty()) return null;

        StatusSnapshot snap = tryParse(text, sourceUrl, fetchedAt);
        if (snap != null) return snap;

        String extracted = extractFirstJsonObject(text);
        if (extracted != null && !extracted.equals(text)) {
            snap = tryParse(extracted, sourceUrl, fetchedAt);
            if (snap != null) return snap;
        }

        Log.w(TAG, "no parseable status JSON from " + sourceUrl + " (len=" + text.length() + ")");
        return null;
    }

    private static StatusSnapshot tryParse(String text, String sourceUrl, String fetchedAt) {
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (Throwable t) {
            return null;
        }

        JSONObject deviceObj = root.optJSONObject("device");
        if (deviceObj == null) deviceObj = root.optJSONObject("device_status");
        // 1Panel 的安全入口页 / 404 页也可能被解析成 JSON，靠这些字段排除
        if (deviceObj == null && !root.has("status") && !root.has("time")) return null;

        LinkedHashMap<String, DeviceInfo> devices = new LinkedHashMap<>();
        if (deviceObj != null) {
            for (Iterator<String> it = deviceObj.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject d = deviceObj.optJSONObject(key);
                if (d == null) continue;
                devices.put(key, new DeviceInfo(
                        key,
                        d.optString("show_name", key),
                        d.optString("app_name", ""),
                        d.optBoolean("using", false)));
            }
        }

        JSONObject info = root.optJSONObject("info");
        return new StatusSnapshot(
                devices,
                info == null ? "" : info.optString("name", ""),
                info == null ? "" : info.optString("desc", ""),
                root.optBoolean("private_mode", false),
                root.optInt("device_status_slice", 0),
                sourceUrl,
                fetchedAt);
    }

    /** 从任意文本里抠出第一个括号平衡的 JSON 对象。 */
    static String extractFirstJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (esc) {
                esc = false;
            } else if (ch == '\\' && inStr) {
                esc = true;
            } else if (ch == '"') {
                inStr = !inStr;
            } else if (!inStr && ch == '{') {
                depth++;
            } else if (!inStr && ch == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return null;
    }
}

// ------------------------------------------------------------------ 组行

final class LineBuilder {

    static final String MISSING = "—";

    /** 应用名在通知里的最大长度，太长会把两行撑爆 */
    private static final int MAX_APP_LEN = 28;

    /** 手机那行把 app 和电量分开用的分隔符 */
    private static final char SEP = '·';

    private LineBuilder() {
    }

    /**
     * 第一行 = 手机 show_name + app_name + 电量（默认模板 "{name} {app}{batt_sep}{batt}"）
     * 第二行 = 电脑 show_name + app_name（默认模板 "{name} {app}"）
     *
     * 占位符：{name} {app} {batt} {batt_sep} {key}
     * {batt_sep} 只在电量存在时输出 " · "，否则为空。
     */
    static List<String> build(StatusSnapshot snap) {
        DeviceInfo phone = snap.device(Prefs.phoneDevice());
        DeviceInfo battery = snap.device(Prefs.batteryDevice());
        DeviceInfo pc = snap.device(Prefs.pcDevice());

        String batt = (battery == null || battery.appName == null) ? "" : battery.appName.trim();
        // 服务端给的是 100，通知两行得读得下，这里再收一下
        int slice = snap.sliceLen > 0 ? Math.min(snap.sliceLen, MAX_APP_LEN) : MAX_APP_LEN;

        List<String> out = new ArrayList<>(2);
        out.add(format(Prefs.line1Tpl(), phone, slice, batt));
        out.add(format(Prefs.line2Tpl(), pc, slice, ""));
        return out;
    }

    private static String format(String tpl, DeviceInfo device, int sliceLen, String battery) {
        String name = (device == null || device.showName == null) ? "" : device.showName.trim();
        String app = slice((device == null || device.appName == null) ? "" : device.appName.trim(), sliceLen);
        String batt = battery == null ? "" : battery.trim();

        String appShown = app.isEmpty() ? MISSING : app;

        String out = (tpl == null ? "" : tpl)
                .replace("{name}", name.isEmpty() ? MISSING : name)
                .replace("{key}", device == null ? MISSING : device.key)
                .replace("{app}", appShown)
                .replace("{batt}", batt.isEmpty() ? MISSING : batt)
                .replace("{batt_sep}", batt.isEmpty() ? "" : " " + SEP + " ");

        // 收尾：折叠多余空格，并砍掉空占位符留下的悬空分隔符。
        //   两行都缺数据时 "— ——" → "—"；不碰内容中间的短横线。
        String[] lines = out.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            lines[i] = cleanupLine(lines[i].replaceAll("[ \\t]{2,}", " ").trim());
        }
        return String.join("\n", lines).trim();
    }

    /** 反复砍掉结尾的「空格 + 分隔符/占位符」，但至少留下一个字符。 */
    private static String cleanupLine(String s) {
        String line = s == null ? "" : s.trim();
        while (line.length() > 1) {
            char last = line.charAt(line.length() - 1);
            if (last != SEP && last != MISSING.charAt(0)) break;
            line = line.substring(0, line.length() - 1).trim();
        }
        return line;
    }

    /** 应用名过长时按服务端的 sliceLen 截断，保持通知两行都读得下。 */
    private static String slice(String s, int len) {
        if (s == null) return "";
        return (len > 0 && s.length() > len) ? s.substring(0, len) + "…" : s;
    }
}

// ------------------------------------------------------------------ 抓取

final class StatusRepository {

    private static final String TAG = "QixinAlive/Fetch";

    static final class Attempt {
        final String url;
        final boolean ok;
        final String note;

        Attempt(String url, boolean ok, String note) {
            this.url = url;
            this.ok = ok;
            this.note = note;
        }
    }

    static final class FetchReport {
        final StatusSnapshot snapshot;
        final List<Attempt> attempts;

        FetchReport(StatusSnapshot snapshot, List<Attempt> attempts) {
            this.snapshot = snapshot;
            this.attempts = attempts;
        }

        String firstNote() {
            return attempts.isEmpty() ? "" : attempts.get(0).note;
        }
    }

    private StatusRepository() {
    }

    /**
     * 补全成可抓取的 URL。
     * 没写路径就补 /query；写了路径（例如 .../query）就原样用。
     */
    static String queryUrl(String entry) {
        String h = entry.trim();
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        String withScheme = (h.startsWith("http://") || h.startsWith("https://")) ? h : "https://" + h;
        String withoutScheme = withScheme.substring(withScheme.indexOf("://") + 3);
        boolean hasPath = withoutScheme.indexOf('/') >= 0;
        return hasPath ? withScheme : withScheme + "/query";
    }

    /** 页面地址（不带 /query）。 */
    static String baseUrl(String entry) {
        String q = queryUrl(entry);
        int idx = q.lastIndexOf('/');
        // 只截掉路径末端，别把 "https://" 里的斜杠也截了
        if (idx > q.indexOf("://") + 2) return q.substring(0, idx);
        return q;
    }

    /**
     * 按顺序尝试各地址。上次成功的那个会被提到最前面，
     * 这样 inory.xin:11026 打不通时不用每次都为它白等一次超时。
     */
    static FetchReport fetch(Context ctx, int timeoutMs) {
        List<String> configured = Prefs.hosts();
        String preferred = Prefs.preferredHost();

        List<String> ordered = new ArrayList<>();
        if (preferred != null && configured.contains(preferred)) ordered.add(preferred);
        for (String h : configured) if (!h.equals(preferred)) ordered.add(h);

        List<Attempt> attempts = new ArrayList<>();
        String now = Time.nowStamp();

        for (String host : ordered) {
            String url = queryUrl(host);
            HttpResult res = Http.get(url, timeoutMs);
            if (!res.ok) {
                attempts.add(new Attempt(url, false, res.shortError()));
                continue;
            }
            StatusSnapshot snap = StatusParser.parse(res.body, url, now);
            if (snap == null) {
                boolean looksLikePanel = res.body.contains("1pctl") || res.body.contains("安全入口");
                String note = looksLikePanel
                        ? "不是状态页（1Panel 安全入口）"
                        : "响应不是状态 JSON (" + res.body.length() + "B)";
                attempts.add(new Attempt(url, false, note));
                Log.w(TAG, url + " → " + note);
                continue;
            }
            attempts.add(new Attempt(url, true, "OK (" + snap.devices.size() + " 设备)"));
            Prefs.setPreferredHost(host);
            Log.i(TAG, url + " → OK, " + snap.devices.size() + " devices, updated=" + snap.fetchedAt);
            return new FetchReport(snap, attempts);
        }

        StringBuilder sb = new StringBuilder();
        for (Attempt a : attempts) sb.append(a.url).append('=').append(a.note).append("; ");
        Log.w(TAG, "all hosts failed: " + sb);
        return new FetchReport(null, attempts);
    }
}

final class Time {

    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);

    private Time() {
    }

    static synchronized String nowStamp() {
        return FMT.format(new Date());
    }
}
