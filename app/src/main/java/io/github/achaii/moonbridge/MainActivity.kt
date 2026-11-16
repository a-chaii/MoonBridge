package io.github.achaii.moonbridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * 测试页：验证 Gaia over SPP 客户端能否真正控制 Golden Ages 2。
 * 这个页面不需要 Xposed，装上去就能用。
 */
class MainActivity : Activity() {

    private val client = GaiaClient()
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }

        statusView = TextView(this).apply {
            text = "未连接"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(statusView, matchWidth())

        root.addView(buttonRow(
            "选择设备" to { chooseDevice() },
            "断开" to { doDisconnect() }
        ))
        root.addView(buttonRow(
            "查版本" to { sendBasicVersion() },
            "查电量" to { sendBattery() },
            "查ANC" to { sendAncQuery() }
        ))
        root.addView(buttonRow(
            "关闭" to { sendAnc(1) },
            "降噪" to { sendAnc(2) },
            "透传" to { sendAnc(4) }
        ))
        root.addView(buttonRow(
            "抗风" to { sendAnc(3) },
            "清空日志" to { logView.text = "" }
        ))

        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(logView, matchWidth())

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            }
        }
        log("就绪。点「选择设备」连耳机。")
    }

    // ---------------- UI helpers ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun buttonRow(vararg items: Pair<String, () -> Unit>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for ((label, action) in items) {
            val b = Button(this).apply {
                text = label
                textSize = 13f
                isAllCaps = false
                setOnClickListener { action() }
            }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        row.setPadding(0, dp(4), 0, dp(4))
        return row
    }

    private fun log(msg: String) {
        android.util.Log.i("MoonBridge", msg)
        runOnUiThread {
            logView.append(msg + "\n")
        }
    }

    private fun setStatus(s: String) {
        runOnUiThread { statusView.text = s }
    }

    private fun runBg(what: String, block: () -> String) {
        worker.execute {
            val t0 = System.currentTimeMillis()
            try {
                val r = block()
                val ms = System.currentTimeMillis() - t0
                runOnUiThread { logView.append("[$what ${ms}ms] $r\n") }
            } catch (e: Throwable) {
                val ms = System.currentTimeMillis() - t0
                runOnUiThread { logView.append("[$what ${ms}ms] 异常: ${e.javaClass.simpleName}: ${e.message}\n") }
            }
        }
    }

    // ---------------- 动作 ----------------

    private fun chooseDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            log("缺 BLUETOOTH_CONNECT 权限")
            return
        }
        val devices = try {
            client.candidateDevices()
        } catch (e: Throwable) {
            log("读取配对列表失败: $e")
            return
        }
        if (devices.isEmpty()) {
            log("没有已配对设备")
            return
        }
        val labels = devices.map { "${it.name ?: "?"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择耳机")
            .setItems(labels) { _, which -> connectTo(devices[which]) }
            .show()
    }

    private fun connectTo(dev: android.bluetooth.BluetoothDevice) {
        setStatus("连接中: ${dev.address}")
        runBg("连接") {
            client.connect(dev)
            setStatus("已连接: ${client.deviceName} ${client.deviceAddress}")
            "OK ${client.deviceName}"
        }
    }

    private fun doDisconnect() {
        runBg("断开") {
            client.close()
            setStatus("未连接")
            "closed"
        }
    }

    private fun ensureConnected(): Boolean {
        if (!client.isConnected) {
            log("请先连接耳机")
            return false
        }
        return true
    }

    private fun showResult(tag: String, r: GaiaClient.Result) {
        log("  TX→RX 原始: ${r.rawHex}")
        if (r.frames.isEmpty()) {
            log("  未能切出帧")
        } else {
            for (f in r.frames) log("  ${GaiaProtocol.describe(f)}")
        }
    }

    private fun sendBasicVersion() {
        if (!ensureConnected()) return
        runBg("查版本") {
            val frame = GaiaProtocol.build(GaiaProtocol.F_BASIC, GaiaProtocol.TYPE_CMD, 0)
            log("  发送: ${client.hex(frame)}")
            val r = client.request(frame)
            showResult("查版本", r)
            r.frames.filter { it.feature == GaiaProtocol.F_BASIC && it.type == GaiaProtocol.TYPE_RESP }
                .joinToString("") { String(it.payload, Charsets.US_ASCII) }
                .ifBlank { "(无响应)" }
        }
    }

    private fun sendBattery() {
        if (!ensureConnected()) return
        runBg("查电量") {
            val frame = GaiaProtocol.build(
                GaiaProtocol.F_BATTERY, GaiaProtocol.TYPE_CMD, 1, byteArrayOf(0x01, 0x02)
            )
            log("  发送: ${client.hex(frame)}")
            val r = client.request(frame)
            showResult("查电量", r)
            val resp = r.frames.firstOrNull { it.feature == GaiaProtocol.F_BATTERY }
            resp?.let { "电量原始: ${it.payloadHex()}" } ?: "(无响应)"
        }
    }

    private fun sendAncQuery() {
        if (!ensureConnected()) return
        runBg("查ANC") {
            val frame = GaiaProtocol.build(
                GaiaProtocol.F_ANC_V2, GaiaProtocol.TYPE_CMD, GaiaProtocol.ANC_GET_MODE
            )
            log("  发送: ${client.hex(frame)}")
            val r = client.request(frame)
            showResult("查ANC", r)
            val resp = r.frames.firstOrNull { it.feature == GaiaProtocol.F_ANC_V2 }
            resp?.let { "当前 ANC 模式 = ${it.payload.joinToString(",") { b -> (b.toInt() and 0xFF).toString() }}" }
                ?: "(无响应)"
        }
    }

    private fun sendAnc(mode: Int) {
        if (!ensureConnected()) return
        val name = when (mode) {
            1 -> "关闭"; 2 -> "降噪"; 3 -> "抗风"; 4 -> "透传"; else -> "mode$mode"
        }
        runBg("设ANC=$name") {
            val frame = GaiaProtocol.build(
                GaiaProtocol.F_ANC_V2, GaiaProtocol.TYPE_CMD, GaiaProtocol.ANC_SET_MODE,
                byteArrayOf(mode.toByte())
            )
            log("  发送: ${client.hex(frame)}")
            val r = client.request(frame)
            showResult("设ANC=$name", r)
            val resp = r.frames.firstOrNull { it.feature == GaiaProtocol.F_ANC_V2 }
            resp?.let { "回包 payload = ${it.payloadHex()}" } ?: "(无响应)"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            client.close()
        } catch (e: Exception) {
        }
        worker.shutdownNow()
    }
}