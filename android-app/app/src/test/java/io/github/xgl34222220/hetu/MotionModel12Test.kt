package io.github.xgl34222220.hetu

import androidx.compose.animation.core.SpringSpec
import androidx.compose.ui.unit.*
import io.github.xgl34222220.hetu.ui.crystalPopoverPosition
import org.junit.Assert.*
import org.junit.Test

class MotionModel12Test {
    @Test fun spatialSpecIsActuallyUnderdampedSpring() {
        val spec = HetuMotion12.spatial<Float>() as SpringSpec<Float>
        assertEquals(.84f, spec.dampingRatio, .0001f)
        assertEquals(280f, spec.stiffness, .0001f)
    }
    @Test fun reducedMotionIsImmediateRatherThanAnotherSlowSpring() {
        assertFalse(HetuMotion12.spatial<Float>(false) is SpringSpec<*>)
    }
    @Test fun sheetCoverageUsesMeasuredGeometryNotABoolean() {
        assertEquals(0f, sheetCoverage12(852f, 600f, 852f), 0f)
        assertEquals(.5f, sheetCoverage12(852f, 600f, 552f), .0001f)
        assertEquals(1f, sheetCoverage12(852f, 600f, 252f), 0f)
    }
    @Test fun coverageIsIndependentOfPixelDensity() {
        for (density in listOf(1f, 2f, 2.75f, 3f, 3.5f))
            assertEquals(.65f, sheetCoverage12(800f*density, 500f*density, 475f*density), .0001f)
    }
    @Test fun unmeasuredAndInvalidSheetNeverProduceNanOrNegativeOpacity() {
        assertEquals(0f, sheetCoverage12(0f, 0f, Float.NaN), 0f)
        assertEquals(0f, sheetCoverage12(800f, 0f, 200f), 0f)
        assertEquals(0f, sheetCoverage12(800f, 500f, 900f), 0f)
        assertEquals(1f, sheetCoverage12(800f, 500f, -30f), 0f)
        assertEquals(0f, sheetCoverage12(Float.POSITIVE_INFINITY, 500f, 0f), 0f)
    }
    @Test fun backgroundScaleRemainsBoundedAndFullyRestores() {
        assertEquals(.96f, backdropScale12(1f, true), .0001f)
        assertEquals(.98f, backdropScale12(.5f, true), .0001f)
        assertEquals(1f, backdropScale12(0f, true), 0f)
        assertEquals(1f, backdropScale12(1f, false), 0f)
    }
    @Test fun longerBottomPullNeverMovesBackwards() {
        var previous = 0f
        for (raw in 0..2000) {
            val next = bottomRubberBand12(raw.toFloat())
            assertTrue(next >= previous); assertTrue(next < 64f)
            previous = next
        }
    }
    @Test fun rubberBandResistanceIncreasesAsFingerTravelIncreases() {
        val early = bottomRubberBand12(20f) - bottomRubberBand12(10f)
        val middle = bottomRubberBand12(120f) - bottomRubberBand12(110f)
        val late = bottomRubberBand12(620f) - bottomRubberBand12(610f)
        assertTrue(early > middle); assertTrue(middle > late); assertTrue(late > 0f)
        assertEquals(0f, bottomRubberBand12(-1f), 0f)
        assertEquals(0f, bottomRubberBand12(Float.NaN), 0f)
    }
    @Test fun popupFlipsUpAndClampsInsideNarrowWindow() {
        val p = crystalPopoverPosition(IntRect(270,730,318,778), IntSize(320,800), IntSize(280,300), LayoutDirection.Ltr, 16, 6)
        assertTrue(p.x >= 16); assertTrue(p.x + 280 <= 304)
        assertTrue(p.y >= 16); assertTrue(p.y + 300 < 730)
    }
    @Test fun popupUsesLogicalEndInBothLayoutDirections() {
        val anchor = IntRect(140,100,188,148)
        val ltr = crystalPopoverPosition(anchor, IntSize(500,800), IntSize(120,180), LayoutDirection.Ltr, 16, 6)
        val rtl = crystalPopoverPosition(anchor, IntSize(500,800), IntSize(120,180), LayoutDirection.Rtl, 16, 6)
        assertEquals(68,ltr.x); assertEquals(140,rtl.x)
        assertEquals(154,ltr.y); assertEquals(ltr.y,rtl.y)
    }
}
