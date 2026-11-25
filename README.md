# MoonBridge

> 把水月雨耳机接进 ColorOS 的设备空间。

> ⚠️ **代码由 AI 辅助生成** —— 本项目绝大部分代码由大语言模型编写，作者负责提需求、真机实测和取舍
> 它没有经过代码审计或多机型回归测试

**MoonBridge** 是一个 LSPosed 模块，把水月雨（MOONDROP）蓝牙耳机的控制能力注入 ColorOS 的
「设备空间」卡片（`com.heytap.mydevices`）—— 不用装官方 App，也不用离开系统自带的耳机面板。

- 仅适用于 **ColorOS / OPPO / 一加 / realme** 的设备空间卡片
- 需要 **LSPosed**，作用域只勾 `com.heytap.mydevices` 一个


## 它做什么

| 能力 | 说明 |
|---|---|
| 降噪档位 | 按型号档案下发（关 / 降噪 / 透传 / 抗风噪），档位文案与当前状态跟随设备回报 |
| 空间音频 | 总开关 + 固定 / 小角度 / 全角度三档；**单耳模式下整块禁用**（固件在单耳时会失效甚至把状态搞乱） |
| 左右耳电量 | 30 秒轮询；不在线的那只显示「离线」，不拿旧值顶替 |
| 型号识别 | 从宿主 Intent 的 `device_title` / `device_mac_info` 判定型号，按档案走对应映射 |
| 自动弹窗 | 耳机连上后自动唤出设备卡片（可关） |
| 图标替换 | 按型号绑定，只有对应型号会被替换 |

## 已知限制

- 只在 **ColorOS 系**的设备空间卡片上验证过；AOSP、HyperOS、One UI 都不适用（也没有计划支持）
- 依赖宿主的视图结构与 Intent 参数，**宿主大版本更新后可能失效**
- 型号映射中「理论上支持」的部分按协议推断，尚未逐型号实机验证
- 空间音频相关的档位命名以本机固件实测为准，与其他型号可能不一致

## 下载
从 [**Releases**](https://github.com/a-chaii/MoonBridge/releases/latest) 下载最新 APK（`MoonBridge-x.y.z.apk`）。仓库里只有源码，安装包都在 Releases 页面。

## 安装
1. 设备已 Root 并安装 **LSPosed**（本模块工作在 `com.heytap.mydevices` 进程内）
2. 安装上面下载的 APK
3. 在 LSPosed 管理器里启用 **MoonBridge**，作用域勾选 `设备空间 / com.heytap.mydevices`
4. 重启设备（或强制停止 `com.heytap.mydevices`）
5. 连上耳机，打开设备卡片，卡片内会出现控制面板

## 从源码构建

```bash
git clone https://github.com/a-chaii/MoonBridge.git
cd moonbridge
./gradlew assembleDebug
```

- 需要 Android SDK（`local.properties` 里配 `sdk.dir`）
- 编译期依赖 `de.robv.android.xposed:api:82`，运行时由 LSPosed 提供

## 兼容性

| 型号 | 状态 |
|---|---|
| Golden Ages 2（梦回 2） | 实机验证 |
| Space Travel 2 Ultra | … |
| 其他水月雨型号 | 按协议推断，欢迎反馈日志 |

> 反馈时请附：LSPosed 日志（过滤 `MoonBridge`）、耳机型号、ColorOS 版本。
> **不要**附 MAC 地址等个人信息。

## 致谢

- [FxxkMoondrop](https://github.com/bqj6666/FxxkMoondrop)（GPL-3.0）
  —— 本项目的**型号档案（ANC 档位映射、ANC_V2 六槽、DC 增益/LED）、GAIA 命令常量与
  回包语义、能力位图解析，以及「LE 地址缓存闭环」的思路**参考自该项目；
  代码以 Kotlin 重写，未整文件复制。逐项对应关系见 [THIRD_PARTY.md](THIRD_PARTY.md)
- 参考实现中已公开的 GAIA 协议实测记录

## 许可证

本项目以 **GNU General Public License v3.0** 发布，全文见 [LICENSE](LICENSE)。

```
Copyright (C) 2026 MoonBridge contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```

## 关于风险（请抽两分钟看完）

**先说影响范围——这个模块能造成多大的问题，其实是有上限的。**

它只注入 `com.heytap.mydevices`（设备空间）这一个应用进程，不碰系统框架、SystemUI、蓝牙服务，
也不写系统分区、不改任何开机流程。所以：

- 最坏情况是**设备空间打不开、卡片闪退**，或者面板里的电量/档位显示错乱
- 这时在 LSPosed 里关掉本模块（或直接卸载），再强制停止设备空间，就恢复正常了
- 它不会导致无法开机，不影响其他 App，也不会有残留的系统级修改
- 唯一需要留意的持久副作用：如果面板把错误的档位下发给耳机，耳机可能需要用官方 App 或机身按键恢复一次

**然后是几条实话：**

1. **个人项目**。非商业，无公司或团队背书。作者对 ColorOS 的后续改动、对耳机固件的行为都没有控制力，
   所以不承诺长期可用。
2. **AI 辅助生成**。代码主要由大语言模型编写，没有经过代码审计、渗透测试或多机型回归测试。
3. **无担保**。本项目按 GPL-3.0 第 15、16 条以「现状」提供，不附带任何明示或暗示担保。
4. **隐私**。不申请 `INTERNET` 权限（`AndroidManifest.xml` 里没有），代码中也没有任何 HTTP / Socket
   联网逻辑；不收集、不上传、无遥测。只申请蓝牙权限，用于向耳机发 GAIA 指令、读电量与状态。
   注意：你自己贴出来的日志里**可能含 MAC 地址**，反馈前请先脱敏。
5. **数据仅供参考**。电量、档位、空间音频状态都来自耳机固件回报，有延迟也可能不一致，
   不要当作安全依据。
6. **商标**。`ColorOS`、`OPPO`、`一加`、`realme` 为 OPPO 及其关联公司的商标；`MOONDROP`、`水月雨`
   为水月雨（Moondrop）的商标；`LSPosed`、`Xposed` 分属各自项目。本项目仅作描述性使用，
   **与上述任何一方均无隶属、合作或背书关系**，也未获其授权。
7. **与上游的关系**。本项目参考/移植了 [FxxkMoondrop](https://github.com/bqj6666/FxxkMoondrop)
   （GPL-3.0）的部分实现，详见 [THIRD_PARTY.md](THIRD_PARTY.md)。本项目与其作者无隶属关系，
   上游不为本项目的问题负责。
8. **保修与合规**。Root、LSPosed 及修改系统界面行为可能影响设备保修，也可能与你所在组织的设备
   管理规定冲突，请自行评估。
9. **权利方异议**。若任何权利方认为本项目存在不当使用，请通过 Issue 联系，会配合修改或下架。
