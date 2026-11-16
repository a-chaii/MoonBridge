package io.github.achaii.moonbridge

import android.content.Context
import de.robv.android.xposed.XposedBridge
import java.io.File

/**
 * 经典地址 → LE 地址 的持久化缓存（对应上游 README 里的「生命闭环」）。
 *
 * 为什么需要它：
 *   双模耳机的经典地址（A2DP 用，…58:22）和 LE 地址（GAIA 用，…0D:22）是两个，
 *   每次连都现场扫一遍才能确定 LE 地址 —— 那 1~2 秒就是官方 App 也躲不掉的等待。
 *   上游的做法是：连成功后把 LE 地址写回文件，下次直接拿来用；地址变了（换设备/固件重置）
 *   连接失败自动作废重扫。
 *
 * 存放位置：宿主自己的 filesDir（注入端同进程读，无权限问题）：
 *
 *   filesDir/moonbridge_le_cache.txt
 *   24:01:30:B5:58:22=24:01:30:B5:0D:22
 */
object LeCache {

    private const val TAG = "[MoonBridge]"
    private const val FILE = "moonbridge_le_cache.txt"

    @Volatile
    private var app: Context? = null

    fun attach(ctx: Context) {
        if (app == null) app = ctx.applicationContext
    }

    fun get(classicAddr: String?): String? {
        val c = app ?: return null
        if (classicAddr.isNullOrEmpty()) return null
        return try {
            readAll(c)[classicAddr.uppercase()]
        } catch (t: Throwable) {
            null
        }
    }

    /** 这个地址本身是不是某个已缓存的 LE 地址（用来忽略重复的 LE 广播，别再扫一次） */
    fun isKnownLe(addr: String?): Boolean {
        val c = app ?: return false
        if (addr.isNullOrEmpty()) return false
        return try {
            readAll(c).values.any { it.equals(addr, true) }
        } catch (t: Throwable) {
            false
        }
    }

    fun put(ctx: Context?, classicAddr: String?, leAddr: String?) {
        val c = ctx?.applicationContext ?: app ?: return
        if (app == null) app = c
        if (classicAddr.isNullOrEmpty() || leAddr.isNullOrEmpty()) return
        try {
            val m = readAll(c)
            val k = classicAddr.uppercase()
            val old = m[k]
            if (old != null && old.equals(leAddr, true)) return
            m[k] = leAddr.uppercase()
            writeAll(c, m)
            log("LE 地址缓存：$k → ${leAddr.uppercase()}（${if (old == null) "新增" else "更新"}）")
        } catch (t: Throwable) {
            log("LE 地址缓存写入失败: ${t.javaClass.simpleName}")
        }
    }

    fun invalidate(ctx: Context?, classicAddr: String?) {
        val c = ctx?.applicationContext ?: app ?: return
        if (classicAddr.isNullOrEmpty()) return
        try {
            val m = readAll(c)
            val k = classicAddr.uppercase()
            if (m.remove(k) != null) {
                writeAll(c, m)
                log("LE 地址缓存：$k 已作废（下次重新扫描）")
            }
        } catch (t: Throwable) {
        }
    }

    // ------------------------------------------------------------------

    private fun file(c: Context) = File(c.filesDir, FILE)

    private fun readAll(c: Context): MutableMap<String, String> {
        val out = HashMap<String, String>()
        val f = file(c)
        if (!f.exists()) return out
        f.forEachLine { line ->
            val i = line.indexOf('=')
            if (i > 0) {
                val k = line.substring(0, i).trim().uppercase()
                val v = line.substring(i + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
            }
        }
        return out
    }

    private fun writeAll(c: Context, m: Map<String, String>) {
        file(c).writeText(m.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    private fun log(s: String) = XposedBridge.log("$TAG $s")
}