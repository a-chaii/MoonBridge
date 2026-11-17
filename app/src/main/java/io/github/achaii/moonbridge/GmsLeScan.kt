package io.github.achaii.moonbridge

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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

/**
 * GMS 侧 BLE 扫描 —— 对应上游 README 的：
 *   「FastPairHook（LSPosed 模块，注入 Google Play 服务）：借道 GMS 的 BLE 扫描能力
 *     动态发现耳机 LE 地址并推送给应用」
 *
 * ★ 为什么非得放 GMS 里：
 *   宿主（com.heytap.mydevices）被连接广播唤醒时是**后台进程**，
 *   Android 12+ 对后台扫描有节流，实测扫不到广播。
 *   GMS 常驻、有 BLUETOOTH_SCAN、不受后台节流。
 *
 * 0.7.0 起两种扫描并存：
 *
 *   ① 常驻发现扫描 —— SCAN_MODE_BALANCED，一直挂着。开盖那一刻耳机就在广播，
 *      所以这条能**在蓝牙连上之前**就发现它并推给宿主 → 宿主立刻弹窗。
 *      这是「像上游一样一扫到就弹」的唯一办法：等 ACL_CONNECTED 永远比连接慢。
 *   ② 请求触发的急扫 —— 宿主 ACL 时喊一嗓子，LOW_LATENCY 6 秒，
 *      常驻扫描万一没命中（刚开机、蓝牙刚开）还能补一刀。
 *
 * 通信方式（两个应用之间，只能用广播）：
 *
 *   宿主 → GMS   io.github.achaii.moonbridge.REQ_LE_SCAN   (extra: classic)
 *   GMS → 宿主   io.github.achaii.moonbridge.LE_ADDR_FOUND (extra: classic, le, name)
 */
object GmsLeScan {

    private const val TAG = "[MoonBridge]"

    const val ACT_REQ = "io.github.achaii.moonbridge.REQ_LE_SCAN"
    const val ACT_FOUND = "io.github.achaii.moonbridge.LE_ADDR_FOUND"

    private const val HOST_PKG = "com.heytap.mydevices"

    /** 急扫：窗口 6s、节流 2.5s（窗口比节流长会「上一轮还在进行」，0.6.1 踩过） */
    private const val WINDOW_MS = 6_000L
    private const val THROTTLE_MS = 2_500L

    /** 常驻扫描命中后歇多久再挂回去，避免同一副耳机反复上报把宿主弹窗刷成循环 */
    private const val GUARD_COOLDOWN_MS = 15_000L

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var installed = false

    @Volatile
    private var scanning = false

    @Volatile
    private var lastScanAt = 0L

    @Volatile
    private var app: Context? = null

    // ---- 常驻发现扫描 ----
    @Volatile
    private var guardScanning = false
    private var guardCb: ScanCallback? = null

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val a = param.thisObject as? Application ?: return
                        bind(a, lpparam.processName)
                    }
                }
            )
            log("GMS 侧已挂 Application#onCreate（准备常驻发现 + 接收扫描请求）")
        } catch (t: Throwable) {
            log("GMS 侧挂钩失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    @Synchronized
    private fun bind(a: Application, process: String) {
        if (app != null) return
        app = a.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val classic = i.getStringExtra("classic")
                log("收到扫描请求 classic=${classic ?: "?"}（process=$process）")
                startScan(classic)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                Context::class.java.getMethod(
                    "registerReceiver",
                    BroadcastReceiver::class.java,
                    IntentFilter::class.java,
                    Int::class.javaPrimitiveType
                ).invoke(a, r, IntentFilter(ACT_REQ), 0x2)
            } else {
                a.registerReceiver(r, IntentFilter(ACT_REQ))
            }
            log("GMS 侧已注册扫描请求接收器（process=$process）")
        } catch (t: Throwable) {
            log("GMS 侧注册接收器失败: ${t.javaClass.simpleName}: ${t.message}")
        }
        // ★ 常驻发现扫描：开盖那一刻耳机就开始广播，一直挂着才能立刻发现
        main.post { startGuardScan(process) }
    }

    // ------------------------------------------------------------------
    // 常驻发现扫描
    // ------------------------------------------------------------------

    private fun startGuardScan(process: String) {
        if (guardScanning) return
        val adapter = adapterOrNull() ?: return
        if (!adapter.isEnabled) return
        val scanner = try {
            adapter.bluetoothLeScanner
        } catch (t: Throwable) {
            null
        } ?: return

        val settings = ScanSettings.Builder()
            // BALANCED：2s 窗口 / 3s 间隔。LOW_POWER（0.5s/5s）延迟太大，LOW_LATENCY 又太费电。
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                try {
                    val c = app ?: return
                    val hit = matchMoondrop(adapter, result) ?: return
                    log("常驻扫描命中：${hit.first} name=${hit.third ?: "(空)"}")
                    stopGuardScan("命中")
                    pushFound(c, hit.second, hit.first, hit.third)
                    main.postDelayed({ startGuardScan(process) }, GUARD_COOLDOWN_MS)
                } catch (t: Throwable) {
                    log("常驻扫描回调异常: ${t.javaClass.simpleName}")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                log("常驻扫描失败 code=$errorCode（10s 后重试）")
                guardScanning = false
                guardCb = null
                main.postDelayed({ startGuardScan(process) }, 10_000L)
            }
        }
        guardCb = cb
        try {
            scanner.startScan(null, settings, cb)
            guardScanning = true
            log("GMS 常驻发现扫描已挂上（process=$process）")
        } catch (t: Throwable) {
            log("常驻扫描启动失败: ${t.javaClass.simpleName}: ${t.message}")
            guardScanning = false
            guardCb = null
        }
    }

    private fun stopGuardScan(reason: String) {
        val cb = guardCb ?: return
        guardScanning = false
        guardCb = null
        val adapter = adapterOrNull() ?: return
        try {
            adapter.bluetoothLeScanner?.stopScan(cb)
            log("GMS 常驻扫描暂停（$reason）")
        } catch (t: Throwable) {
        }
    }

    /** 命中判断：返回 (le 地址, classic 地址, 广播名)。不是水月雨就返回 null */
    private fun matchMoondrop(adapter: BluetoothAdapter, result: ScanResult?): Triple<String, String?, String?>? {
        val dev = result?.device ?: return null
        val addr = try {
            dev.address
        } catch (t: Throwable) {
            null
        } ?: return null
        val name = result.scanRecord?.deviceName ?: runCatching { dev.name }.getOrNull()
        if (!name.isNullOrEmpty() && MoondropProfile.isMoondrop(name)) {
            return Triple(addr, resolveClassic(adapter, addr, name), name)
        }
        if (name.isNullOrEmpty()) {
            // 广播里没名字时靠地址前缀（同一副耳机的经典/LE 地址前 12 位相同）
            val prefix = addr.substring(0, 12).uppercase()
            val bonded = runCatching {
                adapter.bondedDevices?.firstOrNull { d ->
                    val dn = d.name ?: return@firstOrNull false
                    val da = d.address ?: return@firstOrNull false
                    MoondropProfile.isMoondrop(dn) && da.length >= 12 && da.substring(0, 12).uppercase() == prefix
                }
            }.getOrNull() ?: return null
            return Triple(addr, bonded.address, bonded.name)
        }
        return null
    }

    /** 从已配对设备里找同一副耳机的经典地址（名字相同，或地址前 12 位相同） */
    private fun resolveClassic(adapter: BluetoothAdapter, le: String, name: String?): String? {
        return try {
            val prefix = if (le.length >= 12) le.substring(0, 12).uppercase() else ""
            adapter.bondedDevices?.firstOrNull { d ->
                val dn = d.name ?: return@firstOrNull false
                val da = d.address ?: return@firstOrNull false
                if (!MoondropProfile.isMoondrop(dn)) return@firstOrNull false
                (dn == name) || (prefix.isNotEmpty() && da.length >= 12 && da.substring(0, 12).uppercase() == prefix)
            }?.address
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------
    // 请求触发的急扫（补刀用）
    // ------------------------------------------------------------------

    private fun startScan(classicAddr: String?) {
        val c = app ?: run {
            log("扫描跳过：还没有 context")
            return
        }
        val now = System.currentTimeMillis()
        if (scanning) {
            log("扫描跳过：上一轮还在进行")
            return
        }
        if (now - lastScanAt < THROTTLE_MS) {
            log("扫描跳过：刚扫过，等节流")
            return
        }

        val adapter = adapterOrNull()
        if (adapter == null || !adapter.isEnabled) {
            log("扫描跳过：蓝牙未开")
            return
        }
        val scanner = try {
            adapter.bluetoothLeScanner
        } catch (t: Throwable) {
            log("扫描跳过：没有 scanner（${t.javaClass.simpleName}）")
            return
        } ?: return

        scanning = true
        lastScanAt = now
        log("GMS 开始扫描（6s 窗口）")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                try {
                    val hit = matchMoondrop(adapter, result) ?: return
                    log("GMS 扫描命中：${hit.first} name=${hit.third ?: "(空)"}")
                    try {
                        scanner.stopScan(this)
                    } catch (t: Throwable) {
                    }
                    scanning = false
                    pushFound(c, classicAddr ?: hit.second, hit.first, hit.third)
                } catch (t: Throwable) {
                    log("扫描回调异常: ${t.javaClass.simpleName}")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                log("GMS 扫描失败 code=$errorCode")
                scanning = false
            }
        }

        try {
            scanner.startScan(null, settings, cb)
        } catch (t: Throwable) {
            log("GMS 扫描启动失败: ${t.javaClass.simpleName}: ${t.message}")
            scanning = false
            return
        }

        main.postDelayed({
            if (!scanning) return@postDelayed
            try {
                scanner.stopScan(cb)
            } catch (t: Throwable) {
            }
            scanning = false
            log("GMS 扫描：窗口结束（未命中）")
        }, WINDOW_MS)
    }

    private fun adapterOrNull(): BluetoothAdapter? = try {
        BluetoothAdapter.getDefaultAdapter()
    } catch (t: Throwable) {
        null
    }

    private fun pushFound(c: Context, classic: String?, le: String, name: String?) {
        try {
            val i = Intent(ACT_FOUND).apply {
                setPackage(HOST_PKG)
                putExtra("classic", classic)
                putExtra("le", le)
                putExtra("name", name)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            c.sendBroadcast(i)
            log("已推送 LE 地址：$le（classic=${classic ?: "?"}）")
        } catch (t: Throwable) {
            log("推送 LE 地址失败: ${t.javaClass.simpleName}")
        }
    }

    private fun log(s: String) = XposedBridge.log("$TAG $s")
}