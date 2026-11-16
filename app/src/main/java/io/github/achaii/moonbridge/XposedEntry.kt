package io.github.achaii.moonbridge

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed 入口（第二阶段：宽网侦察）。
 *
 * 已知事实：
 *  - com.oplus.mydevices.bluetooth.BlueToothDetailActivity 继承自
 *    androidx FragmentActivity，自己只声明了 onCreate / onNewIntent / onStop 等，
 *    **没有 onResume / onStart**（继承来的），所以精确匹配必然失败。
 *  - 用户真正看到的那个「设备详情页」在 com.android.settings 里，
 *    行结构像 PreferenceScreen。
 *
 * 所以这一版：凡是进程名/类名里带 bluetooth 的 Activity，只要走到 onResume，
 * 就把 Fragment 列表 + Preference 条目 + View 树 dump 出来。
 * 一个进程里同一个类只 dump 一次，避免刷屏。
 */
class XposedEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "[MoonBridge]"

        /** 只在几个包里活动：宿主卡片、系统设置页、GMS（Fast Pair 提前信号） */
        private val TARGET_PKGS = setOf(
            "com.heytap.mydevices",
            "com.android.settings",
            "com.google.android.gms"
        )

        /** 已 dump 过的 Activity 类，避免重复刷 */
        private val dumped = HashSet<String>()

        /** 出现过的 Activity 类名（去重），用来发现「真正的页面」 */
        private val seenActs = HashSet<String>()

        private fun looksLikeBt(name: String): Boolean {
            val n = name.lowercase()
            return n.contains("bluetooth") || n.contains("blue_tooth") || n.contains("btdevice")
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg !in TARGET_PKGS) return
        log("=== 载入 $pkg (process=${lpparam.processName}) ===")

        // GMS 里只干一件事：盯 Fast Pair 半屏出现的时机，拿到「设备来了」的提前信号。
        // 不做 Activity dump、不注入面板，免得在 GMS 里留一堆无用钩子。
        if (pkg == "com.google.android.gms") {
            FastPairSignal.install(lpparam)
            return
        }

        hookAllActivityOnResume(lpparam)
        hookKnownClass(lpparam)

        // 注入器：只在耳机面板所在的那个包里干活
        if (pkg == "com.heytap.mydevices") {
            PanelInjector.install(lpparam)
            // 连上耳机自动弹卡片（每个进程各挂一份，谁活着谁弹，跨进程去重）
            AutoPopup.install(lpparam)
        }
    }

    // ------------------------------------------------------------------
    // 主力：框架 Activity#onResume，按类名筛选
    // ------------------------------------------------------------------

    private fun hookAllActivityOnResume(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val base = XposedHelpers.findClass("android.app.Activity", lpparam.classLoader)
            XposedBridge.hookAllMethods(base, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val a = param.thisObject as? Activity ?: return
                    val cn = a.javaClass.name
                    if (!looksLikeBt(cn) && !looksLikeBt(a.javaClass.simpleName)) {
                        // 名字不带 bluetooth 的也记一次（去重），防止漏掉真正的页面
                        if (markSeen(cn)) log("  （非蓝牙页）Activity=$cn")
                        return
                    }
                    if (!markDumped(cn)) {
                        log("  （已 dump 过，跳过）$cn")
                        return
                    }
                    log("--- 命中 Bluetooth 页: $cn")
                    safeDump(a)
                    // onResume 时内容常常还没 inflate，延迟再补两次
                    val decor = a.window?.decorView
                    if (decor != null) {
                        decor.postDelayed({
                            if (markDumped("$cn#600")) {
                                log("--- 延迟 dump (+600ms) ---")
                                safeDump(a)
                            }
                        }, 600)
                        decor.postDelayed({
                            if (markDumped("$cn#1600")) {
                                log("--- 延迟 dump (+1600ms) ---")
                                safeDump(a)
                            }
                        }, 1600)
                    }
                }
            })
            log("已挂 android.app.Activity#onResume")
        } catch (t: Throwable) {
            log("挂 Activity#onResume 失败: ${t.javaClass.simpleName}")
        }
    }

    @Synchronized
    private fun markDumped(key: String): Boolean = dumped.add(key)

    private fun log(s: String) = XposedBridge.log("$TAG $s")

    @Synchronized
    private fun markSeen(key: String): Boolean = seenActs.add(key)

    // ------------------------------------------------------------------
    // 顺手：mydevices 那个类，列出它自己声明的方法（保留，方便下一轮挑挂点）
    // ------------------------------------------------------------------

    private fun hookKnownClass(lpparam: XC_LoadPackage.LoadPackageParam) {
        val actCls = try {
            XposedHelpers.findClass(
                "com.oplus.mydevices.bluetooth.BlueToothDetailActivity",
                lpparam.classLoader
            )
        } catch (t: Throwable) {
            return
        }
        log("类已找到: ${actCls.name}")
        try {
            actCls.declaredMethods.forEach { m ->
                log("  method: ${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})")
            }
        } catch (t: Throwable) {
        }
    }

    // ------------------------------------------------------------------

    private fun safeDump(a: Activity) {
        try {
            dump(a)
        } catch (t: Throwable) {
            log("dump 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun dump(act: Activity) {
        val intent: Intent? = act.intent
        log("    intent = ${intent?.component?.flattenToShortString()} action=${intent?.action}")
        intent?.extras?.keySet()?.forEach { k ->
            val v = try {
                intent.extras?.get(k)?.toString()
            } catch (e: Throwable) {
                "?"
            }
            log("    extra[$k] = $v")
        }

        val fragments = collectFragments(act)
        if (fragments.isEmpty()) log("    (没有找到 Fragment)")
        for ((i, f) in fragments.withIndex()) {
            val fn = f.javaClass.name
            log("    Fragment[$i]: $fn")
            logFragMethods(f)
            dumpPreferenceTree(f, "      ")
        }

        log("    --- View 树（Activity 自己的窗口）---")
        walk(act.window?.decorView, 0, intArrayOf(0))

        log("    --- 本进程所有窗口 ---")
        dumpAllWindows("all")
    }

    /**
     * 用反射读 WindowManagerGlobal.mViews，把进程里所有根 View 都 dump 出来。
     * 这样 Dialog / PopupWindow / 浮层也逃不掉。
     */
    private fun dumpAllWindows(label: String) {
        try {
            val wmgCls = Class.forName("android.view.WindowManagerGlobal")
            val inst = wmgCls.getMethod("getInstance").invoke(null)
            val f = wmgCls.getDeclaredField("mViews")
            f.isAccessible = true
            val views = f.get(inst) as? List<*>
            log("    [$label] 窗口数量 = ${views?.size ?: 0}")
            views?.forEachIndexed { i, v ->
                if (v !is View) return@forEachIndexed
                log("    --- window[$i] ${v.javaClass.name} ---")
                walk(v, 0, intArrayOf(0))
            }
        } catch (t: Throwable) {
            log("    [$label] 读窗口列表失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun logFragMethods(f: Any) {
        try {
            f.javaClass.declaredMethods
                .filter { m -> m.parameterTypes.isEmpty() || m.name.startsWith("on") }
                .take(25)
                .forEach { m ->
                    log("      f.method: ${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})")
                }
        } catch (t: Throwable) {
        }
    }

    private fun collectFragments(act: Activity): List<Any> {
        val out = ArrayList<Any>()
        try {
            val fm = act.javaClass.getMethod("getSupportFragmentManager").invoke(act)
            val list = fm?.javaClass?.getMethod("getFragments")?.invoke(fm) as? List<*>
            list?.forEach { if (it != null) out.add(it) }
        } catch (t: Throwable) {
        }
        try {
            @Suppress("DEPRECATION")
            val fm = act.fragmentManager
            @Suppress("DEPRECATION")
            fm?.fragments?.forEach { f -> out.add(f) }
        } catch (t: Throwable) {
        }
        return out
    }

    /** 递归打印 PreferenceScreen 树 */
    private fun dumpPreferenceTree(fragment: Any, pad: String) {
        try {
            val screen = try {
                fragment.javaClass.getMethod("getPreferenceScreen").invoke(fragment)
            } catch (t: Throwable) {
                null
            } ?: return
            log("${pad}PreferenceScreen: ${screen.javaClass.name}")
            walkPref(screen, pad, 0)
        } catch (t: Throwable) {
        }
    }

    private fun walkPref(p: Any?, pad: String, depth: Int) {
        if (p == null || depth > 4) return
        try {
            val cls = p.javaClass
            val key = try {
                cls.getMethod("getKey").invoke(p)
            } catch (t: Throwable) {
                null
            }
            val title = try {
                cls.getMethod("getTitle").invoke(p)
            } catch (t: Throwable) {
                null
            }
            val summary = try {
                cls.getMethod("getSummary").invoke(p)
            } catch (t: Throwable) {
                null
            }
            log("$pad- ${cls.name} key=$key title=$title summary=$summary")
            // 子屏
            if (cls.name.contains("PreferenceGroup") || cls.superclass?.name?.contains("PreferenceGroup") == true) {
                val n = try {
                    cls.getMethod("getPreferenceCount").invoke(p) as Int
                } catch (t: Throwable) {
                    0
                }
                for (i in 0 until n) {
                    val c = try {
                        cls.getMethod("getPreference", Int::class.javaPrimitiveType).invoke(p, i)
                    } catch (t: Throwable) {
                        null
                    }
                    walkPref(c, "$pad  ", depth + 1)
                }
            }
        } catch (t: Throwable) {
        }
    }

    private fun walk(v: View?, depth: Int, counter: IntArray) {
        if (v == null || depth > 30 || counter[0] > 1400) return
        counter[0]++
        val text = (v as? TextView)?.text?.toString()?.replace("\n", " ")?.take(40)
        val pad = "    " + "  ".repeat(depth)
        val cc = (v as? ViewGroup)?.childCount ?: -1
        log(
            "$pad${v.javaClass.simpleName} id=${idName(v)}" +
                (if (cc >= 0) " children=$cc" else "") +
                (if (text.isNullOrBlank()) "" else " text=\"$text\"")
        )
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1, counter)
        }
    }

    private fun idName(v: View): String {
        val id = v.id
        if (id == View.NO_ID) return "-"
        return try {
            v.resources.getResourceEntryName(id)
        } catch (t: Throwable) {
            id.toString()
        }
    }
}