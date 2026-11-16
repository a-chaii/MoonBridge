# 交接单 · MoonPanel（重启前留档）

> 写于 2026-11-13 23:2x，手机即将重启。重启后从这里接着干。

## 一句话现状

模块 **已编译通过、已装机**，但 LSPosed 的模块库**只在开机时扫描一次**，所以它一直看不到 `moe.moonpanel`。重启后才会出现。

## 硬结论（不要推翻）

- 协议：**Gaia V3/V4 over Classic BT SPP**，SCN=1，UUID `00001101-...`，vendor `0x001D`（QTiL）
- 耳机 GA2 的 ANC 设备码：**1=关 / 2=降噪 / 3=抗风噪 / 4=透传**
- BLE 的 `9ECA0000/0001/0003` 与 ANC/电量无关，别往那条路走
- 详细资料在 `docs/protocol/`（proto.md / mc.md / NOTES.md / 三个参考 Kotlin 文件）

## 工程状态

- 路径：本工作区，包名 `moe.moonpanel`，`compileSdk=35`
- 已写：`GaiaProtocol.kt`(组帧/解帧)、`GaiaClient.kt`(SPP 三路兜底 socket)、`MainActivity.kt`(8 按钮测试页)、`XposedEntry.kt`(侦察用，dump Fragment/Preference/View)
- 编译命令：`cd <工作区> && ./gradlew assembleDebug`
- 关键修复记录：
  1. `local.properties` 必须写 `sdk.dir=/root/Android`，否则报 SDK location not found
  2. `XposedEntry.kt` 必须 `import de.robv.android.xposed.callbacks.XC_LoadPackage`
  3. Manifest 必须声明 `<category android:name="de.robv.android.xposed.category.MODULE_SETTINGS"/>`，LSPosed 靠这个扫模块
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（12 MB，已装）

## 重启后第一步

1. 打开 LSPosed → 模块 → 应出现 **MoonPanel**
2. 启用模块；作用域**只勾** `com.heytap.mydevices`（蓝牙）——**绝对不要**勾 `com.android.bluetooth`
3. 手动打开「设置 → 蓝牙 → 点 Golden Ages 2 进详情页」，停在那
4. 读日志：`/data/adb/lspd/log/modules_*.log`，找 `[MoonPanel]` 开头的行
   - 会 dump：Activity 全名、intent extras、Fragment 列表、PreferenceScreen 每条 key/title、View 树（深度 8、上限 120 个节点）
5. 目标：确定注入锚点 → 决定插 Preference 还是插 View → 克隆 COUI 样式 → 做幂等

## 已知坑

- 若日志里没有 `[MoonPanel]`，说明 `com.oplus.mydevices.bluetooth.BlueToothDetailActivity` 类名猜错，
  需要从 `com.heytap.mydevices` 的 dex 里扒真实的蓝牙详情页类名（纯读文件，不碰运行时）
- LSPosed 管理器 App 原先没装，是我从 `/data/adb/modules/zygisk_lsposed/manager.apk` 装的
- 环境是**用户手机里的虚拟机**，不做 spawn / 全量类枚举 / 杀关键进程这类过激操作

## 用户偏好

- UI 条目用中文；不兼容 `com.oplus.melody`，只做 `com.heytap.mydevices`
- 第三方 App `com.fxxkmoondrop.secret`（Mooncontroller）可作为协议参照，已实测可用
