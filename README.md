# 防止烧屏（Antiburn Statusbar Auto-Hide）

防 OLED 烧屏的 LSPosed 模块。周期性将状态栏图标层透明化（不点亮像素），同时保留触摸区域——隐藏期间照样能下拉通知栏和控制中心。

作者：@酷安锦无名

---

## 特性

- **周期防烧屏**：状态栏图标层（时钟 / 信号 / 电量）按周期透明化，透明即不点亮像素，从根源上减少 OLED 烧屏风险
- **下拉不受影响**：透明化而非隐藏，触摸区域完整保留，**任意 App 中都能正常下拉通知栏与控制中心**
- **导航栏可选**：可一并透明化底部导航栏（手势热区保留）
- **App 白名单**：前台命中白名单时不隐藏（打游戏 / 看视频 / 导航时防误伤）
- **灭屏自动暂停**：熄屏时停止周期，亮屏自动恢复
- **快捷开关磁贴**：下拉快捷面板一键开 / 关防烧屏
- **深色模式**：界面自动跟随系统深浅色
- **配置界面**：隐藏范围、周期时长、白名单均可在 App 内调整，保存后自动重启系统界面立即生效

## 原理

通过 LSPosed 仅 hook **com.android.systemui**（SystemUI）进程，在状态栏窗口挂载时定位图标内容容器视图，周期性执行 `setAlpha(0f / 1f)`。

- `alpha=0` → 不绘制、不点亮像素（防烧屏），但视图仍在 → 触摸与下拉正常
- `alpha=1` → 恢复显示

相比 `settings put global policy_control` 方案（澎湃 HyperOS 4.0 已实测不响应），本方案直接作用于视图层，兼容性更稳。

## 环境要求

| 依赖 | 说明 |
|---|---|
| KernelSU / Magisk | 二选一，需 root |
| ZygiskNext 或同类 Zygisk | KernelSU 不内置 Zygisk，需自行安装 |
| LSPosed（IT 版） | 推荐支持 HyperOS 4 / Android 16 的版本（如 LSPosed IT 2.1.x） |
| 系统 | 已在澎湃 HyperOS 4.0 / Android 16（K90 Ultra）实测 |

## 安装

1. 安装 `防止烧屏-v1.5.apk`
2. 打开 **LSPosed Manager** → 模块 → 启用「防止烧屏」→ 勾选作用域 **SystemUI**
3. 重启手机（或重启 SystemUI）
4. 可选：下拉快捷面板 → 编辑磁贴 → 将「防止烧屏」拖入常用区，实现一键开关

## 配置

App 内即可完成全部配置（推荐），保存后自动重启系统界面生效。

配置文件位于 `/data/local/tmp/antiburn.conf`，格式：

```ini
# 隐藏状态栏图标（1=开 0=关）
enable_status=1
# 隐藏导航栏（1=开 0=关）
enable_navigation=0
# 隐藏持续秒数
hide_duration=30
# 显示恢复秒数
show_duration=10
# 白名单：前台在这些包时不隐藏，逗号分隔
whitelist=com.miui.home,com.tencent.mm
```

修改配置文件保存后，模块会在一个周期内自动读取生效，无需重启。

## 验证

模块运行日志 tag 为 `Antiburn`：

```sh
logcat -d -s Antiburn
```

正常工作时应能看到 `status transparent` / `status shown` 交替输出。

## 卸载

1. LSPosed Manager 中停用 / 移除模块
2. 卸载 App
3. 删除配置文件：`rm /data/local/tmp/antiburn.conf`
4. 重启

模块不修改系统文件、不打补丁，卸载后完全还原。

## 兼容性说明

- 状态栏图标容器通过多级候选 id（`status_bar_contents` / `status_bar_icons` / `notification_icon_area`）定位，适配不同 ROM 布局
- 若你的机型下拉异常或导航栏不生效，请抓取 `logcat -s Antiburn` 日志反馈，作者会跟进适配

## 许可证

[GPL-3.0](LICENSE)

本模块以 GNU GPL v3 开源：允许自由使用、修改与分发，但**任何衍生作品必须同样以 GPL 许可开源**，不得将本模块或其衍生代码闭源商用牟利。

## 致谢

- [LSPosed](https://github.com/LSPosed/LSPosed) / [LSposed (IT)](https://github.com/JingMatrix/LSposed) —— Xposed 框架
- [ZygiskNext](https://github.com/Dr-TSNG/ZygiskNext) —— Zygisk 实现
