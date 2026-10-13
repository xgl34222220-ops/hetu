package io.github.xgl34222220.hetu

import android.animation.ValueAnimator
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.home.HetuHomeTheme
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 2026-10 motion pass: the app's motion switch honours the system 「移除动画」 (animator duration
 * scale 0), and the shared Hx motion helpers render their final state at once when it is off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MotionPass107Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun prepare() {
        app.getSharedPreferences("hetu", 0).edit().clear().commit()
    }

    @After fun restoreScale() = setAnimatorScale(1f)

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    @Test fun zeroAnimatorScaleTurnsTheAppThemeMotionSwitchOff() {
        setAnimatorScale(0f)
        rule.setContent {
            HetuAppTheme(appearance = "light", dynamic = false) {
                Text(if (hxMotion()) "动效开启107" else "动效关闭107")
            }
        }
        rule.onNodeWithText("动效关闭107").assertExists()
    }

    @Test fun normalAnimatorScaleKeepsTheAppThemeMotionSwitchOn() {
        setAnimatorScale(1f)
        rule.setContent {
            HetuAppTheme(appearance = "light", dynamic = false) {
                Text(if (hxMotion()) "动效开启107" else "动效关闭107")
            }
        }
        rule.onNodeWithText("动效开启107").assertExists()
    }

    @Test fun inAppAnimationSwitchAlsoTurnsMotionOff() {
        app.getSharedPreferences("hetu", 0).edit().putBoolean("enableAnimations", false).commit()
        rule.setContent {
            HetuAppTheme(appearance = "light", dynamic = false) {
                Text(if (hxMotion()) "动效开启107" else "动效关闭107")
            }
        }
        rule.onNodeWithText("动效关闭107").assertExists()
    }

    @Test fun inlineRevealLeavesInTheSameMomentWithoutMotion() {
        val visible = mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                Column { HxVisibility(visible.value) { Text("横幅107", Modifier.testTag("banner107")) } }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("banner107").assertExists()
        visible.value = false
        rule.mainClock.advanceTimeBy(48)
        rule.onNodeWithTag("banner107").assertDoesNotExist()
    }

    @Test fun inlineRevealAnimatesItsExitWithMotion() {
        val visible = mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides true) {
                Column { HxVisibility(visible.value) { Text("横幅107", Modifier.testTag("banner107")) } }
            }
        }
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithTag("banner107").assertExists()
        visible.value = false
        rule.mainClock.advanceTimeBy(48)
        // Still on its way out (shrink + fade), not cut in one frame.
        rule.onNodeWithTag("banner107").assertExists()
        rule.mainClock.advanceTimeBy(1200)
        rule.onNodeWithTag("banner107").assertDoesNotExist()
    }

    @Test fun skeletonCarriesNoTextAndSettlesWithoutMotion() {
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuHomeTheme(dark = false) { HxSkeletonLines(count = 3, modifier = Modifier.testTag("skeleton107")) }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("skeleton107").assertExists()
        assertTrue(rule.onAllNodesWithText("", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun hierarchyTransitionIsInstantWithoutMotionAndDirectionalWithIt() {
        val still = hxHierarchyTransition(deeper = true, motion = false)
        assertEquals(EnterTransition.None, still.targetContentEnter)
        assertEquals(ExitTransition.None, still.initialContentExit)
        val moving = hxHierarchyTransition(deeper = true, motion = true)
        assertNotEquals(EnterTransition.None, moving.targetContentEnter)
        assertNotEquals(ExitTransition.None, moving.initialContentExit)
    }

    @Test fun ambientLoopsRunOnlyWhenActiveAndMotionAllowed() {
        assertTrue(hxAmbientAllowed(motion = true, active = true))
        assertFalse(hxAmbientAllowed(motion = false, active = true))
        assertFalse(hxAmbientAllowed(motion = true, active = false))
        for (i in 0 until 9) {
            listOf(false, true).forEach { second ->
                val w = hxSkeletonWidth(i, second)
                assertTrue(w > 0f && w <= 1f)
            }
        }
    }
}
