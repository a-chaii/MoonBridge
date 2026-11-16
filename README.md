# MoonBridge · 月桥

> 把水月雨的降噪，桥进 ColorOS 的设备空间。

**MoonBridge** 是一个 LSPosed 模块，把水月雨（Moondrop）蓝牙耳机的控制能力注入到 ColorOS 的
设备空间卡片（`com.heytap.mydevices`）里 —— 不用装官方 App，也不用离开系统自带的耳机面板。

- 仅适用于 **ColorOS / OPPO / 一加 / realme** 的设备空间卡片
- 需要 **LSPosed**，作用域仅 `com.heytap.mydevices`
- 许可证：**GPL-3.0**

---

## 它做什么

| 能力 | 说明 |
|---|---|
| 降噪档位 | 按型号档案下发（关 / 降噪 / 透传 / 抗风噪），档位文案与当前状态跟随设备回报 |
| 空间音频 | 总开关 + 固定 / 小角度 / 全角度三档；**单耳模式下整块禁用**（固件在单耳时会失效甚至把状态搞乱） |
| 左右耳电量 | 30 秒轮询；不在线的那只显示「离线」，不拿旧值顶替 |
| 型号识别 | 从宿主 Intent 的 `device_title` / `device_mac_info` 判定型号，按档案走对应映射 |
| 自动弹窗 | 耳机连上后自动唤出设备卡片（可关） |
| 图标替换 | 按型号绑定，仅对应型号会被替换 |

## 已知限制

- 只在 **ColorOS 系**的设备空间卡片上验证过；AOSP、HyperOS、One UI 都不适用（也没有计划支持）
- 依赖宿主的视图结构与 Intent 参数，**宿主大版本更新后可能失效**
- 型号映射中「理论上支持」的部分按协议推断，尚未逐型号实机验证
- 空间音频相关的档位命名以本机固件实测为准，与其他型号可能不一致
- 不提供通知、EQ、独立播放界面 —— 这是插件，不是官方 App 的替代品

## 安装

1. 设备已 Root 并安装 **LSPosed**（本模块工作在 `com.heytap.mydevices` 进程内）
2. 安装 APK
3. 在 LSPosed 管理器里启用 **MoonBridge**，作用域勾选 `设备空间 / com.heytap.mydevices`
4. 重启设备（或强制停止 `com.heytap.mydevices`）
5. 连上耳机，打开设备卡片，卡片内会出现控制面板

## 从源码构建

```bash
git clone https://github.com/a-chaii/moonbridge.git
cd moonbridge
./gradlew assembleDebug
```

- 需要 Android SDK（`local.properties` 里配 `sdk.dir`）
- 编译期依赖 `de.robv.android.xposed:api:82`，运行时由 LSPosed 提供

## 兼容性

| 型号 | 状态 |
|---|---|
| Golden Ages 2（梦回 2） | 实机验证 |
| Space Travel 2（太空漫游 2） | 实机验证 |
| Space Travel 2 Ultra | 实机验证 |
| Robin（知更鸟） | 实机验证 |
| Pudding（布丁） | 实机验证 |
| 其他水月雨型号 | 按协议推断，欢迎反馈日志 |

> 反馈时请附：LSPosed 日志（过滤 `MoonBridge` / 模块日志）、耳机型号、ColorOS 版本。
> **不要**附 MAC 地址等个人信息。

## 致谢

- [FxxkMoondrop](https://github.com/bqj6666/FxxkMoondrop)（GPL-3.0）—— 本项目的协议实现与
  型号档案思路参考自该项目，详见 [THIRD_PARTY.md](THIRD_PARTY.md)
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

> 名称中的 "ColorOS"、"Moondrop" 为各自权利人的商标。本项目与 OPPO / 水月雨官方无关，
> 未获其背书。