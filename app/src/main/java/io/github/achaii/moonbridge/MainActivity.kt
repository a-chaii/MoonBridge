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

    private fun pickIconKey() {
        val keys = MoondropProfile.keys()
        val current = SettingsStore.iconKey(this)
        val items = keys.map { if (it == current) "$it（当前）" else it } + listOf("恢复内置图")
        MaterialAlertDialogBuilder(this)
            .setTitle("给哪个型号换头图")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == keys.size) clearIcon() else {
                    pendingKey = keys[which]
                    openImagePicker()
                }
            }
            .setNegativeButton("取消", null)
            .show()
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

    private fun clearIcon() {
        val key = SettingsStore.iconKey(this)
        if (key.isEmpty()) {
            toast("当前没有自定义头图")
            return
        }
        SettingsStore.localIconFile(this, key).delete()
        SettingsStore.clearIconKey(this)
        refreshIconRow()
        toast("已恢复内置图（重启设备空间后生效）")
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