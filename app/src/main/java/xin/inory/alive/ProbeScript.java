package xin.inory.alive;

import org.json.JSONObject;

/**
 * 注入到页面里的探针脚本。
 *
 * 作用：
 *   1. 页面自己渲染设备列表时就会请求 /query，探针同样抓一份，在页面里把「两行」算好，
 *      通过 Android.setLines() 回传给原生 —— 即使原生抓取失败，通知也还有数据。
 *   2. 提供 window.__qxSetLines() / window.__qxProbe() 给原生调用。
 */
final class ProbeScript {

    private ProbeScript() {
    }

    /** 页面底部预览条的样式。 */
    static final String BOX_CSS =
            "#qx-app-lines-wrap{position:fixed;left:8px;right:8px;bottom:8px;z-index:2147483000;"
                    + "background:rgba(20,20,26,.82);border:1px solid rgba(255,210,230,.35);"
                    + "border-radius:12px;padding:8px 10px;color:#ffe6f1;"
                    + "font:13px/1.5 system-ui,-apple-system,\"PingFang SC\",\"Microsoft YaHei\",sans-serif;"
                    + "backdrop-filter:blur(6px);pointer-events:none}"
                    + "#qx-app-lines-wrap .qx-cap{font-size:11px;opacity:.65;margin-bottom:2px;letter-spacing:.08em}"
                    + "#qx-app-lines-wrap .qx-line{white-space:pre-wrap;word-break:break-all}";

    static String build() {
        String phone = js(Prefs.phoneDevice());
        String battery = js(Prefs.batteryDevice());
        String pc = js(Prefs.pcDevice());
        String tpl1 = js(Prefs.line1Tpl());
        String tpl2 = js(Prefs.line2Tpl());
        String miss = js(LineBuilder.MISSING);

        return ""
                + "(function(){\n"
                + "var CFG={phone:" + phone + ",battery:" + battery + ",pc:" + pc
                + ",tpl1:" + tpl1 + ",tpl2:" + tpl2 + ",miss:" + miss + "};\n"
                + "function applyTpl(tpl,name,app,batt,key,slice){\n"
                + "  if(slice>0&&app&&app.length>slice)app=app.slice(0,slice)+'…';\n"
                + "  var out=String(tpl||'')\n"
                + "    .replace(/\\{name\\}/g,name||CFG.miss)\n"
                + "    .replace(/\\{app\\}/g,app||CFG.miss)\n"
                + "    .replace(/\\{batt\\}/g,batt||CFG.miss)\n"
                + "    .replace(/\\{batt_sep\\}/g,batt?' · ':'')\n"
                + "    .replace(/\\{key\\}/g,key||CFG.miss);\n"
                + "  return out.split('\\n').map(function(l){return l.replace(/[ \\t]{2,}/g,' ').trim();})"
                + ".join('\\n').trim();\n"
                + "}\n"
                + "function buildLines(root){\n"
                + "  var slice=parseInt(root.device_status_slice||0,10)||0;\n"
                + "  if(slice<=0||slice>28)slice=28;\n"
                + "  var devs=root.device||root.device_status||{};\n"
                + "  var p=devs[CFG.phone]||{},b=devs[CFG.battery]||{},c=devs[CFG.pc]||{};\n"
                + "  var batt=String(b.app_name||'').trim();\n"
                + "  var l1=applyTpl(CFG.tpl1,p.show_name,p.app_name,batt,CFG.phone,slice);\n"
                + "  var l2=applyTpl(CFG.tpl2,c.show_name,c.app_name,'',CFG.pc,slice);\n"
                + "  return {lines:[l1.split('\\n')[0]||CFG.miss,l2.split('\\n')[0]||CFG.miss],ok:true};\n"
                + "}\n"
                + "window.__qxSetLines=function(json){\n"
                + "  try{\n"
                + "    var lines=JSON.parse(json);\n"
                + "    if(!lines||lines.length<2)return;\n"
                + "    var box=document.getElementById('qx-app-lines');\n"
                + "    if(box)box.innerHTML=lines.map(function(l){return '<div class=\"qx-line\">'+String(l)\n"
                + "      .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')+'</div>';}).join('');\n"
                + "  }catch(e){}\n"
                + "};\n"
                + "window.__qxProbe=function(){\n"
                + "  var base=(window.location.origin||'')+'/';\n"
                + "  fetch(base+'query',{cache:'no-store'}).then(function(r){return r.json();})\n"
                + "    .then(function(d){\n"
                + "      var res=buildLines(d);\n"
                + "      window.__qxLast=res;\n"
                + "      if(window.Android&&Android.setLines)Android.setLines(JSON.stringify(res.lines));\n"
                + "    })\n"
                + "    .catch(function(){if(window.Android&&Android.onProbe)Android.onProbe(document.title,false);});\n"
                + "};\n"
                + "function installBox(){\n"
                + "  if(document.getElementById('qx-app-lines-wrap'))return;\n"
                + "  var wrap=document.createElement('div');\n"
                + "  wrap.id='qx-app-lines-wrap';\n"
                + "  wrap.innerHTML='<div class=\"qx-cap\">常驻通知</div><div id=\"qx-app-lines\"></div>';\n"
                + "  (document.body||document.documentElement).appendChild(wrap);\n"
                + "}\n"
                + "installBox();\n"
                + "setTimeout(window.__qxProbe,800);\n"
                + "setInterval(window.__qxProbe,60000);\n"
                + "if(window.Android&&Android.onProbe)Android.onProbe(document.title,true);\n"
                + "})();";
    }

    /** 把字符串安全地包成 JS 双引号字面量。 */
    private static String js(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }
}
