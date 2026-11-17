package io.github.achaii.moonbridge

/**
 * 水月雨型号档案库 —— 与上游 `AncProfileLib` / `DcProfile` 逐条对齐。
 *
 * 数据全部照参考实现（FxxkMoondrop 的 AncProfileLib、DcProfile）的**实测记录**抄，
 * 每条都能追溯到某次真机抓包，不是我推断的：
 *
 *  - GOLDEN AGES 2：SET 1=关 2=降 4=透传 3=抗风；GET 0-based 直传；空间音频 ✅；三档增益（反向 [2,1,0]）
 *  - SPACE TRAVEL 2：SET/GET 与 GA2 相同；无空间音频；三档增益（反向）
 *  - ROBIN（知更鸟）：只有「降噪(dev=1)」「透传(dev=2)」两档，关/抗风不存在 → -1；无 DC 能力
 *  - PUDDING（布丁）：走 ANC_V2（feature 0x20），SET [0,4,2,3,1,-1] / GET [0,4,2,3,1]；增益恒等；有 LED
 *  - NEKOCAKE / PILL：9ECA 蓝讯系，增益恒等 0/1/2，无 LED、无空间音频
 *  - MOCA（猫咖）：增益恒等，有 LED，无空间音频
 *
 * 约定：
 *  - `setMap` 下标 = UI 档位（0 关 / 1 降噪 / 2 透传 / 3 抗风噪），值 = 设备码，-1 = 该档设备没有
 *  - `getMap` 下标 = 设备码，值 = UI 档位；为 null 时用 setMap 反查
 *  - `ancV2Set/Get` 只在设备走 ANC_V2（feature 0x20）时用，多出「4 自适应」「5 直播」两档
 *  - `spatial` 来自 DC 档案的 hasSpatial（目前只有 GA2 为 true）
 *  - `hasGain/hasLed/gainMap` 来自 DC 档案；`gainMap` 下标 = 增益档（0 低 / 1 中 / 2 高），值 = 设备码
 *  - `ancHint` 只在探测拿不到结果时兜底（探测优先，见 PanelInjector.ancFeature()）
 */
object MoondropProfile {
    class Profile(
        val key: String,
        val setMap: IntArray,
        val getMap: IntArray? = null,
        val spatial: Boolean = false,
        val ancHint: Int = 8,
        /** ANC_V2（0x20）通道的 SET 映射，6 槽：[关,降,透,抗,自适应,直播] */
        val ancV2Set: IntArray? = null,
        /** ANC_V2 通道的 GET 映射（dev → UI） */
        val ancV2Get: IntArray? = null,
        val hasGain: Boolean = false,
        val hasLed: Boolean = false,
        /** 增益档 → 设备码（0 低 / 1 中 / 2 高），默认反向 [2,1,0] */
        val gainMap: IntArray = intArrayOf(2, 1, 0)
    )

    private val PROFILES = listOf(
        // 梦回二 —— 官方 App 抓包 + 双向实测（SET 与 GET 是两套编号，不能互换）
        Profile(
            "GOLDEN AGES 2", intArrayOf(1, 2, 4, 3), intArrayOf(0, 1, 2, 3),
            spatial = true, hasGain = true, hasLed = false, gainMap = intArrayOf(2, 1, 0)
        ),
        // 太空漫游 2 —— 同一套映射；增益反向（实测 0=高 / 2=低）
        Profile(
            "SPACE TRAVEL 2", intArrayOf(1, 2, 4, 3), intArrayOf(0, 1, 2, 3),
            hasGain = true, hasLed = false, gainMap = intArrayOf(2, 1, 0)
        ),
        // 知更鸟 —— 只有降噪/透传两档；-1 的槽位 UI 直接不显示，免得长出死按钮
        Profile("ROBIN", intArrayOf(-1, 1, 2, -1)),
        // 布丁 —— ANC_V2 路径（6 档：多出「自适应」，「直播」设备不支持 → -1）
        Profile(
            "PUDDING", intArrayOf(0, 4, 2, 3), intArrayOf(0, 4, 2, 3, 1),
            ancHint = 0x20,
            ancV2Set = intArrayOf(0, 4, 2, 3, 1, -1),
            ancV2Get = intArrayOf(0, 4, 2, 3, 1),
            hasGain = true, hasLed = true, gainMap = intArrayOf(0, 1, 2)
        ),
        // 其余已知型号：ANC 未实测 → 名义映射；增益恒等，部分有 LED
        Profile(
            "NEKOCAKE", intArrayOf(1, 2, 3, 4),
            hasGain = true, hasLed = false, gainMap = intArrayOf(0, 1, 2)
        ),
        Profile(
            "MOCA", intArrayOf(1, 2, 3, 4),
            hasGain = true, hasLed = true, gainMap = intArrayOf(0, 1, 2)
        ),
        Profile(
            "PILL", intArrayOf(1, 2, 3, 4),
            hasGain = true, hasLed = false, gainMap = intArrayOf(0, 1, 2)
        )
    )

    /** 未命中任何档案时用的名义映射（协议名义编码：1=关 2=降 3=透 4=抗） */
    val DEFAULT = Profile("默认", intArrayOf(1, 2, 3, 4))

    /** 所有已知型号的 key（顺序同档案表），给模块界面选型号用 */
    fun keys(): List<String> = PROFILES.map { it.key }

    /** 按设备名（大写 contains）找档案；找不到返回 null */
    fun of(name: String?): Profile? {
        val n = name?.uppercase()?.trim()
        if (n.isNullOrEmpty()) return null
        for (p in PROFILES) if (n.contains(p.key)) return p
        return null
    }

    fun ofOrDefault(name: String?): Profile = of(name) ?: DEFAULT

    /**
     * 是不是水月雨设备 —— 面板注入的唯一门禁。
     * 规则照参考实现：设备名含 "MOONDROP"，或命中任一已知型号档案。
     * 连名字都拿不到时返回 false（宁可不动别人家的面板）。
     */
    fun isMoondrop(name: String?): Boolean {
        val n = name?.uppercase()?.trim()
        if (n.isNullOrEmpty()) return false
        if (n.contains("MOONDROP")) return true
        return of(n) != null
    }

    /**
     * 该型号实际支持的 UI 档位。
     * @param ancV2 当前链路走的是否 ANC_V2（feature 0x20）；是则用 6 槽表，否则 4 槽
     */
    fun supportedUi(p: Profile, ancV2: Boolean = false): IntArray {
        val map = if (ancV2) p.ancV2Set else null
        val n = map?.size ?: 4
        val out = ArrayList<Int>(n)
        for (ui in 0 until n) {
            val v = map?.getOrNull(ui) ?: p.setMap.getOrNull(ui) ?: -1
            if (v >= 0) out.add(ui)
        }
        return out.toIntArray()
    }

    /** UI 档位 → 设备码；-1 = 不支持 */
    fun devForUi(p: Profile, ui: Int, ancV2: Boolean = false): Int {
        p.ancV2Set?.let { if (ancV2) return it.getOrNull(ui) ?: -1 }
        return p.setMap.getOrNull(ui) ?: -1
    }

    /** 设备码 → UI 档位；-1 = 认不出 */
    fun uiForDev(p: Profile, dev: Int, ancV2: Boolean = false): Int {
        if (ancV2) {
            p.ancV2Get?.let { m ->
                val i = m.indexOf(dev)
                if (i >= 0) return i
            }
            p.ancV2Set?.let { m ->
                val i = m.indexOf(dev)
                if (i >= 0) return i
            }
            return -1
        }
        p.getMap?.let { m ->
            val i = m.indexOf(dev)
            if (i >= 0) return i
        }
        return p.setMap.indexOf(dev)
    }

    /** UI 档位名（0 关 / 1 降噪 / 2 透传 / 3 抗风噪 / 4 自适应 / 5 直播） */
    fun uiName(ui: Int): String = when (ui) {
        0 -> "关闭"
        1 -> "降噪"
        2 -> "透传"
        3 -> "抗风噪"
        4 -> "自适应"
        5 -> "直播"
        else -> "档$ui"
    }

    /** 增益档名（0 低 / 1 中 / 2 高） */
    fun gainName(index: Int): String = when (index) {
        0 -> "低"
        1 -> "中"
        2 -> "高"
        else -> "档$index"
    }
}