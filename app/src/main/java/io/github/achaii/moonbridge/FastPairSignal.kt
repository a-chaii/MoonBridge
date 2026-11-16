package io.github.achaii.moonbridge

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * GMS 侧：Fast Pair 半屏出现的「提前信号」。
 *
 * ★ 为什么用半屏页而不是发现回调：
 *   GMS 的发现回调类名是**混淆的**（上游用 DexKit 现场解 dex 才能定位），
 *   但半屏页 `com.google.android.gms.nearby.discovery.fastpair.HalfSheetActivity`
 *   是**明文类名**，写在系统的 Intent/组件清单里。钩它不需要 DexKit、
 *   也不需要跟着 GMS 版本改类名。
 *
 * ★ 它比 A2DP 早多少：
 *   半屏是「设备已被发现、正在配对」时弹出的，此时 A2DP/HFP 都还没建链。
 *   拿到这个信号后我们提前把 GAIA 链路建好，等卡片真正需要数据时就不用再等握手。
 *
 * 只做「通知 + 提前建链」，不碰 GMS 的任何 UI（那是上游那 2183 行干的事，我们不需要）。
 */
object FastPairSignal {

    private const val TAG = "[MoonBridge]"

    /** 发给宿主进程的广播：GMS 侧看到 Fast Pair 半屏了 */
    const val ACT_SEEN = "io.github.achaii.moonbridge.FASTPAIR_SEEN"

    /** 半屏出现后等多久再试着建链：太早的话设备还没配对完，bondedDevices 里查不到 */
    private const val PREWARM_DELAY_MS = 1_500L

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var installed = false

    @Volatile
    private var lastPingAt = 0L

    /** 已经打过日志的类名，避免刷屏 */
    private val seenClasses = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 只对「和耳机发现有关」的类名打观察日志 */
    private fun logNearby(cn: String): Boolean =
        cn.contains("nearby", true) || cn.contains("discovery", true) ||
            cn.contains("pair", true) || cn.contains("HalfSheet", true)

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true
        try {
            val base = XposedHelpers.findClass("android.app.Activity", lpparam.classLoader)
            XposedBridge.hookAllMethods(base, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val a = param.thisObject as? Activity ?: return
                        val cn = a.javaClass.name
                        // ★ 只认 nearby/discovery/fastpair 下面那几个半屏。
                        //   GMS 里还有 multidevice 的 HalfSheet，误钩会把「多设备同步」当成耳机出现。
                        if (!cn.contains("nearby.discovery.fastpair") && !cn.contains("FastPair")) {
                            if (seenClasses.add(cn) && logNearby(cn)) {
                                log("观察到相关页（不处理）: $cn")
                            }
                            return
                        }
                        onSheet(a, cn)
                    } catch (t: Throwable) {
                        log("半屏回调异常: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            })
            log("GMS 侧已挂 Activity#onResume（寻找 Fast Pair 半屏）")
        } catch (t: Throwable) {
            log("GMS 侧挂钩失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun onSheet(act: Activity, cn: String) {
        val now = System.currentTimeMillis()
        // 同一块半屏会 onResume 好几次（弹窗内切换状态），5 秒内只报一次
        if (now - lastPingAt < 5_000L) return
        lastPingAt = now

        val name = guessDeviceName(act)
        val pkg = act.packageName
        log("Fast Pair 半屏出现: $cn 包=$pkg 设备名=${name ?: "(未取到)"}")

        // 通知宿主进程：可以提前建链了
        try {
            val i = Intent(ACT_SEEN).apply {
                setPackage("com.heytap.mydevices")
                putExtra("name", name)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            act.sendBroadcast(i)
            log("已通知宿主：FASTPAIR_SEEN（提前建链）")
        } catch (t: Throwable) {
            log("发送 FASTPAIR_SEEN 失败: ${t.javaClass.simpleName}")
        }
    }

    /**
     * 从半屏的 View 树里猜设备名：找「看起来像耳机名」的 TextView。
     * 不解析 GMS 的私有字段（那才是版本相关的部分），失败就返回 null，不影响功能。
     */
    private fun guessDeviceName(a: Activity): String? {
        val out = ArrayList<String>()
        try {
            collectTexts(a.window?.decorView as? ViewGroup, out, 0)
        } catch (t: Throwable) {
            return null
        }
        return out.firstOrNull { MoondropProfile.isMoondrop(it) }
            ?: out.firstOrNull { it.contains("MOONDROP", true) }
    }

    private fun collectTexts(v: ViewGroup?, out: MutableList<String>, depth: Int) {
        if (v == null || depth > 25 || out.size > 60) return
        for (i in 0 until v.childCount) {
            val ch = v.getChildAt(i)
            if (ch is TextView) {
                val t = ch.text?.toString()?.trim()
                if (!t.isNullOrEmpty() && t.length in 3..60) out.add(t)
            }
            if (ch is ViewGroup) collectTexts(ch, out, depth + 1)
        }
    }

    /** 给宿主侧用：延迟多久再 prewarm */
    fun prewarmDelayMs(): Long = PREWARM_DELAY_MS

    private fun log(s: String) = XposedBridge.log("$TAG $s")
}