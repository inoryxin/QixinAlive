# 祈歆 Alive

干三件事的安卓 app：

1. 用系统 **WebView** 显示固定网页（祈歆的状态页）—— **全屏，没有任何工具栏**
2. 把网页内容提取出来，放进**常驻通知**，只显示两行
3. 同样两行放一个 **4x2 桌面小组件**

```
通知 / 小组件：
第一行：手机 show_name + app_name + 电量      →  祈歆の📱 祈歆 Alive · 10%
第二行：电脑 show_name + app_name             →  祈歆的💻 League of Legends (TM) Client
```

数据走状态页的 **SSE 长连接实时推送**（不是轮询）。界面里**没有任何设置入口**，
所有参数都写死在 `Prefs.java` 里，别人拿不到也改不了。

包名 `xin.inory.alive`，minSdk 26（Android 8.0），targetSdk 35。

---

## 数据从哪来

状态页是 1Panel 机器上的 sleepy 应用（Flask），页面标题 **「祈歆 Alive?」**。

| 用途 | 地址 |
|---|---|
| 页面（WebView） | `https://10.0.0.123:9010/` |
| 数据（通知） | `https://10.0.0.123:9010/query` |

`/query` 返回的 JSON 长这样（2026-10-09 实测）：

```json
{
  "status": 0,
  "device_status_slice": 100,
  "private_mode": false,
  "device": {
    "zfold6":   { "show_name": "祈歆の📱",  "app_name": "bilibili",  "using": true },
    "zfold6dl": { "show_name": "📱のPower", "app_name": "14%",       "using": true },
    "desktop":  { "show_name": "祈歆的💻",  "app_name": "League of Legends (TM) Client", "using": true },
    "home":     { "show_name": "祈歆🏠の🌡", "app_name": "26℃",      "using": true },
    "light":    { "show_name": "祈歆🏠の💡", "app_name": "开",        "using": true }
  }
}
```

**两行是这样拼出来的：**

- 第一行 = `zfold6.show_name` + `zfold6.app_name` + `zfold6dl.app_name`（电量）
  → `祈歆の📱` + `bilibili` + `14%`
- 第二行 = `desktop.show_name` + `desktop.app_name`
  → `祈歆的💻` + `League of Legends (TM) Client`

> 电量在页面里是**独立的一台设备** `zfold6dl`（`show_name = 📱のPower`），
> 所以第一行是把 `zfold6` 和 `zfold6dl` 两台合起来。设备 key 写死在 `Prefs.java`。

---

## 关于 `inory.xin:11026`

实测（从局域网内 SSH 到 1Panel 机器上确认）：

```
ss -lntp | grep 11026
LISTEN 0 4096 0.0.0.0:11026 0.0.0.0:*  users:(("1panel-core",pid=2947814,fd=12))
```

**`11026` 是 1Panel 自己的管理面板端口**，任何路径都返回它的「暂时无法访问 / 当前环境已经开启了安全入口登录」页面：

```
GET https://127.0.0.1:11026/        → 1410 B HTML（1Panel 安全入口）
GET https://127.0.0.1:11026/query   → 1410 B HTML（同上，不是状态 JSON）
```

所以 app 按你要求的顺序探测，但会**自动跳过**它：

```
[跳过] https://inory.xin:11026/query  →   SocketTimeoutException: Connect timed out
[OK]   https://10.0.0.123:9010/query  →   OK (5 设备)
```

判定逻辑写在 `StatusRepository.fetch()`：拿不到带 `device` / `status` / `time` 字段的 JSON 就
换下一个地址，并把**上次成功**的地址记下来优先使用，避免每次都为不可达的地址白等一次超时。

如果 11026 的端口映射其实指向别的服务，改 `Prefs.java` 里的 `HOSTS` 常量即可。

---

## 打开 app 后会发生什么

1. 请求通知权限（Android 13+）和「忽略电池优化」（保常驻）
2. 启动 `AliveService` 前台服务 → 立刻常驻通知，内容形如：

   ```
   祈歆 Alive
   已更新于 10-09 21:26:05
   祈歆の📱 祈歆 Alive · 10%
   祈歆的💻 安卓 WebView 通知应用 — DSH 本地构建 —…
   ```

3. **界面就是一个全屏网页**，没有任何工具栏 / 状态行 / 设置入口
4. 之后数据变化会**实时**推过来（见下节），不用等轮询

### 页面与系统栏

网页**不会**被状态栏和导航栏挡住。做法不是依赖「系统是否边到边」这个会变的前提，
而是在 `MainActivity.setupSystemBars()` 里直接读 `WindowInsets`，把系统栏和刘海的高度
做成根布局的内边距：

```java
insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
```

这样 Android 15（targetSdk 35，强制边到边）和低版本（不边到边）两种行为下结果一致。
Android 11 以下没有 `WindowInsets.Type`，退回布局的 `fitsSystemWindows`。
另外 `layoutInDisplayCutoutMode = NEVER`，内容不会顶进刘海。

---

## 实时推送（SSE）

**能，而且已经是实时了。** 状态页的 `/events` 就是标准 SSE，服务端实现（`server.py`）：

```python
@app.route('/events')
def events():
    def event_stream():
        last_update = None
        last_heartbeat = time.time()
        while True:
            current_time = time.time()
            current_update = d.data['last_updated']
            if last_update != current_update:
                last_update = current_update
                last_heartbeat = current_time
                ret = query(ret_as_dict=True)
                yield f"event: update\ndata: {json5.dumps(ret, quote_keys=True)}\n\n"
            elif current_time - last_heartbeat >= 30:
                yield f"event: heartbeat\ndata: {...}\n\n"
            time.sleep(1)          # 每秒检查一次数据变化
    response = flask.Response(event_stream(), mimetype="text/event-stream")
    response.headers["X-Accel-Buffering"] = "no"   # 经 nginx 也不缓冲
    return response
```

几个关键点：

- `update` 事件推的 data **就是一份完整的 `/query` 响应**，所以直接用同一个
  `StatusParser` 解析即可，不用写两套
- 服务端每 1 秒检查一次 `last_updated`，所以是**秒级实时**
- 空闲时每 30 秒发一次 `heartbeat`，正好可以当"连接还活着"的信号
- 已设 `X-Accel-Buffering: no`，走 openresty 反代也不会被缓冲住

所以 `AliveService` 现在是这个结构：

```
默认：挂着 /events 长连接
        ├─ update    → 直接解析 → 立刻刷通知（秒级）
        └─ heartbeat → 只用来喂看门狗
断线/连不上：先 GET /query 补一次，然后每 3 秒重连
看门狗：75 秒没收到任何事件 → 认为连接死了 → 重连
```

实测（真连 25 秒）：

```
eventsUrl = https://10.0.0.123:9010/events
[I] QixinAlive/SSE: connected: https://10.0.0.123:9010/events
[   176ms] 已连接，等推送…
[   181ms] update 累计=1  →  ①祈歆の📱 祈歆 Alive · 12%   ②祈歆的💻 安卓 WebView 通知应用 — DSH 本地构建 —…
结论: SSE 可用，update 能直接解析成两行
```

`USE_SSE` 常量（`Prefs.java`）改成 `false` 就回到定时轮询 `INTERVAL_SEC`（默认 30 秒）。
**注意：SSE 是长连接，比轮询省电**（一次连接 vs 每 30 秒一次请求），也更快。

---

## 保活说明

- 前台服务 `foregroundServiceType="specialUse"` + 常驻通知（`IMPORTANCE_LOW`，不响不震）
- `START_STICKY` + `onTaskRemoved` 里用 AlarmManager 拉活 → 从最近任务划掉也会继续跑
- `BootReceiver` → 开机 / 应用更新后自动启动
- 建议在系统设置里给本应用关掉电池优化（app 首次启动会主动提示一次）

### ⚠️ 为什么前台服务类型是 specialUse 而不是 dataSync

这是真机上炸出来的坑。原先用 `dataSync`，跑了一天多之后开始**无限弹「屡次停止运行」**：

```
FATAL EXCEPTION: main
java.lang.RuntimeException: Unable to create service xin.inory.alive.AliveService
Caused by: android.app.ForegroundServiceStartNotAllowedException:
        Time limit already exhausted for foreground service type dataSync
    at xin.inory.alive.AliveService.startForegroundCompat(AliveService.java:287)
    at xin.inory.alive.AliveService.onCreate(AliveService.java:114)
```

原因：**Android 15+ 对 `dataSync` 前台服务有「每 24 小时累计约 6 小时」的运行时长配额**。
额度用尽后 `startForeground()` 直接抛异常 → 服务创建失败 → `START_STICKY` 又把它拉起来
→ 再抛 → 死循环，于是系统反复弹崩溃框。

两处修改：

1. **换成 `specialUse`**（没有这个时长配额）：

   ```xml
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
   <service android:name=".AliveService" android:foregroundServiceType="specialUse">
       <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                 android:value="常驻显示个人状态页的两行信息……" />
   </service>
   ```

   Android 14 以下不认 `specialUse`，所以 `fgsType()` 按版本挑合法值，避免 `IllegalArgumentException`。

2. **`startForeground` 包 try/catch**：万一还是被拒（后台限制等），就退化成
   「普通后台服务 + 手动发通知」，**功能照跑，绝不崩**。

真机验证（Samsung Z Fold6 / Android 16）：

```
QixinAlive/Svc: service created, foregroundOk=true
QixinAlive/Svc: SSE open: https://10.0.0.123:9010/events
dumpsys: isForeground=true foregroundId=1001 types=0x40000000   ← 0x40000000 = SPECIAL_USE
crash buffer: (空)
```

---

## 桌面小组件（4x2）

常驻通知里那两行，原样放到桌面上（横向长条）。用框架自带的 `AppWidgetProvider` +
`RemoteViews` 实现，**没有引入任何第三方库**（不是 Glance）。

```
┌────────────────────────────────────────┐
│ ✿ 祈歆 Alive       10-09 23:54:47   ↻ │   ← 品牌 · 数据时间 · 刷新
│ ● 祈歆の📱 One UI 主屏幕 · 95%          │   ← 第一行：手机 + 电量（粉点）
│ ● 祈歆的💻 安卓 WebView 通知应用 — …    │   ← 第二行：电脑（金点）
└────────────────────────────────────────┘
```

深色毛玻璃卡片 + 粉色描边，圆角 24dp；两行前面各有一个小圆点当「设备指示灯」，
第一行加粗、第二行稍淡，层级比纯文字清楚。刷新按钮是右上角那个粉色小圆。

**格数与尺寸**（`res/xml/widget_alive_info.xml`）：

| 属性 | 值 | 说明 |
|---|---|---|
| `targetCellWidth` / `targetCellHeight` | `4` / `2` | Android 12+ 用格数指定：4 列宽 2 行高 |
| `minWidth` / `minHeight` | `294dp` / `146dp` | Android 11 及以下按 ≈74dp 一格折算 |
| `resizeMode` | `horizontal\|vertical` | 可自由拉伸 |
| `previewLayout` | 带示例内容 | 组件选择器里不显示空白 |

> ⚠️ **这两组尺寸必须给同一个答案，否则启动器直接拒绝添加。**
> 最开始写成 `targetCell=2x4`（竖条）却把 `minWidth/minHeight` 设成 `146×294dp`，
> One UI 按 dp 折算出的格数和声明对不上，就报「无法添加微件」。
> 现在 4 格宽 ≈ 294dp、2 格高 ≈ 146dp，两边一致 —— 已在真机上确认能正常添加：
>
> ```
> HoneySpace.AppCloseAnimationDelegate: ... 祈歆 Alive, appWidgetId=29, spanX=4, spanY=2
> QixinAlive/Widget: rendered 1 widget(s): 祈歆の📱 One UI 主屏幕 · 95% | 祈歆的💻 …
> AppWidgetHost: updateAppWidgetView, appWidgetId = 29 ... RemoteViewInfo(packages=xin.inory.alive)
> ```
>
> 另外：`RemoteViews` 只用 `LinearLayout` / `TextView` / `ImageView`，
> **绝对不要用 `ConstraintLayout`**（RemoteViews 不支持，会 inflate 失败）。

**数据怎么来的**：组件不自己联网，而是直接复用 `AliveService` 已经算好的那份
（`AliveService.lastLines()`）。服务每次刷新（SSE 推送或轮询）都会调
`AliveWidget.updateAll()`，所以**通知和组件永远同一条数据、同一时刻**，也不额外耗电。
`onUpdate` 里是先按缓存渲染、再尝试拉起服务，所以刚添加到桌面时不会是空白。

`updatePeriodMillis=1800000`（30 分钟，系统允许的最小值）只是系统层面的兜底，
真正驱动刷新的是那个常驻服务。**如果服务被系统杀掉了，组件会停在上次的数据**，
所以还是建议允许本应用不受电池优化限制。

### 怎么添加（不用长按图标）

各家桌面的「长按图标 → 添加小部件」**不是 Android 的强制要求**，小米 HyperOS 上就没有
（长按图标只有应用信息 / 卸载）。所以 app 改成**自己发起标准的固定请求**：

```java
AppWidgetManager mgr = AppWidgetManager.getInstance(this);
if (mgr.isRequestPinAppWidgetSupported()
        && mgr.getAppWidgetIds(new ComponentName(this, AliveWidget.class)).length == 0) {
    mgr.requestPinAppWidget(provider, null, null);   // 桌面弹「是否添加至主屏幕？」
}
```

`requestPinAppWidget` 是框架 API，桌面对它的支持由系统保证，完全绕开长按菜单那条路。
触发时机在 `MainActivity.requestWidgetPinIfNeeded()`：**只在桌面上还没有本组件时请求一次**，
所以不想要的人点掉「取消」就不会再被打扰；哪天删掉了，再打开一次 app 就能加回来。

真机实测（Samsung Z Fold6 / Android 16）：

```
QixinAlive/UI:     requestPinAppWidget -> true
(系统弹窗)          是否添加至主屏幕？  [取消] [添加]
                    预览卡片：祈歆 Alive  4 x 2
QixinAlive/Widget: rendered 1 widget(s): 祈歆の📱 One UI 主屏幕 · 91% | 祈歆的💻 Steam
```

> 如果某台机器连这个都不弹（极少数深度定制 ROM），最后的兜底是桌面设置里找
> 「小组件 / 小部件」，或者桌面双指捏合 → 小组件。

### 组件里的两个点击

- 点整块 → 打开 app（`PendingIntent.getActivity`）
- 点 ↻ → 发一个广播让服务立刻抓一次（`AliveService.refresh`）

---

## 全部参数（写死，无设置界面）

app 里**没有任何设置界面**，下面这些都固定在 `Prefs.java` 的常量里：

| 常量 | 值 | 说明 |
|---|---|---|
| `HOSTS` | `https://inory.xin:11026,https://10.0.0.123:9010` | 逗号分隔，按顺序探测 |
| `PHONE_DEVICE` | `zfold6` | 第一行的设备名来源 |
| `BATTERY_DEVICE` | `zfold6dl` | 第一行的电量来源 |
| `PC_DEVICE` | `desktop` | 第二行 |
| `LINE1_TPL` | `{name} {app}{batt_sep}{batt}` | |
| `LINE2_TPL` | `{name} {app}` | |
| `USE_SSE` | `true` | 关掉则定时轮询 |
| `INTERVAL_SEC` | `30` | 仅 SSE 关闭 / 断线重连时用 |

模板占位符：`{name}` `{app}` `{batt}` `{batt_sep}` `{key}`
（`{batt_sep}` 在没有电量数据时变成空串，不会留下多余的 `·`）

唯一还在内存里的运行时状态是 `Prefs.preferredHost`（上次成功的地址），
只是为了避免每次都为不可达的地址白等一次超时，**不落盘**。

---

## 构建

零第三方依赖（HTTP 用 `HttpURLConnection`，JSON 用系统自带 `org.json`），APK 只有 ~36 KB。

```powershell
.\build.ps1
```

或者手动：

```powershell
$env:JAVA_HOME="C:\Users\anzu\Documents\xin\tools\jdk\jdk-17.0.20.1+1"
$env:ANDROID_HOME="C:\Users\anzu\Documents\xin\tools\android-sdk"
& "C:\Users\anzu\.gradle\wrapper\dists\gradle-8.11.1-bin\eac4u065zwes5phgltp5f9b9e\gradle-8.11.1\bin\gradle.bat" `
  -p . :app:assembleRelease
```

产物：

- `app/build/outputs/apk/release/app-release.apk`
- `dist/QixinAlive-v1.4.1-release.apk`

签名复用工作区里的祈歆签名（`..\keystore`，可用环境变量 `QIXIN_KEYSTORE_DIR` 覆盖）：

```
Signer #1 certificate DN: CN=Qixin IME, OU=inoryxin, O=Qixin, L=Beijing, ST=Beijing, C=CN
Verified using v2 scheme (APK Signature Scheme v2): true
```

> 注意：因为复用了祈歆的签名，这个 app 与 QixinIME 同签名。如果不想要这点，把
> `app/build.gradle.kts` 里的 `signingConfigs` 去掉即可（会产出 unsigned APK）。

---

## 代码结构

```
app/src/main/java/xin/inory/alive/
  MainActivity.java    全屏 WebView + JS 桥 + SSL 放行 + 系统栏内边距（无设置界面）
  AliveService.java     前台服务：SSE 实时推送（默认）/ 定时轮询兜底
  SseClient.java        /events 的 SSE 客户端（长连接 + 事件解析）
  AliveWidget.java      4x2 桌面小组件（复用服务算好的两行）
  BootReceiver.java     开机自启
  Net.java              Tls（自签证书策略）/ Http（GET）/ HttpResult
  Status.java           DeviceInfo / StatusSnapshot / StatusParser / LineBuilder / StatusRepository
  Notifier.java         两行常驻通知 + WebViewHolder（页面探针弱引用）
  ProbeScript.java      注入页面的探针 JS（页面底部预览条 + 回传两行）
  Prefs.java            写死的全部参数（无设置界面，别人改不了）
```

### WebView 关键配置

- `javaScriptEnabled` / `domStorageEnabled`（页面用 localStorage 记主题）/ `databaseEnabled`
- `onReceivedSslError` → **proceed**：内网用 IP 访问时证书是 `inory.xin` 的 Let's Encrypt 证书，
  主机名对不上；自用 app 直接放行
- `MIXED_CONTENT_COMPATIBILITY_MODE`：页面引用了外链 https 图床
- `shouldOverrideUrlLoading`：站内留在 WebView，站外跳系统浏览器

### 三层数据来源

1. **主（实时）**：`SseClient` 挂 `/events` 长连接 → `update` 一到就解析 → 通知 + 小组件（秒级）
2. **兜底（轮询）**：SSE 连不上/断流时 GET `/query`，并按 `INTERVAL_SEC` 重试
3. **最后兜底**：页面里的探针 JS 自己也 GET `/query`，算好两行后经
   `Android.setLines()` 回传原生；前两层都失败时调用 `window.__qxProbe()` 让它回传

---

## 已验证

用**生产环境的真实类**（`Prefs` / `StatusParser` / `LineBuilder` / `StatusRepository` /
`SseClient`，只把 `Context`、`Log` 换成内存桩）打真实状态页跑通：

**SSE 实时推送**（真连 25 秒）：

```
eventsUrl = https://10.0.0.123:9010/events
[I] QixinAlive/SSE: connected: https://10.0.0.123:9010/events
[   176ms] 已连接，等推送…
[   181ms] update 累计=1  →  ①祈歆の📱 祈歆 Alive · 12%   ②祈歆的💻 安卓 WebView 通知应用 — DSH 本地构建 —…
结论: SSE 可用，update 能直接解析成两行
```

**地址回退 + 组行**：

```
== 完整抓取流程 StatusRepository.fetch()（默认地址顺序）==
  [跳过] https://inory.xin:11026/query  →  SocketTimeoutException: Connect timed out
  [OK]   https://10.0.0.123:9010/query  →  OK (5 设备)

+-------- 常驻通知（两行）--------+
| 1: 祈歆の📱 祈歆 Alive · 12%
| 2: 祈歆的💻 安卓 WebView 通知应用 — DSH 本地构建 —…
+--------------------------------+

== 反例：1Panel 安全入口页 ==    → 解析为 null，会被跳过
== 设备缺失时 ==                  → 第 1 行 "—"，第 2 行 "—"（不会留下悬空 "·"）
== 应用名超长时 ==                → 按 28 字截断，两行都读得下
```

## 还需要你在手机上看一眼的

- 常驻通知在**锁屏 / 息屏**下是否持续刷新（各厂商 ROM 后台策略不同，这是最容易出问题的地方）
- 全屏 WebView 的显示效果（内容已避开状态栏和导航栏，看还有没有被挡）
- 小组件 4x2 的比例是否合适、长应用名有没有被省略号截断
- `inory.xin:11026` 这个地址在你的实际网络里是否真的应该排在第一位

### 小组件相关的问题与排查

1. **添加不了** → 打开一次 app。它会自己调 `requestPinAppWidget` 弹系统确认框，
   不依赖各家桌面的长按菜单（小米 HyperOS 的图标长按里没有「添加小部件」）。
2. **加上了但不显示 / 显示旧样式** → 覆盖安装后桌面可能缓存着旧的 provider 信息。
   把桌面上那个删掉，**完全卸载再装**（`adb uninstall xin.inory.alive` 再装），然后重开 app 让它重新请求。
3. **添加时报「无法添加微件」** → 基本就是 `targetCellWidth/targetCellHeight` 与
   `minWidth/minHeight` 折算出的格数不一致（见上面那节），两边必须给同一个答案。
4. 看日志：
   ```powershell
   adb logcat -s QixinAlive/Widget QixinAlive/Svc QixinAlive/UI
   ```
   正常应该看到 `requestPinAppWidget -> true` 和
   `rendered 1 widget(s): 祈歆の📱 … | 祈歆的💻 …`；布局有问题时会有 `InflateException`。
