# 第三方作品与许可

本项目以 **GPL-3.0** 发布。以下列出参考/移植来源，保留其原始版权与许可声明。

## FxxkMoondrop

- 来源：<https://github.com/bqj6666/FxxkMoondrop>（main 分支，2026-11 快照）
- 版权：Copyright (C) bqj6666 及贡献者
- 许可证：GNU General Public License v3.0
- 本项目**实际参考/移植**的内容：

| 内容 | 上游文件 | 我们这边 |
|---|---|---|
| 型号 → ANC 档位映射（GA2 / 太空漫游 2 / 知更鸟 / 布丁） | `AncProfileLib.PROFILES` | `MoondropProfile.PROFILES`（改写为 Kotlin 数据结构） |
| ANC_V2 六槽映射（布丁：含自适应、屏蔽直播） | `AncProfileLib.ANC_V2_PROFILES` | `Profile.ancV2Set / ancV2Get` |
| DC 能力档案（空间音频 / 增益 / LED / gainMap） | `AncProfileLib.DcProfile` | `Profile.hasGain / hasLed / gainMap / spatial` |
| ANC_V1 两态映射（ui0→dev0，其余→dev1） | `GaiaCommands.ancDevFromUi` | `MoondropProfile.v1DevForUi / v1UiForDev` |
| GAIA feature / command 常量与 payload 格式（电量、ANC、空间音频、头部追踪、DAC 增益、LED） | `GaiaCommands` / `GaiaConstants` | `GaiaProtocol` / `GaiaClient` |
| 能力位图解析（4 字节大字位图、配对表、分页） | `CapabilityProbe.parseSupportedFeatures` | `Capabilities` |
| 回包判定与「设备自报优先于型号猜测」的顺序 | `GaiaPacketHandler` / `GaiaBleClient` | `GaiaClient.parse*` + `PanelInjector.ancFeature()` |
| 「LE 地址缓存 → 下次秒连，地址变化自动重扫」的思路 | README「生命闭环」 | `LeCache` + `GaiaGattTransport.resolveLeCandidates` |
| GAIA 帧结构（RFCOMM/SPP 分帧、长度/校验） | `GaiaRfcommFramer` 等 | `GaiaRfcommFramer`（独立实现，行为对齐） |

以上均为**协议常量、实测数据与行为语义**层面的参考，代码由本项目用 Kotlin 重写，
没有整文件复制上游源码。

本项目**未包含**的上游部分：9ECA BleSource 私有协议、EQ/PEQ 编辑、麦克风增益、
Fast Pair 半屏接管、常驻通知、`com.android.settings` 设备详情页注入、
ProbeBudget、DeviceMatcher，以及任何官方 App 的反编译产物。

如果上游作者对上述参考方式有异议，或希望调整署名措辞、删除某部分，请开 issue，
我们会按作者意愿修改。

## 其他

- GAIA 协议字段与命令值来自公开资料与该项目的实测记录，本项目未分发任何官方 App 的反编译产物。
- 图标素材不随仓库分发，公开版本不包含官方提取的产品图；应用图标为项目作者自制。