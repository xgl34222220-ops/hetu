package io.github.xgl34222220.hetu

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import org.json.JSONObject

/**
 * Local-only WebUI backed by Mihomo's existing loopback controller.
 *
 * It does not expose the controller to LAN, change the controller port, or modify
 * the user's YAML. The page is loaded with a 127.0.0.1 base URL so all requests
 * stay on-device and keep the existing bearer-secret protection.
 */
class ProxyLocalWebUiActivity : ComponentActivity() {
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("hetu", MODE_PRIVATE)
        val port = prefs.getInt("proxyControllerPort", MihomoStartupConfig.CONTROLLER_PORT)
        val secret = prefs.getString("proxyControllerSecret", "").orEmpty()

        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        webView = view
        setContentView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        val html = WEB_UI
            .replace("__PORT__", port.toString())
            .replace("__SECRET_JSON__", JSONObject.quote(secret))
        view.loadDataWithBaseURL(
            "http://127.0.0.1:$port/",
            html,
            "text/html",
            "UTF-8",
            null,
        )
    }

    override fun onBackPressed() {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private companion object {
        val WEB_UI = """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>河图 WebUI</title>
<style>
:root{color-scheme:light dark;--bg:#f3f6fa;--surface:rgba(255,255,255,.86);--text:#142033;--muted:#748197;--line:rgba(20,32,51,.08);--blue:#2563eb;--green:#0f9f75;--red:#dc5a68}
@media(prefers-color-scheme:dark){:root{--bg:#0f141c;--surface:rgba(28,35,46,.88);--text:#edf3fb;--muted:#9aa7b9;--line:rgba(255,255,255,.08);--blue:#6e9cff;--green:#47c89d;--red:#ff8791}}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 85% -10%,rgba(37,99,235,.12),transparent 34%),var(--bg);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:var(--text)}
main{max-width:900px;margin:auto;padding:max(18px,env(safe-area-inset-top)) 16px calc(28px + env(safe-area-inset-bottom))}
header{display:flex;align-items:center;gap:12px;margin:6px 0 16px}h1{font-size:24px;margin:0;letter-spacing:-.6px}header small{color:var(--muted)}
.status{margin-left:auto;width:48px;height:48px;border-radius:16px;background:linear-gradient(180deg,#3275f2,#1d59d8);display:grid;place-items:center;color:white;font-size:27px;box-shadow:0 8px 22px rgba(37,99,235,.22)}
.tabs{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-bottom:12px}.tabs button,.action{border:0;border-radius:999px;padding:11px 12px;background:var(--surface);color:var(--text);font-weight:650}.tabs button.active{background:var(--blue);color:white}
.card{background:var(--surface);border:1px solid var(--line);border-radius:20px;margin:10px 0;overflow:hidden;box-shadow:0 8px 26px rgba(18,34,58,.05);backdrop-filter:blur(18px)}
.row{padding:13px 14px;border-top:1px solid var(--line)}.row:first-child{border-top:0}.title{font-size:14px;font-weight:700}.sub{font-size:12px;color:var(--muted);margin-top:4px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}.metric{padding:14px}.metric b{display:block;font-size:18px;margin-top:5px}.pill{display:inline-flex;align-items:center;padding:4px 8px;border-radius:999px;background:rgba(37,99,235,.10);color:var(--blue);font-size:11px;font-weight:700}
.node{display:flex;align-items:center;gap:8px;margin-top:9px}.node select{min-width:0;flex:1;border:1px solid var(--line);border-radius:12px;padding:9px;background:transparent;color:var(--text)}
.empty{padding:30px 18px;text-align:center;color:var(--muted)}.error{color:var(--red)}.ok{color:var(--green)}
@media(max-width:520px){.grid{grid-template-columns:1fr}}
</style>
</head>
<body>
<main>
<header><div><h1>河图 WebUI</h1><small id="backend">127.0.0.1:__PORT__</small></div><div id="status" class="status">…</div></header>
<div class="tabs"><button data-tab="overview" class="active">概览</button><button data-tab="proxies">策略组</button><button data-tab="connections">连接</button></div>
<section id="content"><div class="card"><div class="empty">正在连接 Mihomo 控制器…</div></div></section>
</main>
<script>
const SECRET=__SECRET_JSON__;
const headers=SECRET?{"Authorization":"Bearer "+SECRET}: {};
let current="overview", cache={version:null,proxies:null,connections:null};
const fmt=n=>{n=Number(n||0);if(n<1024)return n+" B";if(n<1048576)return(n/1024).toFixed(1)+" KB";if(n<1073741824)return(n/1048576).toFixed(1)+" MB";return(n/1073741824).toFixed(2)+" GB"};
const esc=s=>String(s??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
async function api(path,opt={}){const r=await fetch(path,{...opt,headers:{...headers,...(opt.headers||{})}});if(!r.ok)throw new Error("HTTP "+r.status);if(r.status===204)return null;return r.json()}
async function refresh(){
 try{
   const [version,proxies,connections]=await Promise.all([api("/version"),api("/proxies"),api("/connections")]);
   cache={version,proxies,connections};document.getElementById("status").textContent="✓";document.getElementById("status").classList.add("ok");render();
 }catch(e){document.getElementById("status").textContent="!";document.getElementById("content").innerHTML='<div class="card"><div class="empty error">控制器未连接：'+esc(e.message)+'</div></div>'}
}
function groups(){const p=(cache.proxies&&cache.proxies.proxies)||{};return Object.entries(p).filter(([,v])=>Array.isArray(v.all)&&v.all.length)}
function render(){
 const c=cache.connections||{};const gs=groups();
 if(current==="overview"){
   document.getElementById("content").innerHTML='<div class="grid"><div class="card metric"><span class="sub">核心版本</span><b>'+esc(cache.version?.version||"—")+'</b></div><div class="card metric"><span class="sub">当前连接</span><b>'+((c.connections||[]).length)+'</b></div><div class="card metric"><span class="sub">策略组</span><b>'+gs.length+'</b></div><div class="card metric"><span class="sub">累计流量</span><b>↑ '+fmt(c.uploadTotal)+' · ↓ '+fmt(c.downloadTotal)+'</b></div></div>';
 }else if(current==="proxies"){
   document.getElementById("content").innerHTML=gs.length?gs.map(([name,g])=>'<div class="card"><div class="row"><div class="title">'+esc(name)+'</div><div class="sub">'+esc(g.type||"Group")+' · '+g.all.length+' 节点</div><div class="node"><span class="pill">'+esc(g.now||"未选择")+'</span><select data-group="'+esc(name)+'">'+g.all.map(n=>'<option '+(n===g.now?'selected':'')+'>'+esc(n)+'</option>').join("")+'</select></div></div></div>').join(""):'<div class="card"><div class="empty">没有策略组</div></div>';
   document.querySelectorAll("select[data-group]").forEach(el=>el.onchange=()=>selectNode(el.dataset.group,el.value));
 }else{
   const xs=(c.connections||[]).slice(0,80);
   document.getElementById("content").innerHTML=xs.length?'<div class="card">'+xs.map(x=>{const m=x.metadata||{};return '<div class="row"><div class="title">'+esc(m.host||m.destinationIP||"未知目标")+'</div><div class="sub">'+esc((x.chains||[]).join(" → ")||x.rule||"未分流")+' · ↑ '+fmt(x.upload)+' · ↓ '+fmt(x.download)+'</div></div>'}).join("")+'</div>':'<div class="card"><div class="empty">暂无活动连接</div></div>';
 }
}
async function selectNode(group,node){try{await api("/proxies/"+encodeURIComponent(group),{method:"PUT",headers:{"Content-Type":"application/json"},body:JSON.stringify({name:node})});await refresh()}catch(e){alert("切换失败："+e.message)}}
document.querySelectorAll(".tabs button").forEach(b=>b.onclick=()=>{document.querySelectorAll(".tabs button").forEach(x=>x.classList.remove("active"));b.classList.add("active");current=b.dataset.tab;render()});
refresh();setInterval(refresh,2500);
</script>
</body>
</html>
""".trimIndent()
    }
}
