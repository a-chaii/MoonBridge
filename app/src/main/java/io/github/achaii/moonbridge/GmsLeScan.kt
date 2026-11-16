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
 *   Android 12+ 对后台扫描有节流，实测扫不到广播（开盖后 9 秒才弹就是这个原因）。
 *   GMS 常驻、有 BLUETOOTH_SCAN、不受后台节流，扫一次几秒内必命中。
 *
 * 通信方式（两个应用之间，只能用广播）：
 *
 *   宿主 → GMS   io.github.achaii.moonbridge.REQ_LE_SCAN   (extra: classic)
 *   GMS → 宿主   io.github.achaii.moonbridge.LE_ADDR_FOUND (extra: classic, le, name)
 *
 * 不 hook GMS 的任何业务类：只在它进程里注册一个接收器 + 用系统 API 扫。
 */
object GmsLeScan {

    private const val TAG = "[MoonBridge]"

    const val ACT_REQ = "io.github.achaii.moonbridge.REQ_LE_SCAN"
    const val ACT_FOUND = "io.github.achaii.moonbridge.LE_ADDR_FOUND"

    private const val HOST_PKG = "com.heytap.mydevices"
    private const val WINDOW_MS = 10_000L
    private const val THROTTLE_MS = 8_000L

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var installed = false

    @Volatile
    private var scanning = false

    @Volatile
    private var lastScanAt = 0L

    @Volatile
    private var app: Context? = null

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
            log("GMS 侧已挂 Application#onCreate（准备接收扫描请求）")
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
    }

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
            log("扫描跳过：8s 内刚扫过")
            return
        }

        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (t: Throwable) {
            null
        }
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
        log("GMS 开始扫描（10s 窗口）")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                try {
                    val dev = result?.device ?: return
                    val addr = dev.address ?: return
                    val name = result.scanRecord?.deviceName
                        ?: runCatching { dev.name }.getOrNull()

                    var hit = !name.isNullOrEmpty() && MoondropProfile.isMoondrop(name)
                    if (!hit && name.isNullOrEmpty()) {
                        val prefix = addr.substring(0, 12).uppercase()
                        hit = runCatching {
                            adapter.bondedDevices.any { d ->
                                val dn = d.name ?: return@any false
                                val da = d.address ?: return@any false
                                MoondropProfile.isMoondrop(dn) &&
                                    da.length >= 12 && da.substring(0, 12).uppercase() == prefix
                            }
                        }.getOrDefault(false)
                    }
                    if (!hit) return

                    log("GMS 扫描命中：$addr name=${name ?: "(空)"}")
                    try {
                        scanner.stopScan(this)
                    } catch (t: Throwable) {
                    }
                    scanning = false
                    pushFound(c, classicAddr, addr, name)
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