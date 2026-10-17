package xin.inory.alive;

/**
 * 固定配置（写死在代码里，app 里不再提供任何设置界面，别人改不了）。
 *
 * 通知两行：
 *   第一行 = 手机的 show_name + app_name + 电量
 *   第二行 = 电脑的 show_name + app_name
 *
 * 取数地址按顺序探测：
 *   1. https://inory.xin:11026   —— 外网地址
 *   2. https://10.0.0.123:9010   —— 内网（WiFi 下才可达）
 * 哪个能拿到状态 JSON 就用哪个；不行的自动跳过。
 *
 * 另外仍保留一项「运行时状态」：preferredHost（上次成功的地址），
 * 只存在内存里、不落盘，用来避免每次都为不可达的地址白等一次超时。
 */
public final class Prefs {

    private Prefs() {
    }

    // ------------------------------------------------------------------ 常量

    /** 地址候选（逗号分隔），每项可以带路径 */
    public static final String HOSTS = "https://inory.xin:11026,https://10.0.0.123:9010";

    /** 手机（第一行的设备名来源） */
    public static final String PHONE_DEVICE = "zfold6";

    /** 电量（第一行的电量来源，页面上是独立一台设备） */
    public static final String BATTERY_DEVICE = "zfold6dl";

    /** 电脑（第二行） */
    public static final String PC_DEVICE = "desktop";

    /**
     * 第一行模板：{name} {app}{batt_sep}{batt}
     *   {name}=祈歆の📱  {app}=息屏中/bilibili  {batt}=15%
     *   {batt_sep} 在没有电量数据时变成空串，不留多余分隔符
     */
    public static final String LINE1_TPL = "{name} {app}{batt_sep}{batt}";

    /** 第二行模板：{name} {app} */
    public static final String LINE2_TPL = "{name} {app}";

    /** 是否用 SSE 实时推送；false 则退回定时轮询 */
    public static final boolean USE_SSE = true;

    /** 轮询间隔（秒），仅 SSE 关闭或断线重连时用 */
    public static final int INTERVAL_SEC = 30;

    // ------------------------------------------------------------------ 读取

    public static java.util.List<String> hosts() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : HOSTS.split("[,;\\n]")) {
            String t = part.trim();
            while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public static String phoneDevice() {
        return PHONE_DEVICE;
    }

    public static String batteryDevice() {
        return BATTERY_DEVICE;
    }

    public static String pcDevice() {
        return PC_DEVICE;
    }

    public static String line1Tpl() {
        return LINE1_TPL;
    }

    public static String line2Tpl() {
        return LINE2_TPL;
    }

    public static boolean useSse() {
        return USE_SSE;
    }

    public static int intervalSec() {
        return INTERVAL_SEC;
    }

    // ------------------------------------------------- 运行时状态（不落盘）

    private static volatile String preferredHost;

    /** 上次成功的地址，下次优先用它。 */
    public static String preferredHost() {
        return preferredHost;
    }

    public static void setPreferredHost(String host) {
        preferredHost = host;
    }
}
