# 防止烧屏（Antiburn）

防 OLED 烧屏的 LSPosed 模块：周期性透明化固定 UI（不点亮像素、保留触摸），支持状态栏 / 导航栏 / 抖音 / 红果分区防烧屏、励志古诗滚动、白名单、悬浮球与快捷磁贴。

**当前版本** v1.6（精简磁贴版）｜包名 `io.github.wumisc.antiburn`｜作者 @酷安锦无名

## 功能特性

- **周期防烧屏**：状态栏图标、底部导航栏按周期透明化，透明化而非隐藏，下拉/触摸不受影响；通知图标区保留显示
- **白名单**：前台命中白名单的应用不隐藏（游戏 / 视频 / 导航防误伤）；熄屏自动暂停
- **抖音**：顶部 / 底部 / 右侧按钮 / 右上 / 左上，五区独立开关；直播间固定 UI 独立开关
- **红果短剧**：同抖音五区独立开关
- **励志古诗滚动**：状态栏横向滚动，联网每 3 天换一批，内置库兜底，可自定义诗句
- **悬浮球**：屏幕右侧小球，点击一键切换隐藏控件显示 / 消失，可拖动
- **界面**：Compose 液态玻璃导航栏（首页 / 功能 / 关于 3 Tab），深色模式自适应；下拉快捷磁贴一键启停

## 环境要求

| 依赖 | 说明 |
|---|---|
| KernelSU / Magisk | 二选一，需 root |
| LSPosed（IT 版） | 推荐支持 HyperOS 4 / Android 17 的版本 |
| 系统 | 已在澎湃 HyperOS 4 / Android 17（Redmi K90 Ultra）实测 |

## 安装

1. 下载 [antiburn-v1.6.apk](https://github.com/Xposed-Modules-Repo/io.github.wumisc.antiburn/releases/download/v1.6/antiburn-v1.6.apk) 并安装（若装有旧包 `com.antiburn.lite`，先在 LSPosed 取消勾选避免冲突）
2. LSPosed Manager → 模块 → 启用「防止烧屏」
3. 勾选作用域：**SystemUI**（必勾）、**抖音** `com.ss.android.ugc.aweme`、**红果短剧** `com.phoenix.read`
4. 重启手机（或重启 SystemUI）

## 配置

App 内可调全部开关，保存后一个周期内自动生效。配置写入 `/data/local/tmp/antiburn.conf`（需 root）。

## 更新日志

**v1.6（2026-11-04）**
- 包名统一为 `io.github.wumisc.antiburn`
- 修复：功能页时长文案误把配置对象 `AbState(...)` 打进界面（Kotlin 字符串插值缺 `${}`）——现只显示秒数
- 界面重构为 Kyant0 液态玻璃导航栏（3 Tab）+ 深色模式自适应
- 功能全面对标老版：状态栏/导航栏透明化、抖音五区+直播间、红果五区、古诗滚动、悬浮球、白名单、磁贴、诊断日志

**历史**：v2.6 Miuix UI+深色+新图标｜v2.5 黑名单→白名单反转｜v2.4 修复导航栏遮盖+覆盖 bug｜v2.0 抖音/红果防烧屏、悬浮球、古诗弹幕

## 许可证

[AGPL-3.0](LICENSE) —— 允许自由使用、修改与分发，衍生作品须同样以 AGPL 开源，不得闭源商用。

## 致谢

[LSPosed](https://github.com/LSPosed/LSPosed) / [LSposed (IT)](https://github.com/JingMatrix/LSposed)、[ZygiskNext](https://github.com/Dr-TSNG/ZygiskNext)、[一言](https://hitokoto.cn/) 与整个 Xposed 社区。

**界面致敬**：液态玻璃导航栏（`LiquidBottomTabs`）组件移植自 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0，基于 backdrop-android）——向作者致敬。

> 如果觉得好用，欢迎支持作者：<img src="donate_qr.webp" alt="微信捐赠二维码" width="120" />
