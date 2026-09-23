package io.github.xgl34222220.hetu

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

internal object ProxyPolicyIconOverrides {
    internal const val PREF_KEY = "proxyPolicyIconOverridesJson"
    private const val MAX_BYTES = 1572864

    internal data class Entry(val name: String, val url: String = "", val path: String = "")

    fun all(prefs: SharedPreferences): Map<String, Entry> {
        val raw = prefs.getString(PREF_KEY, "{}").orEmpty().ifBlank { "{}" }
        val root = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        val result = linkedMapOf<String, Entry>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val item = root.optJSONObject(name) ?: continue
            val url = item.optString("url", "").trim()
            val path = item.optString("path", "").trim()
            if (url.isNotBlank() || path.isNotBlank()) result[name] = Entry(name, url, path)
        }
        return result
    }

    fun get(prefs: SharedPreferences, name: String): Entry? = all(prefs)[name]

    fun put(prefs: SharedPreferences, entry: Entry) {
        val root = JSONObject(prefs.getString(PREF_KEY, "{}").orEmpty().ifBlank { "{}" })
        root.put(
            entry.name,
            JSONObject().put("url", entry.url.trim()).put("path", entry.path.trim()),
        )
        prefs.edit().putString(PREF_KEY, root.toString()).apply()
    }

    fun remove(prefs: SharedPreferences, name: String) {
        val root = JSONObject(prefs.getString(PREF_KEY, "{}").orEmpty().ifBlank { "{}" })
        root.remove(name)
        prefs.edit().putString(PREF_KEY, root.toString()).apply()
    }

    fun clear(context: Context, prefs: SharedPreferences) {
        runCatching { File(context.filesDir, "policy-icons").deleteRecursively() }
        prefs.edit().remove(PREF_KEY).apply()
    }

    fun importLocal(context: Context, prefs: SharedPreferences, name: String, uri: Uri): Entry {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val data = input.readBytes()
            if (data.size > MAX_BYTES) throw IllegalArgumentException("图标不能超过 1.5 MiB")
            data
        } ?: throw IllegalArgumentException("无法读取所选图标")
        if (bytes.isEmpty()) throw IllegalArgumentException("图标文件为空")
        val directory = File(context.filesDir, "policy-icons").apply { mkdirs() }
        val target = File(
            directory,
            Integer.toHexString(name.hashCode()) + "-" + System.currentTimeMillis().toString(16) + ".img",
        )
        val temp = File(directory, target.name + ".new")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) temp.copyTo(target, overwrite = true)
        temp.delete()
        val previous = get(prefs, name)
        previous?.path?.takeIf { it.isNotBlank() && it != target.absolutePath }?.let { runCatching { File(it).delete() } }
        return Entry(name = name, path = target.absolutePath).also { put(prefs, it) }
    }
}

class ProxyPolicyIconsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { ProxyPolicyIconsScreen { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyPolicyIconsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", Context.MODE_PRIVATE) }
    val tokens = LocalHetuTokens.current
    val scope = rememberCoroutineScope()
    val repo = remember { ProxyDashboardRepository(context) }

    var revision by remember { mutableIntStateOf(0) }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var manualName by remember { mutableStateOf("") }
    var editingName by remember { mutableStateOf<String?>(null) }
    var remoteUrl by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var importName by remember { mutableStateOf("") }

    val overrides = remember(revision) { ProxyPolicyIconOverrides.all(prefs) }

    LaunchedEffect(revision) {
        val runtimeNames = runCatching { repo.state().groups.map { it.name } }.getOrDefault(emptyList())
        names = (runtimeNames + overrides.keys).distinct().sorted()
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val name = importName
        importName = ""
        if (uri == null || name.isBlank()) return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching {
                withContext(Dispatchers.IO) { ProxyPolicyIconOverrides.importLocal(context, prefs, name, uri) }
                revision++
                "已为“" + name + "”设置本地图标"
            }.getOrElse { it.message ?: "导入图标失败" }
        }
    }

    Scaffold(
        containerColor = tokens.pageBackground,
        modifier = Modifier.fillMaxSize().background(tokens.pageBackground),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("策略图标", fontWeight = FontWeight.Bold)
                        Text("覆盖策略组原有图标，不修改 YAML", fontSize = 11.sp, color = tokens.textSecondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (overrides.isNotEmpty()) {
                        TextButton(onClick = {
                            ProxyPolicyIconOverrides.clear(context, prefs)
                            revision++
                            message = "已清除全部自定义策略图标"
                        }) { Text("清除全部") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = tokens.pageBackground),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = tokens.cardBackground),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("手动添加策略", color = tokens.textPrimary, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = manualName,
                            onValueChange = { manualName = it.take(80) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("策略组名称") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                        )
                        Button(
                            onClick = {
                                val name = manualName.trim()
                                if (name.isBlank()) {
                                    message = "请输入策略组名称"
                                } else {
                                    editingName = name
                                    remoteUrl = overrides[name]?.url.orEmpty()
                                    manualName = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) { Text("添加 / 编辑") }
                    }
                }
            }

            if (message.isNotBlank()) {
                item {
                    Text(message, color = tokens.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }

            items(names, key = { it }) { name ->
                val customIcon = overrides[name]
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = tokens.cardBackground),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (customIcon == null) Icons.Rounded.Image else if (customIcon.path.isNotBlank()) Icons.Rounded.AddPhotoAlternate else Icons.Rounded.Link,
                                contentDescription = null,
                                tint = if (customIcon == null) tokens.textSecondary else MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, color = tokens.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when {
                                        customIcon == null -> "使用 YAML / 内置图标"
                                        customIcon.path.isNotBlank() -> "本地图标"
                                        else -> customIcon.url
                                    },
                                    color = tokens.textSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    importName = name
                                    imagePicker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/svg+xml"))
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp),
                            ) { Text("相册 / 文件") }
                            OutlinedButton(
                                onClick = {
                                    editingName = name
                                    remoteUrl = customIcon?.url.orEmpty()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp),
                            ) { Text("HTTPS 链接") }
                            if (customIcon != null) {
                                IconButton(onClick = {
                                    customIcon.path.takeIf { it.isNotBlank() }?.let { runCatching { File(it).delete() } }
                                    ProxyPolicyIconOverrides.remove(prefs, name)
                                    revision++
                                    message = "“" + name + "”已恢复原图标"
                                }) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "清除 " + name + " 自定义图标")
                                }
                            }
                        }
                    }
                }
            }

            if (names.isEmpty()) {
                item {
                    Text(
                        "当前没有读取到策略组。代理运行后会自动列出策略，也可以在上方手动输入策略名称。",
                        color = tokens.textSecondary,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }

    editingName?.let { name ->
        ModalBottomSheet(
            onDismissRequest = { editingName = null },
            containerColor = tokens.cardBackground,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("“" + name + "”图标", color = tokens.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "使用 HTTPS 图片地址；也可以关闭此面板后使用策略卡上的“相册 / 文件”。",
                    color = tokens.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                OutlinedTextField(
                    value = remoteUrl,
                    onValueChange = { remoteUrl = it.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTPS 图标链接") },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { editingName = null },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text("取消") }
                    Button(
                        onClick = {
                            val url = remoteUrl.trim()
                            if (!url.startsWith("https://")) {
                                message = "策略图标链接只接受 HTTPS"
                            } else {
                                ProxyPolicyIconOverrides.put(prefs, ProxyPolicyIconOverrides.Entry(name = name, url = url))
                                revision++
                                message = "已保存“" + name + "”的远程图标"
                                editingName = null
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text("保存") }
                }
            }
        }
    }}
