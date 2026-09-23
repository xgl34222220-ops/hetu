package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.CrystalSurface as Surface
import io.github.xgl34222220.hetu.ui.*

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.LocalHetuTokens

class ThemeSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var revision by remember { mutableIntStateOf(0) }
            key(revision) {
                HetuTheme {
                    ThemeSettingsScreen(
                        onBack = { finish() },
                        onThemeChanged = { revision++ },
                    )
                }
            }
        }
    }
}

private data class PickerOption(val value: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSettingsScreen(onBack: () -> Unit, onThemeChanged: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    val t = LocalHetuTokens.current

    var uiStyle by remember { mutableStateOf(prefs.getString("uiStyle", "Miuix") ?: "Miuix") }
    var appearance by remember { mutableStateOf(prefs.getString("appearance", "system") ?: "system") }
    var pureBlack by remember { mutableStateOf(prefs.getBoolean("pureBlackDark", false)) }
    var monet by remember { mutableStateOf(prefs.getBoolean("enableMonet", false)) }
    var palette by remember { mutableStateOf(prefs.getString("colorPalette", "TonalSpot") ?: "TonalSpot") }
    var standard by remember { mutableStateOf(prefs.getString("colorStandard", "Material3_2021") ?: "Material3_2021") }
    var accent by remember { mutableStateOf(prefs.getString("accentHex", "#2563EB") ?: "#2563EB") }
    var blur by remember { mutableStateOf(prefs.getBoolean("enableBlur", true)) }
    var floating by remember { mutableStateOf(prefs.getBoolean("floatingBottomBar", true)) }
    var liquid by remember { mutableStateOf(prefs.getBoolean("liquidGlass", true)) }
    var panelTab by remember { mutableStateOf(prefs.getBoolean("showPanelTab", true)) }
    var predictiveBack by remember { mutableStateOf(prefs.getBoolean("predictiveBackAnimation", true)) }
    var predictiveBackFollowEdge by remember { mutableStateOf(prefs.getBoolean("predictiveBackFollowEdge", true)) }
    var scale by remember { mutableFloatStateOf(prefs.getFloat("uiScale", 1f).coerceIn(.8f, 1.2f)) }

    var pickerTitle by remember { mutableStateOf<String?>(null) }
    var pickerOptions by remember { mutableStateOf<List<PickerOption>>(emptyList()) }
    var pickerSelected by remember { mutableStateOf("") }
    var pickerApply by remember { mutableStateOf<(String) -> Unit>({}) }

    fun openPicker(title: String, selected: String, options: List<PickerOption>, apply: (String) -> Unit) {
        pickerTitle = title
        pickerSelected = selected
        pickerOptions = options
        pickerApply = apply
    }

    fun persistString(key: String, value: String, refresh: Boolean = true) {
        prefs.edit().putString(key, value).apply()
        if (refresh) onThemeChanged()
    }

    fun persistBoolean(key: String, value: Boolean, refresh: Boolean = true) {
        prefs.edit().putBoolean(key, value).apply()
        if (refresh) onThemeChanged()
    }

    LazyColumn(
        Modifier.fillMaxSize().crystalPageBackground(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = hetuContentBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Column(Modifier.statusBarsPadding()) {
            HetuPageHeader("主题与界面", onBack, subtitle = "河图界面偏好")
        } }

        item { ThemeSection("基础主题") {
            ThemeValueRow(Icons.Rounded.DashboardCustomize, "界面风格", uiStyle) {
                openPicker("界面风格", uiStyle, listOf(PickerOption("Miuix", "Miuix"), PickerOption("Material", "Material"))) {
                    uiStyle = it; persistString("uiStyle", it)
                }
            }
            ThemeDivider()
            ThemeValueRow(Icons.Rounded.DarkMode, "主题模式", when (appearance) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" }) {
                openPicker("主题模式", appearance, listOf(PickerOption("system", "跟随系统"), PickerOption("light", "浅色"), PickerOption("dark", "深色"))) {
                    appearance = it; persistString("appearance", it)
                }
            }
            ThemeDivider()
            ThemeSwitchRow(Icons.Rounded.Contrast, "深色纯黑背景", "OLED 模式使用 #000000", pureBlack) {
                pureBlack = it; persistBoolean("pureBlackDark", it)
            }
            ThemeDivider()
            ThemeSwitchRow(Icons.Rounded.Palette, "Monet 动态取色", "Android 12+ 读取系统动态色", monet) {
                monet = it; persistBoolean("enableMonet", it)
            }
        } }

        item { ThemeSection("颜色") {
            ThemeValueRow(Icons.Rounded.ColorLens, "色彩风格", palette.replace("TonalSpot", "Tonal Spot").replace("FruitSalad", "Fruit Salad")) {
                val values = listOf("TonalSpot", "Neutral", "Vibrant", "Expressive", "Rainbow", "FruitSalad", "Monochrome", "Fidelity")
                openPicker("色彩风格", palette, values.map { PickerOption(it, it.replace("TonalSpot", "Tonal Spot").replace("FruitSalad", "Fruit Salad")) }) {
                    palette = it; persistString("colorPalette", it)
                }
            }
            ThemeDivider()
            ThemeValueRow(Icons.Rounded.AutoAwesome, "色彩标准", if (standard == "Material3_Expressive_2025") "Material 3 Expressive 2025" else "Material 3 2021") {
                openPicker("色彩标准", standard, listOf(PickerOption("Material3_2021", "Material 3 2021"), PickerOption("Material3_Expressive_2025", "Material 3 Expressive 2025"))) {
                    standard = it; persistString("colorStandard", it)
                }
            }
            ThemeDivider()
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("强调色预设", color = t.textPrimary, style = MaterialTheme.typography.bodyMedium)
                val accents = listOf("#2563EB", "#EF4444", "#EC4899", "#8B5CF6", "#6D28D9", "#4F46E5", "#0EA5E9", "#14B8A6")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    accents.forEach { hex ->
                        val color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Blue)
                        Box(
                            Modifier.size(if (accent == hex) 34.dp else 30.dp)
                                .background(color, CircleShape)
                                .clickable {
                                    accent = hex
                                    persistString("accentHex", hex)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (accent == hex) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        } }

        item { ThemeSection("玻璃与导航") {
            ThemeSwitchRow(Icons.Rounded.BlurOn, "模糊效果总开关", "控制应用内磨砂与背景模糊", blur) {
                blur = it; persistBoolean("enableBlur", it)
            }
            ThemeDivider()
            ThemeSwitchRow(Icons.Rounded.SpaceBar, "悬浮底栏", "关闭后吸附到屏幕底部", floating) {
                floating = it; persistBoolean("floatingBottomBar", it)
            }
            ThemeDivider()
            ThemeSwitchRow(Icons.Rounded.WaterDrop, "底栏液态玻璃", "启用磨砂折射渐变外壳", liquid) {
                liquid = it; persistBoolean("liquidGlass", it)
            }
            ThemeDivider()
            ThemeSwitchRow(Icons.Rounded.Link, "显示面板入口", "关闭后底栏保留首页 / 策略 / 工具 / 设置", panelTab) {
                panelTab = it; persistBoolean("showPanelTab", it, false)
            }
        } }

        item { ThemeSection("交互") {
            ThemeSwitchRow(Icons.AutoMirrored.Rounded.ArrowBack, "预测式返回动画", "节点详情随系统返回手势平滑退出", predictiveBack) {
                predictiveBack = it
                persistBoolean("predictiveBackAnimation", it, false)
            }
            ThemeDivider()
            ThemeSwitchRow(Icons.Rounded.Swipe, "跟随手势边缘", "从右侧返回时动画方向同步反转", predictiveBackFollowEdge) {
                predictiveBackFollowEdge = it
                persistBoolean("predictiveBackFollowEdge", it, false)
            }
            ThemeDivider()
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ZoomIn, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("界面缩放", color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${(scale * 100).toInt()}%", color = t.textSecondary, style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = scale,
                    onValueChange = { scale = it.coerceIn(.8f, 1.2f) },
                    onValueChangeFinished = {
                        prefs.edit().putFloat("uiScale", scale).apply()
                        onThemeChanged()
                    },
                    valueRange = .8f..1.2f,
                )
            }
        } }

        item {
            Text(
                "主题设置只改变界面层，不修改代理核心、规则或订阅。这里展示的开关都直接对应当前界面行为；不可用数据仍显示为 —。",
                color = t.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }

    pickerTitle?.let { title ->
        ModalBottomSheet(
            onDismissRequest = { pickerTitle = null },
            containerColor = Color.Transparent,
            contentColor = t.textPrimary,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(topStart = HetuGlassRadius.Sheet, topEnd = HetuGlassRadius.Sheet),
            dragHandle = {
                Box(
                    Modifier.padding(top = 10.dp, bottom = 7.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .background(t.textMuted.copy(alpha = .40f), CircleShape),
                )
            },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .crystalMaterial(
                        RoundedCornerShape(topStart = HetuGlassRadius.Sheet, topEnd = HetuGlassRadius.Sheet),
                        depth = CrystalDepth.Popover,
                    )
                    .navigationBarsPadding()
                    .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title, color = t.textPrimary, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.ExtraBold)
                Text("选择后立即生效", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                pickerOptions.forEach { option ->
                    val selected = option.value == pickerSelected
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .crystalMaterial(
                                RoundedCornerShape(HetuGlassRadius.Input),
                                depth = CrystalDepth.InsetItem,
                                selection = selected,
                            )
                            .clickable {
                                pickerSelected = option.value
                                pickerApply(option.value)
                                pickerTitle = null
                            },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(option.label, Modifier.weight(1f), color = t.textPrimary, fontWeight = FontWeight.SemiBold)
                            if (selected) {
                                Box(
                                    Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .11f), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun ThemeSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalHetuTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = t.textSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp))
        GroupedInsetSection(content = content)
    }
}

@Composable
private fun ThemeValueRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        HetuListIcon(icon)
        Spacer(Modifier.width(12.dp))
        Text(title, color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = t.textMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ThemeSwitchRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val t = LocalHetuTokens.current
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        HetuListIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = t.textPrimary, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
        LiquidSwitch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun ThemeDivider() {
    HorizontalDivider(color = LocalHetuTokens.current.outline)
}
