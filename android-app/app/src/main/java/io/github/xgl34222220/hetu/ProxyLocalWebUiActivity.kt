package io.github.xgl34222220.hetu

import android.annotation.SuppressLint
import android.os.Bundle
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
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                    if (request?.url?.toString() == "about:hetu-back") { finish(); return true }
                    return false
                }
                override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                    val uri = request?.url ?: return null
                    if (uri.host == "127.0.0.1" && uri.port == port && uri.path.orEmpty().startsWith(WebPanelAssets.PREFIX)) {
                        return WebPanelAssets.response(this@ProxyLocalWebUiActivity, uri.path.orEmpty())
                    }
                    return null
                }
            }
            webChromeClient = WebChromeClient()
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        webView = view
        setSafeWebViewContent(view)
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        (view.parent as? android.view.View)?.setBackgroundColor(android.graphics.Color.parseColor(if (dark) "#0E0F13" else "#ECEEFB"))
        if (view.restoreSavedPage(savedInstanceState)) return

        val mode = prefs.getString("proxyWebPanelLocalMode", "auto").orEmpty()
        if (mode != "builtin" && WebPanelAssets.installed(this)) {
            val endpoint = "http://127.0.0.1:$port${WebPanelAssets.PREFIX}#/setup?hostname=127.0.0.1&port=$port&secret=" +
                android.net.Uri.encode(secret) + "&disableUpgradeCore=1&disableTunMode=1"
            view.loadUrl(endpoint)
        } else {
            val html = WEB_UI
                .replace("__PORT__", port.toString())
                .replace("__SECRET_JSON__", JSONObject.quote(secret))
            view.loadDataWithBaseURL("http://127.0.0.1:$port/", html, "text/html", "UTF-8", null)
            if (mode == "zashboard") {
                android.widget.Toast.makeText(this, "尚未安装 Zashboard，已打开内置面板；请在 Web 面板设置中安装", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.savePage(outState)
        super.onSaveInstanceState(outState)
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
:root{color-scheme:light dark;--bg:#edf1fd;--surface:#f9faff;--inset:#f1f2fc;--text:#171a24;--muted:#666c7d;--line:#e2e5f3;--blue:#0068ff;--soft:#e6ecff;--green:#00b85c;--red:#ee4351}
@media(prefers-color-scheme:dark){:root{--bg:#0e0f13;--surface:#181a20;--inset:#22252d;--text:#ebedf2;--muted:#9ca2b0;--line:#2a2e37;--blue:#7ea6ff;--soft:#1d2c52;--green:#4ade80;--red:#f87171}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);font-family:-apple-system,BlinkMacSystemFont,"Roboto","Segoe UI",sans-serif;color:var(--text)}
button,select{font:inherit}button{cursor:pointer;-webkit-tap-highlight-color:transparent}button:disabled{opacity:.5;cursor:default}button:focus-visible{outline:2px solid var(--blue);outline-offset:2px}
main{max-width:900px;margin:auto;padding:0 14px calc(20px + env(safe-area-inset-bottom))}
header{height:88px;position:relative;display:flex;align-items:center;justify-content:center;text-align:center;margin-bottom:5px}h1{font-size:22px;line-height:28px;margin:0;font-weight:750}header small{display:block;color:var(--muted);font-size:12.5px;line-height:18px}
.back{position:absolute;left:-5px;top:22px;width:40px;height:44px;display:grid;place-items:center;color:var(--text);text-decoration:none}.back svg{width:23px;height:23px}
.status{position:absolute;right:0;top:28px;display:flex;align-items:center;gap:4px;border:1px solid #b4dfd0;border-radius:999px;padding:4px 7px;color:#269f7b;background:#edf9f4;font-size:10px;line-height:15px;font-weight:650}.status .mark{border-radius:50%;background:currentColor;width:15px;height:15px;position:relative}.status .mark:after{content:'✓';position:absolute;inset:0;color:white;text-align:center;font-size:11px;line-height:15px}.status.error{color:var(--red);border-color:var(--red);background:var(--surface)}.status.error .mark:after{content:'!'}
.tabs{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:4px;margin:0 1px 12px}.tabs button{border:1px solid #d1d7ee;border-radius:14px;padding:10px 8px;background:#e7eafb;color:var(--muted);font-size:15px;line-height:18px;font-weight:650}.tabs button.active{background:var(--surface);color:var(--text)}
.card{background:var(--surface);border-radius:20px;margin-bottom:12px;overflow:hidden}.metric{display:flex;align-items:center;min-height:80px;padding:17px 18px;gap:26px}.glyph{width:26px;height:26px;flex:none;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}
.title{font-size:18px;line-height:24px;font-weight:700;overflow-wrap:anywhere}.sub{font-size:14px;line-height:19px;color:var(--muted);overflow-wrap:anywhere}.metric .sub{font-size:14.5px}.group{padding:18px}.group-head{display:flex;align-items:center;gap:26px}.node{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-top:12px}.pill{display:block;max-width:42%;min-width:96px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;text-align:center;padding:7px 12px;border-radius:999px;background:var(--soft);color:var(--blue);font-size:13px;line-height:18px;font-weight:650}
.node-picker{border:1px solid var(--line);border-radius:12px;background:var(--inset);color:var(--text);padding:9px 12px;min-width:0;width:60%;font-size:14px;line-height:18px;display:flex;align-items:center;justify-content:space-between;gap:8px}.node-picker span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.node-picker .glyph{width:16px;height:16px;stroke-width:1.6}
.connections{padding:0 14px}.connection{display:flex;align-items:center;gap:26px;padding:15px 4px;border-top:1px solid var(--line)}.connection:first-child{border-top:0}.connection>div{min-width:0}.traffic{display:flex;flex-wrap:wrap;gap:12px;font-size:13px;line-height:20px;margin-top:1px;font-weight:600}.up{color:var(--blue)}.down{color:var(--green)}.traffic i{font-style:normal;font-size:21px;vertical-align:-1px;margin-right:5px}
.empty{padding:30px 18px;text-align:center;color:var(--muted);font-size:14px;line-height:22px}.error{color:var(--red)}.retry{display:block;margin:16px auto 0;background:var(--blue);color:white;border:0;border-radius:12px;padding:10px 24px}
dialog{border:0;border-radius:22px;background:var(--surface);color:var(--text);width:min(300px,calc(100vw - 40px));max-height:80vh;padding:0;box-shadow:0 14px 42px #0002}dialog::backdrop{background:#10182788}.dialog-title{font-size:20px;line-height:27px;font-weight:700;padding:18px 22px 8px}.node-options{max-height:48vh;overflow:auto;padding:0 17px}.node-option{border:0;border-bottom:1px solid var(--line);background:transparent;color:var(--text);display:flex;align-items:center;gap:23px;text-align:left;width:100%;padding:11px 5px;font-size:15px;line-height:18px}.node-option:last-child{border-bottom:0}.radio{width:20px;height:20px;flex:none;border:1.5px solid var(--muted);border-radius:50%;position:relative}.node-option.selected .radio{border:2px solid var(--blue)}.node-option.selected .radio:after{content:'';position:absolute;inset:3px;border-radius:50%;background:var(--blue)}.dialog-footer{border-top:1px solid var(--line);display:flex;justify-content:flex-end;padding:5px 13px}.dialog-footer button{border:0;color:var(--blue);background:transparent;padding:8px 10px;font-size:16px;line-height:18px;font-weight:650}#notice{width:min(238px,calc(100vw - 40px));border-radius:13px}#notice .dialog-title{font-size:18px;padding:17px 20px 2px}#notice-message{padding:0 20px 8px;font-size:14px;line-height:20px;overflow-wrap:anywhere}
@media(prefers-color-scheme:dark){.tabs button{background:var(--inset);border-color:var(--line)}.status{background:var(--surface)}}
@media(max-width:350px){.title{font-size:16px}.group-head,.metric,.connection{gap:17px}.pill{min-width:74px}.node{gap:8px}.status{top:6px}}
</style>
</head>
<body>
<main>
<header><a class="back" href="about:hetu-back" aria-label="返回"><svg class="glyph" viewBox="0 0 24 24"><path d="m15 4-8 8 8 8"/></svg></a><div><h1>河图 WebUI</h1><small id="backend">127.0.0.1:__PORT__</small></div><div id="status" class="status" role="status"><span class="mark"></span><span id="status-label">连接中</span></div></header>
<nav class="tabs" aria-label="面板页面"><button data-tab="overview" class="active" aria-selected="true">概览</button><button data-tab="proxies" aria-selected="false">策略组</button><button data-tab="connections" aria-selected="false">连接</button></nav>
<section id="content"><div class="card"><div class="empty">正在连接 Mihomo 控制器…</div></div></section>
</main>
<dialog id="node-dialog" aria-labelledby="node-dialog-title"><div id="node-dialog-title" class="dialog-title">节点选择</div><div id="node-options" class="node-options"></div><div class="dialog-footer"><button id="cancel-node">取消</button></div></dialog>
<dialog id="notice" aria-labelledby="notice-title"><div id="notice-title" class="dialog-title">提示</div><div id="notice-message"></div><div class="dialog-footer"><button id="confirm-notice">确定</button></div></dialog>
<script>
const SECRET=__SECRET_JSON__;
const headers=SECRET?{"Authorization":"Bearer "+SECRET}: {};
let current="overview",cache={version:null,proxies:null,connections:null},connected=false,lastError="",refreshing=null,pendingGroup=null;
const fmt=n=>{if(n==null||!Number.isFinite(Number(n)))return "—";n=Number(n);if(n<1024)return n+" B";if(n<1048576)return +(n/1024).toFixed(1)+" KB";if(n<1073741824)return +(n/1048576).toFixed(1)+" MB";return +(n/1073741824).toFixed(2)+" GB"};
const esc=s=>String(s??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const paths={cube:'M12 2 3 7v10l9 5 9-5V7L12 2ZM3 7l9 5 9-5M12 12v10',link:'M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7L12 5M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7L12 19',layers:'m3 7 9-5 9 5-9 5-9-5Zm0 5 9 5 9-5m-18 5 9 5 9-5',traffic:'M6 3v18m-4-14 4-4 4 4M18 21V3m-4 14 4 4 4-4',tune:'M3 5h4m4 0h10M3 12h10m4 0h4M3 19h4m4 0h10M7 5a2 2 0 1 0 4 0a2 2 0 1 0-4 0M13 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0M7 19a2 2 0 1 0 4 0a2 2 0 1 0-4 0',target:'M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0M8 12a4 4 0 1 0 8 0a4 4 0 1 0-8 0',shield:'M12 2 3 6v7c0 5 5 8 9 10 4-2 9-5 9-10V6L12 2Z',bolt:'m13 2-10 12h8l-1 9 11-13h-8l1-8Z',down:'m6 9 6 6 6-6'};
const icon=n=>'<svg class="glyph" aria-hidden="true" viewBox="0 0 24 24"><path d="'+paths[n]+'"/></svg>';
async function api(path,opt={}){const r=await fetch(path,{...opt,headers:{...headers,...(opt.headers||{})}});if(!r.ok)throw new Error("HTTP "+r.status);if(r.status===204)return null;return r.json()}
function status(ok,label){const s=document.getElementById("status");s.classList.toggle("error",!ok);document.getElementById("status-label").textContent=label}
async function refresh(){
 if(refreshing)return refreshing;
 refreshing=(async()=>{try{
  const [version,proxies,connections]=await Promise.all([api("/version"),api("/proxies"),api("/connections")]);
  cache={version,proxies,connections};connected=true;lastError="";status(true,"已连接");render();return true;
 }catch(e){connected=false;lastError=e.message;status(false,"未连接");render();return false;}finally{refreshing=null;}})();
 return refreshing;
}
function groups(){const p=(cache.proxies&&cache.proxies.proxies)||{};return Object.entries(p).filter(([,v])=>Array.isArray(v.all)&&v.all.length)}
function metric(symbol,label,value){return '<div class="card metric">'+icon(symbol)+'<div><div class="title">'+label+'</div><div class="sub">'+esc(value)+'</div></div></div>'}
function render(){
 const content=document.getElementById("content");
 if(!connected){content.innerHTML='<div class="card"><div class="empty error">'+(lastError?'控制器未连接：'+esc(lastError):'正在连接 Mihomo 控制器…')+(lastError?'<button class="retry" id="retry">重试</button>':'')+'</div></div>';const retry=document.getElementById("retry");if(retry)retry.onclick=refresh;return;}
 const c=cache.connections||{},gs=groups();
 if(current==="overview"){
  const raw=cache.version?.version,version=raw?(String(raw).toLowerCase().startsWith('mihomo')?raw:'Mihomo '+raw):'—';
  content.innerHTML=metric('cube','核心版本',version)+metric('link','当前连接',(c.connections||[]).length)+metric('layers','策略组',gs.length)+metric('traffic','累计流量','↑ '+fmt(c.uploadTotal)+' · ↓ '+fmt(c.downloadTotal));
 }else if(current==="proxies"){
  content.innerHTML=gs.length?gs.map(([name,g],index)=>{const type=String(g.type||'Group'),symbol=type==='Selector'?'target':type==='URLTest'?'tune':type==='Fallback'?'shield':'layers';return '<div class="card group"><div class="group-head">'+icon(symbol)+'<div><div class="title">'+esc(name)+'</div><div class="sub">'+esc(type)+' · '+g.all.length+' 节点</div></div></div><div class="node"><span class="pill">'+esc(g.now||'未选择')+'</span><button class="node-picker" data-index="'+index+'" '+(pendingGroup===name?'disabled':'')+' aria-label="'+esc(name)+'：选择节点"><span>'+esc(pendingGroup===name?'切换中…':g.now||'未选择')+'</span>'+icon('down')+'</button></div></div>'}).join(''):'<div class="card"><div class="empty">没有策略组</div></div>';
  document.querySelectorAll('.node-picker').forEach(el=>el.onclick=()=>{const [name,g]=gs[Number(el.dataset.index)];openNodes(name,g);});
 }else{
  const xs=(c.connections||[]).slice(0,80);
  content.innerHTML=xs.length?'<div class="card connections">'+xs.map(x=>{const m=x.metadata||{};return '<div class="connection">'+icon('link')+'<div><div class="title">'+esc(m.host||m.destinationIP||'未知目标')+'</div><div class="sub">'+esc((x.chains||[]).join(' → ')||x.rule||'未分流')+'</div><div class="traffic"><span class="up"><i>↑</i>'+fmt(x.upload)+'</span><span class="down"><i>↓</i>'+fmt(x.download)+'</span></div></div></div>'}).join('')+'</div>':'<div class="card"><div class="empty">暂无活动连接</div></div>';
 }
}
function openNodes(group,g){
 const dialog=document.getElementById('node-dialog'),options=document.getElementById('node-options');
 options.innerHTML=g.all.map((node,index)=>'<button class="node-option '+(node===g.now?'selected':'')+'" data-index="'+index+'" role="radio" aria-checked="'+(node===g.now)+'"><span class="radio"></span><span>'+esc(node)+'</span></button>').join('');
 options.querySelectorAll('button').forEach(button=>button.onclick=()=>{dialog.close();selectNode(group,g.all[Number(button.dataset.index)]);});dialog.showModal();
}
function notice(message){document.getElementById('notice-message').textContent=message;document.getElementById('notice').showModal();}
async function selectNode(group,node){
 if(pendingGroup)return;pendingGroup=group;render();
 try{
  if(refreshing)await refreshing;
  await api('/proxies/'+encodeURIComponent(group),{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:node})});
  const ok=await refresh();if(!ok)throw new Error(lastError||'无法确认节点状态');
  if(cache.proxies?.proxies?.[group]?.now!==node)throw new Error('控制器尚未确认所选节点');
 }catch(e){notice('切换失败：'+e.message);}finally{pendingGroup=null;render();}
}
document.getElementById('cancel-node').onclick=()=>document.getElementById('node-dialog').close();
document.getElementById('confirm-notice').onclick=()=>document.getElementById('notice').close();
document.querySelectorAll('.tabs button').forEach(b=>b.onclick=()=>{document.querySelectorAll('.tabs button').forEach(x=>{x.classList.toggle('active',x===b);x.setAttribute('aria-selected',x===b?'true':'false');});current=b.dataset.tab;render();});
refresh();setInterval(()=>{if(!document.hidden&&!pendingGroup)refresh();},2500);
</script>
</body>
</html>
""".trimIndent()
    }
}
