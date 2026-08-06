# 码记 MaJi

## 本软件为自用，本人仅需要简单的记账功能，这个软件基本满足使用，所以无重大问题不会再更新

取件码提醒 + 记账的 Android 单机工具，主打「截图 / 通知 → 大模型识别 → 自动上岛 / 自动记账」。

## 功能

- **截图识别，自动上岛**：对取件码 / 取餐码截图，自动识别并挂到小米 HyperOS **超级岛**（焦点通知）；支持取餐、奶茶、咖啡、取件码、快递柜、票号、排号等类型。
- **取件码管理**：列表按未完成在前、时间倒序排列；长按多选批量删除 / 上岛 / 标记已取；支持手动新增与编辑；已取超过 7 天自动清理。
- **记账**：收入 / 支出，多分类；截图识别到金额时自动生成一笔；按日分组 + 今日 / 本月汇总；长按多选批量删除。
- **通知识别**：通过通知监听服务读取通知，命中白名单关键词后交由大模型提取取件码自动上岛，支付类通知自动记账（需在系统设置中授予「通知使用权」）。
- **MiMo 智能识别**：优先使用小米 MiMo 大模型（复用设备已登录的小米账号能力），未登录时回退到标准 OpenAI 兼容 API。
- **自动备份**：本地（公共 Download/MaJiBackup 目录）+ WebDAV；支持手动 / 定时，可选加密。
- **桌面收支 Widget**：标准 Android AppWidget，独立进程控内存。

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.3.20 |
| UI 框架 | JetBrains Compose Multiplatform | 1.11.1 |
| 设计语言 | Miuix（HyperOS） | 0.9.3 |
| 页面跳转 | AndroidX navigation3 + navigationevent | 1.1.4 / 1.1.2 |
| 数据库 | Room + KSP | 2.8.3 |
| 图片加载 | Coil | 2.7.0 |
| 协程 | kotlinx-coroutines-android | 1.9.0 |
| 原生桥接 | Rust（`maji_core` cdylib → `libmaji_core.so`） | reqwest + rustls + tokio |
| 高权限通道 | Shizuku | 13.1.5 |
| 渐进模糊 | Haze | 1.7.2 |

最低 / 目标 / 编译 SDK：33 / 37 / 37；包名 `com.zhaoyi.maji`，versionName `2.0-next`（versionCode 47）。

## 技术原理

- **识别链路**：截图 / 通知文本 → 提示词（设置内可编辑，代码内置默认）→ MiMo 会话优先，否则标准 OpenAI 兼容 API → 解析为取件码 / 账单 JSON → 入本地库并上岛。
- **截图能力**：优先走 Shizuku / Root，无高权限时回退到系统媒体投影授权；另有快捷磁贴、音量键无障碍快捷两种触发方式。
- **超级岛**：双后端分发——小米私有 `miui.focus` 协议（HyperOS）与 Android AOSP 推广常驻通知（live_update，API<36 降级为普通常驻通知）；非小米设备退化为本地列表。
- **本地数据**：Room 持久化（`maji.db`, version 4）；识别核心逻辑由 Rust 编译的 `libmaji_core` 提供（`rust/` 源码）。

## 权限与触发

- **通知使用权**：通知识别所需，系统设置中手动授予。
- **媒体投影**：截图识别所需（系统授权）。
- **无障碍服务**：音量键快捷触发（可选开启）。
- **Shizuku**：获取更高截图权限（可选）。
- **开机自启 / 后台保活**：避免冷启动识别卡顿，需在系统设置中允许自启。

## 构建

环境要求：

- Android SDK（compileSdk 37）+ JDK 17
- Rust 工具链（如需重新编译 `libmaji_core` 原生库；已编译产物 `app/src/main/jniLibs/arm64-v8a/libmaji_core.so` 可直接打包）

构建产物：

```bash
./gradlew assembleRelease   # 输出 app/build/outputs/apk/release/maji-2.0-next.apk
```

仓库自带便捷脚本：`build_and_install.bat`（Windows 一键构建+安装到设备）。

## 致谢

以下与 App 内「设置 → 第三方开源许可」页一致，码记在这些项目的基础上开发：

- [Miuix](https://github.com/compose-miuix-ui/miuix) —— HyperOS / MIUI 设计语言组件库（Apache-2.0）
- [miuix-skill](https://github.com/limczhh/miuix-skill) —— Miuix 组件用法参考（许可见仓库）
- [BeeCount](https://github.com/TNT-Likely/BeeCount) —— 记账交互参考（自定义许可，见仓库）
- [KernelSU-Style-UI-Kit](https://github.com/chenaizhang/KernelSU-Style-UI-Kit) —— UI 风格参考（GPL-3.0）
- [Maling-Island](https://github.com/SiberiaApp/Maling-Island) —— 超级岛实现参考（AGPL-3.0）
- [SignalDock](https://github.com/jizizr/signaldock) —— 上岛 / Dock 交互参考（GPL-3.0）
- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) —— 液态玻璃底栏参考（Apache-2.0）

## License

GNU GPL-3.0-only
