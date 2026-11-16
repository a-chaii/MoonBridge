package io.github.achaii.moonbridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ★ 照抄参考工程 `GaiaBleClient` 的 BLE GATT 传输层。
 *
 * 为什么必须抄这一层：官方 App 的运行日志（`com.fxxkmoondrop.secret/files/appslog/runtime.log`）
 * 里有 184 条 `GAIA RX`，全部来自
 *
 *     connect() addr=24:01:30:B5:58:22 -> GATT connected 24:01:30:B5:0D:22
 *
 * 也就是说它**走的是 BLE GATT（LE 地址 …0D:22）**，不是 SPP/RFCOMM。
 * 我们之前一直用 RFCOMM，设备一声不吭（1.2s 窗口读空），所以永远读不到状态。
 *
 * 官方时序（就是「控制连接前的那段等待」）：
 *   ① BLE 扫描找 LE 地址（SCAN_DURATION_MS = 8000，缓存到 gaia_le_addr.txt）
 *   ② connectGatt(TRANSPORT_LE)
 *   ③ onServicesDiscovered → 取 GAIA 服务 00001100-d102-… 的
 *      command(1101) / response(1102) / data(1103)
 *   ④ enableNotification(response) 且 enableNotification(data, required=false)
 *      —— 即 setCharacteristicNotification + 写 CCCD，等 onDescriptorWrite
 *   ⑤ 这一整套跑完（约 2~4 秒）才 markReady，之后才允许发命令
 */
class GaiaGattTransport(
    private val context: Context,
    private val onPacket: (ByteArray) -> Unit,
    private val onDisconnected: (String) -> Unit,
    private val onLog: (String) -> Unit
) {

    companion object {
        val UUID_SERVICE: UUID = UUID.fromString("00001100-d102-11e1-9b23-00025b00a5a5")
        val UUID_COMMAND: UUID = UUID.fromString("00001101-d102-11e1-9b23-00025b00a5a5")
        val UUID_RESPONSE: UUID = UUID.fromString("00001102-d102-11e1-9b23-00025b00a5a5")
        val UUID_DATA: UUID = UUID.fromString("00001103-d102-11e1-9b23-00025b00a5a5")
        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** 参考实现 GaiaConstants.GATT_PENDING_TIMEOUT_MS */
        const val CONNECT_TIMEOUT_MS = 12000L
        /** 参考实现 GaiaConstants.SCAN_DURATION_MS */
        const val SCAN_DURATION_MS = 8000L

        private const val WRITE_GAP_MS = 30L
        private const val WRITE_WATCHDOG_MS = 400L

        /** 上次成功连上的 LE 地址（参考实现 gaia_le_addr.txt 的作用） */
        @Volatile
        var cachedLeAddress: String? = null

        fun looksLikeTarget(name: String?): Boolean {
            val n = (name ?: "").lowercase()
            return n.contains("moondrop") || n.contains("golden") || n.contains("ages") ||
                n.contains("水月雨") || n.contains("梦回")
        }

        private fun remote(address: String): BluetoothDevice? = try {
            BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
        } catch (t: Throwable) {
            null
        }

        /**
         * 官方 App 自己缓存的 LE 地址，原文在
         * `/data/data/com.fxxkmoondrop.secret/files/gaia_le_addr.txt` = 24:01:30:B5:0D:22。
         *
         * 它不是随机地址（前 3 字节和 BR/EDR 地址同一个 OUI），所以是稳定的；
         * 官方每次连接也都是先拿这个缓存地址去 connectGatt，扫不到才重新扫。
         * 我们在 mydevices 进程里起扫描拿不到结果（实测 8 秒 0 条），所以照官方的
         * 「缓存优先」直接用它。
         */
        const val KNOWN_LE_ADDRESS = "24:01:30:B5:0D:22"

        /**
         * 照抄 `GaiaBleClient.resolveLeAddress`，但返回「候选列表」而不是单个地址 ——
         * 官方也是这样：candidates 数组 + advanceCandidate() 逐个试，全失败才判死。
         *
         * 顺序：①本身是 LE → ②已配对里的同名 LE 条目 → ③上次连上的缓存
         *      → ④官方 App 缓存的那个地址 → ⑤真扫一遍（8s）
         */
        fun resolveLeCandidates(device: BluetoothDevice, log: (String) -> Unit): List<BluetoothDevice> {
            val out = ArrayList<BluetoothDevice>()
            fun add(a: String?) {
                if (a == null) return
                if (out.any { it.address.equals(a, true) }) return
                val d = remote(a) ?: return
                out.add(d)
            }

            // ① 本身已经是 LE 设备
            try {
                if (device.type == BluetoothDevice.DEVICE_TYPE_LE) add(device.address)
            } catch (t: Throwable) {
            }

            // ② 已配对列表里的同名 LE 条目
            try {
                val bonded = BluetoothAdapter.getDefaultAdapter()?.bondedDevices ?: emptySet()
                val target = try {
                    device.name
                } catch (t: Throwable) {
                    null
                }
                for (d in bonded) {
                    val type = try {
                        d.type
                    } catch (t: Throwable) {
                        BluetoothDevice.DEVICE_TYPE_UNKNOWN
                    }
                    val dn = try {
                        d.name
                    } catch (t: Throwable) {
                        null
                    }
                    if (type == BluetoothDevice.DEVICE_TYPE_LE &&
                        ((target != null && target == dn) || looksLikeTarget(dn))
                    ) {
                        add(d.address)
                    }
                }
            } catch (t: Throwable) {
                log("读已配对列表失败: ${t.javaClass.simpleName}")
            }

            // ③ 上次连上的缓存
            add(cachedLeAddress)

            // ③.5 持久化缓存（跨重启有效）—— 上游「生命闭环」那一环：
            //      连成功一次就把 经典→LE 的对应写盘，之后直接拿来用，跳过扫描。
            add(LeCache.get(try { device.address } catch (t: Throwable) { null }))

            // ④ 官方 App 缓存的 LE 地址
            add(KNOWN_LE_ADDRESS)

            // ⑤ 前面都没有，才真扫一遍
            if (out.isEmpty()) {
                val scanned = scanForLe(log)
                if (scanned != null) {
                    cachedLeAddress = scanned
                    add(scanned)
                }
            }

            log("LE 候选: " + out.joinToString(", ") { it.address })
            return out
        }

        /** 扫 8 秒，按 GAIA 服务 UUID 或设备名认领，返回 LE 地址 */
        private fun scanForLe(log: (String) -> Unit): String? {
            val adapter = try {
                BluetoothAdapter.getDefaultAdapter()
            } catch (t: Throwable) {
                null
            } ?: return null
            val scanner = try {
                adapter.bluetoothLeScanner
            } catch (t: Throwable) {
                null
            }
            if (scanner == null) {
                log("BLE 扫描器不可用（权限？）")
                return null
            }
            val latch = CountDownLatch(1)
            val bestAddr = arrayOfNulls<String>(1)
            val bestRssi = intArrayOf(-999)
            val seen = ArrayList<String>()
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    val r = result ?: return
                    val dev = r.device ?: return
                    val name = try {
                        r.scanRecord?.deviceName ?: dev.name
                    } catch (t: Throwable) {
                        null
                    }
                    val uuidHit = try {
                        r.scanRecord?.serviceUuids?.any { it.uuid == UUID_SERVICE } == true
                    } catch (t: Throwable) {
                        false
                    }
                    if (seen.size < 12 && name != null) seen.add("$name@${dev.address}")
                    // 诊断：把每条原始结果都记下来（含无名设备），否则扫到 0 条时无从判断
                    if (seen.size < 12) {
                        val uu = try {
                            r.scanRecord?.serviceUuids?.joinToString(",") { it.uuid.toString().take(8) }
                        } catch (t: Throwable) {
                            null
                        }
                        seen.add("${name ?: "(无名)"}@${dev.address} rssi=${r.rssi} uuid=[$uu]")
                    }
                    if (uuidHit || looksLikeTarget(name)) {
                        if (r.rssi > bestRssi[0]) {
                            bestRssi[0] = r.rssi
                            bestAddr[0] = dev.address
                        }
                        log("扫描命中 ${name ?: "?"} ${dev.address} rssi=${r.rssi} uuid=$uuidHit")
                        latch.countDown()
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    log("扫描失败 errorCode=$errorCode")
                    latch.countDown()
                }
            }
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            try {
                scanner.startScan(null, settings, cb)
            } catch (t: Throwable) {
                log("startScan 异常: ${t.javaClass.simpleName}: ${t.message}")
                return null
            }
            log("开始 BLE 扫描（最多 ${SCAN_DURATION_MS}ms）")
            try {
                latch.await(SCAN_DURATION_MS, TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
            } finally {
                try {
                    scanner.stopScan(cb)
                } catch (t: Throwable) {
                }
            }
            val addr = bestAddr[0]
            if (addr == null) log("扫描没找到目标（看到: ${seen.joinToString()}）")
            return addr
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var cmdChar: BluetoothGattCharacteristic? = null

    @Volatile
    private var respChar: BluetoothGattCharacteristic? = null

    @Volatile
    private var dataChar: BluetoothGattCharacteristic? = null

    @Volatile
    private var ready = false

    @Volatile
    private var closed = false

    /** 0=无 1=等 response 的 CCCD 2=等 data 的 CCCD */
    private var descriptorStep = 0

    private val lock = Any()
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false

    fun isReady(): Boolean = ready && !closed && gatt != null && cmdChar != null

    // ------------------------------------------------------------------
    // 连接（阻塞到「服务发现 + 订阅通知」全部完成）
    // ------------------------------------------------------------------

    fun connect(device: BluetoothDevice, timeoutMs: Long = CONNECT_TIMEOUT_MS): Boolean {
        close()
        closed = false
        val latch = CountDownLatch(1)
        val ok = booleanArrayOf(false)
        val done = booleanArrayOf(false)

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
                try {
                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        onLog("GATT connected ${g?.device?.address} status=$status -> discovering services")
                        handler.postDelayed({
                            try {
                                if (!g!!.discoverServices()) {
                                    onLog("discoverServices() 返回 false")
                                    ok[0] = false
                                    done[0] = true
                                    latch.countDown()
                                }
                            } catch (t: Throwable) {
                                onLog("discoverServices 异常: ${t.message}")
                                ok[0] = false
                                done[0] = true
                                latch.countDown()
                            }
                        }, 150L)
                    } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                        onLog("GATT disconnected status=$status")
                        ready = false
                        if (!done[0]) {
                            ok[0] = false
                            done[0] = true
                            latch.countDown()
                        } else {
                            onDisconnected("链路断开 status=$status")
                        }
                    }
                } catch (t: Throwable) {
                    onLog("onConnectionStateChange 异常: ${t.message}")
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
                try {
                    if (g == null) return
                    val list = try {
                        g.services?.joinToString(" ") { it.uuid.toString() } ?: "(null)"
                    } catch (t: Throwable) {
                        "(异常)"
                    }
                    onLog("GATT services(" + (g.services?.size ?: 0) + "): " + list)
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        onLog("服务发现失败 status=$status")
                        fail(latch, ok, done)
                        return
                    }
                    val svc = g.getService(UUID_SERVICE)
                    if (svc == null) {
                        onLog("没有 GAIA 服务 $UUID_SERVICE")
                        fail(latch, ok, done)
                        return
                    }
                    cmdChar = svc.getCharacteristic(UUID_COMMAND)
                    respChar = svc.getCharacteristic(UUID_RESPONSE)
                    dataChar = svc.getCharacteristic(UUID_DATA)
                    if (cmdChar == null) {
                        onLog("GAIA 服务存在但缺 command 特征")
                        fail(latch, ok, done)
                        return
                    }
                    // 照抄 enableNotification(respChar) / enableNotification(dataChar, required=false)
                    descriptorStep = 1
                    if (!subscribe(g, respChar)) {
                        onLog("订阅 response 失败")
                        fail(latch, ok, done)
                    }
                } catch (t: Throwable) {
                    onLog("onServicesDiscovered 异常: ${t.message}")
                    fail(latch, ok, done)
                }
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt?,
                descriptor: BluetoothGattDescriptor?,
                status: Int
            ) {
                try {
                    if (g == null) return
                    if (descriptorStep == 1) {
                        onLog("CCCD(response) 写入 status=$status")
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            fail(latch, ok, done)
                            return
                        }
                        descriptorStep = 2
                        val d = dataChar
                        if (d != null) {
                            if (!subscribe(g, d)) finishReady(latch, ok, done)
                        } else {
                            finishReady(latch, ok, done)
                        }
                    } else if (descriptorStep == 2) {
                        onLog("CCCD(data) 写入 status=$status（可选，失败也继续）")
                        finishReady(latch, ok, done)
                    }
                } catch (t: Throwable) {
                    onLog("onDescriptorWrite 异常: ${t.message}")
                    finishReady(latch, ok, done)
                }
            }

            override fun onCharacteristicChanged(g: BluetoothGatt?, ch: BluetoothGattCharacteristic?) {
                val value = try {
                    ch?.value
                } catch (t: Throwable) {
                    null
                } ?: return
                if (value.isNotEmpty()) onPacket(value)
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt?,
                ch: BluetoothGattCharacteristic?,
                status: Int
            ) {
                synchronized(lock) { writing = false }
                handler.postDelayed({ writeNext() }, WRITE_GAP_MS)
            }
        }

        val g = try {
            device.connectGatt(context.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (t: Throwable) {
            onLog("connectGatt 异常: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (g == null) {
            onLog("connectGatt 返回 null")
            return false
        }
        gatt = g

        val arrived = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            false
        }
        if (!arrived || !ok[0]) {
            onLog("GATT 握手未完成（arrived=$arrived ok=${ok[0]}），用时约 ${timeoutMs}ms 上限")
            close()
            return false
        }
        return true
    }

    private fun fail(latch: CountDownLatch, ok: BooleanArray, done: BooleanArray) {
        ok[0] = false
        done[0] = true
        latch.countDown()
    }

    private fun finishReady(latch: CountDownLatch, ok: BooleanArray, done: BooleanArray) {
        if (done[0]) return
        ready = true
        ok[0] = true
        done[0] = true
        onLog("GATT 就绪（服务+通知已订阅）${gatt?.device?.address}")
        latch.countDown()
    }

    private fun subscribe(g: BluetoothGatt, ch: BluetoothGattCharacteristic?): Boolean {
        if (ch == null) return false
        val d = ch.getDescriptor(UUID_CCCD) ?: return false
        return try {
            if (!g.setCharacteristicNotification(ch, true)) {
                onLog("setCharacteristicNotification 返回 false (${ch.uuid})")
                false
            } else {
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }
        } catch (t: Throwable) {
            onLog("subscribe 异常: ${t.message}")
            false
        }
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    fun send(packet: ByteArray) {
        if (!isReady()) {
            onLog("GATT 未就绪，丢弃一帧")
            return
        }
        synchronized(lock) { writeQueue.add(packet.copyOf()) }
        writeNext()
    }

    private fun writeNext() {
        val g = gatt ?: return
        val ch = cmdChar ?: return
        val pkt: ByteArray
        synchronized(lock) {
            if (writing) return
            val p = writeQueue.poll() ?: return
            pkt = p
            writing = true
        }
        try {
            val props = ch.properties
            val noResp = (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            val canWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            var type = if (!canWrite && noResp) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            ch.writeType = type
            ch.value = pkt
            var sent = try {
                g.writeCharacteristic(ch)
            } catch (t: Throwable) {
                false
            }
            if (!sent && type == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT && noResp) {
                type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.writeType = type
                ch.value = pkt
                sent = try {
                    g.writeCharacteristic(ch)
                } catch (t: Throwable) {
                    false
                }
            }
            if (!sent) {
                onLog("写入被拒（命令队列忙）")
                synchronized(lock) { writing = false }
                return
            }
            // NO_RESPONSE 某些固件不回 onCharacteristicWrite，加个看门狗免得卡死
            handler.postDelayed({
                val stuck = synchronized(lock) {
                    if (writing) {
                        writing = false
                        true
                    } else {
                        false
                    }
                }
                if (stuck) {
                    onLog("写入看门狗超时，放行队列")
                    writeNext()
                }
            }, WRITE_WATCHDOG_MS)
        } catch (t: Throwable) {
            onLog("写入异常: ${t.message}")
            synchronized(lock) { writing = false }
        }
    }

    // ------------------------------------------------------------------
    // 关闭
    // ------------------------------------------------------------------

    fun close() {
        closed = true
        ready = false
        synchronized(lock) {
            writeQueue.clear()
            writing = false
        }
        val g = gatt
        gatt = null
        cmdChar = null
        respChar = null
        dataChar = null
        descriptorStep = 0
        if (g != null) {
            try {
                g.disconnect()
            } catch (t: Throwable) {
            }
            try {
                g.close()
            } catch (t: Throwable) {
            }
        }
    }

    fun uptime(): Long = SystemClock.elapsedRealtime()
}
