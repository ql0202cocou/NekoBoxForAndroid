package io.nekohasekai.sagernet.widget.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 下层抓取的纯计算（几何键、偏移、可见性、重录判定、脉冲窗口），纯 JVM */
class GlassBackdropGeometryTest {

    private val key = BackdropKey(offsetX = 32, offsetY = 2164, width = 848, height = 168, scale = 0.5f)

    @Test
    fun offsetIsHostMinusSourceInWindowSpace() {
        assertEquals(32, BackdropGeometry.offset(hostInWindow = 32, sourceInWindow = 0))
        // 来源不在窗口原点（例如被插入区推开）时，偏移是两者之差
        assertEquals(-10, BackdropGeometry.offset(hostInWindow = 5, sourceInWindow = 15))
    }

    @Test
    fun statusCardTranslationChangesTheKey() {
        // 状态卡片滑动时窗口纵坐标变了，键随之变，必须重录
        val moved = key.copy(offsetY = key.offsetY + 40)
        assertTrue(BackdropGeometry.shouldRequestRecord(moved, recorded = key, requested = null))
    }

    @Test
    fun captureExtentRoundsUpAndNeverCollapses() {
        assertEquals(424, BackdropGeometry.captureExtent(848, 0.5f))
        assertEquals(85, BackdropGeometry.captureExtent(169, 0.5f))
        assertEquals(1, BackdropGeometry.captureExtent(1, 0.5f))
        assertEquals(1, BackdropGeometry.captureExtent(0, 0.5f))
        assertEquals(848, BackdropGeometry.captureExtent(848, 1f))
    }

    @Test
    fun scaleIsPartOfTheKey() {
        assertTrue(BackdropGeometry.shouldRequestRecord(key.copy(scale = 1f), recorded = key, requested = null))
    }

    @Test
    fun sameKeyNeverRequestsARecord() {
        assertFalse(BackdropGeometry.shouldRequestRecord(key, recorded = key, requested = null))
        assertFalse(BackdropGeometry.shouldRequestRecord(key.copy(), recorded = key, requested = null))
    }

    @Test
    fun hiddenHostNeverRequestsARecord() {
        assertFalse(BackdropGeometry.shouldRequestRecord(null, recorded = key, requested = null))
        assertFalse(BackdropGeometry.shouldRequestRecord(null, recorded = null, requested = null))
    }

    @Test
    fun anAlreadyRequestedKeyIsNotRequestedAgain() {
        // 宿主没有真的重绘时，每帧的检查不能反复失效它（否则永动循环）
        val moved = key.copy(offsetY = 100)
        assertTrue(BackdropGeometry.shouldRequestRecord(moved, recorded = key, requested = null))
        assertFalse(BackdropGeometry.shouldRequestRecord(moved, recorded = key, requested = moved))
        // 键再变就又要请求
        assertTrue(BackdropGeometry.shouldRequestRecord(moved.copy(offsetY = 101), recorded = key, requested = moved))
    }

    @Test
    fun firstRecordIsRequestedWhenNothingWasRecorded() {
        assertTrue(BackdropGeometry.shouldRequestRecord(key, recorded = null, requested = null))
    }

    @Test
    fun overlapNeedsSharedAreaNotJustAnEdge() {
        // 来源是 1080x2400；状态卡片（高 213）滑出到屏幕底边之外：与来源恰好贴边，不算
        fun overlaps(top: Int) = BackdropGeometry.overlaps(42, top, 996, 213, 0, 0, 1080, 2400)
        assertTrue(overlaps(1930))
        assertTrue(overlaps(2399))
        assertFalse(overlaps(2400))
        assertFalse(overlaps(3000))
        // 完全滑到上方、或横向在外
        assertFalse(BackdropGeometry.overlaps(42, -213, 996, 213, 0, 0, 1080, 2400))
        assertFalse(BackdropGeometry.overlaps(1080, 100, 996, 213, 0, 0, 1080, 2400))
        // 没有尺寸
        assertFalse(BackdropGeometry.overlaps(42, 100, 0, 213, 0, 0, 1080, 2400))
    }

    @Test
    fun pulseWindowCoversTheDurationAndOnlyEverExtends() {
        val w = PulseWindow()
        assertFalse(w.active(0))
        w.extend(now = 1_000, millis = 350)
        assertTrue(w.active(1_000))
        assertTrue(w.active(1_349))
        assertFalse(w.active(1_350))
        // 较短的请求不会缩短已有窗口；较长的会往后推
        w.extend(now = 1_100, millis = 100)
        assertTrue(w.active(1_349))
        w.extend(now = 1_300, millis = 350)
        assertTrue(w.active(1_649))
        assertFalse(w.active(1_650))
        w.clear()
        assertFalse(w.active(1_300))
    }
}
