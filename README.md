# 防止烧屏（Antiburn）

防 OLED 烧屏工具，当前版本 **v3.4.1（versionCode 143）**，包名 `io.github.wumisc.antiburn`。**从本版起改用独立正式签名**，与 3.3.2 及更早版本的签名不同，从旧签名版本升级需先卸载（详见 Release 说明）。

OLED 像素随点亮时长累积衰减：状态栏的时钟、信号、电量，导航栏按键，短视频里固定不动的点赞、评论按钮，长期停在一处会留下擦不掉的残影。模块让它们别一直亮在同一处：能透明化就透明化，不能就周期遮盖、像素微移，把累积曝光摊开。

## 反馈

遇到问题、想要功能，最快的路是 App 里点 **功能设置 › 反馈问题**（v3.4.1 起）：一句话描述现象，自动附上机型、系统版本与最近日志。

也可以到主仓库开 issue：**https://github.com/wumisc/antiburn/issues**

- 附上机型、ROM 版本、Android 版本、LSPosed 版本、模块版本号，以及「诊断日志」导出的 `antiburn.log`；缺日志基本只能靠猜。
- 这里（LSPosed 镜像仓库）的 issues 被平台统一禁用，留言看不到，请不要在这里提。

## 两种运行方式

- **LSPosed 模块**（root + LSPosed）：透明化 / 隐藏状态栏、导航栏与抖音、红果、快手、B站各区域，可像素微移；前提是模块被注入目标进程。
- **无障碍遮盖**（免 root）：按周期盖黑系统栏，按比例遮盖四个视频 App 的右侧列、顶部、底部，触摸完全穿透；做不到透明化与像素偏移。

同一 APK 二选一：首页「运行方式」选引擎，再打开「防烧屏总开关」，总闸关着时一切隐藏与遮盖都不生效。

## 功能

- 状态栏、导航栏独立开关；周期时长在「功能 → 通用」调整
- 抖音、红果短剧、快手、B站各自独立卡片，分区隐藏互不干扰
- 名单三态：白名单豁免 / 黑名单仅命中保护 / 关闭，两份独立保存
- 七套界面适配（HyperOS / MagicOS / ColorOS / OriginOS / HarmonyOS / Flyme / AOSP），按机型自动识别
- 进直播间自动暂停隐藏，退出恢复；固定控件微移 2~3px，画面可见、触摸照常
- 悬浮球、诊断日志、旧版 Hook 自检、一键重启系统界面；配置改动即时落盘
- 待机低功耗：配置预取与遮盖服务自适应降频，灭屏停车；日志只在状态变化时落盘

## 上手

1. 从 [Releases](https://github.com/Xposed-Modules-Repo/io.github.wumisc.antiburn/releases) 下载安装
2. LSPosed 路线：管理器启用模块，勾选 SystemUI、抖音 `com.ss.android.ugc.aweme`、红果 `com.phoenix.read.oversea.gp`、快手 `com.smile.gifmaker`、B站 `tv.danmaku.bili`，重启系统界面
3. 免 root 路线：开启无障碍服务即可；有 root 时首次启动会自动启用

## 常见问题

- **装完没反应**：先确认总开关已开、作用域已勾，再重启系统界面
- **视频 App 分区不生效**：日志无 `INJECTED` 行说明未注入该进程；到「功能 → 通用 → 作用域自检」补勾作用域，强停对应 App 再重开
- **隐藏太多或不够**：到对应 App 卡片单独调右侧列、顶部、底部、角落
- **换 ROM 后失效**：先到「界面适配」重选机型

## 构建与测试

JDK 21 + Gradle 9.8.2 + SDK 37：`cd antiburn-lite && gradle assembleRelease`。纯 JVM 回归测试 `./run-all.sh`。

## 其他

AGPL-3.0 开源，仅供学习交流。界面致敬 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 与 [Miuix](https://github.com/YuKongA/Miuix)，感谢 LSPosed 与 Xposed 社区。捐赠完全自愿、与功能无关。
