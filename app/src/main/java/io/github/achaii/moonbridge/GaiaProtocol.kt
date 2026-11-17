package io.github.achaii.moonbridge

/**
 * Gaia V3/V4 协议（高通 QTiL）+ Moondrop 耳机实际用法。
 *
 * 传输帧（RFCOMM/SPP）：
 *   [FF][VER][FLAGS][LEN][PDU(LEN+4)][可选 1B 校验和]
 *   FLAGS bit0 = 带校验和；bit1 = LEN 为 2 字节（仅 VER>=4）
 *
 * PDU：
 *   vendor(2B BE) | cmdValue(2B BE) | payload
 *   cmdValue = (feature shl 9) | (type shl 7) | cmd
 */
object GaiaProtocol {

    const val SOF = 0xFF
    const val VER_V1 = 0x01
    const val VER_V3 = 0x04
    const val VENDOR_QTI = 0x001D

    // type
    const val TYPE_CMD = 0
    const val TYPE_NOTIF = 1
    const val TYPE_RESP = 2
    const val TYPE_ERR = 3

    // feature
    const val F_BASIC = 0
    const val F_MUSIC_PROCESSING = 5
    const val F_AUDIO_CURATION = 8
    const val F_BATTERY = 13
    const val F_CODEC_TYPE = 16
    const val F_SPATIAL = 18
    const val F_ONEBRINGTWO = 20
    const val F_ANC_V1 = 2
    const val F_ANC_V2 = 32
    /**
     * 扩展设备控制（上游 DcProfile 用的那条通道）：
     *   F_DAC_GAIN = 0x0F，CMD 1=GET 2=SET payload[0]=档位
     *   F_LED      = 0x13，CMD 1=GET 2=SET payload[0]=0/1
     */
    const val F_DAC_GAIN = 15
    const val F_LED = 19
    const val DAC_GET_GAIN = 1
    const val DAC_SET_GAIN = 2
    const val LED_GET_STATE = 1
    const val LED_SET_STATE = 2

    // ANC 命令
    const val ANC_GET_MODE = 3
    const val ANC_SET_MODE = 4

    // 电量命令（参考 GaiaConstants: CMD_GET_BATTERY_LEVELS = 0x01）
    //   发：writeCommand(FEATURE_BATTERY, 0x01, byteArrayOf(左耳1, 右耳2))
    //       → 裸 PDU 00 1D 1A 01 01 02
    //   回：00 1D 1B 01 <id 级> <id 级>…
    //       官方日志实证：GAIA RX 00 1D 1B 01 01 64 02 64  = 左耳100% 右耳100%
    const val BATT_GET_LEVELS = 1
    const val BATT_LEFT = 1
    const val BATT_RIGHT = 2
    const val BATT_CASE = 3

    // 空间音频 / 头部追踪（参考 GaiaConstants / GaiaCommands）
    //   FEATURE_SPATIAL_AUDIO = 0x12
    //   CMD 1 = GET spatial state      → 00 1D 24 01
    //   CMD 2 = SET spatial state      → 00 1D 24 02 <0/1>
    //   CMD 3 = GET head tracking      → 00 1D 24 03
    //   CMD 4 = SET head tracking      → 00 1D 24 04 <0=关闭追踪 1=30° 2=全方位>
    //   回包：00 1D 25 01 <state> / 00 1D 25 03 <mode>
    const val SP_GET_STATE = 1
    const val SP_SET_STATE = 2
    const val SP_GET_TRACK = 3
    const val SP_SET_TRACK = 4
    /** 三档：0=关闭追踪 1=30° 2=全方位（官方 AncProfileLib.trackingLabels） */
    const val TRACK_OFF = 0
    const val TRACK_30 = 1
    const val TRACK_FULL = 2

    data class Frame(
        val ver: Int,
        val vendor: Int,
        val type: Int,
        val feature: Int,
        val cmd: Int,
        val payload: ByteArray,
        val raw: ByteArray
    ) {
        fun hex(): String = raw.joinToString(" ") { "%02X".format(it) }
        fun payloadHex(): String =
            if (payload.isEmpty()) "(空)" else payload.joinToString(" ") { "%02X".format(it) }
    }

    /**
     * 组一个 PDU：vendor(2B BE) + cmdValue(2B BE) + payload。
     *
     * 注意：这就是参考实现 GaiaCommands.v3Packet 的全部内容 —— 官方 App 在 RFCOMM 上
     * **直接发裸 PDU**（`00 1D 40 04 02`），外面没有 `FF ver flags len` 那一层。
     * 我们之前多包了一层传输头，设备一声不吭全丢了。
     */
    fun build(
        feature: Int,
        type: Int,
        cmd: Int,
        payload: ByteArray = ByteArray(0),
        ver: Int = VER_V3
    ): ByteArray {
        val cmdValue = (feature shl 9) or (type shl 7) or cmd
        val pdu = ByteArray(4 + payload.size)
        pdu[0] = ((VENDOR_QTI ushr 8) and 0xFF).toByte()
        pdu[1] = (VENDOR_QTI and 0xFF).toByte()
        pdu[2] = ((cmdValue ushr 8) and 0xFF).toByte()
        pdu[3] = (cmdValue and 0xFF).toByte()
        payload.copyInto(pdu, 4)
        return pdu
    }

    /**
     * 可选的传输帧封装：[FF][VER][FLAGS][LEN][PDU][可选校验和]。
     * 部分固件要走这一层，作为裸 PDU 无回包时的退路。
     */
    fun wrap(pdu: ByteArray, ver: Int = VER_V3, flags: Int = 0): ByteArray {
        val payloadLen = pdu.size - 4
        val out = ByteArray(4 + pdu.size)
        out[0] = SOF.toByte()
        out[1] = ver.toByte()
        out[2] = flags.toByte()
        out[3] = payloadLen.toByte()
        pdu.copyInto(out, 4)
        return out
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    /**
     * 从一段流数据里切出所有帧。
     * 兼容两种封装：FF 传输帧、裸 PDU（00 1D 开头）。
     */
    fun parseAll(buf: ByteArray): List<Frame> {
        val out = ArrayList<Frame>()
        var i = 0
        while (i < buf.size) {
            // 1) FF 传输帧
            if ((buf[i].toInt() and 0xFF) == SOF && i + 4 <= buf.size) {
                val ver = buf[i + 1].toInt() and 0xFF
                val flags = buf[i + 2].toInt() and 0xFF
                var header = 4
                var len = buf[i + 3].toInt() and 0xFF
                if ((flags and 0x02) != 0 && ver >= VER_V3) {
                    if (i + 5 > buf.size) break
                    len = ((buf[i + 3].toInt() and 0xFF) shl 8) or (buf[i + 4].toInt() and 0xFF)
                    header = 5
                }
                val pduLen = len + 4
                val csLen = if ((flags and 0x01) != 0) 1 else 0
                val total = header + pduLen + csLen
                if (i + total > buf.size) break        // 半截帧，等后续
                val pdu = buf.copyOfRange(i + header, i + header + pduLen)
                out.add(makeFrame(ver, pdu, buf.copyOfRange(i, i + total)))
                i += total
                continue
            }
            // 2) 裸 PDU：00 1D 开头，无长度字段
            if ((buf[i].toInt() and 0xFF) == 0x00 && i + 1 < buf.size &&
                (buf[i + 1].toInt() and 0xFF) == 0x1D && i + 4 <= buf.size
            ) {
                val pdu = buf.copyOfRange(i, buf.size)
                out.add(makeFrame(VER_V3, pdu, pdu))
                break
            }
            i++
        }
        return out
    }

    private fun makeFrame(ver: Int, pdu: ByteArray, raw: ByteArray): Frame {
        val vendor = if (pdu.size >= 4) u16(pdu, 0) else 0
        val cmdValue = if (pdu.size >= 4) u16(pdu, 2) else 0
        val payload = if (pdu.size > 4) pdu.copyOfRange(4, pdu.size) else ByteArray(0)
        return Frame(
            ver = ver,
            vendor = vendor,
            type = (cmdValue shr 7) and 0x03,
            feature = cmdValue shr 9,
            cmd = cmdValue and 0x7F,
            payload = payload,
            raw = raw
        )
    }

    fun describe(f: Frame): String {
        val typeName = when (f.type) {
            TYPE_CMD -> "CMD"
            TYPE_NOTIF -> "NOTIF"
            TYPE_RESP -> "RESP"
            TYPE_ERR -> "ERR"
            else -> "T${f.type}"
        }
        return "$typeName f=${f.feature} cmd=${f.cmd} payload=${f.payloadHex()}"
    }
}