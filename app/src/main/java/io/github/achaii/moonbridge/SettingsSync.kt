package io.github.achaii.moonbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Base64
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.io.File

/**
 * 注入端（跑在宿主进程里）的设置同步。
 *
 * 为什么不直接读模块 App 的 SharedPreferences：那是另一个 uid 的私有目录，宿主进程读不到。
 * 所以由模块 App 广播过来，这里落盘到**宿主自己的** filesDir：
 *
 *   filesDir/moonbridge_settings.json      {"autoPopup":true}
 *   filesDir/moonbridge_icons/<KEY>.jpg    用户自选头图（按型号档案 key）
 *
 * 落盘后同进程随便读，不需要任何权限。
 */
object SettingsSync {

    private const val TAG = "[MoonBridge]"
    private const val FILE_SETTINGS = "moonbridge_settings.json"
    private const val DIR_ICONS = "moonbridge_icons"

    @Volatile
    private var ctx: Context? = null

    @Volatile
    private var installed = false

    /** 缓存，避免每次弹窗都读盘；mtime 变了才重读 */
    @Volatile
    private var cachedPopup = true

    /** 提速弹窗（GMS 常驻扫描）：默认关 */
    @Volatile
    private var cachedFast = false

    @Volatile
    private var cachedMtime = -1L

    fun install(app: Context) {
        if (installed) return
        installed = true
        ctx = app.applicationContext

        register(app, SettingsStore.ACT_SETTINGS) { i ->
            val json = i.getStringExtra("json") ?: return@register
            try {
                val o = JSONObject(json)
                writeFile(app, json)
                cachedPopup = o.optBoolean("autoPopup", true)
                cachedFast = o.optBoolean("fastPopup", false)
                cachedMtime = settingsFile(app).lastModified()
                log("设置已更新：自动弹窗=$cachedPopup 提速弹窗=$cachedFast")
                // 顺手把「要不要常驻扫描」告诉 GMS 那边（它在另一个进程，读不到这份文件）
                sendFastConfig(app)
            } catch (t: Throwable) {
                log("设置解析失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        register(app, SettingsStore.ACT_HEAD_ICON) { i ->
            val json = i.getStringExtra("json") ?: return@register
            try {
                val o = JSONObject(json)
                val key = o.optString("key", "").trim()
                val b64 = o.optString("img", "")
                if (key.isEmpty() || b64.isEmpty()) return@register
                val bytes = Base64.decode(b64, Base64.NO_WRAP)
                val dir = File(app.filesDir, DIR_ICONS).apply { mkdirs() }
                val f = File(dir, "$key.jpg")
                f.writeBytes(bytes)
                log("头图已更新：$key（${bytes.size / 1024} KB）→ ${f.absolutePath}")
            } catch (t: Throwable) {
                log("头图落盘失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /** 自动弹窗是否启用（默认 true，读写失败也返回 true，宁可按默认行为走） */
    fun autoPopup(): Boolean {
        val c = ctx ?: return true
        val f = settingsFile(c)
        val mt = runCatching { f.lastModified() }.getOrDefault(0L)
        if (f.exists() && mt != cachedMtime) {
            cachedMtime = mt
            cachedPopup = runCatching {
                JSONObject(f.readText()).optBoolean("autoPopup", true)
            }.getOrDefault(true)
        }
        return cachedPopup
    }

    /** 提速弹窗是否启用（默认 false） */
    fun fastPopup(): Boolean {
        val c = ctx ?: return false
        val f = settingsFile(c)
        val mt = runCatching { f.lastModified() }.getOrDefault(0L)
        if (f.exists() && mt != cachedMtime) {
            cachedMtime = mt
            val o = runCatching { JSONObject(f.readText()) }.getOrNull()
            cachedPopup = o?.optBoolean("autoPopup", true) ?: true
            cachedFast = o?.optBoolean("fastPopup", false) ?: false
        }
        return cachedFast
    }

    /**
     * 把「提速弹窗 / 常驻扫描」开关同步给 GMS 进程。
     * GMS 侧默认不扫，只有收到 true 才挂常驻扫描 —— 这样不开这个功能的用户不会多耗电。
     */
    fun sendFastConfig(c: Context) {
        try {
            val i = Intent(GmsLeScan.ACT_CFG).apply {
                setPackage("com.google.android.gms")
                putExtra("fast", fastPopup())
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            c.sendBroadcast(i)
            log("已同步提速弹窗开关到 GMS：${fastPopup()}")
        } catch (t: Throwable) {
            log("同步提速开关失败: ${t.javaClass.simpleName}")
        }
    }

    /** 用户给这个型号设的头图；没有就返回 null（调用方回退到内置图） */
    fun customIcon(key: String): File? {
        val c = ctx ?: return null
        if (key.isEmpty()) return null
        val f = File(File(c.filesDir, DIR_ICONS), "$key.jpg")
        return if (f.exists() && f.length() > 0) f else null
    }

    // ------------------------------------------------------------------

    private fun register(app: Context, action: String, onReceive: (Intent) -> Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onReceive(i)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // 0x2 = RECEIVER_EXPORTED：模块 App 是另一个应用，必须导出才收得到
                Context::class.java.getMethod(
                    "registerReceiver",
                    BroadcastReceiver::class.java,
                    IntentFilter::class.java,
                    Int::class.javaPrimitiveType
                ).invoke(app, r, IntentFilter(action), 0x2)
            } else {
                app.registerReceiver(r, IntentFilter(action))
            }
            log("设置通道已注册：$action")
        } catch (t: Throwable) {
            log("注册 $action 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun settingsFile(c: Context) = File(c.filesDir, FILE_SETTINGS)

    private fun writeFile(app: Context, json: String) {
        runCatching { settingsFile(app).writeText(json) }
    }

    private fun log(s: String) = XposedBridge.log("$TAG $s")
}