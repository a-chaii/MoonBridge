package io.github.achaii.moonbridge

import io.github.achaii.moonbridge.GaiaProtocol.F_ANC_V1
import io.github.achaii.moonbridge.GaiaProtocol.F_ANC_V2
import io.github.achaii.moonbridge.GaiaProtocol.F_AUDIO_CURATION
import io.github.achaii.moonbridge.GaiaProtocol.F_SPATIAL

/**
 * 能力位图解析（照抄 FxxkMoondrop 的 CapabilityProbe / GaiaCommands.parseSupportedFeatures）。
 *
 * 为什么这一步重要：型号名字是可以骗人的（改名、联名、固件差异），
 * 而 GET_SUPPORTED_FEATURES 的回包是**设备自己报的**。没有实机可测时，
 * 这是唯一能区分「这型号有没有空间音频 / 走哪条 ANC 路径」的客观依据。
 *
 * 两种回包格式（都是官方固件真实出现过的）：
 *   1. 位图字：若干 4 字节大字，第 n 个字第 b 位 = feature (n*32+b)
 *   2. 配对表：首字节 ≤ 1（还有下一页标记），之后 (featureId, flags) 两两成对
 */
object Capabilities {

    /** 首字节 ≤1 且长度为奇数 → 配对表（末字节是单独的） */
    fun isPairList(p: ByteArray): Boolean =
        p.isNotEmpty() && p.size % 2 == 1 && (p[0].toInt() and 0xFF) <= 1

    fun parseFeatures(payload: ByteArray?): Set<Int> {
        val p = payload ?: return emptySet()
        if (p.isEmpty()) return emptySet()
        if (isPairList(p)) {
            val out = HashSet<Int>()
            var i = 1
            while (i + 1 < p.size) {
                out.add(p[i].toInt() and 0xFF)
                i += 2
            }
            return out
        }
        val out = HashSet<Int>()
        var wordIdx = 0
        var i = 0
        while (i + 3 < p.size) {
            val word = ((p[i].toInt() and 0xFF) shl 24) or
                ((p[i + 1].toInt() and 0xFF) shl 16) or
                ((p[i + 2].toInt() and 0xFF) shl 8) or
                (p[i + 3].toInt() and 0xFF)
            for (bit in 0 until 32) {
                if (word and (1 shl bit) != 0) out.add(wordIdx * 32 + bit)
            }
            wordIdx++
            i += 4
        }
        return out
    }

    /** 配对表里还有下一页（首字节 = 1） */
    fun hasMorePages(payload: ByteArray?): Boolean {
        val p = payload ?: return false
        return isPairList(p) && (p[0].toInt() and 0xFF) == 1
    }

    /** 有空间音频能力 */
    fun hasSpatial(features: Set<Int>): Boolean = F_SPATIAL in features

    /**
     * ANC 走哪条路径 —— 与上游一致：AudioCuration 优先，其次 ANC_V2，再次 ANC_V1。
     * 返回 0 表示位图里没有 ANC 相关位（可能是截断，也可能真没有）。
     */
    fun ancPathOf(features: Set<Int>): Int = when {
        F_AUDIO_CURATION in features -> F_AUDIO_CURATION
        F_ANC_V2 in features -> F_ANC_V2
        F_ANC_V1 in features -> F_ANC_V1
        else -> 0
    }
}