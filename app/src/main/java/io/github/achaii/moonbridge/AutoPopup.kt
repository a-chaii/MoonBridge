package io.github.achaii.moonbridge

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.security.MessageDigest

/**
 * 连上耳机 → 自动弹出那张设备卡片（等同于点开面板里那张卡）。
 *
 * 所有参数都不是猜的，是从官方启动时的 Intent 里抓下来的（XposedEntry.dump 早先打印过）：
 *
 *   action  = com.oplus.mydevices.ACTION_DEVICE_DETAILED_PANEL
 *   target  = com.heytap.mydevices/com.oplus.mydevices.bluetooth.BlueToothDetailActivity
 *   extras  = device_connect_state=2 / device_type="bt-device" / model_id="sAlm"
 *             device_title="Moondrop Golden Ages 2" / device_secondary_type=2000
 *             mask=-1 / device_id=md5(mac) / device_mac_info="24:01:30:B5:58:22"
 *
 * 其中 device_id 已实测验证 = MD5("24:01:30:B5:58:22")（带冒号、原样小写），
 * 所以这里运行时算，不硬编码。
 */
object AutoPopup {

    private const val TAG = "[MoonBridge]"

    private const val PKG = "com.heytap.mydevices"
    private const val CLS_DETAIL = "com.oplus.mydevices.bluetooth.BlueToothDetailActivity"
    private const val ACT_DETAIL = "com.oplus.mydevices.ACTION_DEVICE_DETAILED_PANEL"

    /** 设备标题（同官方 extra） */
    private const val TITLE = "Moondrop Golden Ages 2"

    /** 官方 extra 里的 model_id（原样照抄，不推测含义） */
    private const val MODEL_ID = "sAlm"

    /** 官方 extra 里的 device_secondary_type */
    private const val SECONDARY_TYPE = 2000

    /**
     * 接受这几个地址（大小写不敏感）：
     *  - 24:01:30:B5:58:22 —— 经典蓝牙地址，面板 extra 里的 device_mac_info 就是它
     *  - 24:01:30:B5:0D:22 —— 我们 GAIA 直连用的 BLE 地址（双模耳机两个地址不同）
     */
    private val MACS = setOf(
        "24:01:30:B5:58:22",
        "24:01:30:B5:0D:22"
    )

    /** 连接广播到弹窗之间的等待：留一点时间给耳机把 A2DP 链路和卡片数据准备好 */
    private const val CONNECT_DELAY_MS = 900L

    /** 去重窗口：同一次连接里可能连着来好几条广播（ACL / A2DP / HFP） */
    private const val DEDUPE_MS = 20_000L

    private const val ACT_TEST = "io.github.achaii.moonbridge.POPUP_TEST"

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var installed = false

    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var lastFire = 0L

    /**
     * 插在每个进程的 Application#onCreate 之后。
     * 之所以每个进程都插：谁也说不准连接那一刻活的是哪个进程（:cards / 主进程），
     * 谁活着就谁来弹；重复触发靠 dedupeWindow() 这个跨进程时间戳压掉。
     */
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        bind(app, lpparam.processName)
                    }
                }
            )
            log("自动弹窗：已挂 Application#onCreate（process=${lpparam.processName}）")
        } catch (t: Throwable) {
            log("自动弹窗：挂 Application#onCreate 失败 ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    @Synchronized
    private fun bind(app: Application, process: String) {
        if (installed) return
        installed = true
        appCtx = app
        registerBtReceiver(app, process)
        registerTestReceiver(app, process)
    }

    private fun registerBtReceiver(app: Application, process: String) {
        val filter = IntentFilter().apply {
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
        }
        try {
            app.registerReceiver(btReceiver, filter)
            log("自动弹窗：已注册蓝牙连接广播（process=$process）")
        } catch (t: Throwable) {
            log("自动弹窗：注册蓝牙广播失败 ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try {
                val act = i.action ?: return
                val dev = deviceOf(i)
                val addr = dev?.address
                val state = i.getIntExtra("android.bluetooth.profile.extra.STATE", -1)
                log("蓝牙广播 $act addr=$addr state=$state")
                if (addr == null || !MACS.any { it.equals(addr, true) }) return
                // 带 state 的（A2DP / HFP）只认 2 = 已连接；ACL_CONNECTED 没有 state
                if (state != -1 && state != 2) return
                fire("广播 $act")
            } catch (t: Throwable) {
                log("自动弹窗：广播处理异常 ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /** 测试用：`am broadcast -a io.github.achaii.moonbridge.POPUP_TEST` 直接弹一次，不用真断连 */
    private fun registerTestReceiver(app: Application, process: String) {
        val filter = IntentFilter(ACT_TEST)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getBooleanExtra("reset", false)) {
                    lastFire = 0L
                    log("自动弹窗：已重置去重计时")
                }
                fire("测试广播")
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                Context::class.java.getMethod(
                    "registerReceiver",
                    BroadcastReceiver::class.java,
                    IntentFilter::class.java,
                    Int::class.javaPrimitiveType
                ).invoke(app, r, filter, 0x2) // 0x2 = RECEIVER_EXPORTED
            } else {
                app.registerReceiver(r, filter)
            }
            log("自动弹窗：已注册测试广播（process=$process）")
        } catch (t: Throwable) {
            log("自动弹窗：注册测试广播失败 ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun fire(reason: String) {
        if (!dedupeWindow(System.currentTimeMillis())) {
            log("自动弹窗：去重窗口内，跳过（$reason）")
            return
        }
        main.postDelayed({
            try {
                startPanel(reason)
            } catch (t: Throwable) {
                log("自动弹窗：启动失败 ${t.javaClass.simpleName}: ${t.message}")
            }
        }, CONNECT_DELAY_MS)
    }

    /**
     * 跨进程去重：两个进程各注册了一份广播，谁先来谁写时间戳，另一个看到就跳过。
     * 用真实时间而不是 elapsedRealtime，因为要跨进程共享。
     */
    private fun dedupeWindow(now: Long): Boolean {
        val ctx = appCtx ?: return false
        if (now - lastFire < DEDUPE_MS) return false
        val f = File(ctx.filesDir, "moonpanel_popup.ts")
        try {
            if (f.exists()) {
                val prev = f.readText().trim().toLongOrNull() ?: 0L
                if (now - prev < DEDUPE_MS) return false
            }
        } catch (t: Throwable) {
        }
        lastFire = now
        try {
            f.writeText(now.toString())
        } catch (t: Throwable) {
        }
        return true
    }

    private fun startPanel(reason: String) {
        val c = appCtx
        if (c == null) {
            log("自动弹窗：没有 context，放弃")
            return
        }
        val mac = "24:01:30:B5:58:22"
        val i = Intent().apply {
            setClassName(PKG, CLS_DETAIL)
            action = ACT_DETAIL
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("device_connect_state", 2)
            putExtra("device_type", "bt-device")
            putExtra("model_id", MODEL_ID)
            putExtra("device_title", TITLE)
            putExtra("device_secondary_type", SECONDARY_TYPE)
            putExtra("mask", -1)
            putExtra("device_id", md5Hex(mac))
            putExtra("device_mac_info", mac)
        }
        try {
            c.startActivity(i)
            log("自动弹窗：已启动 $CLS_DETAIL（$reason）")
        } catch (t: Throwable) {
            log("自动弹窗：startActivity 被拒 ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ------------------------------------------------------------------

    /** BroadcastReceiver.getParcelableExtra 在 33 之后换了签名，这里两种都试 */
    private fun deviceOf(i: Intent): android.bluetooth.BluetoothDevice? {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                i.getParcelableExtra(
                    "android.bluetooth.device.extra.DEVICE",
                    android.bluetooth.BluetoothDevice::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                i.getParcelableExtra("android.bluetooth.device.extra.DEVICE")
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun md5Hex(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }

    private fun log(s: String) = XposedBridge.log("$TAG $s")
}
