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
 *   extras  = device_connect_state=2 / device_type="bt-device" / model_id=<宿主型号短码>
 *             device_title=<设备名> / device_secondary_type=2000
 *             mask=-1 / device_id=md5(mac) / device_mac_info=<经典蓝牙地址>
 *
 * ★ extras 的**键**是抓官方 Intent 得到的、固定不变；**值**全部运行时取：
 *   - 地址：从触发我们的那条连接广播里取（同一副耳机不再写死）
 *   - 标题：取系统里的蓝牙设备名（换耳机就跟着变）
 *   - model_id：只有抓过官方 Intent 的型号才填（见 MODEL_IDS），其余省略让宿主自己查库
 *   - device_id 已实测 = MD5(地址字符串，带冒号、原样小写)，所以运行时算。
 */
object AutoPopup {

    private const val TAG = "[MoonBridge]"

    private const val PKG = "com.heytap.mydevices"
    private const val CLS_DETAIL = "com.oplus.mydevices.bluetooth.BlueToothDetailActivity"
    private const val ACT_DETAIL = "com.oplus.mydevices.ACTION_DEVICE_DETAILED_PANEL"
    /** 官方 extra 里的 device_secondary_type（蓝牙耳机固定值） */
    private const val SECONDARY_TYPE = 2000

    /**
     * 宿主自己的 model_id（就是官方 Intent 里那个短码）。
     *
     * ★ 这张表只能靠抓官方启动 Intent 得到，不能推断。
     *   表里没有的型号就**不塞 model_id**，让宿主按 device_id / mac 自己去查它的设备库，
     *   比瞎填一个错的短码安全得多。
     */
    private val MODEL_IDS = mapOf(
        "GOLDEN AGES 2" to "sAlm"
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
        // 设置通道（自动弹窗开关 / 自定义头图）由模块 App 广播过来，落盘在宿主自己的 filesDir
        SettingsSync.install(app)
        registerBtReceiver(app, process)
        registerTestReceiver(app, process)
        registerFoundReceiver(app, process)
    }

    /**
     * 请 GMS 帮忙扫一次 BLE（GMS 常驻、有扫描权限、不受后台节流）。
     *
     * 顺带留一手：3.5 秒内没收到 GMS 回音（没装 GMS / 没勾作用域），
     * 就在宿主进程里自己扫一次兜底 —— 命中率低总比完全没有强。
     */
    private fun requestScanFromGms(c: Context, classic: String?) {
        try {
            val i = Intent(GmsLeScan.ACT_REQ).apply {
                setPackage("com.google.android.gms")
                putExtra("classic", classic)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            c.sendBroadcast(i)
            log("已请求 GMS 扫描（classic=${classic ?: "?"}）")
        } catch (t: Throwable) {
            log("请求 GMS 扫描失败: ${t.javaClass.simpleName}")
        }

        // 兜底：GMS 没响应就在本进程扫
        main.postDelayed({
            if (LeCache.get(classic) != null) {
                log("GMS 已回填缓存，跳过兜底扫描")
                return@postDelayed
            }
            log("3.5s 内没等到 GMS 回音 → 宿主自己扫一次兜底")
            LeDiscovery.start(c) { leAddr, leName ->
                log("兜底扫描命中：$leAddr（${leName ?: "无名字"}）")
                LeCache.put(c, classic, leAddr)
                PanelInjector.prewarm(c, 0L)
            }
        }, 3_500L)
    }

    /** GMS 扫到地址后的回信 */
    private fun registerFoundReceiver(app: Application, process: String) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                try {
                    val classic = i.getStringExtra("classic")
                    val le = i.getStringExtra("le")
                    val name = i.getStringExtra("name")
                    if (le.isNullOrEmpty()) return
                    log("GMS 推送 LE 地址：$le（classic=${classic ?: "?"} 设备=${name ?: "?"}）")
                    LeCache.put(c, classic, le)
                    PanelInjector.prewarm(c, 0L)
                } catch (t: Throwable) {
                    log("处理 GMS 推送失败: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                Context::class.java.getMethod(
                    "registerReceiver",
                    BroadcastReceiver::class.java,
                    IntentFilter::class.java,
                    Int::class.javaPrimitiveType
                ).invoke(app, r, IntentFilter(GmsLeScan.ACT_FOUND), 0x2)
            } else {
                app.registerReceiver(r, IntentFilter(GmsLeScan.ACT_FOUND))
            }
            log("已注册 GMS 扫描回信（process=$process）")
        } catch (t: Throwable) {
            log("注册 GMS 回信失败: ${t.javaClass.simpleName}: ${t.message}")
        }
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
                val name = deviceName(dev)
                val state = i.getIntExtra("android.bluetooth.profile.extra.STATE", -1)
                log("蓝牙广播 $act addr=$addr name=$name state=$state")
                if (addr == null) return
                // ★ 门禁从「写死两个 MAC」换成「按名字判断是不是水月雨」：
                //   换耳机、换型号、买新的都照样能弹；别人家的耳机不会被我们弹出来。
                if (!MoondropProfile.isMoondrop(name)) {
                    log("自动弹窗：不是水月雨（name=$name），跳过")
                    return
                }
                // ACL 连上 = 耳机已在附近 → 请 GMS 帮我们扫（它常驻、不受后台扫描节流）
                if (act == "android.bluetooth.device.action.ACL_CONNECTED") {
                    requestScanFromGms(c, addr)
                    return
                }
                if (!act.endsWith("CONNECTION_STATE_CHANGED")) return
                if (state != 2) return
                // 刚刚扫到过设备（8 秒内）说明链路早就热了，不用再等 900ms
                val delay = if (LeDiscovery.freshHit()) 0L else CONNECT_DELAY_MS
                fire(addr, titleOf(name), "广播 $act", delay)
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
                // 可以在广播里指定标题/地址，方便手头没有那副耳机时也能验证弹窗
                val mac = i.getStringExtra("mac") ?: "00:00:00:00:00:00"
                val title = i.getStringExtra("title") ?: "MOONDROP 测试设备"
                fire(mac, title, "测试广播")
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

    private fun fire(mac: String, title: String, reason: String, delayMs: Long = CONNECT_DELAY_MS) {
        // 设置里关掉就什么都不做。放在去重之前，免得白占一次去重窗口
        if (!SettingsSync.autoPopup()) {
            log("自动弹窗：已在模块设置里关闭，跳过（$reason）")
            return
        }
        if (!dedupeWindow(System.currentTimeMillis())) {
            log("自动弹窗：去重窗口内，跳过（$reason）")
            return
        }
        main.postDelayed({
            try {
                startPanel(mac, title, reason)
            } catch (t: Throwable) {
                log("自动弹窗：启动失败 ${t.javaClass.simpleName}: ${t.message}")
            }
        }, delayMs)
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

    private fun startPanel(mac: String, title: String, reason: String) {
        val c = appCtx
        if (c == null) {
            log("自动弹窗：没有 context，放弃")
            return
        }
        val profileKey = MoondropProfile.ofOrDefault(title).key
        val modelId = MODEL_IDS[profileKey]
        val i = Intent().apply {
            setClassName(PKG, CLS_DETAIL)
            action = ACT_DETAIL
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("device_connect_state", 2)
            putExtra("device_type", "bt-device")
            putExtra("device_title", title)
            putExtra("device_secondary_type", SECONDARY_TYPE)
            putExtra("mask", -1)
            putExtra("device_id", md5Hex(mac))
            putExtra("device_mac_info", mac)
            // 只有抓过官方 Intent 的型号才塞 model_id，其余留空让宿主自己查
            if (modelId != null) putExtra("model_id", modelId)
        }
        try {
            c.startActivity(i)
            log(
                "自动弹窗：已启动 $CLS_DETAIL（$reason）" +
                    " 标题=$title 档案=$profileKey model_id=${modelId ?: "(省略)"} mac=$mac"
            )
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

    /** 设备名。name 在 Android 12+ 需要 BLUETOOTH_CONNECT：宿主有这个权限，拿不到就返回 null */
    private fun deviceName(dev: android.bluetooth.BluetoothDevice?): String? {
        if (dev == null) return null
        return try {
            dev.name?.trim()?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        }
    }

    /** 弹窗标题：优先用系统里看到的设备名，拿不到时给一个中性名，绝不编造成某个具体型号 */
    private fun titleOf(name: String?): String = name ?: "蓝牙音频设备"

    private fun md5Hex(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }

    private fun log(s: String) = XposedBridge.log("$TAG $s")
}
