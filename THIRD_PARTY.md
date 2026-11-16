# 第三方作品与许可

本项目以 **GPL-3.0** 发布。以下列出参考/移植来源，保留其原始版权与许可声明。

## FxxkMoondrop

- 来源：<https://github.com/bqj6666/FxxkMoondrop>
- 版权：Copyright (C) bqj6666 及贡献者
- 许可证：GNU General Public License v3.0
- 移植/参考范围（随实现推进更新）：
  - 型号 → 档位映射的设计（`AncProfileLib` 的档案组织方式与 GAIA 实测记录）
  - GAIA feature / command 常量取值
  - 能力探测（feature bitmap）优先于型号猜测的思路
  - **未移植**：GMS Fast Pair 弹窗、`com.android.settings` 设备详情页注入、常驻通知、EQ 编辑器

> 说明：以上为**思路与实测数据**层面的参考。若后续直接移植其源文件，将在文件头保留
> 原始版权声明并注明「移植自 …、已修改」，同时在本文件补充具体来源 commit。

## 其他

- GAIA 协议字段与命令值来自公开资料与该项目的实测记录，本项目未分发任何官方 App 的反编译产物。
- 图标素材不随仓库分发，公开版本不包含官方提取的产品图。
