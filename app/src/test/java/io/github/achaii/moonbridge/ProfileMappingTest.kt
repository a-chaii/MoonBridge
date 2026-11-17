package io.github.achaii.moonbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 型号档案映射的单元测试（移植自 FxxkMoondrop 的 AncProfileLibTest 思路）。
 *
 * 没有实机时，这是唯一能防「数组顺序抄错 → 全系错档」的手段：
 * 每个断言都对应一次真机抓包记录，改代码时跑一遍就知道有没有把表改坏。
 */
class ProfileMappingTest {

    // ---------------- 梦回 2 / 太空漫游 2：SET 与 GET 是两套编号 ----------------

    @Test
    fun goldenAges2_setMap() {
        val p = MoondropProfile.of("MOONDROP Golden Ages 2")!!
        assertEquals("GOLDEN AGES 2", p.key)
        // UI 关/降/透/抗 → 设备码 1/2/4/3
        assertArrayEquals(intArrayOf(1, 2, 4, 3), p.setMap)
    }

    @Test
    fun goldenAges2_getMap() {
        val p = MoondropProfile.of("Golden Ages 2")!!
        // 设备回报码 → UI 档位（直传）
        assertEquals(0, MoondropProfile.uiForDev(p, 0))
        assertEquals(1, MoondropProfile.uiForDev(p, 1))
        assertEquals(2, MoondropProfile.uiForDev(p, 2))
        assertEquals(3, MoondropProfile.uiForDev(p, 3))
    }

    @Test
    fun goldenAges2_supportsAllFourModes() {
        val p = MoondropProfile.of("MOONDROP Golden Ages 2")!!
        assertArrayEquals(intArrayOf(0, 1, 2, 3), MoondropProfile.supportedUi(p))
    }

    @Test
    fun spaceTravel2_sameAsGoldenAges2() {
        val p = MoondropProfile.of("MOONDROP Space Travel 2")!!
        assertEquals("SPACE TRAVEL 2", p.key)
        assertArrayEquals(intArrayOf(1, 2, 4, 3), p.setMap)
        // 9ECA 私有协议，本轮只保证 UI 不误导
        assertFalse(p.spatial)
    }

    // ---------------- 知更鸟：只有两档 ----------------

    @Test
    fun robin_onlyNoiseCancellingAndTransparency() {
        val p = MoondropProfile.of("MOONDROP Robin")!!
        assertEquals("ROBIN", p.key)
        assertArrayEquals(intArrayOf(-1, 1, 2, -1), p.setMap)
        // 关 / 抗风噪 不存在 → UI 里不能长出死按钮
        assertArrayEquals(intArrayOf(1, 2), MoondropProfile.supportedUi(p))
        assertEquals(-1, MoondropProfile.devForUi(p, 0))
        assertEquals(-1, MoondropProfile.devForUi(p, 3))
    }

    // ---------------- 布丁：ANC_V2 路径，编码语义与 AudioCuration 不同 ----------------

    @Test
    fun pudding_ancV2Mapping() {
        val p = MoondropProfile.of("MOONDROP Pudding")!!
        assertEquals("PUDDING", p.key)
        assertEquals(0x20, p.ancHint)
        assertArrayEquals(intArrayOf(0, 4, 2, 3), p.setMap)
        // getMap 里多一个 1 = 自适应（UI 没有这一档），不能把它错认成「降噪」
        assertEquals(1, MoondropProfile.uiForDev(p, 4))
        assertEquals(2, MoondropProfile.uiForDev(p, 2))
        assertEquals(3, MoondropProfile.uiForDev(p, 3))
        assertEquals(0, MoondropProfile.uiForDev(p, 0))
        assertEquals(4, MoondropProfile.uiForDev(p, 1))
    }

    // ---------------- 未实测型号：名义映射，不猜功能 ----------------

    @Test
    fun unknownKnownModel_usesNominalMapping() {
        val p = MoondropProfile.of("MOONDROP NEKOCAKE")!!
        assertEquals("NEKOCAKE", p.key)
        assertArrayEquals(intArrayOf(1, 2, 3, 4), p.setMap)
        assertFalse("未实测型号不应声称支持空间音频", p.spatial)
    }

    @Test
    fun unknownName_fallsBackToDefault() {
        val p = MoondropProfile.ofOrDefault("Something Else")
        assertEquals("默认", p.key)
        assertArrayEquals(intArrayOf(1, 2, 3, 4), p.setMap)
        assertTrue(MoondropProfile.supportedUi(p).isNotEmpty())
    }

    // ---------------- 门禁：别把别人家的耳机当成水月雨 ----------------

    @Test
    fun isMoondrop_acceptsBrandAndKnownModels() {
        assertTrue(MoondropProfile.isMoondrop("MOONDROP Golden Ages 2"))
        assertTrue(MoondropProfile.isMoondrop("moondrop space travel 2"))
        assertTrue(MoondropProfile.isMoondrop("Robin"))       // 命中档案
        assertTrue(MoondropProfile.isMoondrop("Pudding"))
    }

    @Test
    fun isMoondrop_rejectsOthers() {
        assertFalse(MoondropProfile.isMoondrop(null))
        assertFalse(MoondropProfile.isMoondrop(""))
        assertFalse(MoondropProfile.isMoondrop("AirPods Pro"))
        assertFalse(MoondropProfile.isMoondrop("WH-1000XM5"))
    }

    // ---------------- 往返一致性 ----------------

    /**
     * 往返一致性 —— 只对「SET 与 GET 共用一张表」的型号成立。
     *
     * 梦回 2 / 太空漫游 2 是**两套编号**（SET 1=关 2=降 4=透 3=抗；GET 0=关 1=降 2=透 3=抗），
     * 所以 devForUi(0)=1 而 uiForDev(1)=1，往返必然不闭合 —— 这不是 bug，是协议本身的样子。
     */
    @Test
    fun roundTrip_onlyForSingleTableProfiles() {
        for (key in MoondropProfile.keys()) {
            val p = MoondropProfile.of(key)!!
            if (p.getMap != null) continue // 两套编号的型号不做往返断言
            for (ui in MoondropProfile.supportedUi(p)) {
                val dev = MoondropProfile.devForUi(p, ui)
                assertEquals("$key: UI $ui → 设备码 → UI 应回到自身", ui, MoondropProfile.uiForDev(p, dev))
            }
        }
    }

    /** 两套编号的型号：按抓包记录固定断言，防止有人"顺手统一"成一张表 */
    @Test
    fun dualTableProfiles_keepBothDirections() {
        for (key in listOf("GOLDEN AGES 2", "SPACE TRAVEL 2")) {
            val p = MoondropProfile.of(key)!!
            assertEquals("$key: UI 关 → SET 1", 1, MoondropProfile.devForUi(p, 0))
            assertEquals("$key: UI 降噪 → SET 2", 2, MoondropProfile.devForUi(p, 1))
            assertEquals("$key: UI 透传 → SET 4", 4, MoondropProfile.devForUi(p, 2))
            assertEquals("$key: UI 抗风噪 → SET 3", 3, MoondropProfile.devForUi(p, 3))
            assertEquals("$key: GET 0 → UI 关", 0, MoondropProfile.uiForDev(p, 0))
            // GET 表里没有 4，会走「setMap 反查」兜底 → 命中 SET 4 = 透传（UI 2）
            assertEquals("$key: GET 4 走 SET 反查兜底", 2, MoondropProfile.uiForDev(p, 4))
        }
    }

    @Test
    fun uiNames_areStable() {
        assertEquals("关闭", MoondropProfile.uiName(0))
        assertEquals("降噪", MoondropProfile.uiName(1))
        assertEquals("透传", MoondropProfile.uiName(2))
        assertEquals("抗风噪", MoondropProfile.uiName(3))
    }

    // ---- 上游 DcProfile 同步：增益 / LED / ANC_V2 六档 ----

    @Test
    fun dcCapabilities_matchUpstream() {
        assertTrue(MoondropProfile.of("GOLDEN AGES 2")!!.hasGain)
        assertFalse(MoondropProfile.of("GOLDEN AGES 2")!!.hasLed)
        assertTrue(MoondropProfile.of("PUDDING")!!.hasLed)
        assertFalse(MoondropProfile.of("ROBIN")!!.hasGain)
    }

    @Test
    fun gainMaps_arePerModel() {
        // GA2 / 太空漫游 2：0=高 1=中 2=低（反向）
        assertArrayEquals(intArrayOf(2, 1, 0), MoondropProfile.of("GOLDEN AGES 2")!!.gainMap)
        assertArrayEquals(intArrayOf(2, 1, 0), MoondropProfile.of("SPACE TRAVEL 2")!!.gainMap)
        // 布丁等：恒等
        assertArrayEquals(intArrayOf(0, 1, 2), MoondropProfile.of("PUDDING")!!.gainMap)
    }

    @Test
    fun ancV2_supportedUi_hasAdaptiveButNoLive() {
        val p = MoondropProfile.of("PUDDING")!!
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4), MoondropProfile.supportedUi(p, ancV2 = true))
        assertEquals(1, MoondropProfile.devForUi(p, 4, ancV2 = true))
        assertEquals(4, MoondropProfile.uiForDev(p, 1, ancV2 = true))
        assertEquals(-1, MoondropProfile.devForUi(p, 5, ancV2 = true))
    }
}