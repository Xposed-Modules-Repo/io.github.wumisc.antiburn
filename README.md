# 防止烧屏（Antiburn）

防 OLED 烧屏工具，当前稳定版 **v2.6.5（versionCode 28）**，包名 `io.github.wumisc.antiburn`。签名证书不变，可直接覆盖安装、无需卸载。

## 两种运行方式

| 引擎 | 适用 | 能做到 | 做不到 |
|---|---|---|---|
| **LSPosed 模块** | root + LSPosed | 透明化 / 隐藏状态栏、导航栏与抖音、红果各区域，像素微移 | — |
| **无障碍遮盖** | **免 root** | 按周期盖黑系统栏，触摸完全穿透 | 透明化、像素偏移 |

同一 APK 二选一：装完先在首页「运行方式」选引擎，再打开「**防烧屏总开关**」——总闸关着时所有隐藏与遮盖都不生效。免 root 路线可选装 Shizuku 代开无障碍；要关闭功能，须先开启无障碍再关下方总开关。遮盖是让像素不发光、不是把界面变透明，想要透明化与像素微移仍需 LSPosed 引擎。

## 功能

- 状态栏、导航栏独立开关；周期时长在「功能 → 通用」磁贴弹窗调整
- 抖音 / 红果短剧分区隐藏；快手、哔哩哔哩列入适配计划（本版仅占位，不提供开关）
- 名单三态：白名单（命中豁免、其余才保护）、黑名单（仅命中才保护）、关闭；两份名单独立保存、切换互不覆盖
- 六厂商界面适配（HyperOS / MagicOS / ColorOS / OriginOS / HarmonyOS / AOSP）
- 悬浮球、诊断日志、旧版 Hook 自检与一键重启系统界面；配置改动即时落盘

## 上手

1. 从 [Releases](https://github.com/Xposed-Modules-Repo/io.github.wumisc.antiburn/releases) 下载安装
2. LSPosed 路线：管理器启用模块，勾选作用域 SystemUI、抖音 `com.ss.android.ugc.aweme`、红果 `com.phoenix.read` 后重启
3. 免 root 路线：开启无障碍服务即可

## 构建与测试

JDK 21 + Gradle 9.6 + SDK Platform 37：`cd antiburn-lite && gradle assembleDebug`。纯 JVM 回归测试 `./antiburn-tests/run-all.sh`，六套件 347 项断言全过。

## 其他

AGPL-3.0 开源，仅供学习交流。界面致敬 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 与 [Miuix](https://github.com/YuKongA/Miuix)，感谢 [LSPosed](https://github.com/LSPosed/LSPosed) 与 Xposed 社区。
