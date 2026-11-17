package io.github.achaii.moonbridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 只做一件事：把参考工程的传输层/发包路径原样接进来，自己不再写协议逻辑。
 *
 * 组成（全部来自参考实现）：
 *   GaiaRfcommTransport  ← transport.kt（阻塞读线程 + burst 拼包 + 断链回调）
 *   GaiaRfcommFramer     ← GaiaRfcommFramer.kt（官方 FF 帧 / 裸 PDU 流式切帧）
 *   sendCommand()        ← GaiaBleClient.writeCommand（v3Packet + 1 秒内相同帧抑制）
 *   v3Packet             ← GaiaCommands.v3PacketVendor（vendor + cmdValue + payload，无 FF 头）
 *
 * 自己加的东西只有两处非协议代码：
 *   1. 候选设备筛选（找 Moondrop / Golden Ages）；
 *   2. RX 队列：参考实现是回调推给 handler，我们面板注入场景需要一个「可选等待」的接口。
 */
class GaiaClient {

    companion object {
        /** 参考实现 GaiaBleClient.DUP_TX_WINDOW_MS */
        private const val DUP_TX_WINDOW_MS = 1000L
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 旧路径：RFCOMM/SPP（设备在这条路上从不回包，仅作兜底） */
    @Volatile
    private var transport: GaiaRfcommTransport? = null

    /** ★ 官方路径：BLE GATT（唯一能收到回包的那条） */
    @Volatile
    private var gatt: GaiaGattTransport? = null

    var deviceName: String = ""
        private set

    var deviceAddress: String = ""
        private set

    /** 设备回报 ANC 模式（GET 方向的设备码，0-based）时回调 */
    var onAncMode: ((Int) -> Unit)? = null

    /** 设备回报电量时回调：-1 表示这一只没报（参考实现 parseBatteryLevels 的缺省值） */
    var onBattery: ((left: Int, right: Int, case: Int) -> Unit)? = null

    /** 空间音频开关回报（0 关 / 1 开） */
    var onSpatial: ((state: Int) -> Unit)? = null

    /** 头部追踪档位回报（0 关闭追踪 / 1 30° / 2 全方位） */
    var onHeadTracking: ((mode: Int) -> Unit)? = null
    /** 增益档回报（设备码；0/1/2 的具体含义由型号档案的 gainMap 决定） */
    var onGain: ((dev: Int) -> Unit)? = null
    /** 指示灯开关回报 */
    var onLed: ((on: Boolean) -> Unit)? = null

    val isConnected: Boolean
        get() = gatt?.isReady() == true || transport?.isReady() == true

    /** 当前链路类型，只用于日志 */
    val linkKind: String
        get() = when {
            gatt?.isReady() == true -> "GATT"
            transport?.isReady() == true -> "RFCOMM"
            else -> "无"
        }

    /** 收到的 PDU（来自 framer 切分后的完整帧） */
    private val rxPdus = ArrayList<ByteArray>()

    /** 参考实现：相同 (feature, cmd, payload) 在 1s 内只发一次 */
    private val lastTxAtByKey = HashMap<String, Long>()

    /** 日志出口，由注入器塞进来（Logcat + XposedBridge 双写） */
    var logger: ((String) -> Unit)? = null

    private fun log(s: String) {
        logger?.invoke(s)
    }

    // ------------------------------------------------------------------
    // 设备
    // ------------------------------------------------------------------

    fun candidateDevices(): List<BluetoothDevice> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        val bonded = try {
            adapter.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            return emptyList()
        }
        return bonded.sortedBy { (it.name ?: "") }.toList()
    }

    fun pickMoondrop(): BluetoothDevice? =
        candidateDevices().firstOrNull { d ->
            val n = (d.name ?: "").lowercase()
            n.contains("moondrop") || n.contains("golden") || n.contains("ages")
        }

    // ------------------------------------------------------------------
    // 连接 / 断开
    // ------------------------------------------------------------------

    /**
     * ★ 照抄官方路径：BLE GATT。
     *
     * 官方日志的铁证：`connect() addr=…58:22` 之后紧接着
     * `GATT connected 24:01:30:B5:0D:22 -> discovering services`，
     * 然后才有 184 条 `GAIA RX`。所以「能读到状态」的前提就是 GATT + 订阅通知。
     *
     * 阻塞到「服务发现 + CCCD 订阅」都完成才返回（最长 12s），
     * 这段等待就是官方 App 在控制可用前憋着的那几秒。
     */
    fun connectGatt(ctx: Context, device: BluetoothDevice): Boolean {
        close()
        // 持久化缓存要在解析候选之前就绪（命中就跳过扫描）
        LeCache.attach(ctx)
        val t = GaiaGattTransport(
            ctx,
            onPacket = { pdu -> onRx(pdu) },
            onDisconnected = { why -> log("GATT 断链: $why") },
            onLog = { s -> log("GATT: $s") }
        )
        val candidates = GaiaGattTransport.resolveLeCandidates(device) { s -> log("LE 解析: $s") }
        if (candidates.isEmpty()) {
            log("没有任何 LE 候选地址，GATT 连接放弃")
            return false
        }
        for (le in candidates) {
            log("GATT 尝试连接 ${le.address}")
            if (t.connect(le)) {
                gatt = t
                deviceAddress = le.address
                deviceName = try {
                    le.name ?: device.name ?: "?"
                } catch (e: SecurityException) {
                    "?"
                }
                GaiaGattTransport.cachedLeAddress = le.address
                // 写回持久化缓存：下次连接直接命中，跳过扫描（上游的「秒连」闭环）
                LeCache.put(ctx, try { device.address } catch (e: Throwable) { null }, le.address)
                return true
            }
            log("GATT 连接 ${le.address} 失败，换下一个候选")
        }
        log("所有 LE 候选都失败")
        // 全部失败说明缓存里的地址已经不对了（换设备 / 固件重置）→ 作废，下次重新扫
        LeCache.invalidate(ctx, try { device.address } catch (e: Throwable) { null })
        t.close()
        return false
    }

    /** 收到一帧 PDU：入队给 request() 用，并解析 ANC 模式回报 */
    private fun onRx(pdu: ByteArray) {
        log("RX ${hex(pdu)}")
        synchronized(rxPdus) {
            rxPdus.add(pdu)
            (rxPdus as Object).notifyAll()
        }
        try {
            parseAncMode(pdu)
            parseBatteryLevels(pdu)
            parseSpatial(pdu)
            parseFeatures(pdu)
            parseGain(pdu)
            parseLed(pdu)
        } catch (t: Throwable) {
            log("解析回包失败: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // 扩展设备控制：增益（F_DAC_GAIN 0x0F）/ 指示灯（F_LED 0x13）
    //   照上游 GaiaCommands.dacGainGet/Set、ledGet/Set：
    //     GET  feature=0x0F cmd=1 → 回 00 1D 1F 01 <level>
    //     SET  feature=0x0F cmd=2 <level>
    //     GET  feature=0x13 cmd=1 → 回 00 1D 27 01 <0/1>
    //     SET  feature=0x13 cmd=2 <0/1>
    // ------------------------------------------------------------------
    fun fetchGain(): Boolean = sendCommand(GaiaProtocol.F_DAC_GAIN, GaiaProtocol.DAC_GET_GAIN)

    fun setGain(devLevel: Int): Boolean = sendCommand(
        GaiaProtocol.F_DAC_GAIN, GaiaProtocol.DAC_SET_GAIN, byteArrayOf(devLevel.toByte())
    )

    fun fetchLed(): Boolean = sendCommand(GaiaProtocol.F_LED, GaiaProtocol.LED_GET_STATE)

    fun setLed(on: Boolean): Boolean = sendCommand(
        GaiaProtocol.F_LED, GaiaProtocol.LED_SET_STATE, byteArrayOf((if (on) 1 else 0).toByte())
    )

    private fun parseGain(pdu: ByteArray) {
        if (pdu.size < 5) return
        val vendor = ((pdu[0].toInt() and 0xFF) shl 8) or (pdu[1].toInt() and 0xFF)
        if (vendor != 0x001D) return
        val cmdValue = ((pdu[2].toInt() and 0xFF) shl 8) or (pdu[3].toInt() and 0xFF)
        val feature = cmdValue shr 9
        val type = (cmdValue shr 7) and 0x03
        val cmd = cmdValue and 0x7F
        if (type != GaiaProtocol.TYPE_RESP) return
        if (feature != GaiaProtocol.F_DAC_GAIN || cmd != GaiaProtocol.DAC_GET_GAIN) return
        val level = pdu[4].toInt() and 0xFF
        log("设备回报增益：dev=$level")
        onGain?.invoke(level)
    }

    private fun parseLed(pdu: ByteArray) {
        if (pdu.size < 5) return
        val vendor = ((pdu[0].toInt() and 0xFF) shl 8) or (pdu[1].toInt() and 0xFF)
        if (vendor != 0x001D) return
        val cmdValue = ((pdu[2].toInt() and 0xFF) shl 8) or (pdu[3].toInt() and 0xFF)
        val feature = cmdValue shr 9
        val type = (cmdValue shr 7) and 0x03
        val cmd = cmdValue and 0x7F
        if (type != GaiaProtocol.TYPE_RESP) return
        if (feature != GaiaProtocol.F_LED || cmd != GaiaProtocol.LED_GET_STATE) return
        val on = (pdu[4].toInt() and 0xFF) != 0
        log("设备回报指示灯：${if (on) "开" else "关"}")
        onLed?.invoke(on)
    }

    /**
     * 能力位图：BASIC(0x00) / GET_SUPPORTED_FEATURES(0x01) 的回包。
     *
     * 只做「累加」不做「覆盖」——固件可能分页回，也可能只在某些时机回一页。
     */
    private fun parseFeatures(pdu: ByteArray) {
        if (pdu.size < 5) return
        val vendor = ((pdu[0].toInt() and 0xFF) shl 8) or (pdu[1].toInt() and 0xFF)
        if (vendor != 0x001D) return
        val cmdValue = ((pdu[2].toInt() and 0xFF) shl 8) or (pdu[3].toInt() and 0xFF)
        val feature = cmdValue shr 9
        val type = (cmdValue shr 7) and 0x03
        val cmd = cmdValue and 0x7F
        if (type != GaiaProtocol.TYPE_RESP) return
        if (feature != GaiaProtocol.F_BASIC || cmd != 0x01) return

        val payload = pdu.copyOfRange(4, pdu.size)
        val add = Capabilities.parseFeatures(payload)
        if (add.isEmpty()) return
        features = features + add
        log(
            "能力位图 features=[${features.sorted().joinToString(",")}]" +
                " 空间音频=${Capabilities.hasSpatial(features)}" +
                " ANC路径=${Capabilities.ancPathOf(features)}"
        )
    }

    /**
     * 查左右耳电量 —— 照抄官方 GaiaBleClient.fetchBatteryLevels：
     *
     *     val payload = byteArrayOf(BATTERY_LEFT.toByte(), BATTERY_RIGHT.toByte())
     *     writeCommand(FEATURE_BATTERY, CMD_GET_BATTERY_LEVELS, payload)
     *
     * 也就是发 `00 1D 1A 01 01 02`。官方在 scheduleInitialQueries 里 1200ms 前置之后调它，
     * 之后 HeadsetDetectService 每 30 秒再刷一次。
     */
    fun fetchBatteryLevels(): Boolean = sendCommand(
        GaiaProtocol.F_BATTERY,
        GaiaProtocol.BATT_GET_LEVELS,
        byteArrayOf(GaiaProtocol.BATT_LEFT.toByte(), GaiaProtocol.BATT_RIGHT.toByte())
    )

    /**
     * `00 1D 1B 01 <id 级> <id 级>…` → 逐对解析。
     * 官方 GaiaPacketHandler.parseBatteryLevels 原文：
     *   奇数长度或不足 2 字节直接丢弃；每 2 字节一组 (id, level)；1=左耳 2=右耳 3=充电盒。
     */
    private fun parseBatteryLevels(pdu: ByteArray) {
        if (pdu.size < 6) return
        val vendor = ((pdu[0].toInt() and 0xFF) shl 8) or (pdu[1].toInt() and 0xFF)
        if (vendor != 0x001D) return
        val cmdValue = ((pdu[2].toInt() and 0xFF) shl 8) or (pdu[3].toInt() and 0xFF)
        val feature = cmdValue shr 9
        val type = (cmdValue shr 7) and 0x03
        val cmd = cmdValue and 0x7F
        if (type != GaiaProtocol.TYPE_RESP) return
        if (feature != GaiaProtocol.F_BATTERY || cmd != GaiaProtocol.BATT_GET_LEVELS) return

        val payload = pdu.copyOfRange(4, pdu.size)
        if (payload.size < 2 || payload.size % 2 != 0) {
            log("电量回包长度异常 ${payload.size}，丢弃")
            return
        }
        var left = -1
        var right = -1
        var case = -1
        var i = 0
        val sb = StringBuilder()
        while (i + 1 < payload.size) {
            val id = payload[i].toInt() and 0xFF
            val level = payload[i + 1].toInt() and 0xFF
            sb.append(" id=$id level=$level")
            when (id) {
                GaiaProtocol.BATT_LEFT -> left = level
                GaiaProtocol.BATT_RIGHT -> right = level
                GaiaProtocol.BATT_CASE -> case = level
            }
            i += 2
        }
        log("设备回报电量:$sb")
        onBattery?.invoke(left, right, case)
    }

    // ------------------------------------------------------------------
    // 空间音频 / 头部追踪（照抄 GaiaBleClient.fetchSpatial/setSpatial/…）
    // ------------------------------------------------------------------

    /** `writeCommand(FEATURE_SPATIAL_AUDIO, CMD_SPATIAL_GET_STATE, 空)` → 00 1D 24 01 */
    fun fetchSpatial(): Boolean = sendCommand(
        GaiaProtocol.F_SPATIAL, GaiaProtocol.SP_GET_STATE
    )

    /** `setSpatial(state)` → 00 1D 24 02 <0/1> */
    fun setSpatial(state: Int): Boolean = sendCommand(
        GaiaProtocol.F_SPATIAL, GaiaProtocol.SP_SET_STATE, byteArrayOf(state.toByte())
    )

    /** `fetchHeadTracking()` → 00 1D 24 03 */
    fun fetchHeadTracking(): Boolean = sendCommand(
        GaiaProtocol.F_SPATIAL, GaiaProtocol.SP_GET_TRACK
    )

    /** `setHeadTracking(state)` → 00 1D 24 04 <0/1/2> */
    fun setHeadTracking(state: Int): Boolean = sendCommand(
        GaiaProtocol.F_SPATIAL, GaiaProtocol.SP_SET_TRACK, byteArrayOf(state.toByte())
    )

    /**
     * 空间音频回包（官方 GaiaPacketHandler 的 FEATURE_SPATIAL_AUDIO 分支）：
     *   `00 1D 25 01 <state>` → onSpatial
     *   `00 1D 25 03 <mode>`  → onHeadTracking
     * SET 的 ACK 官方是忽略的，只靠 300ms 后再 GET 一次确认。
     */
    private fun parseSpatial(pdu: ByteArray) {
        if (pdu.size < 5) return
        val vendor = ((pdu[0].toInt() and 0xFF) shl 8) or (pdu[1].toInt() and 0xFF)
        if (vendor != 0x001D) return
        val cmdValue = ((pdu[2].toInt() and 0xFF) shl 8) or (pdu[3].toInt() and 0xFF)
        val feature = cmdValue shr 9
        val type = (cmdValue shr 7) and 0x03
        val cmd = cmdValue and 0x7F
        if (type != GaiaProtocol.TYPE_RESP) return
        if (feature != GaiaProtocol.F_SPATIAL) return
        val state = pdu[4].toInt() and 0xFF
        when (cmd) {
            GaiaProtocol.SP_GET_STATE -> {
                log("设备回报空间音频 state=$state")
                onSpatial?.invoke(state)
            }
            GaiaProtocol.SP_GET_TRACK -> {
                log("设备回报头部追踪 mode=$state")
                onHeadTracking?.invoke(state)
            }
            else -> {
                // SET 的 ACK：官方也是忽略，等 300ms 后的 GET 回读
            }
        }
    }

    /**
     * `00 1D 11 03 <dev> 01 00 00` → feature=0x08, type=RESPONSE, cmd=0x03, payload[0]=设备码。
     * 官方日志里的 `ancModeParse path=8 dev=N ui=N` 就是这条。
     */
    private fun parseAncMode(pdu: ByteArray) {
        if (pdu.size < 5) return
        val vendor = ((pdu[0].toInt() and 0xFF) shl 8) or (pdu[1].toInt() and 0xFF)
        if (vendor != 0x001D) return
        val cmdValue = ((pdu[2].toInt() and 0xFF) shl 8) or (pdu[3].toInt() and 0xFF)
        val feature = cmdValue shr 9
        val type = (cmdValue shr 7) and 0x03
        val cmd = cmdValue and 0x7F
        if (type != GaiaProtocol.TYPE_RESP) return
        if (feature == GaiaProtocol.F_AUDIO_CURATION && cmd == GaiaProtocol.ANC_GET_MODE) {
            if (ancPath == 0) ancPath = GaiaProtocol.F_AUDIO_CURATION
            val dev = pdu[4].toInt() and 0xFF
            log("设备回报 ANC 模式 dev=$dev（path=AC/0x08）")
            onAncMode?.invoke(dev)
        } else if (feature == GaiaProtocol.F_ANC_V2 && cmd == GaiaProtocol.ANC_GET_MODE) {
            // 先回的赢：两条都回包时不要被后到的覆盖（GA2 只回 AC，布丁只回 V2）
            if (ancPath == 0) ancPath = GaiaProtocol.F_ANC_V2
            val dev = pdu[4].toInt() and 0xFF
            log("设备回报 ANC 模式 dev=$dev（path=V2/0x20）")
            onAncMode?.invoke(dev)
        }
    }

    /**
     * 探测出来的 ANC 路径：0 = 还没有任何一帧回包 / 8 = AudioCuration / 0x20 = ANC_V2。
     *
     * 握手时会同时探 AC 与 V2 两条，谁的 getMode **真的回了** 就记谁 ——
     * 这比按型号名猜可靠：型号档案只是兜底（见 PanelInjector.ancFeature()）。
     */
    @Volatile
    var ancPath = 0

    /**
     * 设备自报的能力位图（GET_SUPPORTED_FEATURES 回包，累积）。
     * 空集 = 还没收到（不是「什么都不支持」），调用方要区分这两种情况。
     */
    @Volatile
    var features: Set<Int> = emptySet()
        private set

    /** 阻塞连接（必须在工作线程调用）—— RFCOMM/SPP 兜底路径 */
    fun connect(device: BluetoothDevice) {
        close()
        val t = GaiaRfcommTransport(
            handler,
            onPacket = { pdu -> onRx(pdu) },
            onDisconnected = { log("链路被设备关闭") },
            onError = { msg -> log("传输错误: $msg") }
        )
        if (!t.connect(device)) {
            log("连接失败 ${device.address}")
            throw IllegalStateException("RFCOMM 连接失败")
        }
        transport = t
        deviceAddress = device.address
        deviceName = try {
            device.name ?: "?"
        } catch (e: SecurityException) {
            "?"
        }
    }

    fun close() {
        try {
            gatt?.close()
        } catch (t: Throwable) {
        }
        gatt = null
        try {
            transport?.disconnect()
        } catch (t: Throwable) {
        }
        transport = null
        synchronized(rxPdus) { rxPdus.clear() }
        lastTxAtByKey.clear()
    }

    // ------------------------------------------------------------------
    // 发送（照抄参考实现 writeCommand）
    // ------------------------------------------------------------------

    /**
     * 发送一条 GAIA 命令。
     *
     * 参考实现原文（GaiaBleClient.writeCommand）：
     *   「相同 (feature, cmd, payload) 的请求在 1s 内只发一次。实测连接刚建立时
     *     3.5s 内发了 6 次 ANC 查询 + 5 次电量查询（多个调用方叠加），全是重复请求
     *     —— 在本来就脆弱的固件上纯属增加下行压力。只按「完全相同」去重。」
     *
     * @return true = 真的写出去了；false = 被 1s 去重挡掉
     */
    fun sendCommand(feature: Int, command: Int, payload: ByteArray = ByteArray(0)): Boolean {
        if (!isConnected) {
            log("未连接，丢弃 feature=0x${Integer.toHexString(feature)} cmd=0x${Integer.toHexString(command)}")
            return false
        }
        val key = Integer.toHexString(feature) + ":" + Integer.toHexString(command) + ":" +
            payload.contentToString()
        val now = System.currentTimeMillis()
        val prev = lastTxAtByKey[key]
        if (prev != null && now - prev < DUP_TX_WINDOW_MS) {
            log("TX 抑制（1s 内重复 ${now - prev}ms）$key")
            return false
        }
        lastTxAtByKey[key] = now
        if (lastTxAtByKey.size > 64) lastTxAtByKey.clear()

        val packet = GaiaProtocol.build(feature, GaiaProtocol.TYPE_CMD, command, payload)
        log("TX feature=0x${Integer.toHexString(feature)} cmd=0x${Integer.toHexString(command)} ${payload.contentToString()} | ${hex(packet)} [$linkKind]")
        sendRaw(packet)
        return true
    }

    /** 真正落地写：优先 GATT（官方路径），RFCOMM 仅兜底 */
    private fun sendRaw(packet: ByteArray) {
        val g = gatt
        if (g != null && g.isReady()) {
            g.send(packet)
            return
        }
        transport?.send(packet)
    }

    /**
     * 发一帧并尽量把这段窗口内收到的回包拿回来。
     * GA2 实测不回任何 ACK，所以窗口通常读空 —— 这里只是保留「能读就读」的能力。
     */
    fun request(feature: Int, command: Int, payload: ByteArray, windowMs: Long): Result {
        synchronized(rxPdus) { rxPdus.clear() }
        val sent = sendCommand(feature, command, payload)
        val raw = StringBuilder()
        val frames = ArrayList<GaiaProtocol.Frame>()
        val deadline = System.currentTimeMillis() + windowMs
        synchronized(rxPdus) {
            while (true) {
                val now = System.currentTimeMillis()
                if (rxPdus.isNotEmpty()) {
                    for (pdu in rxPdus) {
                        raw.append(hex(pdu)).append(' ')
                        frames.addAll(GaiaProtocol.parseAll(pdu))
                    }
                    rxPdus.clear()
                    break
                }
                if (now >= deadline || !sent) break
                try {
                    (rxPdus as Object).wait(20L)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        return Result(frames, if (raw.isEmpty()) "" else raw.toString().trim())
    }

    data class Result(val frames: List<GaiaProtocol.Frame>, val rawHex: String)

    /**
     * 诊断用：直接发一段裸字节（不组帧、不去重），并尽量收回包。
     * 只给模块自带的调试 Activity 用，面板注入路径一律走 sendCommand。
     */
    fun request(frame: ByteArray, windowMs: Long = 1200L): Result {
        if (!isConnected) return Result(emptyList(), "(未连接)")
        synchronized(rxPdus) { rxPdus.clear() }
        sendRaw(frame)
        val frames = ArrayList<GaiaProtocol.Frame>()
        val raw = StringBuilder()
        val deadline = System.currentTimeMillis() + windowMs
        synchronized(rxPdus) {
            while (true) {
                if (rxPdus.isNotEmpty()) {
                    for (pdu in rxPdus) {
                        raw.append(hex(pdu)).append(' ')
                        frames.addAll(GaiaProtocol.parseAll(pdu))
                    }
                    rxPdus.clear()
                    break
                }
                if (System.currentTimeMillis() >= deadline) break
                try {
                    (rxPdus as Object).wait(20L)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        return Result(frames, if (raw.isEmpty()) "(无数据)" else raw.toString().trim())
    }

    fun hex(b: ByteArray): String =
        if (b.isEmpty()) "(无数据)" else b.joinToString(" ") { "%02X".format(it) }
}