package io.github.achaii.moonbridge

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * 往 com.heytap.mydevices 的耳机面板里插一张「降噪模式」卡。
 *
 * ── 血泪史（三次崩溃换来的）──────────────────────────────
 * 1. 模块 ClassLoader 解析不到宿主里的 androidx 类 → 全程反射，不 import 任何 androidx 类型。
 * 2. RecyclerView$ViewHolder 这类内嵌类被 R8 改了名 → 不猜类名，从方法签名反推。
 * 3. 改了 itemCount / viewType，而 onCreateViewHolder 的钩子被 ART 内联掉没触发
 *    → 原 Adapter 拿到未知 viewType，直接 IndexOutOfBounds 崩掉面板。
 *
 * ── 所以现在的原则：不改列表结构 ──────────────────────────
 * 借用一格「本来就是空的」位置（dump 实测 [5]、[6] 是空 FrameLayout），
 * 在绑定那一格时把容器里的内容换成我们的卡片。itemCount / viewType / holder 创建
 * 全都不碰 —— 最坏的结果是卡片不显示，没有任何可能导致宿主崩溃的路径。
 */
object PanelInjector {

    private const val TAG = "[MoonBridge]"

    /**
     * 借用的格子。dump 实测 7 条里 [1]、[5]、[6] 是空 FrameLayout；
     * 但日志证明面板只会绑定 0/1/2（其余在可视区外，滚不到就不 bind），
     * 所以只能借 [1] —— 它就在耳机头图正下方，永远在可视区内。
     */
    private const val SLOT = 1

    /** 打在我们塞进去的卡片上的标记 */
    private const val MARK_VIEW = 0x4D4F4F4E
    /** 打在「图标已替换过」的那格 itemView 上的 keyed tag（不动宿主自己的 tag） */
    private const val MARK_ICON = 0x4D4F4F49

    /**
     * 换图标只对这一个型号生效。
     *
     * IconAsset 里那张 490×284 是**梦回 2** 的产品图，套到太空漫游 / 知更鸟 / 布丁上就是错的图。
     * 所以这里按型号档案卡住：只有档案 key 等于这个值才换。
     */
    private const val ICON_PROFILE = "GOLDEN AGES 2"

    /** 「跳过换图标」已经打过日志的型号，避免刷屏 */
    private var iconSkipLoggedFor: String? = null
    /** 耳机产品图所在的列表位置 */
    private const val ICON_POS = 0

    /** ★ 临时诊断开关：卡片底部显示「码0~码4」直发按钮，用于实测设备码映射。测完改 false */
    private const val PROBE = false

    /**
     * 当前面板对应的设备名（宿主 Intent 里的 `device_title`）与其型号档案。
     *
     * 以前这里是两份硬编码的 GA2 映射；现在映射由 [MoondropProfile] 按型号给，
     * 面板注入的门禁也走这里（不是水月雨就整块不动，见 bind 钩子）。
     */
    @Volatile
    private var deviceTitle: String? = null

    @Volatile
    private var profile: MoondropProfile.Profile = MoondropProfile.DEFAULT

    /**
     * 空间音频的三种模式（总开关管开关，这里只列模式，不再单列「关闭」——与开关重复）。
     * 1 = 固定模式   → setSpatial(1) + setHeadTracking(0)
     * 2 = 小角度模式 → setSpatial(1) + setHeadTracking(1)
     * 3 = 全角度模式 → setSpatial(1) + setHeadTracking(2)
     *
     * 注意：官方 App 只有「空间音频 / 头部追踪」两个开关，把 track=0 读成 30°（见
     * AncProfileLib.correctedTrackingMode 的补档逻辑），所以它只有三档；但这台固件的
     * track=0 其实是「固定」——用户实测耳机端就是四档（含关闭），按设备走。
     */
    private val SPATIAL_MODES = intArrayOf(1, 2, 3)

    @Volatile
    private var target: Any? = null

    @Volatile
    private var origCount = 0

    /** 挂过的方法，避免同一基类方法被重复 hook */
    private val hookedMethods = HashSet<Method>()

    /** 被我们藏起来的子 View 及其原始可见性（复用时要还原） */
    private val hiddenByUs = WeakHashMap<View, Int>()

    /**
     * 借用格子的可见性「手术记录」：itemView → [(被我们改过的 View, 原可见性)]
     * 实测第 1 格的 itemView 是 INVISIBLE 占位条，不改回来卡片就算有尺寸也不会被绘制。
     */
    private val savedVis = HashMap<View, MutableList<Pair<View, Int>>>()

    /** 观察到的绑定位置（只用来打诊断日志） */
    private val seenPositions = HashSet<Int>()

    private val worker = Executors.newSingleThreadExecutor()
    private val client = GaiaClient()

    /** 把客户端的日志也接到我们这个 TAG 上（XposedBridge，LSPosed 日志里可见） */
    init {
        client.logger = { s -> log(s) }
        // 设备回报的真实模式 → 同步胶囊高亮（官方日志里的 ancModeParse）
        client.onAncMode = { dev -> main.post { applyDeviceMode(dev) } }
        // 设备回报左右耳电量（官方 onBatteryLevel → BatteryStore + 通知刷新）
        client.onBattery = { l, r, c -> main.post { applyBattery(l, r, c) } }
        // 空间音频 / 头部追踪（官方 onSpatialResult / onHeadTrackingResult）
        client.onSpatial = { s -> main.post { applySpatial(s) } }
        client.onHeadTracking = { m -> main.post { applyHeadTracking(m) } }
        // 扩展设备控制：增益 / 指示灯回报
        client.onGain = { dev -> main.post { gainDev = dev; refreshGainChips() } }
        client.onLed = { on -> main.post { ledOn = on; refreshLedSwitch() } }
    }
    internal val main = Handler(Looper.getMainLooper())

    @Volatile
    internal var currentMode = 0
/** 能力探测只做一次（GA2 不回 ACK，探测结果没有的话不必反复问） */
    @Volatile
    private var probedOnce = false

    // ↓↓↓ 以下三个常量照抄参考实现 CapabilityProbe / GaiaBleClient，就是「那段等待」的配方
    /** CapabilityProbe.PROBE_DELAY_BASE */
    private const val PROBE_DELAY_BASE = 400L
    /** CapabilityProbe.PROBE_DELAY_STEP */
    private const val PROBE_DELAY_STEP = 250L
    /** GaiaBleClient.scheduleInitialQueries 的 1200ms 前置等待 */
    private const val INITIAL_QUERY_DELAY_MS = 1200L
    /** HeadsetDetectService 的电量轮询周期（官方原文：每 30 秒刷新一次电量） */
    private const val BATTERY_POLL_MS = 30_000L

    /** 本次会话是否已经跑完「连接 → 阶梯探测 → 1200ms 收口」 */
    @Volatile
    private var handshakeDone = false

    /** 建链防重入的时间戳：prewarm 和卡片页几乎同时调用 ensureLink，两次 GATT 会互相打断 */
    @Volatile
    private var linkingAt = 0L

    /** 正在发送中的目标模式（-1 = 空闲）。用来画 loading 态 */
    @Volatile
    private var pendingChipMode = -1

    /** 上次真正发出的时间（ms），用于节流 */
    @Volatile
    private var lastSendAt = 0L

    /** 最近一次构建的胶囊组（每张卡片一份），供异步回调刷新 */
    @Volatile
    private var lastChips: List<TextView>? = null

    /** 耳机头图位图（只解码一次） */
    @Volatile
    private var iconCache: Bitmap? = null

    /** 最近一次回报的电量，-1 = 还没拿到（官方 BatteryStore 缺省值同样是 -1） */
    @Volatile
    private var battLeft = -1

    @Volatile
    private var battRight = -1

    @Volatile
    private var battCase = -1

    /** 是否收到过电量回包（收到之前显示「电量 --」，别显示假数据） */
    @Volatile
    private var battReported = false

    /** 左右耳此刻是否在线（回包里有没有它那一对） */
    @Volatile
    private var leftOnline = true

    @Volatile
    private var rightOnline = true

    /** 「单耳模式，空间音频已禁用」这句话是否已经打过日志 */
    @Volatile
    private var gateLogged = false

    /** 卡片上那行电量文字 */
    @Volatile
    private var battText: TextView? = null

    /** 30 秒电量轮询是否已启动（官方 HeadsetDetectService 同频） */
    @Volatile
    private var battPolling = false

    /** 轮询任务本身。留着引用才能在卡片关掉时 removeCallbacks 掉 */
    @Volatile
    private var pollRunnable: Runnable? = null

    /** 空间音频开关状态：-1 未知 / 0 关 / 1 开 */
    @Volatile
    private var spatialOn = -1

    /** 头部追踪档位：-1 未知 / 0 关闭追踪 / 1 30° / 2 全方位 */
    @Volatile
    private var trackMode = -1

    /** 空间音频三档胶囊 */
    @Volatile
    private var spatialChips: List<TextView>? = null

    // ---- 扩展设备控制（上游 DcProfile）：增益 / 指示灯 ----
    /** 增益胶囊（低/中/高）；型号没有增益能力时为 null */
    private var gainChips: List<TextView>? = null
    /** 设备回报的增益设备码（-1 = 还没拿到） */
    @Volatile
    private var gainDev = -1
    private var ledSwitch: Switch? = null
    @Volatile
    private var ledOn = false
    private var ledSyncing = false

    /** 空间音频总开关（官方那个） */
    @Volatile
    private var spatialSwitch: Switch? = null

    /** 防止「按设备状态回写开关」触发 listener 又发一次命令 */
    @Volatile
    private var spatialSyncing = false


    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------

    /**
     * 提前建链（A4 的落点）。
     *
     * GMS 的 Fast Pair 半屏出现时（设备刚被发现、A2DP 还没建链），宿主侧会收到广播，
     * 这里就抢先把 GAIA 链路和握手做完。等卡片真的被打开时，电量/档位已经是现成的，
     * 不用再等 1~2 秒的握手。
     *
     * 幂等：已连上就直接返回；握手本身也靠 handshakeDone 去重。
     */
    fun prewarm(ctx: Context, delayMs: Long = 0L) {
        main.postDelayed({
            worker.execute {
                try {
                    if (client.isConnected) {
                        log("prewarm：链路已在，跳过")
                        return@execute
                    }
                    log("prewarm：开始提前建链")
                    val t0 = SystemClock.elapsedRealtime()
                    if (!ensureLink(ctx)) {
                        log("prewarm：建链失败（设备可能还没配对完）")
                        return@execute
                    }
                    log("prewarm：链路就绪 ${SystemClock.elapsedRealtime() - t0}ms，开始握手")
                    handshake()
                } catch (t: Throwable) {
                    log("prewarm 异常: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }, delayMs)
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val loader = lpparam.classLoader
        val rvCls = loadClass("androidx.recyclerview.widget.RecyclerView", loader)
        if (rvCls == null) {
            log("✗ 没有 RecyclerView 类，放弃注入")
            return
        }

        try {
            XposedBridge.hookAllMethods(rvCls, "setAdapter", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val rv = param.thisObject ?: return
                        if (!isTargetRecycler(rv)) return
                        val a = XposedHelpers.callMethod(rv, "getAdapter") ?: return
                        track(a)
                    } catch (t: Throwable) {
                        log("setAdapter 回调出错: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            })
            log("✓ 已挂 RecyclerView#setAdapter")
        } catch (t: Throwable) {
            log("✗ 挂 setAdapter 失败: ${t.javaClass.simpleName}: ${t.message}")
        }

        // 面板复用（进程缓存命中）时 setAdapter 可能早就调用过了，钩子永远等不到。
        // 但每次弹层重建，RecyclerView 视图都是新的，一定会走 onAttachedToWindow —— 用它兜底。
        try {
            XposedBridge.hookAllMethods(rvCls, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val rv = param.thisObject ?: return
                        if (!isTargetRecycler(rv)) return
                        ensureTracked(rv)
                    } catch (t: Throwable) {
                        log("attach 回调出错: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            })
            log("✓ 已挂 RecyclerView#onAttachedToWindow（兜底锁定 adapter）")
        } catch (t: Throwable) {
            log("✗ 挂 onAttachedToWindow 失败: ${t.javaClass.simpleName}: ${t.message}")
        }

        // 设备身份：宿主在启动卡片时把 device_title / device_mac_info 塞在 Intent 里，
        // 这是唯一可靠的「这台是哪副耳机」来源（列表里的文字会被复用污染）。
        hookDetailIntent(loader)
    }

    /**
     * 挂 BlueToothDetailActivity 的 onCreate / onNewIntent，把 Intent 里的设备名取出来。
     *
     * 时机上 onCreate 一定早于列表绑定，所以在 bind 钩子里读 deviceTitle 是有效的。
     * 拿不到类（宿主改名）就只是不设门禁 —— 这种情况下按「未知设备」处理，不动面板。
     */
    private fun hookDetailIntent(loader: ClassLoader) {
        val cls = loadClass("com.oplus.mydevices.bluetooth.BlueToothDetailActivity", loader) ?: return
        val cb = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    readDevice(param.thisObject)
                } catch (t: Throwable) {
                    log("读设备身份失败: ${t.javaClass.simpleName}")
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                cls, "onCreate", android.os.Bundle::class.java, cb
            )
            log("✓ 已挂 ${cls.simpleName}#onCreate（读设备身份）")
        } catch (t: Throwable) {
            log("✗ 挂 onCreate 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
        try {
            XposedHelpers.findAndHookMethod(cls, "onNewIntent", Intent::class.java, cb)
            log("✓ 已挂 ${cls.simpleName}#onNewIntent（读设备身份）")
        } catch (t: Throwable) {
            log("✗ 挂 onNewIntent 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
        // 页面销毁 = 用户关掉了这张卡片 → 停轮询、断 GAIA，别在后台继续占连接
        try {
            XposedHelpers.findAndHookMethod(
                cls, "onDestroy",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            shutdown("卡片页面关闭")
                        } catch (t: Throwable) {
                            log("收尾失败: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                }
            )
            log("✓ 已挂 ${cls.simpleName}#onDestroy（收尾：停轮询 + 断连接）")
        } catch (t: Throwable) {
            log("✗ 挂 onDestroy 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 从 Activity 的 Intent 里取 device_title / device_mac_info，并切换档案 */
    private fun readDevice(act: Any) {
        val intent = XposedHelpers.callMethod(act, "getIntent") as? Intent ?: return
        val title = intent.getStringExtra("device_title")
        val mac = intent.getStringExtra("device_mac_info")
        val titleChanged = title != deviceTitle
        if (titleChanged) {
            deviceTitle = title
            profile = MoondropProfile.ofOrDefault(title)
            log(
                "面板设备 = ${title ?: "(无)"} mac=$mac " +
                    "档案=${profile.key} 水月雨=${MoondropProfile.isMoondrop(title)} " +
                    "档位=${MoondropProfile.supportedUi(profile, ancV2Path()).joinToString("/")}"
            )
        }
        // 切到另一台设备时，已注入的卡片要按新档案重建（区间可能变、空间音频整行可能该消失）
        if (titleChanged) main.post { dropInjectedCards() }
    }

    /** 把已经塞进列表的卡片全部摘掉，下次绑定时按当前档案重建 */
    private fun dropInjectedCards() {
        lastChips = null
        spatialChips = null
        spatialSwitch = null
        battText = null
        val cards = ArrayList<View>()
        try {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val inst = wmg.getMethod("getInstance").invoke(null)
            val f = wmg.getDeclaredField("mViews")
            f.isAccessible = true
            (f.get(inst) as? List<*>)?.forEach { root ->
                if (root is ViewGroup) collectCards(root, cards, 0)
            }
        } catch (t: Throwable) {
        }
        for (card in cards) {
            try {
                (card.parent as? ViewGroup)?.removeView(card)
            } catch (t: Throwable) {
            }
        }
        if (cards.isNotEmpty()) log("已摘掉旧卡片 ${cards.size} 张（按新档案重建）")
    }

    private fun collectCards(v: ViewGroup, out: MutableList<View>, depth: Int) {
        if (depth > 40) return
        for (i in 0 until v.childCount) {
            val ch = v.getChildAt(i)
            if (ch.getTag(MARK_VIEW) == true) out.add(ch)
            if (ch is ViewGroup) collectCards(ch, out, depth + 1)
        }
    }

    /** 上一次「跳过注入」已经打过日志的设备名，避免同一次绑定刷屏 */
    private var skipLoggedFor: String? = null

    /**
     * 注入门禁 —— 这就是「一一对应」的那道闸。
     *
     * 判定只认宿主给的 `device_title`：含 MOONDROP 或命中已知型号档案才放行。
     * 名字拿不到（null）时也不放行：宁可不动别人家的面板，也不要瞎塞一张降噪卡。
     */
    private fun allowed(): Boolean {
        val t = deviceTitle
        if (MoondropProfile.isMoondrop(t)) return true
        if (skipLoggedFor != t) {
            skipLoggedFor = t
            log("跳过注入：不是水月雨设备（${t ?: "名字未知"}）")
        }
        return false
    }

    /**
     * 反复（最多 ~3 秒）尝试从 RecyclerView 上把 adapter 摸出来。
     * attach 的时刻 adapter 往往还是 null（setAdapter 稍后才发生），所以要轮询。
     */
    private fun ensureTracked(rv: Any) {
        var tries = 0
        val task = object : Runnable {
            override fun run() {
                try {
                    if (target != null && tries > 0) return
                    val a = XposedHelpers.callMethod(rv, "getAdapter")
                    if (a != null) {
                        track(a)
                        return
                    }
                } catch (t: Throwable) {
                    log("轮询 adapter 出错: ${t.javaClass.simpleName}")
                    return
                }
                if (++tries <= 30) main.postDelayed(this, 100)
            }
        }
        main.postDelayed(task, 60)
    }

    private fun loadClass(name: String, loader: ClassLoader): Class<*>? = try {
        Class.forName(name, false, loader)
    } catch (t: Throwable) {
        log("找不到类 $name (${t.javaClass.simpleName})")
        null
    }

    /** 只认 id 名叫 recycler_view 的那个（面板里可能还有别的列表） */
    private fun isTargetRecycler(rv: Any): Boolean {
        val id = XposedHelpers.callMethod(rv, "getId") as? Int ?: return false
        if (id == View.NO_ID) return false
        return try {
            val res = XposedHelpers.callMethod(rv, "getResources") as Resources
            res.getResourceEntryName(id) == "recycler_view"
        } catch (t: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------
    // 锁定 Adapter 并挂绑定钩子
    // ------------------------------------------------------------------

    private fun track(adapter: Any) {
        if (adapter === target) return
        val cls = adapter.javaClass
        target = adapter
        origCount = rawCount(adapter)
        log("锁定 adapter=${cls.name} 条目=$origCount 借用格子=${slot()}")

        val cb = makeBindHook()
        for (name in arrayOf("bindViewHolder", "onBindViewHolder")) {
            findMethod(cls, name, 2)?.let { hookOnce(it, cb) }
            findMethod(cls, name, 3)?.let { hookOnce(it, cb) }
        }

        main.post {
            try {
                XposedHelpers.callMethod(adapter, "notifyDataSetChanged")
                log("已通知 adapter 刷新")
            } catch (t: Throwable) {
                log("notify 失败（无害）: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun makeBindHook(): XC_MethodHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (param.thisObject !== target) return
            try {
                val pos = param.args[1] as? Int ?: return
                if (seenPositions.add(pos)) {
                    log("绑定位置 $pos（已见 ${seenPositions.size} 个位置）")
                }
                if (pos == slot()) return              // 我们这格：让原方法照常跑，什么都不做
                val holder = param.args[0] ?: return
                val iv = itemViewOf(holder) as? ViewGroup ?: return
                hideOurs(iv)                           // 复用前清场，避免卡片残留到别的条目
            } catch (t: Throwable) {
                log("bind 前处理异常: ${t.javaClass.simpleName}")
            }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.thisObject !== target) return
            try {
                val pos = param.args[1] as? Int ?: return
                // 顶部耳机产品图：绑定时顺手换掉（失败只打日志，不影响宿主）
                if (pos == ICON_POS && pos != slot()) {
                    if (!allowed()) return
                    val h0 = param.args[0] ?: return
                    itemViewOf(h0)?.let { applyIcon(it) }
                    return
                }
                if (pos != slot()) return
                val holder = param.args[0] ?: return
                val iv = itemViewOf(holder) as? ViewGroup ?: return
                if (!allowed()) {
                    // 不是水月雨：把我们可能残留的卡片摘掉、子 View 还原，然后什么都不做
                    hideOurs(iv)
                    return
                }
                showOurs(iv)
                log("显示我们的卡片 pos=$pos")
            } catch (t: Throwable) {
                log("bind 后处理异常: ${t.javaClass.simpleName}")
            }
        }
    }

    /** 借用的格子号（列表比预期短时退到最后一条） */
    private fun slot(): Int = if (SLOT > origCount) (origCount - 1).coerceAtLeast(0) else SLOT

    private fun rawCount(a: Any): Int = try {
        (XposedHelpers.callMethod(a, "getItemCount") as? Int) ?: 0
    } catch (t: Throwable) {
        0
    }

    /** 按「方法名 + 参数个数」从 cls 往上找最具体的那个实现 */
    private fun findMethod(cls: Class<*>, name: String, argc: Int): Method? {
        var c: Class<*>? = cls
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name == name && m.parameterTypes.size == argc) return m
            }
            if (c == Any::class.java) break
            c = c.superclass
        }
        return null
    }

    private fun hookOnce(m: Method, cb: XC_MethodHook) {
        if (!hookedMethods.add(m)) return
        try {
            XposedBridge.hookMethod(m, cb)
            val ps = m.parameterTypes.joinToString(",") { it.simpleName }
            log("  ✓ 挂 ${m.declaringClass.simpleName}#${m.name}($ps)")
        } catch (t: Throwable) {
            log("  ✗ 挂 ${m.name} 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // 从 holder 里摸出 itemView（字段名被混淆，但字段类型不会）
    // ------------------------------------------------------------------

    private fun itemViewOf(holder: Any): View? {
        var c: Class<*>? = holder.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (View::class.java.isAssignableFrom(f.type)) {
                    try {
                        f.isAccessible = true
                        return f.get(holder) as? View
                    } catch (t: Throwable) {
                    }
                }
            }
            if (c == Any::class.java) break
            c = c.superclass
        }
        log("✗ 找不到 holder 的 itemView 字段")
        return null
    }

    private fun findOurCard(g: ViewGroup): View? {
        for (i in 0 until g.childCount) {
            val ch = g.getChildAt(i)
            if (ch.getTag(MARK_VIEW) == true) return ch
        }
        return null
    }

    /** 只显示我们的卡片（其余子 View 藏起来，并记住它们原来的可见性） */
    private fun showOurs(g: ViewGroup) {
        // 这一格如果只是「空白占位条」（固定高度很小），直接塞卡片会被裁掉，先撑开
        val lp = g.layoutParams
        val lpH = lp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        if (lpH != ViewGroup.LayoutParams.WRAP_CONTENT && lpH < dp(g.context, 60)) {
            try {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                g.layoutParams = lp
                log("撑开借用格子: h=$lpH -> WRAP_CONTENT")
            } catch (t: Throwable) {
                log("撑开格子失败: ${t.javaClass.simpleName}")
            }
        }

        var card = findOurCard(g)
        // 实测：这一格的 itemView 自己是 INVISIBLE 的占位条 —— 空间留着，整棵子树不绘制。
        // 所以我们连同祖先一起掰成 VISIBLE，并记下原值，切走时还原。
        log("借用格可见性链: ${visChain(g)}")
        if (!savedVis.containsKey(g)) {
            val rec = ArrayList<Pair<View, Int>>()
            var cur: View? = g
            var guard = 0
            while (cur != null && guard < 8) {
                if (cur.visibility != View.VISIBLE) {
                    rec.add(cur to cur.visibility)
                    cur.visibility = View.VISIBLE
                }
                cur = cur.parent as? View
                guard++
            }
            if (rec.isNotEmpty()) {
                savedVis[g] = rec
                log("掰可见性 ${rec.size} 处: " + rec.joinToString { "${it.first.javaClass.simpleName}=${it.second}" })
            }
        }

        if (card == null) {
            val c = buildCard(g.context)
            c.setTag(MARK_VIEW, true)
            card = c
            g.addView(
                c,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        card.visibility = View.VISIBLE
        for (i in 0 until g.childCount) {
            val ch = g.getChildAt(i)
            if (ch === card) continue
            if (ch.visibility != View.GONE && !hiddenByUs.containsKey(ch)) {
                hiddenByUs[ch] = ch.visibility
            }
            ch.visibility = View.GONE
        }
        log("显示我们的卡片 itemView=${g.javaClass.simpleName} 子View=${g.childCount} 高=${g.height} lpH=$lpH")

        // 画完之后回来量一次：尺寸 / 是否真的可见 / 文字颜色
        val cardRef = card
        val firstText = (cardRef as? ViewGroup)?.getChildAt(0) as? TextView
        cardRef.postDelayed({
            try {
                log(
                    "卡片实测 w=${cardRef.width} h=${cardRef.height} shown=${cardRef.isShown} " +
                        "alpha=${cardRef.alpha} 父lpH=${cardRef.layoutParams?.height} " +
                        "父实体h=${g.height} 父shown=${g.isShown} 标题色=${
                            java.lang.Integer.toHexString(firstText?.currentTextColor ?: 0)
                        }"
                )
            } catch (t: Throwable) {
                log("测量失败: ${t.javaClass.simpleName}")
            }
        }, 500)
    }

    /** 撤掉我们的卡片，把藏起来的子 View 还原 */
    private fun hideOurs(g: ViewGroup) {
        // ★ 性能：这个函数在「每一次 bindViewHolder（含所有滚动行）」里都会被调用。
        //   以前无条件遍历子 View 并 removeView，列表一滚动就是每帧 O(n) 的视图操作，
        //   弹窗期间 CPU 飙升/掉帧主要来自这里。没我们东西的格子直接返回。
        val hadOurs = findOurCard(g) != null
        val hadHidden = hiddenByUs.keys.any { it.parent === g }
        if (!hadOurs && !hadHidden && !savedVis.containsKey(g)) return

        if (hadOurs) {
            findOurCard(g)?.let {
                try {
                    g.removeView(it)
                } catch (t: Throwable) {
                }
            }
        }
        for (i in 0 until g.childCount) {
            val ch = g.getChildAt(i)
            val v = hiddenByUs.remove(ch)
            if (v != null) ch.visibility = v
        }
        // 这一格被复用到别的条目时，把我们掰开的可见性还原，别把占位条变成可见
        savedVis.remove(g)?.forEach { (v, vis) ->
            try {
                v.visibility = vis
            } catch (t: Throwable) {
            }
        }
    }
    /** 打印可见性链，方便定位是哪一层把我们挡住了 */
    private fun visChain(v: View): String {
        val sb = StringBuilder()
        var cur: View? = v
        var d = 0
        while (cur != null && d < 8) {
            sb.append('[').append(cur.javaClass.simpleName).append('=').append(cur.visibility).append(']')
            cur = cur.parent as? View
            d++
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 耳机头图替换（pos = 0）
    // ------------------------------------------------------------------

    /**
     * pos=0 那一格就是面板顶部的耳机产品图。绑定时把里面「面积最大的 ImageView」换成我们的图。
     *
     * 两个注意点：
     * ① 不能动宿主自己的 `tag`（很多适配器拿 tag 存图片 URL）—— 用 keyed tag `MARK_ICON` 做幂等；
     * ② 如果原图是异步加载的，加载回调回来会把我们的图盖掉，所以补几次延时复查。
     */
    private fun applyIcon(root: View) {
        if (root.getTag(MARK_ICON) == true) return

        // ① 用户给这个型号设过自定义头图 → 优先用（文件在宿主 filesDir，同进程直接读）
        val custom = SettingsSync.customIcon(profile.key)
        val customBmp = custom?.let {
            runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull()
        }
        // ② 没设过就只能用内置素材，而内置素材只有梦回 2 的一张，其他型号一律不动宿主原图
        val bmp = customBmp ?: run {
            if (profile.key != ICON_PROFILE) {
                if (iconSkipLoggedFor != profile.key) {
                    iconSkipLoggedFor = profile.key
                    log("跳过换图标：内置素材只对应「$ICON_PROFILE」，当前档案=${profile.key}（可在模块里自定义头图）")
                }
                return
            }
            iconBitmap()
        }
        if (bmp == null) {
            log("图标数据不可用，跳过替换")
            return
        }
        // 先排查结构再取图：这样即使图有问题，日志里也有树可以看
        val all = ArrayList<ImageView>()
        collectImageViews(root, all, 0)
        log(
            "pos0 图标排查: ImageView=${all.size} 来源=${if (customBmp != null) "自定义" else "内置"} " +
                "树=${describeTree(root, 0)}"
        )
        if (all.isEmpty()) {
            log("pos0 没有 ImageView —— 图标可能是自定义 View 自己画的，换成 hook onDraw")
            return
        }
        // 面积最大的那个就是产品图（小的是箭头/角标之类）
        val target = all.maxByOrNull { areaOf(it) } ?: return
        root.setTag(MARK_ICON, true)
        installIcon(target, bmp)
    }

    private fun installIcon(iv: ImageView, bmp: Bitmap) {
        fun put(logIt: Boolean) {
            try {
                iv.setImageBitmap(bmp)
                if (iv.scaleType == ImageView.ScaleType.FIT_XY) {
                    iv.scaleType = ImageView.ScaleType.FIT_CENTER
                }
                if (logIt) {
                    log(
                        "图标替换: ${iv.javaClass.simpleName} ${iv.width}x${iv.height} " +
                            "id=${idName(iv)} scale=${iv.scaleType} 原图尺寸=${bmp.width}x${bmp.height}"
                    )
                }
            } catch (t: Throwable) {
                log("图标替换失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        put(true)
        // 异步加载回来会盖掉，补三次（一次性，不循环）
        for (d in longArrayOf(150L, 500L, 1200L)) {
            try {
                iv.postDelayed({ put(false) }, d)
            } catch (t: Throwable) {
            }
        }
    }

    private fun collectImageViews(v: View, out: MutableList<ImageView>, depth: Int) {
        if (depth > 12) return
        if (v is ImageView) out.add(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectImageViews(v.getChildAt(i), out, depth + 1)
        }
    }

    private fun areaOf(v: View): Int {
        val w = if (v.width > 0) v.width else (v.layoutParams?.width ?: 0)
        val h = if (v.height > 0) v.height else (v.layoutParams?.height ?: 0)
        return if (w > 0 && h > 0) w * h else 1
    }

    private fun idName(v: View): String = try {
        if (v.id == View.NO_ID) "-" else v.resources.getResourceEntryName(v.id)
    } catch (t: Throwable) {
        "?"
    }

    private fun describeTree(v: View, depth: Int): String {
        if (depth > 6) return ""
        val sb = StringBuilder()
        sb.append(v.javaClass.simpleName)
        if (v is ImageView) {
            sb.append("(drawable=").append(
                try {
                    if (v.drawable == null) "null" else v.drawable!!.javaClass.simpleName
                } catch (t: Throwable) {
                    "?"
                }
            ).append(")")
        }
        sb.append('[').append(v.width).append('x').append(v.height).append("] ")
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                sb.append("{ ").append(describeTree(v.getChildAt(i), depth + 1)).append(" }")
            }
        }
        return sb.toString()
    }

    /**
     * 取耳机头图（只解码一次）。
     *
     * ★ 不能用 `context.assets` —— 那个 context 是**宿主**的，宿主进程里读不到模块自己的 assets/，
     *   实测就是 `FileNotFoundException: headset_icon.png`。所以图直接内嵌在 IconAsset 里。
     */
    private fun iconBitmap(): Bitmap? {
        iconCache?.let { return it }
        val bmp = IconAsset.bitmap()
        if (bmp == null) {
            log("图标数据解码失败（IconAsset）")
            return null
        }
        iconCache = bmp
        log("图标资源已载入 ${bmp.width}x${bmp.height}")
        return bmp
    }


    // ------------------------------------------------------------------
    // 卡片 UI（纯 framework 控件）
    // ------------------------------------------------------------------

    private fun dp(c: Context, v: Int): Int =
        (v * c.resources.displayMetrics.density + 0.5f).toInt()

    private fun themeColor(c: Context, attr: Int, fallback: Int): Int = try {
        val tv = TypedValue()
        if (c.theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) c.getColor(tv.resourceId) else tv.data
        } else {
            fallback
        }
    } catch (t: Throwable) {
        fallback
    }

    private fun isNight(c: Context): Boolean =
        (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /**
     * 文字颜色。实测这个面板的 textColorPrimary 解析出来是 ff000000（纯黑），
     * 而面板是深色底 —— 直接用主题色等于隐形。所以按明暗主题自己给一套。
     */
    private fun ink(c: Context, primary: Boolean): Int = when {
        primary && isNight(c) -> 0xFFFFFFFF.toInt()
        primary -> 0xFF202124.toInt()
        isNight(c) -> 0xB3FFFFFF.toInt()
        else -> 0xFF808080.toInt()
    }

    /** 强调色：主题给的不透明且不是全黑才用，否则按明暗主题兜底 */
    private fun accent(c: Context): Int {
        val raw = themeColor(c, android.R.attr.colorAccent, 0)
        val opaque = (raw ushr 24) != 0
        val notBlack = (raw and 0x00FFFFFF) != 0
        return if (opaque && notBlack) raw else if (isNight(c)) 0xFF8AB4FF.toInt() else 0xFF4E6EF2.toInt()
    }

    private fun buildCard(c: Context): LinearLayout {
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 20), dp(c, 14), dp(c, 20), dp(c, 14))
        }

        val titleRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(c).apply {
            text = "降噪模式"
            textSize = 15f
            setTextColor(ink(c, true))
        }
        titleRow.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        // 左右耳分开显示（系统那处只有一个混合值，没法告诉我们哪只快没电了）
        val batt = TextView(c).apply {
            textSize = 12f
            gravity = Gravity.END
            setTextColor(ink(c, false))
        }
        titleRow.addView(batt, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(titleRow)
        battText = batt
        refreshBatteryText()

        val sub = TextView(c).apply {
            text = deviceTitle ?: "Moondrop"
            textSize = 11f
            setTextColor(ink(c, false))
            setPadding(0, dp(c, 3), 0, dp(c, 10))
        }
        root.addView(sub)

        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val chips = ArrayList<TextView>()
        // 胶囊按「本型号实际支持的 UI 档位」生成（档案里 setMap = -1 的槽位直接不出现）。
        // 例如知更鸟只有降噪/透传两档，就只长两颗；GA2 四档全有。
        for (ui in MoondropProfile.supportedUi(profile, ancV2Path())) {
            val chip = TextView(c).apply {
                text = modeName(ui)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8))
                isClickable = true
                isFocusable = true
                tag = ui                            // 胶囊记的是 UI 档位，不是设备码
                setOnClickListener { onChipClick(ui, chips) }
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (chips.isNotEmpty()) lp.marginStart = dp(c, 8)
            row.addView(chip, lp)
            chips.add(chip)
        }
        root.addView(row)

        // ── 空间音频（FEATURE_SPATIAL_AUDIO = 0x12）：总开关 + 三档模式 ──
        //    只有 DC 档案里 hasSpatial = true 的型号（目前只有 GA2）才建这一块；
        //    别的型号根本没这功能，整行不出现，免得留一个点了没反应的开关。
        if (supportsSpatial()) {
        val spHeader = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(c, 12), 0, dp(c, 4))
        }
        val spTitle = TextView(c).apply {
            text = "空间音频"
            textSize = 13f
            setTextColor(ink(c, true))
        }
        spHeader.addView(
            spTitle,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        // 官方那个总开关：开 = 耳机端 spatial 开，关 = spatial 关（三档胶囊只在其后生效）
        val spSwitch = Switch(c).apply {
            isChecked = spatialOn == 1
            setOnCheckedChangeListener { _, checked ->
                if (!spatialSyncing) onSpatialToggle(checked)
            }
        }
        spHeader.addView(
            spSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(spHeader)
        spatialSwitch = spSwitch

        val spRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sc = ArrayList<TextView>()
        for (m in SPATIAL_MODES) {
            val chip = TextView(c).apply {
                text = trackName(m)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8))
                isClickable = true
                isFocusable = true
                tag = m
                setOnClickListener { onSpatialClick(m, sc) }
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (sc.isNotEmpty()) lp.marginStart = dp(c, 8)
            spRow.addView(chip, lp)
            sc.add(chip)
        }
        root.addView(spRow)
        spatialChips = sc
        refreshSpatialChips(sc)
        // 卡片重建时电量可能早就回报过了，按当前单耳/双耳状态把这一块设好
        refreshSingleEarGate()
        } else {
            spatialChips = null
            spatialSwitch = null
        }

        // ── 扩展设备控制：增益（低/中/高）与指示灯（来自上游 DcProfile） ──
        //    DcProfile 里没写的型号（如知更鸟）这两块整行不出现，免得出现点了没反应的按钮。
        if (profile.hasGain) {
            val gainHeader = TextView(c).apply {
                text = "增益"
                textSize = 13f
                setTextColor(ink(c, true))
                setPadding(0, dp(c, 12), 0, dp(c, 4))
            }
            root.addView(gainHeader)
            val grow = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val gc = ArrayList<TextView>()
            for (i in 0..2) {
                val chip = TextView(c).apply {
                    text = MoondropProfile.gainName(i)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8))
                    isClickable = true
                    isFocusable = true
                    tag = i
                    setOnClickListener { onGainClick(i, gc) }
                }
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (gc.isNotEmpty()) lp.marginStart = dp(c, 8)
                grow.addView(chip, lp)
                gc.add(chip)
            }
            root.addView(grow)
            gainChips = gc
            refreshGainChips()
        } else {
            gainChips = null
        }
        if (profile.hasLed) {
            val ledRow = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(c, 12), 0, 0)
            }
            val ledTitle = TextView(c).apply {
                text = "指示灯"
                textSize = 13f
                setTextColor(ink(c, true))
            }
            ledRow.addView(
                ledTitle,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            val sw = Switch(c).apply {
                isChecked = ledOn
                setOnCheckedChangeListener { _, checked -> if (!ledSyncing) onLedToggle(checked) }
            }
            ledRow.addView(
                sw,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            root.addView(ledRow)
            ledSwitch = sw
            refreshLedSwitch()
        } else {
            ledSwitch = null
        }
        // ★ 临时诊断行：直接发裸设备码 0~4，用来实测这台耳机到底认哪个码。
        // 测出真实映射后把 PROBE 改回 false（或删掉这几行）。
        if (PROBE) {
            val prow = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(c, 12), 0, 0)
            }
            for (code in 0..4) {
                val b = TextView(c).apply {
                    text = "码$code"
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(c, 7), 0, dp(c, 7))
                    isClickable = true
                    setOnClickListener {
                        toast(it.context, "发码 $code")
                        sendAnc(it.context, code)
                    }
                    setBackgroundColor(0x22FFFFFF)
                    setTextColor(ink(c, false))
                }
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (code > 0) lp.marginStart = dp(c, 6)
                prow.addView(b, lp)
            }
            root.addView(prow)
        }

        root.tag = chips                             // 交给刷新逻辑找回胶囊
        lastChips = chips
        refreshChips(chips)
        // 卡片一出现就在后台把链路连好 + 按官方时序跑完握手，等用户点的时候 SET 才能立刻生效
        worker.execute { prewarm(c) }
        return root
    }

    private fun onChipClick(mode: Int, chips: List<TextView>) {
        // 点哪个就发哪个：不因「本地记录的当前模式」而早退（那个值只是乐观缓存，会和耳机漂移）
        val c = chips.firstOrNull()?.context ?: return
        lastChips = chips
        currentMode = mode
        refreshChips(chips)
        sendAnc(c, mode)
    }

    // ------------------------------------------------------------------
    // 空间音频（总开关 + 三档模式：固定模式 / 小角度模式 / 全角度模式）
    // ------------------------------------------------------------------

    /** UI 档位名（1..3，用户指定的文案） */
    internal fun trackName(mode: Int): String = when (mode) {
        0 -> "关闭"
        1 -> "固定模式"
        2 -> "小角度模式"
        3 -> "全角度模式"
        else -> "档$mode"
    }

    /** 设备头部追踪码（0..2）→ 同一个名字，用于日志核对 */
    private fun devTrackName(code: Int): String = trackName(code + 1)

    /** UI 档位（1..3）→ 设备头部追踪码（0..2） */
    private fun devTrack(uiMode: Int): Int = (uiMode - 1).coerceIn(0, 2)

    private fun refreshSpatialChips(chips: List<TextView>) {
        val c = chips.firstOrNull()?.context ?: return
        val ac = accent(c)
        val normal = ink(c, false)
        val on = spatialOn == 1
        val cur = if (on && trackMode in 0..2) trackMode + 1 else -1
        for (chip in chips) {
            val m = (chip.tag as? Int) ?: -1
            val sel = m == cur
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(c, 18).toFloat()
                setColor(if (sel) (ac and 0x00FFFFFF) or (0x2E shl 24) else 0x14808080)
            }
            chip.setTextColor(if (sel) ac else normal)
            // 单耳模式下三档完全不可点（固件在单耳时切了不生效，还会把状态搞乱）
            val gate = singleEar()
            chip.isEnabled = !gate
            chip.alpha = when {
                gate -> 0.3f
                // 空间音频关着的时候三档是「不可用」状态，压暗但保留可点（点一下会自动开启）
                on -> 1f
                else -> 0.45f
            }
        }
        // 按设备状态回写开关（借 syncing 标记避免反过来又发一帧命令）
        spatialSwitch?.let { sw ->
            if (sw.isChecked != on) {
                spatialSyncing = true
                try {
                    sw.isChecked = on
                } finally {
                    spatialSyncing = false
                }
            }
        }
    }

    /**
     * 点一档。
     *
     * ★ 关键（用户实测的坑）：空间音频「刚打开」的瞬间，设备会先把自己恢复到上次记忆的档位，
     *   这时候压过去的 setHeadTracking 会被它自己的初始化盖掉。所以分两条路：
     *   - 已经开着：直接只发 setHeadTracking。
     *   - 本来关着：先 setSpatial(1)，**等它起来**（600ms）再发 setHeadTracking。
     */
    private fun onSpatialClick(mode: Int, chips: List<TextView>) {
        val c = chips.firstOrNull()?.context ?: return
        // 单耳模式：固件切了不生效还会把状态搞乱，直接不发
        if (singleEar()) {
            log("单耳模式：忽略空间音频档位点击（$mode）")
            return
        }
        val wasOn = spatialOn == 1
        spatialChips = chips
        // 乐观更新（回读会再校正一次）。模式只有 1..3，点模式必然意味着空间音频要开。
        spatialOn = 1
        trackMode = devTrack(mode)
        refreshSpatialChips(chips)
        worker.execute {
            try {
                if (!ensureLink(c)) {
                    toast(c, "耳机链路没连上，稍等一下再点")
                    return@execute
                }
                handshake()
                if (wasOn) {
                    log("空间音频 → ${trackName(mode)}（已开启，只切档 ${devTrack(mode)}）")
                    client.setHeadTracking(devTrack(mode))
                    Thread.sleep(250)
                    client.fetchHeadTracking()
                } else {
                    log("空间音频 → 开启后再设为 ${trackName(mode)}（档位 ${devTrack(mode)}）")
                    client.setSpatial(1)
                    Thread.sleep(600)          // 等设备把空间音频真正开起来、恢复到记忆档
                    client.setHeadTracking(devTrack(mode))
                    Thread.sleep(250)
                    client.fetchHeadTracking()
                    client.fetchSpatial()
                }
            } catch (t: Throwable) {
                log("空间音频下发失败: ${t.javaClass.simpleName}: ${t.message}")
                toast(c, "空间音频下发失败：${t.javaClass.simpleName}")
            }
        }
    }

    /**
     * 总开关（官方那个「空间音频」开关）。
     *   关 → setSpatial(0)，档位作废
     *   开 → setSpatial(1)，然后回读；设备会自己恢复到上次记忆的档位，我们如实显示
     */
    private fun onSpatialToggle(on: Boolean) {
        val c = spatialSwitch?.context ?: spatialChips?.firstOrNull()?.context ?: return
        if (singleEar()) {
            log("单耳模式：忽略空间音频开关（$on）")
            // 把开关位置拨回去，否则 UI 会显示成"已开"
            spatialSwitch?.let { sw ->
                spatialSyncing = true
                try {
                    sw.isChecked = spatialOn == 1
                } finally {
                    spatialSyncing = false
                }
            }
            return
        }
        spatialOn = if (on) 1 else 0
        if (!on) trackMode = -1
        spatialChips?.let { refreshSpatialChips(it) }
        worker.execute {
            try {
                if (!ensureLink(c)) {
                    toast(c, "耳机链路没连上，稍等一下再点")
                    return@execute
                }
                handshake()
                if (!on) {
                    client.setSpatial(0)
                    log("空间音频开关 → 关")
                    Thread.sleep(250)
                    client.fetchSpatial()
                } else {
                    client.setSpatial(1)
                    log("空间音频开关 → 开（回读它自己恢复的档位）")
                    Thread.sleep(600)
                    client.fetchHeadTracking()
                    client.fetchSpatial()
                }
            } catch (t: Throwable) {
                log("空间音频开关下发失败: ${t.javaClass.simpleName}: ${t.message}")
                toast(c, "空间音频开关下发失败：${t.javaClass.simpleName}")
            }
        }
    }

    /** 空间音频开关回报：0 关 / 1 开（官方 onSpatialResult；开的时候顺手查追踪档位） */
    private fun applySpatial(state: Int) {
        if (state != spatialOn) log("空间音频 → ${if (state == 1) "开" else "关"}")
        spatialOn = state
        if (state == 0) trackMode = -1
        spatialChips?.let { refreshSpatialChips(it) }
        if (state == 1) {
            worker.execute {
                try {
                    client.fetchHeadTracking()
                } catch (t: Throwable) {
                }
            }
        }
    }

    /** 头部追踪档位回报（官方 onHeadTrackingResult） */
    private fun applyHeadTracking(mode: Int) {
        if (mode !in 0..2) {
            log("头部追踪档位异常 mode=$mode")
            return
        }
        log("头部追踪 → ${devTrackName(mode)}（device mode=$mode）")
        trackMode = mode
        spatialChips?.let { refreshSpatialChips(it) }
    }

    private fun refreshChips(chips: List<TextView>) {
        val c = chips.firstOrNull()?.context ?: return
        val ac = accent(c)
        val normal = ink(c, false)
        for (chip in chips) {
            val mode = (chip.tag as? Int) ?: -1
            val sel = mode == currentMode
            val busy = mode == pendingChipMode
            chip.text = if (busy) "${modeName(mode)}…" else modeName(mode)
            chip.alpha = if (busy) 0.55f else 1f
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(c, 18).toFloat()
                setColor(
                    when {
                        busy -> (ac and 0x00FFFFFF) or (0x1A shl 24)   // loading：淡淡的底色
                        sel -> (ac and 0x00FFFFFF) or (0x2E shl 24)    // 已生效：实底色
                        else -> 0x14808080
                    }
                )
            }
            chip.setTextColor(if (sel || busy) ac else normal)
        }
    }

    // ------------------------------------------------------------------
    // 发命令
    // ------------------------------------------------------------------

    internal fun sendAnc(ctx: Context, mode: Int, onDone: (() -> Unit)? = null) {
        // 初版：不做任何节流，点一次就按顺序发那一组帧
        lastSendAt = System.currentTimeMillis()
        pendingChipMode = mode
        lastChips?.let { refreshChips(it) }
        worker.execute {
            try {
                doSendAnc(ctx, mode)
            } finally {
                pendingChipMode = -1
                main.post { lastChips?.let { refreshChips(it) } }
                try {
                    onDone?.invoke()
                } catch (t: Throwable) {
                }
            }
        }
    }

    /**
     * 一次点击 = 一帧 SET，发完就结束 —— 与参考实现 `GaiaBleClient.setAncMode` 完全一致：
     *
     *     writeCommand(F_AUDIO_CURATION, CMD_AC_SET_MODE, byteArrayOf(dev))
     *
     * 参考实现里 SET 后面**没有任何** GET / 封装帧，所以「响两遍」的根因就是当年那多出来的
     * 第二帧（FF 封装），现在彻底不掉它了。唯一的前置条件是「能力探测已跑过」
     * （参考实现里 ancPath 未知时直接拒发），所以链路刚连上时先补一轮探测。
     */
    private fun doSendAnc(ctx: Context, ui: Int) {
        try {
            if (!ensureLink(ctx)) {
                toast(ctx, "耳机链路没连上，稍等一下再点")
                return
            }
            // ★ 官方时序（之前缺的就是这一段）：连接 → 阶梯探测 → 1200ms 前置等待 → 才允许下发控制
            handshake()

            val dev = MoondropProfile.devForUi(profile, ui, ancV2Path())
            if (dev < 0) {
                log("${profile.key} 没有「${modeName(ui)}」这一档，不下发")
                toast(ctx, "${profile.key} 不支持「${modeName(ui)}」")
                return
            }
            val feature = ancFeature()
            log("ANC 下发 ui=$ui（${modeName(ui)}）→ dev=$dev path=0x${"%02X".format(feature)}")
            client.sendCommand(feature, GaiaProtocol.ANC_SET_MODE, byteArrayOf(dev.toByte()))
        } catch (t: Throwable) {
            log("发送失败: ${t.javaClass.simpleName}: ${t.message}")
            toast(ctx, "发送失败：${t.javaClass.simpleName}")
            try {
                client.close()
            } catch (e: Throwable) {
            }
        }
    }

    /**
     * ANC 走哪条 feature —— **探测优先，档案兜底**：
     *   - 握手时两条都探（AC 0x08 / V2 0x20），哪条真的回了 getMode 就用哪条；
     *   - 两条都没回（比如固件不回 ACK）才退到型号档案的 hint（GA2 系 = 0x08）。
     */
    /** 当前是不是 ANC_V2（0x20）通道 —— 决定用 4 槽还是 6 槽映射表 */
    private fun ancV2Path(): Boolean = ancFeature() == GaiaProtocol.F_ANC_V2

    private fun ancFeature(): Int {
        val probed = client.ancPath
        if (probed == GaiaProtocol.F_AUDIO_CURATION || probed == GaiaProtocol.F_ANC_V2) {
            if (probed != profile.ancHint) {
                log("ANC 路径以探测为准：探测=0x${"%02X".format(probed)} 档案=${profile.key} hint=0x${"%02X".format(profile.ancHint)}")
            }
            return probed
        }
        return profile.ancHint
    }

    /** 面板出现时预热链路：官方那套「连接 → 握手」先跑完，等用户点的时候 SET 才能立刻生效 */
    private fun prewarm(ctx: Context) {
        try {
            if (!ensureLink(ctx)) return
            handshake()
        } catch (t: Throwable) {
            log("预热失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * ★ 建链：先照抄官方走 BLE GATT（唯一能读到状态的路径），失败才退回 RFCOMM。
     *
     * 官方日志：`connect() addr=…58:22` → `GATT connected 24:01:30:B5:0D:22`
     * → `GATT services(5)` → 184 条 `GAIA RX`。
     * 我们以前只用 RFCOMM，设备一个字都不回，所以永远读不到真实模式。
     */
    private fun ensureLink(ctx: Context): Boolean {
        if (client.isConnected) return true
        // ★ 防重入：prewarm 和卡片页会几乎同时调用这里，两次 GATT 撞在一起
        //   会把订阅打断（日志里的「订阅 response 失败」「链路被设备关闭」就是它），
        //   接着耳机自己掉一次 profile，宿主把刚弹出来的页面收掉——别再来一次。
        val now = SystemClock.elapsedRealtime()
        if (now - linkingAt < 12000L) {
            log("建链：上一次还在进行中（${now - linkingAt}ms 前），跳过本次")
            return false
        }
        linkingAt = now
        val dev = client.pickMoondrop() ?: client.candidateDevices().firstOrNull()
        if (dev == null) {
            log("找不到已配对的耳机")
            return false
        }
        handshakeDone = false
        val t0 = SystemClock.elapsedRealtime()
        log("建链：先试 GATT（目标 ${dev.address} ${dev.name ?: "?"}）")
        if (client.connectGatt(ctx, dev)) {
            log("GATT 就绪 ${client.deviceAddress}，用时 ${SystemClock.elapsedRealtime() - t0}ms")
            linkingAt = 0L
            return true
        }
        log("GATT 没连上（用时 ${SystemClock.elapsedRealtime() - t0}ms）→ 回退 RFCOMM/SPP")
        return try {
            client.connect(dev)
            log("RFCOMM 已连上 ${client.deviceAddress}")
            true
        } catch (t: Throwable) {
            log("RFCOMM 也失败: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * ★ 官方握手时序（之前完全没写的那一段）——照抄
     * `CapabilityProbe.startProbes()` + `GaiaBleClient.scheduleInitialQueries()`：
     *
     *   t=0       连接完成（服务发现 + CCCD 订阅都完成）
     *   t=0       BASIC / GET_SUPPORTED_FEATURES、BASIC / GET_VARIANT 立即发
     *   t=400+i*250  AC GET_MODE / AC GET_SWITCH_CONF / V2 GET_MODE / V2 GET_SWITCH_CONF 阶梯发
     *   t≥1200    才允许后续控制命令（scheduleInitialQueries 的 1200ms 前置）
     *
     * 也就是说：**连上之后先憋住，别急着发 SET**。官方那几个探测帧是错开发出去的，
     * 我们以前一股脑全推下去，固件就当噪声丢了。
     */
    private fun handshake() {
        if (handshakeDone) return
        val t0 = SystemClock.elapsedRealtime()
        val F_AC = GaiaProtocol.F_AUDIO_CURATION
        val F_V2 = GaiaProtocol.F_ANC_V2
        val F_BASIC = GaiaProtocol.F_BASIC
        val steps = listOf(
            Triple(F_BASIC, 1, "BASIC/features"),
            Triple(F_BASIC, 4, "BASIC/variant"),
            Triple(F_AC, GaiaProtocol.ANC_GET_MODE, "AC/getMode"),
            Triple(F_AC, 0x29, "AC/switchConf"),
            Triple(F_V2, GaiaProtocol.ANC_GET_MODE, "V2/getMode"),
            Triple(F_V2, 0x29, "V2/switchConf")
        )
        for ((i, step) in steps.withIndex()) {
            val due = t0 + PROBE_DELAY_BASE + i * PROBE_DELAY_STEP
            val wait = due - SystemClock.elapsedRealtime()
            if (wait > 0) {
                try {
                    Thread.sleep(wait)
                } catch (t: InterruptedException) {
                    return
                }
            }
            val ok = client.sendCommand(step.first, step.second)
            log("探测 ${step.third} sent=$ok")
        }
        val remain = t0 + INITIAL_QUERY_DELAY_MS - SystemClock.elapsedRealtime()
        if (remain > 0) {
            try {
                Thread.sleep(remain)
            } catch (t: InterruptedException) {
                return
            }
        }
        handshakeDone = true
        log("握手完成 ${SystemClock.elapsedRealtime() - t0}ms，链路=${client.linkKind}")

        // 握手第一步就是 GET_SUPPORTED_FEATURES，回包已经落到 client.features 里了。
        // 能力与型号档案不一致时（例如档案说有空间音频、设备说没有），按设备说的重建卡片。
        val feats = client.features
        log(
            "能力探测结果 features=[${feats.sorted().joinToString(",")}]" +
                " 空间音频=${if (feats.isEmpty()) "未知" else Capabilities.hasSpatial(feats)}" +
                " ANC路径=${Capabilities.ancPathOf(feats)}"
        )
        if (feats.isNotEmpty() && Capabilities.hasSpatial(feats) != profile.spatial) {
            log("能力与档案不一致（档案空间音频=${profile.spatial}）→ 按设备自报重建卡片")
            main.post { dropInjectedCards() }
        }

        // ★ 官方 scheduleInitialQueries 的 1200ms 前置之后，第一件事就是查电量，
        //   紧接着 HeadsetDetectService 每 30 秒再刷一次。这里原样照做。
        try {
            val ok = client.fetchBatteryLevels()
            log("电量查询 sent=$ok")
        } catch (t: Throwable) {
            log("电量查询失败: ${t.javaClass.simpleName}")
        }
        // 官方 scheduleInitialQueries 里同一批还调了 DeviceControlBridge.fetchAll() → fetchSpatial + fetchHeadTracking
        // ★ 两条查询之间必须留间隔：GATT 写入队列一次只吃一条，紧挨着发会被「命令队列忙」拒掉
        //   （实测 00 1D 24 01 就是这么丢的，导致弹窗刚打开时空间音频那行是空的）
        try {
            Thread.sleep(350)
        } catch (t: InterruptedException) {
            return
        }
        var spOk = false
        try {
            spOk = client.fetchSpatial()
            log("空间音频查询 sent=$spOk")
        } catch (t: Throwable) {
            log("空间音频查询失败: ${t.javaClass.simpleName}")
        }
        if (!spOk) {
            try {
                Thread.sleep(400)
            } catch (t: InterruptedException) {
                return
            }
            try {
                log("空间音频查询重试 sent=${client.fetchSpatial()}")
            } catch (t: Throwable) {
                log("空间音频查询重试失败: ${t.javaClass.simpleName}")
            }
        }
        // 扩展设备控制：型号支持才问（DcProfile 的 hasGain/hasLed）
        if (profile.hasGain) {
            try {
                Thread.sleep(200)
            } catch (t: InterruptedException) {
                return
            }
            log("增益查询 sent=${client.fetchGain()}")
        }
        if (profile.hasLed) {
            try {
                Thread.sleep(200)
            } catch (t: InterruptedException) {
                return
            }
            log("指示灯查询 sent=${client.fetchLed()}")
        }
        startBattPolling()
    }

    /**
     * 设备回报的真实模式 → 胶囊高亮。
     * 换算**一律走当前型号档案**（SET 与 GET 是两套编号，各型号还不一样）：
     *   GA2：GET 0/1/2/3 = 关/降/透/抗
     *   布丁（ANC_V2）：GET 0/4/2/3 = 关/降/透/抗，多一个 1 = 自适应（UI 里没有这一档）
     */
    private fun applyDeviceMode(dev: Int) {
        val ui = MoondropProfile.uiForDev(profile, dev, ancV2Path())
        if (ui < 0) {
            log("设备回报未知模式 dev=$dev（档案 ${profile.key} 里没有这个码）")
            return
        }
        if (ui == pendingChipMode) {
            log("设备确认 ${modeName(ui)}")
            return
        }
        currentMode = ui
        lastChips?.let { refreshChips(it) }
        log("设备模式 → ${modeName(ui)}（dev=$dev）")
    }

    // ------------------------------------------------------------------
    // 电量
    // ------------------------------------------------------------------

    /**
     * 设备回报电量。
     *
     * 回包是 `(id, level)` 成对出现的（1=左 2=右 3=盒）：**哪只不在线，它那一对就不会出现**，
     * 所以这里 -1 不再当"这次没报、保留旧值"，而是明确的「这只不在线」。
     * 旧策略会让「左耳摘下来放回充电盒」之后还一直显示旧电量，用户看到的其实是假数据。
     */
    // ------------------------------------------------------------------
    // 扩展设备控制：增益 / 指示灯（上游 DcProfile）
    // ------------------------------------------------------------------
    private fun refreshGainChips() {
        val chips = gainChips ?: return
        val c = chips.firstOrNull()?.context ?: return
        val ac = accent(c)
        val normal = ink(c, false)
        val cur = profile.gainMap.indexOf(gainDev)
        for (chip in chips) {
            val i = (chip.tag as? Int) ?: -1
            val sel = i == cur
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(c, 18).toFloat()
                setColor(if (sel) (ac and 0x00FFFFFF) or (0x2E shl 24) else 0x14808080)
            }
            chip.setTextColor(if (sel) ac else normal)
        }
    }

    private fun onGainClick(level: Int, chips: List<TextView>) {
        val c = chips.firstOrNull()?.context ?: return
        val dev = profile.gainMap.getOrNull(level) ?: return
        gainChips = chips
        gainDev = dev                       // 乐观更新，回读会再校正
        refreshGainChips()
        worker.execute {
            try {
                if (!ensureLink(c)) {
                    toast(c, "耳机链路没连上，稍等一下再点")
                    return@execute
                }
                handshake()
                log("增益 → ${MoondropProfile.gainName(level)}（dev=$dev）")
                client.setGain(dev)
                Thread.sleep(250)
                client.fetchGain()
            } catch (t: Throwable) {
                log("设置增益失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun refreshLedSwitch() {
        val sw = ledSwitch ?: return
        if (sw.isChecked != ledOn) {
            ledSyncing = true
            try {
                sw.isChecked = ledOn
            } finally {
                ledSyncing = false
            }
        }
    }

    private fun onLedToggle(on: Boolean) {
        val c = ledSwitch?.context ?: return
        ledOn = on
        refreshLedSwitch()
        worker.execute {
            try {
                if (!ensureLink(c)) {
                    toast(c, "耳机链路没连上，稍等一下再点")
                    return@execute
                }
                handshake()
                log("指示灯 → ${if (on) "开" else "关"}")
                client.setLed(on)
                Thread.sleep(250)
                client.fetchLed()
            } catch (t: Throwable) {
                log("设置指示灯失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun applyBattery(left: Int, right: Int, case: Int) {
        battReported = true
        // ★ 0 也当「不在线」：单耳/入盒时固件**不会省略那一对**，而是回 level=0
        //   （日志实测：`id=1 level=0 id=2 level=100`，左耳在盒里）。真 0% 早就关机了，
        //   所以判 >0 比判 >=0 更贴近事实，不会再把离线的那只写成「0%」。
        leftOnline = left > 0
        rightOnline = right > 0
        if (left > 0) battLeft = left
        if (right > 0) battRight = right
        if (case > 0) battCase = case
        refreshBatteryText()
        refreshSingleEarGate()
    }

    /**
     * 电量文案：
     *   两只都在 → 「左耳 87% · 右耳 85%」
     *   一只离线 → 「左耳 87% · 右耳 离线」
     *   还没报过 → 「电量 --」（别显示假数据）
     */
    private fun refreshBatteryText() {
        val v = battText ?: return
        main.post {
            try {
                val l = if (leftOnline) "$battLeft%" else "离线"
                val r = if (rightOnline) "$battRight%" else "离线"
                // 还没拿到任何电量时显示「获取中」——卡片可能比蓝牙连接先弹出来
                //（扫描发现即弹），这段时间总得让人知道我们在干活，而不是显示假的 0%。
                v.text = if (!battReported) "电量获取中…" else "左耳 $l · 右耳 $r"
            } catch (t: Throwable) {
            }
        }
    }

    /**
     * 单耳模式（一只在充电盒里 / 关机）。
     *
     * 这种状态下空间音频固件行为异常（切了不生效、还会把设备状态搞乱），所以整块禁用。
     */
    private fun singleEar(): Boolean {
        if (!battReported) return false
        return leftOnline != rightOnline
    }

    /**
     * 该不该建「空间音频」那一块。
     *
     * 优先用**设备自报的能力位图**（F_SPATIAL = 0x12）；位图还没回来时退回型号档案。
     * 这样即使型号名被改过、或买了固件不同批次的同型号，也不会出现「点了没反应」的开关。
     */
    private fun supportsSpatial(): Boolean {
        val f = client.features
        return if (f.isEmpty()) profile.spatial else Capabilities.hasSpatial(f)
    }

    /** 按单耳/双耳切换空间音频那一块的可点状态 */
    private fun refreshSingleEarGate() {
        val off = singleEar()
        spatialChips?.let { refreshSpatialChips(it) }
        spatialSwitch?.let { sw ->
            sw.alpha = if (off) 0.4f else 1f
            sw.isEnabled = !off
        }
        if (off != gateLogged) {
            gateLogged = off
            log(
                if (off) "单耳模式：空间音频已禁用（左在线=$leftOnline 右在线=$rightOnline）"
                else "双耳都在线：空间音频已恢复可用"
            )
        }
    }

    /** 官方 HeadsetDetectService 每 30 秒刷一次电量，这里同频 */
    private fun startBattPolling() {
        if (battPolling) return
        battPolling = true
        val r = object : Runnable {
            override fun run() {
                // 已经被停掉了就不要再续期（否则 removeCallbacks 之后还会被自己拉起来）
                if (!battPolling) return
                try {
                    if (client.isConnected) client.fetchBatteryLevels()
                } catch (t: Throwable) {
                }
                if (!battPolling) return
                main.postDelayed(this, BATTERY_POLL_MS)
            }
        }
        pollRunnable = r
        main.postDelayed(r, BATTERY_POLL_MS)
        log("电量轮询已启动（${BATTERY_POLL_MS / 1000}s）")
    }

    /** 停掉电量轮询。卡片关掉 / 换设备时必须调，否则会一直在后台占着 GATT */
    private fun stopBattPolling(reason: String) {
        if (!battPolling) return
        battPolling = false
        pollRunnable?.let { main.removeCallbacks(it) }
        pollRunnable = null
        log("电量轮询已停止（$reason）")
    }

    /**
     * 卡片页面关掉时的收尾：停轮询 + 断 GAIA 连接 + 清掉所有视图引用。
     *
     * 之前这里什么都不做，导致离开卡片后 30s 轮询还在跑、GATT 连接一直挂着，
     * 既耗电，也可能和官方 App 抢连接。
     */
    private fun shutdown(reason: String) {
        stopBattPolling(reason)
        try {
            if (client.isConnected) {
                client.close()
                log("GAIA 连接已断开（$reason）")
            }
        } catch (t: Throwable) {
            log("断开 GAIA 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
        battText = null
        lastChips = null
        spatialChips = null
        spatialSwitch = null
        spatialOn = -1
        trackMode = -1
        battReported = false
        leftOnline = false
        rightOnline = false
        gateLogged = false
        hiddenByUs.clear()
        savedVis.clear()
    }

    private fun toast(ctx: Context, msg: String) {
        main.post {
            try {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
            }
        }
    }

    /** 胶囊文案：入参现在是 **UI 档位**（0 关 / 1 降 / 2 透 / 3 抗），设备码只在发帧时换 */
    internal fun modeName(ui: Int): String = MoondropProfile.uiName(ui)

    private fun log(s: String) = XposedBridge.log("$TAG $s")
}