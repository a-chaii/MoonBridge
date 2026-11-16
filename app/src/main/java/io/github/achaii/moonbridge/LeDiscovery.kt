package io.github.achaii.moonbridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import de.robv.android.xposed.XposedBridge
import java.io.File

/**
 * 提前发现耳机（移植自 FxxkMoondrop `FastPairHookEntry.handleLeScanRequest()`）。
 *
 * ★ 为什么是「自己扫」而不是 hook Fast Pair：
 *   水月雨不是 Google Fast Pair 认证设备，GMS 不会为它弹半屏、也不会有 Fast Pair 回调。
 *   上游同样没有 hook 任何 GMS 扫描逻辑 —— 它就是自己起一次 BLE 扫描，
 *   从广播包里读设备名，命中就记下地址（唯一的区别是它把这段放在 GMS 进程里跑）。
 *
 * 我们放在宿主进程（com.heytap.mydevices）里跑，效果一样，而且不需要多勾一个作用域。
 *
 * 时间线：
 *
 *   ACL_CONNECTED         → 起扫（10 秒窗口）
 *   BLE 广播命中水月雨      → 拿到 LE 地址 → 立刻 prewarm（建 GAIA + 握手）
 *   A2DP/HFP state=2      → 弹卡片，延时可以降到 0（数据已经就绪）
 *
 * 约束：不常驻（只扫 10 秒）、30 秒内不重复、跨进程用锁文件互斥。
 */
object LeDiscovery {

    private const val TAG = "[MoonBridge]"

    private const val WINDOW_MS = 10_000L
    private const val THROTTLE_MS = 30_000L
    private const val LOCK_FILE = "moonbridge_le_scan.lock"
    private const val LOCK_STALE_MS = 15_000L

    /** 命中后多久内算「新鲜」——够弹窗跳过 900ms 等待 */
    private const val FRESH_MS = 8_000L

    @Volatile
    private var scanning = false

    @Volatile
    private var lastStartAt = 0L

    @Volatile
    private var lastHitAt = 0L

    @Volatile
    var lastAddr: String? = null
        private set

    @Volatile
    var lastName: String? = null
        private set

    /** 刚刚扫到过（说明设备就在附近、链路正在建立） */
    fun freshHit(): Boolean {
        val t = lastHitAt
        return t > 0 && SystemClock.elapsedRealtime() - t <= FRESH_MS
    }

    fun start(ctx: Context, onHit: (addr: String, name: String?) -> Unit) {
        val now = System.currentTimeMillis()
        if (scanning) return
        if (now - lastStartAt < THROTTLE_MS) {
            log("LE 扫描：30s 内刚扫过，跳过")
            return
        }

        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (t: Throwable) {
            null
        }
        if (adapter == null || !adapter.isEnabled) {
            log("LE 扫描：蓝牙不可用，跳过")
            return
        }
        val scanner = try {
            adapter.bluetoothLeScanner
        } catch (t: Throwable) {
            log("LE 扫描：拿不到 scanner（${t.javaClass.simpleName}）")
            null
        } ?: return

        if (!acquireLock(ctx)) {
            log("LE 扫描：另一个进程正在扫，跳过")
            return
        }

        scanning = true
        lastStartAt = now
        log("LE 扫描：开始（10s 窗口，找水月雨广播）")

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
                    // 名字为空时：用「地址前 12 位与已配对的水月雨相同」兜底
                    //（双模耳机 LE 地址与经典地址常常只差末尾两字节）
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

                    log("LE 扫描命中：$addr name=${name ?: "(空)"}")
                    try {
                        scanner.stopScan(this)
                    } catch (t: Throwable) {
                    }
                    scanning = false
                    releaseLock(ctx)
                    lastAddr = addr.uppercase()
                    lastName = name
                    lastHitAt = SystemClock.elapsedRealtime()
                    onHit(addr.uppercase(), name)
                } catch (t: Throwable) {
                    log("LE 扫描回调异常: ${t.javaClass.simpleName}: ${t.message}")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                log("LE 扫描失败 code=$errorCode")
                scanning = false
                releaseLock(ctx)
            }
        }

        try {
            scanner.startScan(null, settings, cb)
        } catch (t: Throwable) {
            log("LE 扫描启动失败: ${t.javaClass.simpleName}: ${t.message}")
            scanning = false
            releaseLock(ctx)
            return
        }

        // 10 秒后自动收尾
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!scanning) return@postDelayed
            try {
                scanner.stopScan(cb)
            } catch (t: Throwable) {
            }
            scanning = false
            releaseLock(ctx)
            log("LE 扫描：窗口结束（未命中）")
        }, WINDOW_MS)
    }

    // ------------------------------------------------------------------

    private fun lockFile(ctx: Context) = File(ctx.filesDir, LOCK_FILE)

    private fun acquireLock(ctx: Context): Boolean {
        return try {
            val f = lockFile(ctx)
            if (f.exists() && System.currentTimeMillis() - f.lastModified() > LOCK_STALE_MS) {
                f.delete()
            }
            f.createNewFile()
        } catch (t: Throwable) {
            false
        }
    }

    private fun releaseLock(ctx: Context) {
        try {
            lockFile(ctx).delete()
        } catch (t: Throwable) {
        }
    }

    private fun log(s: String) = XposedBridge.log("$TAG $s")
}