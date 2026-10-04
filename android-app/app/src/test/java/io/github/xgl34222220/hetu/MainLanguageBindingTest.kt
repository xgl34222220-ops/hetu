package io.github.xgl34222220.hetu

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.ui.ht
import io.github.xgl34222220.hetu.ui.translateHetuText
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** Production theme, settings chooser, navigation and raw-content boundaries. No core is started. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainLanguageBindingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", 0)
    private var vm: HetuViewModel? = null

    @Before fun prepare() {
        prefs.edit().clear().putString("appLanguage", "zh-CN")
            .putBoolean("enableBlur", false).putBoolean("enableAnimations", false)
            .putBoolean("liquidGlass", false).putBoolean("predictiveBackAnimation", false).commit()
        rule.mainClock.autoAdvance = false
    }

    @After fun finish() {
        rule.runOnUiThread { rule.activity.setContent {} }
        frame()
        vm?.onBackground()
    }

    private fun frame() {
        repeat(4) {
            rule.mainClock.advanceTimeBy(200)
            rule.runOnUiThread { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        }
    }

    private fun show(content: @Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.setContent { HetuAppTheme("light", false, content = content) } }
        frame()
    }

    private fun nodes(): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun visitNode(node: SemanticsNode) { result += node; node.children.forEach(::visitNode) }
        fun visitView(view: View) {
            view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" }?.let {
                visitNode((it.invoke(view) as SemanticsOwner).rootSemanticsNode)
            }
            if (view is ViewGroup) (0 until view.childCount).forEach { visitView(view.getChildAt(it)) }
        }
        WindowInspector.getGlobalWindowViews().asReversed().forEach(::visitView)
        return result
    }

    private fun labels(node: SemanticsNode) =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text)

    private fun assertVisible(label: String) = rule.runOnUiThread {
        assertTrue("Missing '$label'; visible labels: ${nodes().flatMap(::labels)}", nodes().any { label in labels(it) })
    }

    private fun click(label: String) {
        rule.runOnUiThread {
            val node = nodes().firstOrNull { label in labels(it) && it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertNotNull("No clickable '$label'; visible labels: ${nodes().flatMap(::labels)}", node)
            assertTrue(node!!.config[SemanticsActions.OnClick].action!!.invoke())
        }
        frame()
    }

    @Test fun chooserUpdatesMainNavigationAndSurvivesActivityRecreation() {
        val first = HetuViewModel(app).also { vm = it; it.tab = HxTab.Settings }
        show { HetuRoot(first) }
        click("语言与主题")
        click("语言")
        click("English")
        assertEquals("en", prefs.getString("appLanguage", null))
        assertVisible("Language")
        assertVisible("Appearance settings")
        click("Back")
        assertVisible("Language and appearance")
        assertVisible("Home")
        click("Tools")
        assertVisible("Files")
        assertVisible("View and manage app files")

        // A preference edit outside the current screen must invalidate the theme by itself.
        rule.runOnUiThread { prefs.edit().putString("appLanguage", "ru").apply() }
        frame()
        assertVisible("Инструменты")
        assertVisible("Файлы")
        rule.runOnUiThread { prefs.edit().putString("appLanguage", "zh-TW").apply() }
        frame()
        assertVisible("檔案管理")

        rule.activityRule.scenario.recreate()
        first.onBackground()
        val restarted = HetuViewModel(app).also { vm = it; it.tab = HxTab.Settings }
        show { HetuRoot(restarted) }
        assertEquals("zh-TW", prefs.getString("appLanguage", null))
        assertVisible("設定")
        assertVisible("語言與主題")
    }

    @Test fun systemLocaleChangesAndBlankPreferenceFallBackToConfiguration() {
        val configuration = mutableStateOf(Configuration(app.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags("en-US"))
        })
        rule.runOnUiThread { prefs.edit().putString("appLanguage", "system").apply() }
        show { CompositionLocalProvider(LocalConfiguration provides configuration.value) { Text(ht("首页")) } }
        assertVisible("Home")
        rule.runOnUiThread {
            configuration.value = Configuration(configuration.value).apply { setLocales(LocaleList.forLanguageTags("ru-RU")) }
        }
        frame()
        assertVisible("Главная")
        rule.runOnUiThread { prefs.edit().putString("appLanguage", "zh-CN").apply() }
        frame()
        assertVisible("首页")
        rule.runOnUiThread { prefs.edit().putString("appLanguage", "").apply() }
        frame()
        assertVisible("Главная")
        rule.runOnUiThread { prefs.edit().remove("appLanguage").apply() }
        frame()
        assertVisible("Главная")
    }

    @Test fun userNamesInputsAndDiagnosticsStayVerbatimEvenWhenTheyMatchVocabulary() {
        rule.runOnUiThread { prefs.edit().putString("appLanguage", "en").apply() }
        var userName = "首页"
        show { Column {
            // Config/group/node/file names can legitimately collide with any vocabulary entry.
            SettingsRow("工具", subtitle = "首页")
            HxRow("保存", subtitle = "语言")
            SettingsInput(userName, { userName = it })
        } }
        listOf("工具", "首页", "保存", "语言").forEach(::assertVisible)
        rule.runOnUiThread {
            assertFalse(nodes().flatMap(::labels).any { it in listOf("Tools", "Home", "Save", "Language") })
            val field = nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("设置")))
        }
        assertEquals("设置", userName)
        show { HxPage(title = "首页") { item { Text("工具") } } }
        assertVisible("首页")
        assertVisible("工具")
        // This viewer also renders logs, diagnostics and generated YAML. Never translate its body.
        show { HxTextSheet(title = "工具", text = "首页", onDismiss = {}) }
        assertVisible("工具")
        assertVisible("首页")
        val yaml = "proxy-groups:\n  - name: 工具\n    proxies: [首页, 设置]\n"
        show { HxTextSheet(title = "诊断", text = yaml, onDismiss = {}) }
        assertVisible(yaml)
    }

    @Test fun vocabularySupportsAllFourLanguagesWithoutTranslatingUnknownText() {
        assertEquals("首页", translateHetuText("首页", "zh-CN"))
        assertEquals("首頁", translateHetuText("首页", "zh-TW"))
        assertEquals("首頁", translateHetuText("首页", "zh-Hant-HK"))
        assertEquals("Home", translateHetuText("首页", "en-US"))
        assertEquals("Главная", translateHetuText("首页", "ru-RU"))
        assertEquals("首页", translateHetuText("首页", "fr-FR"))
        val diagnostic = "Mihomo: 配置 tools.yaml\nproxy-groups: [首页]"
        listOf("zh-CN", "zh-TW", "en", "ru").forEach { assertEquals(diagnostic, translateHetuText(diagnostic, it)) }
    }
}
