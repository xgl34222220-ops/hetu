package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual controller errors retain their text and cannot receive the success check/color. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToastFeedbackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun prepare() {
        ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("hetu", 0)
            .edit().clear().putString("appearance", "light").putBoolean("enableBlur", false).commit()
    }

    @Test fun actualControllerHttpExceptionsAreErrorsForClientAndServerResponses() {
        listOf(401, 403, 404, 503).forEach { code ->
            val message = MihomoControllerClient.ControllerHttpException(code, "Unauthorized", false).message!!
            assertEquals(message, HxTone.Bad, hxToastTone(message))
        }
    }

    @Test fun httpErrorContextDoesNotMistakeSuccessfulCountsOrResponseNumbersForErrors() {
        listOf("HTTP 401", "HTTP/1.1 503 Service Unavailable", "请求返回 HTTP:403")
            .forEach { assertEquals(it, HxTone.Bad, hxToastTone(it)) }
        listOf("已导入 401 个节点", "已更新 503 个订阅", "已导出 HTTP 200 日志", "已导出 HTTP 401.yaml",
            "已导出 Mihomo 控制接口返回 401.yaml", "Saved 401 entries")
            .forEach { assertEquals(it, HxTone.Good, hxToastTone(it)) }
        assertEquals(HxTone.Neutral, hxToastTone("HTTP 200"))
        assertEquals(HxTone.Neutral, hxToastTone("控制接口返回 4010"))
    }

    @Test fun englishAuthenticationAndTransportErrorsDoNotClaimSuccess() {
        listOf("Unauthorized", "Error: access denied", "Failed to connect", "Read timed out", "Connection refused")
            .forEach { assertEquals(it, HxTone.Bad, hxToastTone(it)) }
        // A saved filename is not an English error message.
        assertEquals(HxTone.Good, hxToastTone("Saved error.log"))
    }

    @Test fun genuineSuccessWarningsAndUnknownNoticesKeepDistinctMeanings() {
        listOf("已保存", "代理已启动", "校验通过", "已断开全部连接", "运行配置已热重载",
            "已热更新 12 条广告规则（核心已加载）", "开机脚本已安装，重启后自动启动代理", "Updated successfully")
            .forEach { assertEquals(it, HxTone.Good, hxToastTone(it)) }
        assertEquals(HxTone.Warn, hxToastTone("已保存，重启代理后生效"))
        assertEquals(HxTone.Bad, hxToastTone("切换失败：已更新设置但核心未确认"))
        assertEquals(HxTone.Neutral, hxToastTone("当前配置没有在线订阅"))
    }

    private fun showThroughRealHost(message: String) {
        val vm = HetuViewModel(ApplicationProvider.getApplicationContext<Application>())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HetuAppTheme(appearance = "light", dynamic = false) {
                Box(Modifier.fillMaxSize()) { HxToastHost(vm, extraBottom = 0.dp) }
            }
        }
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
        rule.runOnIdle { vm.toast(message) }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithText(message).assertExists()
    }

    private fun statusPixelCounts(): Pair<Int, Int> {
        val bitmap = rule.onNodeWithTag("toast-status", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        var red = 0
        var green = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
            if (r > g * 1.3 && r > b * 1.3) red++
            if (g > r * 1.3 && g > b * 1.3) green++
        }
        return red to green
    }

    @Test fun realToastHostPreservesThe401BodyAndRendersAnErrorInsteadOfAGreenCheck() {
        val message = "Mihomo 控制接口返回 401：{\"message\":\"Unauthorized\"}"
        showThroughRealHost(message)
        val (red, green) = statusPixelCounts()
        assertTrue("Expected the actual error icon color", red > 5)
        assertEquals("An HTTP error must not draw a green success status", 0, green)
    }

    @Test fun realToastHostKeepsSuccessfulCountMessagesAndTheirGreenCheck() {
        showThroughRealHost("已导入 401 个节点")
        val (red, green) = statusPixelCounts()
        assertTrue("Expected the actual success icon color", green > 5)
        assertEquals(0, red)
    }
}
