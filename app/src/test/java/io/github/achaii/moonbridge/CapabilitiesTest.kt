package io.github.achaii.moonbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 能力位图解析（格式照抄上游 GaiaCommands.parseSupportedFeatures，用例自己造的） */
class CapabilitiesTest {

    /** 位图字：feature 18 落在第 0 个字的 bit18 */
    @Test
    fun bitmapWord_singleFeature() {
        val payload = byteArrayOf(0x00, 0x04, 0x00, 0x00) // 1 shl 18
        val f = Capabilities.parseFeatures(payload)
        assertTrue(18 in f)
        assertEquals(1, f.size)
        assertTrue(Capabilities.hasSpatial(f))
    }

    /** 两个字：word0 = feature 1，word1 = feature 32+8 = 40 */
    @Test
    fun bitmapWord_twoWords() {
        val payload = byteArrayOf(
            0x00, 0x00, 0x00, 0x02, // 1 shl 1 -> feature 1
            0x00, 0x00, 0x01, 0x00  // 1 shl 8 -> feature 32+8 = 40
        )
        val f = Capabilities.parseFeatures(payload)
        assertTrue(1 in f)
        assertTrue(40 in f)
        assertEquals(2, f.size)
    }

    /** 配对表：[more=0][18][flags] → 只有 18 */
    @Test
    fun pairList_singlePage() {
        val payload = byteArrayOf(0x00, 18, 0x01)
        val f = Capabilities.parseFeatures(payload)
        assertEquals(setOf(18), f)
        assertFalse(Capabilities.hasMorePages(payload))
    }

    /** 配对表：[more=1][8][x][32][x] → 8 与 32，且提示还有下一页 */
    @Test
    fun pairList_morePages() {
        val payload = byteArrayOf(0x01, 8, 0x00, 32, 0x00)
        val f = Capabilities.parseFeatures(payload)
        assertEquals(setOf(8, 32), f)
        assertTrue(Capabilities.hasMorePages(payload))
    }

    /** ANC 路径优先级：AudioCuration > ANC_V2 > ANC_V1 */
    @Test
    fun ancPath_precedence() {
        assertEquals(8, Capabilities.ancPathOf(setOf(8, 32, 2)))
        assertEquals(32, Capabilities.ancPathOf(setOf(32, 2)))
        assertEquals(2, Capabilities.ancPathOf(setOf(2)))
        assertEquals(0, Capabilities.ancPathOf(setOf(18)))
        assertEquals(0, Capabilities.ancPathOf(emptySet()))
    }

    @Test
    fun emptyPayload_isUnknownNotEmpty() {
        assertTrue(Capabilities.parseFeatures(ByteArray(0)).isEmpty())
        assertTrue(Capabilities.parseFeatures(null).isEmpty())
        // 空集只代表「还没收到」，hasSpatial 自然是 false
        assertFalse(Capabilities.hasSpatial(emptySet()))
    }
}