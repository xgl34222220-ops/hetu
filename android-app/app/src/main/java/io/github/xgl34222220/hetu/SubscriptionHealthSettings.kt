package io.github.xgl34222220.hetu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.*
import java.io.StringReader
import java.net.URI

internal data class SubscriptionHealth(val url: String, val interval: Int, val timeout: Int, val tolerance: Int) {
    fun check() {
        val uri = URI(url)
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && url.none { it.code < 32 }) { "请输入有效的 HTTP(S) 健康检查地址" }
        require(interval in 30..86400 && timeout in 500..30000 && tolerance in 0..5000) { "间隔 30–86400 秒，超时 500–30000 毫秒，容差 0–5000 毫秒" }
    }
}

/** Edits marked YAML fields only, keeping comments, anchors, secrets and unrelated source text. */
internal fun rewriteSubscriptionHealth(source: String, settings: SubscriptionHealth): String {
    settings.check()
    val yaml = Yaml(SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = false }))
    val root = yaml.compose(StringReader(source)) as? MappingNode ?: error("配置必须为 YAML 对象")
    fun MappingNode.field(name: String) = value.firstOrNull { (it.keyNode as? ScalarNode)?.value == name }?.valueNode
    val providers = root.field("proxy-providers") as? MappingNode ?: error("当前配置没有 proxy-providers")
    data class Edit(val start: Int, val end: Int, val value: String)
    val edits = mutableListOf<Edit>()
    fun index(mark: org.yaml.snakeyaml.error.Mark) = source.offsetByCodePoints(0, mark.index)
    fun lineStart(index: Int): Int = source.lastIndexOf('\n', (index - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    fun lineEnd(index: Int): Int = source.indexOf('\n', index).let { if (it < 0) source.length else it + 1 }
    fun change(node: MappingNode, key: String, value: String) {
        require(node.flowStyle == org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK) { "行内 YAML 对象请在编辑器中展开后再使用健康检查设置" }
        val field = node.value.firstOrNull { (it.keyNode as? ScalarNode)?.value == key }
        val indent = node.startMark.column
        if (field == null) {
            val end = index(node.endMark)
            val at = if (end == source.length || node.endMark.column == 0) end else lineStart(end)
            val separator = if (at > 0 && source[at - 1] != '\n') "\n" else ""
            edits += Edit(at, at, separator + " ".repeat(indent) + "$key: $value\n")
        } else {
            val begin = lineStart(index(field.keyNode.startMark))
            val end = field.valueNode.endMark
            val endAt = if (end.index < field.keyNode.startMark.index) lineEnd(index(field.keyNode.endMark))
                else if (end.column == 0) index(end) else lineEnd(index(end))
            edits += Edit(begin, endAt, " ".repeat(indent) + "$key: $value\n")
        }
    }
    val url = "'" + settings.url.replace("'", "''") + "'"
    providers.value.forEach { entry ->
        val node = entry.valueNode as? MappingNode ?: error("订阅定义无效")
        require(node.startMark.index >= entry.keyNode.startMark.index) { "整个订阅使用了别名，请在编辑器中设置其健康检查模板" }
        change(node, "health-check", "{enable: true, url: $url, interval: ${settings.interval}, timeout: ${settings.timeout}}")
    }
    (root.field("proxy-groups") as? SequenceNode)?.value?.filterIsInstance<MappingNode>()?.forEach { group ->
        if ((group.field("type") as? ScalarNode)?.value == "url-test") change(group, "tolerance", settings.tolerance.toString())
    }
    val result = StringBuilder(source)
    edits.sortedByDescending { it.start }.forEach { result.replace(it.start, it.end, it.value) }
    yaml.load<Any>(result.toString())
    return result.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubscriptionHealthSheet(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current; val prefs = remember { context.getSharedPreferences("hetu", 0) }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(prefs.getString("subscriptionHealthUrl", "https://www.gstatic.com/generate_204").orEmpty()) }
    var interval by remember { mutableStateOf(prefs.getInt("subscriptionHealthInterval", 300).toString()) }
    var timeout by remember { mutableStateOf(prefs.getInt("subscriptionHealthTimeout", 5000).toString()) }
    var tolerance by remember { mutableStateOf(prefs.getInt("subscriptionHealthTolerance", 50).toString()) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, containerColor = Color.Transparent,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().liquidSheetMaterial().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(ht("健康检查"), style = MaterialTheme.typography.titleLarge)
            Text("统一修改当前 Mihomo 配置中的订阅健康检查，容差用于 url-test 策略组。保存后手动重载或重启生效。", style = MaterialTheme.typography.bodySmall)
            LiquidGlassTextField(url, { url = it }, "检查地址", Modifier.fillMaxWidth(), enabled = !busy)
            LiquidGlassTextField(interval, { interval = it.filter(Char::isDigit) }, "间隔 / 秒", Modifier.fillMaxWidth(), enabled = !busy)
            LiquidGlassTextField(timeout, { timeout = it.filter(Char::isDigit) }, "超时 / 毫秒", Modifier.fillMaxWidth(), enabled = !busy)
            LiquidGlassTextField(tolerance, { tolerance = it.filter(Char::isDigit) }, "自动选择容差 / 毫秒", Modifier.fillMaxWidth(), enabled = !busy)
            if (error.isNotBlank()) HetuTaskFeedback(error, error = true)
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true; error = ""
                scope.launch {
                    try {
                        val settings = SubscriptionHealth(url.trim(), interval.toIntOrNull() ?: 0, timeout.toIntOrNull() ?: 0, tolerance.toIntOrNull() ?: -1)
                        withContext(Dispatchers.IO) {
                            val library = ProxyConfigLibrary(context)
                            val core = ProxyRuntimeProfile.load(prefs).core
                            require(core == ProxyRuntimeProfile.Core.MIHOMO || core == ProxyRuntimeProfile.Core.MIHOMO_SMART) { "健康检查表单用于 Mihomo；其他核心请使用配置编辑器" }
                            val selected = library.selected(core) ?: error("尚未选择配置")
                            val original = library.read(selected); val changed = rewriteSubscriptionHealth(original, settings)
                            check(library.read(selected) == original) { "配置已变化，请重新打开后重试" }
                            library.write(selected, changed)
                        }
                        prefs.edit().putString("subscriptionHealthUrl", settings.url).putInt("subscriptionHealthInterval", settings.interval).putInt("subscriptionHealthTimeout", settings.timeout).putInt("subscriptionHealthTolerance", settings.tolerance).apply()
                        onSaved(); onDismiss()
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { error = failure.message ?: "保存失败" }
                    finally { busy = false }
                }
            }) { if (busy) HetuBusyIndicator() else Text(ht("保存")) }
        }
    }
}
