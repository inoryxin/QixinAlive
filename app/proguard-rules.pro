# WebView 的 JS 接口不能被混淆掉（本 app 未开启 minify，留着以防以后打开）
-keepclassmembers class xin.inory.alive.MainActivity$Bridge {
    public *;
}
-keepattributes JavascriptInterface
