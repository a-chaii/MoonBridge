package io.github.achaii.moonbridge

/**
 * 水月雨型号档案库。
 *
 * 数据全部照参考实现（FxxkMoondrop 的 AncProfileLib / DcProfile）的**实测记录**抄，
 * 每条都能追溯到某次真机抓包，不是我推断的：
 *
 *  - GOLDEN AGES 2：SET 1=关 2=降 4=透传 3=抗风；GET 0-based 直传
 *  - SPACE TRAVEL 2：SET/GET 与 GA2 相同
 *  - ROBIN（知更鸟）：只有「降噪(dev=1)」「透传(dev=2)」两档，关/抗风不存在 → -1
 *  - PUDDING（布丁）：走 ANC_V2（feature 0x20），SET 0=关 4=降 2=透 3=抗 1=自适应
 *
 * 约定：
 *  - `setMap` 下标 = UI 档位（0 关 / 1 降噪 / 2 透传 / 3 抗风噪），值 = 设备码，-1 = 该档设备没有
 *  - `getMap` 下标 = 设备码，值 = UI 档位；为 null 时用 setMap 反查
 *  - `spatial` 来自 DC 档案的 hasSpatial（目前只有 GA2 为 true）
 *  - `ancHint` 只在探测拿不到结果时兜底（探测优先，见 PanelInjector.ancFeature()）
 */
object MoondropProfile {

    class Profile(
        val key: String,
        val setMap: IntArray,
        val getMap: IntArray? = null,
        val spatial: Boolean = false,
        val ancHint: Int = 8
    )

    private val PROFILES = listOf(
        // 梦回二 —— 官方 App 抓包 + 双向实测（SET 与 GET 是两套编号，不能互换）
        Profile("GOLDEN AGES 2", intArrayOf(1, 2, 4, 3), intArrayOf(0, 1, 2, 3), spatial = true),
        // 太空漫游 2 —— 同一套映射（该型号走 9ECA 私有协议，本轮只保证 UI 不误导）
        Profile("SPACE TRAVEL 2", intArrayOf(1, 2, 4, 3), intArrayOf(0, 1, 2, 3)),
        // 知更鸟 —— 只有降噪/透传两档；-1 的槽位 UI 直接不显示，免得长出死按钮
        Profile("ROBIN", intArrayOf(-1, 1, 2, -1)),
        // 布丁 —— ANC_V2 路径，设备码语义与 AudioCuration 不同
        Profile("PUDDING", intArrayOf(0, 4, 2, 3), intArrayOf(0, 4, 2, 3, 1), ancHint = 0x20),
        // 其余已知型号（DC 档案里存在，ANC 未实测）→ 用默认映射，只保证能被识别成水月雨
        Profile("NEKOCAKE", intArrayOf(1, 2, 3, 4)),
        Profile("MOCA", intArrayOf(1, 2, 3, 4)),
        Profile("PILL", intArrayOf(1, 2, 3, 4))
    )

    /** 未命中任何档案时用的名义映射（协议名义编码：1=关 2=降 3=透 4=抗） */
    val DEFAULT = Profile("默认", intArrayOf(1, 2, 3, 4))

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

    /** 该型号实际支持的 UI 档位（setMap 里非 -1 的槽位） */
    fun supportedUi(p: Profile): IntArray {
        val out = ArrayList<Int>(4)
        for (ui in 0 until 4) {
            if ((p.setMap.getOrNull(ui) ?: -1) >= 0) out.add(ui)
        }
        return out.toIntArray()
    }

    /** UI 档位 → 设备码；-1 = 不支持 */
    fun devForUi(p: Profile, ui: Int): Int = p.setMap.getOrNull(ui) ?: -1

    /** 设备码 → UI 档位；-1 = 认不出 */
    fun uiForDev(p: Profile, dev: Int): Int {
        p.getMap?.let { m ->
            val i = m.indexOf(dev)
            if (i >= 0) return i
        }
        return p.setMap.indexOf(dev)
    }

    /** UI 档位名（顺序固定：0 关 / 1 降噪 / 2 透传 / 3 抗风噪） */
    fun uiName(ui: Int): String = when (ui) {
        0 -> "关闭"
        1 -> "降噪"
        2 -> "透传"
        3 -> "抗风噪"
        else -> "档$ui"
    }
}