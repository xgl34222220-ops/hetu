package io.github.xgl34222220.hetu

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs override scripts in the Android System WebView, a platform component (no bundled engine,
 * native library or new dependency). One offscreen WebView per start; every script runs in its own
 * dedicated Web Worker that is terminated on timeout, so an endless loop cannot block the page.
 * Network loads are blocked (blockNetworkLoads, a CSP of `default-src 'none'` and an interceptor
 * that answers every request with 403): a script only sees the config and profile it is given.
 */
internal class WebViewOverrideEngine(context: Context) : OverrideScriptEngine {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var webView: WebView? = null
    private val bridge = Bridge()
    @Volatile private var ready: CountDownLatch? = null
    @Volatile private var crashed: String? = null

    private class Job(val source: String, val config: String, val profile: String, val timeoutMs: Long, val maxChars: Int)
    private class Reply(val ok: Boolean, val payload: String, val logs: List<String>)

    private inner class Bridge {
        @Volatile var job: Job? = null
        val reply = AtomicReference<Reply?>(null)
        @Volatile var done: CountDownLatch? = null

        @JavascriptInterface fun ready() { this@WebViewOverrideEngine.ready?.countDown() }

        @JavascriptInterface fun job(): String {
            val j = job ?: return "null"
            return "{\"timeoutMs\":" + j.timeoutMs + ",\"maxChars\":" + j.maxChars +
                ",\"source\":" + quote(j.source) + ",\"config\":" + quote(j.config) + ",\"profile\":" + quote(j.profile) + "}"
        }

        @JavascriptInterface fun finish(ok: Boolean, payload: String?, logs: String?) {
            val lines = logs.orEmpty().split('\n').filter { it.isNotEmpty() }.take(OverrideScriptLimits.MAX_LOG_LINES)
            if (reply.compareAndSet(null, Reply(ok, payload.orEmpty(), lines))) done?.countDown()
        }
    }

    override fun evaluate(name: String, source: String, configJson: String, profileJson: String, timeoutMs: Long, maxResultChars: Int): OverrideScriptEngine.Evaluation {
        check(Looper.myLooper() != Looper.getMainLooper()) { "覆写脚本不能在主线程等待" }
        ensureStarted()
        val done = CountDownLatch(1)
        bridge.reply.set(null)
        bridge.done = done
        bridge.job = Job(source, configJson, profileJson, timeoutMs, maxResultChars)
        main.post { webView?.evaluateJavascript("__hetuRun()", null) }
        // The page terminates the worker at timeoutMs; this deadline only covers a hung page.
        val answered = done.await(timeoutMs + 2_000L, TimeUnit.MILLISECONDS)
        bridge.job = null
        crashed?.let { throw OverrideScriptException(it) }
        if (!answered) { close(); throw OverrideScriptException("脚本执行超时（${timeoutMs} ms），已终止") }
        val reply = bridge.reply.get() ?: throw OverrideScriptException("脚本没有返回结果")
        if (!reply.ok) throw OverrideScriptException(reply.payload.ifBlank { "脚本执行失败" })
        return OverrideScriptEngine.Evaluation(reply.payload, reply.logs)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun ensureStarted() {
        if (webView != null && crashed == null) return
        crashed = null
        val latch = CountDownLatch(1)
        ready = latch
        val failure = AtomicReference<Throwable?>(null)
        main.post {
            try {
                val view = WebView(app)
                view.settings.apply {
                    javaScriptEnabled = true
                    blockNetworkLoads = true
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    javaScriptCanOpenWindowsAutomatically = false
                }
                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (request.url.toString().startsWith(BASE_URL)) return null
                        return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        crashed = if (detail.didCrash()) "WebView 渲染进程崩溃，脚本可能占用了过多内存" else "WebView 渲染进程被系统回收（内存不足）"
                        bridge.done?.countDown(); ready?.countDown()
                        if (webView === view) webView = null
                        runCatching { view.destroy() }
                        return true
                    }
                }
                view.addJavascriptInterface(bridge, "HetuOverrideHost")
                view.loadDataWithBaseURL(BASE_URL, HTML, "text/html", "utf-8", null)
                webView = view
            } catch (error: Throwable) {
                failure.set(error)
                latch.countDown()
            }
        }
        if (!latch.await(OverrideScriptLimits.ENGINE_GRACE_MS - 1_000L, TimeUnit.MILLISECONDS)) {
            close(); throw OverrideScriptException("系统 WebView 启动超时，无法执行覆写脚本")
        }
        failure.get()?.let { throw OverrideScriptException("系统 WebView 不可用：" + (it.message ?: it.javaClass.simpleName)) }
        crashed?.let { throw OverrideScriptException(it) }
    }

    override fun close() {
        val view = webView ?: return
        webView = null
        main.post { runCatching { view.removeJavascriptInterface("HetuOverrideHost"); view.stopLoading(); view.destroy() } }
    }

    companion object {
        private const val BASE_URL = "https://override.hetu.invalid/"

        fun store(context: Context) = OverrideScriptStore(File(context.applicationContext.filesDir, "override-scripts"))

        fun quote(text: String): String {
            val out = StringBuilder(text.length + 16)
            CoreJson.string(out, text)
            return out.toString()
        }

        // HETU-OVERRIDE-WORKER-BEGIN
        /** Runs inside a dedicated worker. Also loaded by tools/qa/test_override_js93.py under Node. */
        const val WORKER_JS = """
var __hetuLogs = [];
function __hetuText(v) {
  if (typeof v === 'string') return v;
  try { var s = JSON.stringify(v); return s === undefined ? String(v) : s; } catch (e) { return String(v); }
}
function __hetuLog() {
  if (__hetuLogs.length >= 200) return;
  var parts = [];
  for (var i = 0; i < arguments.length; i++) parts.push(__hetuText(arguments[i]));
  __hetuLogs.push(parts.join(' ').replace(/\n/g, ' ').slice(0, 2000));
}
var console = { log: __hetuLog, info: __hetuLog, warn: __hetuLog, error: __hetuLog, debug: __hetuLog };
function __hetuError(err) {
  if (err && typeof err === 'object' && err.name && err.message) return err.name + ': ' + err.message;
  return String(err);
}
function __hetuEvaluate(source, configText, profileText, maxChars) {
  return Promise.resolve().then(function () {
    var config = JSON.parse(configText);
    var profile = profileText ? JSON.parse(profileText) : null;
    var main = (new Function('console', source + '\n;return typeof main === "function" ? main : undefined;'))(console);
    if (typeof main !== 'function') throw new Error('脚本没有定义 main(config, profile) 函数');
    return main(config, profile);
  }).then(function (result) {
    if (result === null || typeof result !== 'object' || Array.isArray(result)) throw new Error('main 必须返回配置对象（键值映射）');
    var text = JSON.stringify(result);
    if (typeof text !== 'string') throw new Error('返回的配置无法序列化为 JSON');
    if (text.length > maxChars) throw new Error('返回的配置过大（超过 ' + Math.floor(maxChars / 1048576) + ' MiB）');
    return text;
  });
}
if (typeof self !== 'undefined' && typeof importScripts === 'function') {
  self.onmessage = function (e) {
    var d = e.data;
    __hetuEvaluate(d.source, d.config, d.profile, d.maxChars).then(function (text) {
      self.postMessage({ ok: true, text: text, logs: __hetuLogs });
    }, function (err) {
      self.postMessage({ ok: false, error: __hetuError(err), logs: __hetuLogs });
    });
  };
}
"""
        // HETU-OVERRIDE-WORKER-END

        // HETU-OVERRIDE-HARNESS-BEGIN
        /** The page: one worker per script, terminated when the script's time is up. */
        const val HARNESS_JS = """
var __hetuWorkerUrl = null;
function __hetuNewWorker() {
  if (!__hetuWorkerUrl) {
    var code = document.getElementById('hetu-worker').textContent;
    __hetuWorkerUrl = URL.createObjectURL(new Blob([code], { type: 'text/javascript' }));
  }
  return new Worker(__hetuWorkerUrl);
}
function __hetuRunJob(job, done) {
  var settled = false, worker = null, timer = null;
  function finish(ok, payload, logs) {
    if (settled) return;
    settled = true;
    if (timer !== null) clearTimeout(timer);
    try { if (worker) worker.terminate(); } catch (e) {}
    done(ok, payload, (logs || []).join('\n'));
  }
  try { worker = __hetuNewWorker(); } catch (e) { finish(false, '无法创建脚本工作线程：' + e); return; }
  timer = setTimeout(function () { finish(false, '脚本执行超时（' + job.timeoutMs + ' ms），已终止'); }, job.timeoutMs);
  worker.onmessage = function (e) {
    var m = e.data || {};
    finish(!!m.ok, String(m.ok ? m.text : m.error), m.logs);
  };
  worker.onerror = function (e) {
    if (e && e.preventDefault) e.preventDefault();
    finish(false, '脚本错误：' + ((e && e.message) || e));
  };
  worker.postMessage({ source: job.source, config: job.config, profile: job.profile, maxChars: job.maxChars });
}
function __hetuRun() {
  var job = JSON.parse(HetuOverrideHost.job());
  if (!job) return;
  __hetuRunJob(job, function (ok, payload, logs) { HetuOverrideHost.finish(ok, payload, logs); });
}
"""
        // HETU-OVERRIDE-HARNESS-END

        private val HTML = "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' blob:; worker-src blob:; connect-src 'none'\">" +
            "<script id=\"hetu-worker\" type=\"text/plain\">" + WORKER_JS + "</script>" +
            "<script>" + HARNESS_JS + "</script></head><body onload=\"HetuOverrideHost.ready()\"></body></html>"
    }
}

/** The start path's entry (called from RootProxyManager.prepare on its worker thread). */
internal object OverrideScriptStart {
    class Applied(@JvmField val text: String, @JvmField val notice: String, @JvmField val fallback: Boolean)

    @JvmStatic fun hasEnabled(context: Context): Boolean = runCatching { WebViewOverrideEngine.store(context).hasEnabled() }.getOrDefault(false)

    /**
     * Runs the enabled scripts on [source] for a Mihomo ([json] = false) or sing-box source.
     * Returns null when no script is enabled, so the start path stays byte-for-byte unchanged.
     * [sourceName] is null when no profile is selected: the scripts start from an empty config.
     */
    @JvmStatic fun apply(context: Context, coreId: String, supported: Boolean, sourceName: String?, source: String, json: Boolean): Applied? {
        val store = WebViewOverrideEngine.store(context)
        val scripts = try { store.enabled() } catch (failure: Exception) {
            return Applied(source, "覆写脚本读取失败，已使用未修改的配置：" + (failure.message ?: failure.javaClass.simpleName), true)
        }
        if (scripts.isEmpty()) return null
        if (!supported) return Applied(source, "覆写脚本只对 Mihomo / sing-box 生效，本次未执行", true)
        val format = if (json) OverrideFormat.JSON else OverrideFormat.YAML
        val profile: Map<String, Any?>? = sourceName?.let { linkedMapOf("name" to it, "core" to coreId, "format" to format.id) }
        val outcome = WebViewOverrideEngine(context).use { engine ->
            OverrideScriptPipeline.apply(format, source, profile, scripts, engine)
        }
        runCatching { store.recordRun(outcome) }
        return Applied(outcome.text, outcome.notice, outcome.fallback)
    }
}
