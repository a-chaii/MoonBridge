package io.github.achaii.moonbridge

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 模块 App 这一侧的设置存储 + 「把设置推给注入端」的通道。
 *
 * ★ 为什么需要通道：注入端跑在**宿主进程**（com.heytap.mydevices）里，uid 和我们不同，
 *   它读不到我们 /data/data/io.github.achaii.moonbridge 下的任何文件。
 *   所以设置与头图都以广播发过去，由注入端落盘到**宿主自己的** filesDir，那边同进程随便读。
 *
 * 约定：JSON 只有一层扁平字段，方便注入端用正则/JSONObject 直接取。
 */
object SettingsStore {

    /** 注入端监听的两个动作 */
    const val ACT_SETTINGS = "io.github.achaii.moonbridge.SETTINGS"
    const val ACT_HEAD_ICON = "io.github.achaii.moonbridge.HEAD_ICON"

    private const val PREFS = "moonbridge"
    private const val K_POPUP = "autoPopup"
    private const val K_FAST = "fastPopup"
    private const val K_WIND = "showWind"
    private const val K_GAIN = "showGain"
    private const val K_ICON_KEY = "iconKey"

    /** 头图最长边，超过就缩 —— 广播有 1MB 上限，也免得注入端内存吃紧 */
    private const val MAX_EDGE = 1000

    fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 提速弹窗：GMS 侧常驻扫描，开盖扫到就弹（不等蓝牙连接）。
     * 默认关 —— 能省一点电；关掉也不影响使用，只是退回「连接后弹窗」。
     */
    fun fastPopup(c: Context): Boolean = prefs(c).getBoolean(K_FAST, false)

    fun setFastPopup(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(K_FAST, on).apply()
        pushSettings(c)
    }

    /** 卡片里是否显示「抗风噪」这一档（有些型号/固件抗风噪体验差，用户可以选择不看到它） */
    fun showWind(c: Context): Boolean = prefs(c).getBoolean(K_WIND, true)

    fun setShowWind(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(K_WIND, on).apply()
        pushSettings(c)
    }

    /** 卡片里是否显示「增益」那一块 */
    fun showGain(c: Context): Boolean = prefs(c).getBoolean(K_GAIN, true)

    fun setShowGain(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(K_GAIN, on).apply()
        pushSettings(c)
    }

    /** 自动弹窗开关（默认开） */
    fun autoPopup(c: Context): Boolean = prefs(c).getBoolean(K_POPUP, true)

    fun setAutoPopup(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(K_POPUP, on).apply()
        pushSettings(c)
    }

    /** 已自定义头图的型号 key（空 = 没设过） */
    fun iconKey(c: Context): String = prefs(c).getString(K_ICON_KEY, "") ?: ""

    /** 清掉「已设置头图」的记录（宿主那边仍留着 jpg，但不再被引用，重启后自然失效） */
    fun clearIconKey(c: Context) {
        prefs(c).edit().remove(K_ICON_KEY).apply()
        send(c, ACT_HEAD_ICON, "{\"key\":\"\",\"img\":\"\"}")
    }

    /**
     * 恢复内置图：**必须通知宿主删掉那个 jpg**。
     * 以前只删本地预览 + 清记录，宿主的 moonbridge_icons/<KEY>.jpg 还在，
     * applyIcon() 自然继续读它 —— 这就是「点了没反应」的原因。
     */
    fun clearIcon(c: Context, key: String) {
        localIconFile(c, key).delete()
        if (iconKey(c) == key) prefs(c).edit().remove(K_ICON_KEY).apply()
        send(c, ACT_HEAD_ICON, "{\"key\":\"$key\",\"img\":\"\",\"delete\":true}")
    }

    /** 本地那份头图（只用于 App 内预览） */
    fun localIconFile(c: Context, key: String): File =
        File(File(c.filesDir, "heads"), "$key.jpg").apply { parentFile?.mkdirs() }

    fun pushSettings(c: Context) {
        val json = "{" +
            "\"autoPopup\":${autoPopup(c)}," +
            "\"fastPopup\":${fastPopup(c)}," +
            "\"showWind\":${showWind(c)}," +
            "\"showGain\":${showGain(c)}" +
            "}"
        send(c, ACT_SETTINGS, json)
    }

    /**
     * 读一张用户选的图，压到 MAX_EDGE 以内、JPEG 85，然后广播给注入端。
     * 返回本地预览文件；失败返回 null。
     */
    fun pushHeadIcon(c: Context, uri: Uri, key: String): File? {
        val bytes = decodeScaled(c, uri) ?: return null
        val f = localIconFile(c, key)
        return try {
            f.writeBytes(bytes)
            prefs(c).edit().putString(K_ICON_KEY, key).apply()
            send(
                c, ACT_HEAD_ICON,
                "{\"key\":\"${key.replace("\"", "")}\",\"img\":\"${Base64.encodeToString(bytes, Base64.NO_WRAP)}\"}"
            )
            f
        } catch (t: Throwable) {
            null
        }
    }

    private fun decodeScaled(c: Context, uri: Uri): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / sample > MAX_EDGE * 2 || bounds.outHeight / sample > MAX_EDGE * 2) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = c.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            val maxEdge = maxOf(raw.width, raw.height)
            val scaled = if (maxEdge > MAX_EDGE) {
                val ratio = MAX_EDGE.toFloat() / maxEdge
                Bitmap.createScaledBitmap(
                    raw,
                    (raw.width * ratio).toInt().coerceAtLeast(1),
                    (raw.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else raw

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (scaled !== raw) scaled.recycle()
            raw.recycle()
            out.toByteArray()
        } catch (t: Throwable) {
            android.util.Log.w("MoonBridge", "读图失败: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** 广播必须「隐式」发：动态注册的接收器用 setPackage 定向是收不到的 */
    private fun send(c: Context, action: String, json: String) {
        try {
            val i = Intent(action).apply {
                putExtra("json", json)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            c.sendBroadcast(i)
            android.util.Log.i("MoonBridge", "已推送 $action（${json.length} 字符）")
        } catch (t: Throwable) {
            android.util.Log.w("MoonBridge", "推送 $action 失败: ${t.javaClass.simpleName}")
        }
    }
}