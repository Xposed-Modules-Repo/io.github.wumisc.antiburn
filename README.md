# 防止烧屏（Antiburn）

防 OLED 烧屏工具，当前稳定版 **v2.8.3（versionCode 46）**，包名 `io.github.wumisc.antiburn`，签名不变，可直接覆盖安装。

OLED 像素会随点亮时长累积衰减。状态栏的时钟、信号、电量，导航栏的三个按键，短视频里固定不动的点赞、评论按钮，长期停在一处就会留下擦不掉的残影。模块让它们别一直亮在同一处：能透明化就透明化，不能就周期遮盖、像素微移，把曝光摊开。

## 两种运行方式

| 引擎 | 适用 | 能做到 | 做不到 |
|---|---|---|---|
| **LSPosed 模块** | root + LSPosed | 透明化 / 隐藏状态栏、导航栏与抖音、红果、快手、B站各区域，像素微移 | 需要模块确实被注入目标 App 进程 |
| **无障碍遮盖** | **免 root** | 按周期盖黑系统栏，并按屏幕比例遮盖抖音 / 红果 / 快手 / B站 的右侧按钮列、顶部标题、底部导航；触摸完全穿透 | 透明化、像素偏移 |

同一 APK 二选一：先在首页「运行方式」选引擎，再打开「**防烧屏总开关**」——总闸关着时一切隐藏与遮盖都不生效。免 root 路线可用 Shizuku 代开无障碍；要关闭功能，须先开无障碍再关总开关。遮盖只是让像素不发光，想要透明化与像素微移仍需 LSPosed。

**应用内固定区域有三条通道**，任一条成立就能防住：

| 通道 | 前提 | 说明 |
|---|---|---|
| 真隐藏 | 模块注入目标 App 进程 | 效果最好，透明化不留黑边；临时 root 常被反注入拦掉 |
| 应用内区域兜底 | 开启本应用无障碍服务 | 无障碍覆盖层按比例遮盖，服务没连上就不生效 |
| **系统界面代理遮盖（v2.8.3）** | 模块注入 SystemUI | 由系统界面自己加一层覆盖窗口，不需要无障碍、不需要注入目标 App |

有 root 时，首次打开本应用会自动把无障碍服务并入系统设置（`A11Y auto-enabled`），不需要手动去设置页找。三条通道都可在「功能 → 通用」单独关闭。

## 功能

- 状态栏、导航栏独立开关；周期时长在「功能 → 通用」磁贴弹窗调整
- 抖音、红果短剧、快手、哔哩哔哩各自独立卡片，分区隐藏互不干扰（快手 / B站 可分别开关右侧列、顶部、底部、角落）
- 名单三态：白名单（命中豁免）、黑名单（仅命中才保护）、关闭；两份独立保存、切换不互相覆盖
- 七套界面适配（HyperOS / MagicOS / ColorOS / OriginOS / HarmonyOS / **魅族 Flyme** / AOSP），首次打开按机型自动识别
- 进直播间自动暂停隐藏，退出即按原开关恢复
- 应用内区域兜底：不依赖 LSPosed 注入目标进程，按屏幕比例遮盖右侧按钮列 / 顶部标题 / 底部导航，触摸穿透
- 系统界面代理遮盖：由已注入的 SystemUI 直接绘制，不要求无障碍服务与目标进程注入，与系统栏共用同一周期
- 悬浮球、诊断日志、旧版 Hook 自检与一键重启系统界面；配置改动即时落盘
- **待机几乎不耗电**：配置预取自适应降频（活跃 2 秒 / 空闲 20 秒 / 灭屏 60 秒），遮盖服务空转 5 秒一帧，灭屏后不再查询前台应用
- 感谢名单（酷安 / 心之所及等）与支持捐赠

## 上手

1. 从 [Releases](https://github.com/Xposed-Modules-Repo/io.github.wumisc.antiburn/releases) 下载安装
2. LSPosed 路线：管理器启用模块，勾选作用域 SystemUI、抖音 `com.ss.android.ugc.aweme`、红果 `com.phoenix.read`、快手 `com.smile.gifmaker`、B站 `tv.danmaku.bili` 后重启
3. 免 root 路线：开启无障碍服务即可。有 root 时首次启动会自动开启（日志出现 `A11Y auto-enabled`），不需要手动找设置项

## 常见问题

- **装完没反应**：先看总开关是否打开，再看作用域是否勾选，最后用「重启系统界面」让 Hook 重新注入。
- **抖音 / 快手 / B站 分区不生效**：导出日志看 `OVL` 与 `SELF` 行。有 `OVL on app=douyin areas=…` 说明系统界面代理遮盖已经在画，区域位是 `R/T/B/C`；`OVL FAILED attach` 说明本机 ROM 拒绝了覆盖窗口；连 `OVL window attached` 都没有，说明模块没进系统界面。`SELF` 里的 `inject=` 全为 0 即真隐藏没跑。
- **无障碍服务有没有开**：看 `A11Y enabled=`，为 0 时「应用内区域兜底」不会生效。
- **隐藏太多或不够**：到对应 App 卡片里单独调，右侧列、顶部、底部、角落可分开开关。
- **通知图标没了**：隐藏时会保留通知区，若仍看不到，开 30 秒诊断日志看 `notify area` 几行。
- **换 ROM 后失效**：先到「界面适配」重选机型，选错会抓不到容器视图。
- **耗电偏高**：2.7.5 已把三处常驻轮询全部降频；若仍在耗电榜前列，先确认日志里没有 `HOOK-STALE`（混装旧 Hook 会走旧逻辑）。

## 构建与测试

JDK 21 + Gradle 9.6 + SDK Platform 37：`cd antiburn-lite && gradle assembleRelease`。纯 JVM 回归测试 `./run-all.sh`，六套件全绿（含 v2.8.3 新增 T-28 专项 16 项断言）。

## 其他

AGPL-3.0 开源，仅供学习交流。界面致敬 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 与 [Miuix](https://github.com/YuKongA/Miuix)，感谢 [LSPosed](https://github.com/LSPosed/LSPosed) 与 Xposed 社区。

## 支持作者

觉得好用可以请作者喝杯咖啡，捐赠完全自愿、与功能无关。

<table>
  <tr>
    <td align="center"><b>支付宝</b><br><img src="assets/alipay.jpg" width="200" alt="支付宝收款码"></td>
    <td align="center"><b>微信</b><br><img src="assets/wechat.png" width="200" alt="微信收款码"></td>
  </tr>
</table>
