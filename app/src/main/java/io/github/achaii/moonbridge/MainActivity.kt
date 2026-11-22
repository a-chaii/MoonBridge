package io.github.achaii.moonbridge

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * 模块主界面。
 *
 * 风格参照 LSPosed Manager：顶部大标题 + 分组卡片 + 行内开关，配色走 Material 3（跟随系统深浅色）。
 *
 * 它自己不做任何注入 —— 只是「设置面板」：把用户的选择存在本应用，再通过广播同步给
 * 跑在宿主进程里的注入端（见 SettingsStore / SettingsSync）。
 */
class MainActivity : Activity() {

    private lateinit var switchPopup: MaterialSwitch
    private lateinit var switchFast: MaterialSwitch
    private lateinit var switchWind: MaterialSwitch
    private lateinit var switchGain: MaterialSwitch
    private lateinit var tvIconState: TextView
    private lateinit var ivIconPreview: ImageView

    /** 选好型号后，等系统图片选择器回来的那个 key */
    private var pendingKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val version = runCatching { BuildConfig.VERSION_NAME }.getOrDefault("")
        findViewById<MaterialToolbar>(R.id.toolbar).subtitle =
            "${getString(R.string.app_subtitle)} · v$version"
        findViewById<TextView>(R.id.tvVersionChip).text = "v$version"

        switchPopup = findViewById(R.id.switchPopup)
        switchFast = findViewById(R.id.switchFast)
        switchWind = findViewById(R.id.switchWind)
        switchGain = findViewById(R.id.switchGain)
        tvIconState = findViewById(R.id.tvIconState)
        ivIconPreview = findViewById(R.id.ivIconPreview)

        // 先设值再挂监听，否则初始化会触发一次「用户改动」
        switchPopup.isChecked = SettingsStore.autoPopup(this)
        switchPopup.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setAutoPopup(this, checked)
            toast(if (checked) "已开启自动弹窗" else "已关闭自动弹窗")
        }

        // 提速弹窗：GMS 常驻扫描，扫到就弹（默认关，开了才扫）
        switchFast.isChecked = SettingsStore.fastPopup(this)
        switchFast.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setFastPopup(this, checked)
            toast(
                if (checked) "已开启提速弹窗（需要 GMS 作用域）"
                else "已关闭提速弹窗，仍会在连接后弹窗"
            )
        }

        findViewById<View>(R.id.rowIcon).setOnClickListener { pickIconKey() }
        ivIconPreview.setOnClickListener { showLargePreview() }
        findViewById<View>(R.id.rowGithub).setOnClickListener {
            openUrl("https://github.com/a-chaii/MoonBridge")
        }
        findViewById<View>(R.id.rowLicense).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_license)
                .setMessage(
                    "GNU General Public License v3.0\n\n" +
                        "本程序为自由软件：你可以依据自由软件基金会发布的 GPL 第 3 版" +
                        "（或你选择的任何更新版本）条款重新分发和/或修改它。\n\n" +
                        "本程序按「现状」提供，不附带任何担保。完整条款见仓库中的 LICENSE 文件。"
                )
                .setPositiveButton(R.string.ok, null)
                .show()
        }
        findViewById<View>(R.id.rowDisclaimer).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_disclaimer)
                .setMessage(
                    "· 个人项目，非商业，无官方背书。\n" +
                        "· 代码主要由 AI 辅助生成，未经过工程化审查。\n" +
                        "· 只注入「设备空间」这一个应用，最坏情况是它打不开/闪退，关掉模块即恢复。\n" +
                        "· 电量与档位来自耳机回报，仅供参考。\n" +
                        "· 与 OPPO、水月雨官方无关，未获授权。"
                )
                .setPositiveButton(R.string.ok, null)
                .show()
        }
        // 卡片显示偏好
        switchWind.isChecked = SettingsStore.showWind(this)
        switchWind.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setShowWind(this, checked)
            toast(if (checked) "卡片里会显示抗风噪" else "已隐藏抗风噪这一档")
        }
        switchGain.isChecked = SettingsStore.showGain(this)
        switchGain.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setShowGain(this, checked)
            toast(if (checked) "卡片里会显示增益" else "已隐藏增益菜单")
        }

        findViewById<View>(R.id.rowRestart).setOnClickListener { restartHost() }

        findViewById<View>(R.id.rowDebug).setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }

        // 每次进界面把当前设置推一次：注入端可能错过了上一次（当时它没在跑）
        SettingsStore.pushSettings(this)
        refreshIconRow()
    }

    override fun onResume() {
        super.onResume()
        refreshIconRow()
    }

    // ---------------- 头图 ----------------

    private fun refreshIconRow() {
        val key = SettingsStore.iconKey(this)
        val f = if (key.isEmpty()) null else SettingsStore.localIconFile(this, key)
        if (key.isNotEmpty() && f != null && f.exists()) {
            tvIconState.text = "已设置：$key"
            val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
            if (bmp != null) {
                ivIconPreview.setImageBitmap(bmp)
                ivIconPreview.visibility = View.VISIBLE
            }
        } else {
            tvIconState.setText(R.string.feature_icon_none)
            ivIconPreview.visibility = View.GONE
        }
    }

    /**
     * 头图图库：一眼看到**所有型号当前用的头图**（自定义 / 内置 / 默认原图），
     * 而不是"选了照片才看得到预览"。
     */
    private fun pickIconKey() {
        val keys = MoondropProfile.keys()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        for (key in keys) {
            box.addView(buildGalleryRow(key))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        MaterialAlertDialogBuilder(this)
            .setTitle("型号头图")
            .setMessage("点一行就能预览、换图或恢复内置图")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 一行：缩略图 + 型号名 + 当前状态 */
    private fun buildGalleryRow(key: String): View {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(24, 18, 24, 18)
            isClickable = true
            setOnClickListener { showIconActions(key) }
        }
        val thumb = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val lp = android.widget.LinearLayout.LayoutParams(dp(64), dp(64))
        val bmp = currentIconBitmap(key)
        if (bmp != null) {
            thumb.setImageBitmap(bmp)
        } else {
            thumb.setBackgroundColor(0x22808080)
        }
        row.addView(thumb, lp)

        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }
        val name = TextView(this).apply {
            text = key
            textSize = 15f
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        }
        val state = TextView(this).apply {
            textSize = 12f
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        val custom = SettingsStore.localIconFile(this, key)
        state.text = when {
            custom.exists() && custom.length() > 0 -> "自定义头图 · 点击更换"
            key == "GOLDEN AGES 2" -> "内置头图 · 点击更换"
            else -> "默认原图 · 点击设置"
        }
        col.addView(name)
        col.addView(state)
        row.addView(col)
        return row
    }

    /** 某型号当前实际会显示的头图：自定义 > 内置（只有 GA2 有） > null（宿主原图） */
    private fun currentIconBitmap(key: String): android.graphics.Bitmap? {
        val f = SettingsStore.localIconFile(this, key)
        if (f.exists() && f.length() > 0) {
            runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()?.let { return it }
        }
        return if (key == "GOLDEN AGES 2") runCatching { IconAsset.bitmap() }.getOrNull() else null
    }

    /** 点某一行后的操作 */
    private fun showIconActions(key: String) {
        val items = arrayOf("预览当前头图", "换一张图", "恢复内置图")
        MaterialAlertDialogBuilder(this)
            .setTitle(key)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> previewIcon(key)
                    1 -> {
                        pendingKey = key
                        openImagePicker()
                    }
                    else -> clearIcon(key)
                }
            }
            .show()
    }

    /** 预览某个型号当前会用的头图（含内置） */
    private fun previewIcon(key: String) {
        val bmp = currentIconBitmap(key)
        if (bmp == null) {
            toast("$key 用的是宿主原图，没有内置头图")
            return
        }
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(16, 16, 16, 16)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("当前头图：$key")
            .setView(iv)
            .setPositiveButton("换一张") { _, _ ->
                pendingKey = key
                openImagePicker()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 重启设备空间进程：新设置/新代码要它重新加载才生效（走 su，失败就说明情况） */
    private fun restartHost() {
        toast("正在重启设备空间…")
        Thread {
            val ok = try {
                val p = ProcessBuilder("su", "-c", "am force-stop com.heytap.mydevices")
                    .redirectErrorStream(true)
                    .start()
                p.waitFor()
                p.exitValue() == 0
            } catch (t: Throwable) {
                false
            }
            runOnUiThread {
                if (ok) toast("已停止 com.heytap.mydevices，下次打开会重新加载模块")
                else toast("没有拿到 root 权限，请手动在系统里停止「设备空间」")
            }
        }.start()
    }

    /** 取主题色（深浅色都跟着系统走，不写死） */
    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        return if (theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, tv.resourceId)
            else tv.data
        } else 0xFF888888.toInt()
    }

    private fun openImagePicker() {
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent("android.provider.action.PICK_IMAGES").setType("image/*")
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(intent, REQ_PICK)
        } catch (t: Throwable) {
            try {
                startActivityForResult(
                    Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PICK
                )
            } catch (t2: Throwable) {
                toast("没有可用的图片选择器")
            }
        }
    }

    /** 恢复某个型号的内置图（宿主那边那张 jpg 也会被删掉） */
    private fun clearIcon(key: String) {
        val had = SettingsStore.localIconFile(this, key).exists() || SettingsStore.iconKey(this) == key
        if (!had) {
            toast("$key 当前没有自定义头图")
            return
        }
        SettingsStore.clearIcon(this, key)
        refreshIconRow()
        toast("已恢复 $key 的内置图（重启设备空间后生效）")
    }

    @Deprecated("纯 Activity 没有 registerForActivityResult，用旧式回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val key = pendingKey ?: SettingsStore.iconKey(this)
        if (key.isEmpty()) {
            toast("没有选中型号")
            return
        }
        val bmp = runCatching {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        if (bmp == null) {
            toast("读图失败，换一张试试")
            return
        }
        showIconPreviewDialog(key, bmp, uri)
    }

    /** 选完图先预览：「应用 / 重选 / 取消」，不满意不用先写进去再恢复 */
    private fun showIconPreviewDialog(key: String, bmp: android.graphics.Bitmap, uri: Uri) {
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(24, 24, 24, 24)
        }
        val w = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        val h = (bmp.height.toFloat() / bmp.width * w).toInt().coerceAtLeast(120)
        iv.layoutParams = android.view.ViewGroup.LayoutParams(w, h)
        MaterialAlertDialogBuilder(this)
            .setTitle("预览：$key 的头图")
            .setView(iv)
            .setPositiveButton("应用") { _, _ ->
                val f = SettingsStore.pushHeadIcon(this, uri, key)
                if (f == null) {
                    toast("保存失败，换一张试试")
                } else {
                    refreshIconRow()
                    toast("已应用 $key 的头图（重启设备空间后生效）")
                }
            }
            .setNeutralButton("重选") { _, _ ->
                pendingKey = key
                openImagePicker()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 点缩略图看大图 */
    private fun showLargePreview() {
        val key = SettingsStore.iconKey(this)
        if (key.isEmpty()) return
        val f = SettingsStore.localIconFile(this, key)
        val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() ?: return
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(16, 16, 16, 16)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("当前头图：$key")
            .setView(iv)
            .setPositiveButton("换一张") { _, _ -> pickIconKey() }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ---------------- 小工具 ----------------

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            toast("没有可用的浏览器")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PICK = 1001
    }
}