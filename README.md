# 防止烧屏（Antiburn Statusbar Auto-Hide）

防 OLED 烧屏的 LSPosed 模块。周期性将固定 UI 透明化（不点亮像素），同时保留触摸区域——隐藏期间照样能下拉通知栏和控制中心；并针对抖音 / 红果短剧做页面防烧屏，附带状态栏励志古诗弹幕与悬浮球切换控件等实用功能。

当前版本：**v2.0**（HyperOS 4 主版，另附同名 `-hyperos3` 版）

作者：@酷安锦无名

---

## 特性

**系统界面**
- **周期防烧屏**：状态栏图标层（时钟 / 信号 / 电量等）按周期透明化，透明即不点亮像素，从根源上减少 OLED 烧屏风险
- **通知保留**：隐藏时只透明静态元素，**通知图标区照常显示**——新消息照样能看到小图标，不止灵动岛
- **下拉不受影响**：透明化而非隐藏，触摸区域完整保留，任意 App 中都能正常下拉通知栏与控制中心
- **导航栏可选**：可一并透明化底部导航栏（手势热区保留）
- **白名单**：前台命中白名单时不隐藏（打游戏 / 看视频 / 导航时防误伤）
- **灭屏自动暂停**：熄屏时停止周期，亮屏自动恢复

**抖音 / 红果短剧**
- **抖音隐藏**：顶部导航 / 底部导航 / 右侧按钮列（点赞/评论/收藏/分享）/ 右上角 / 左上角，各自独立开关
- **直播间固定 UI**：隐藏直播页固定控件，独立开关（黑屏时关闭可排查是否为模块导致）
- **红果短剧隐藏**：同抖音五区独立开关
- **悬浮球**：屏幕右侧小球，点击一键切换「隐藏控件显示 / 消失」，便于查看各区域隐藏效果；独立开关

**状态栏励志古诗弹幕**
- 状态栏内**横向滚动弹幕**（25fps），一行滚完自动接下一首，随机顺序
- **联网换一批**：数据来自一言接口，联网缓存 5 首，每 3 天自动换一批；无网时用内置 25 首励志库兜底
- **反色自适应**：遇浅背景变深字、遇深背景变浅字
- **支持自定义**：可填自定义诗句（每行一首）

**界面与体验**
- **运行状态总览卡**：一眼看清隐藏/显示周期 + 六个功能芯片（状态栏/导航栏/抖音/红果/古诗/悬浮球，开=绿、关=灰）
- **抖音/红果合并大类**：点击标题展开/收起，分别设置抖音与红果的隐藏区域
- 悬浮球独立成卡，快捷开关磁贴，桌面图标开关，诊断日志开关
- 全新 App 图标（盾牌 + 手机屏幕像素 + 防护对勾）

## 原理

通过 LSPosed 分两路注入：

1. **SystemUI 进程**（`com.android.systemui`）：在状态栏窗口挂载时定位图标内容容器，周期性执行 `setAlpha(0f / 1f)`。
   - `alpha=0` → 不绘制、不点亮像素（防烧屏），但视图仍在 → 触摸与下拉正常
   - `alpha=1` → 恢复显示
   - 相比 `settings put global policy_control` 方案（澎湃 HyperOS 已实测不响应），本方案直接作用于视图层，兼容性更稳
   - 隐藏时通过 `findNotifyArea` 识别状态栏通知图标区并保留显示，只透明时钟/信号/电池等静态元素

2. **抖音 / 红果进程**（`com.ss.android.ugc.aweme`、`com.phoenix.read`）：hook `Activity.onCreate` + 布局变化监听，按开关逐区 `setAlpha` 隐藏顶部/底部/侧边按钮等固定 UI，事件驱动即时压住新容器。

古诗弹幕以 `TYPE_APPLICATION_OVERLAY` 窗口叠在状态栏区域，横向滚动、透明背景，不遮挡 App 界面。

## 环境要求

| 依赖 | 说明 |
|---|---|
| KernelSU / Magisk | 二选一，需 root |
| ZygiskNext 或同类 Zygisk | KernelSU 不内置 Zygisk，需自行安装 |
| LSPosed（IT 版） | 推荐支持 HyperOS 4 / Android 17 的版本（如 LSPosed IT 2.1.x） |
| 系统 | 已在澎湃 HyperOS 4 / Android 17（Redmi K90 Ultra）实测；HyperOS 3 用 `-hyperos3` 版 |

## 安装

1. 安装 `防止烧屏-v2.0.apk`（HyperOS 3 用 `防止烧屏-v2.0-hyperos3.apk`）
2. 打开 **LSPosed Manager** → 模块 → 启用「防止烧屏」
3. 勾选作用域：
   - **SystemUI**（状态栏/导航栏/古诗，必勾）
   - **抖音** `com.ss.android.ugc.aweme`（抖音隐藏/悬浮球）
   - **红果短剧** `com.phoenix.read`（红果隐藏/悬浮球）
4. 重启手机（或重启 SystemUI）
5. 可选：下拉快捷面板 → 编辑磁贴 → 将「防止烧屏」拖入常用区，实现一键开关

> 悬浮球挂载在抖音/红果**页面自己的窗口**上，无需给抖音授予悬浮窗权限即可显示。

## 配置

App 内即可完成全部配置，保存后下个周期自动生效（或点「重启系统界面」立即应用）。

配置文件位于 `/data/local/tmp/antiburn.conf`，格式：

```ini
# 系统界面
enable_status=1              # 隐藏状态栏图标
enable_navigation=0          # 隐藏导航栏

# 抖音（五个区域独立开关）
enable_douyin_top=1          # 顶部导航
enable_douyin_bottom=1       # 底部导航
enable_douyin_like=1         # 右侧按钮列
enable_douyin_right_top=0    # 右上角
enable_douyin_left_top=0     # 左上角
enable_live=1                # 直播间固定 UI

# 红果短剧（五个区域独立开关）
enable_drama_top=1
enable_drama_bottom=1
enable_drama_like=1
enable_drama_right_top=0
enable_drama_left_top=0

# 悬浮球（点击切换控件显示/消失）
enable_ball=0

# 古诗弹幕
enable_poem=0                # 状态栏横向滚动励志古诗
custom_poems=                # 自定义诗句（每行一首，格式：诗句|作者|出处）

# 其它
enable_icon=0                # 桌面图标（关闭后桌面不显示，从 LSPosed 打开）
enable_log=0                 # 诊断日志
hide_duration=30             # 隐藏持续秒数
show_duration=10             # 显示恢复秒数
whitelist=com.miui.home,com.tencent.mm   # 白名单：前台在这些包时不隐藏
```

修改配置文件保存后，模块会在一个周期内自动读取生效，无需重启。

## 古诗弹幕说明

- 状态栏内单行横向滚动，速度约 75px/s、25fps，滚完自动接下一首，随机顺序（播完一轮重新洗牌）
- 数据源：一言接口 `https://v1.hitokoto.cn/?c=i`（励志分类）；缓存于 `/data/local/tmp/antiburn_poems.txt`，**每 3 天换一批**；无网时用内置 25 首励志库
- 文字颜色随状态栏背景反色：浅背景深字、深背景浅字

## 验证

模块运行日志 tag 为 `Antiburn`（SystemUI 部分）与 `AntiburnDy`（抖音/红果部分）：

```sh
logcat -d -s Antiburn AntiburnDy
```

- SystemUI：应能看到 `status transparent` / `status shown` 交替输出
- 抖音/红果：应能看到各区域 `hidden` / `shown` 日志
- 开启诊断日志开关后，会额外写文件 `/sdcard/Download/antiburn_diag.log`

## 卸载

1. LSPosed Manager 中停用 / 移除模块，并取消作用域勾选
2. 卸载 App
3. 删除配置文件：`rm /data/local/tmp/antiburn.conf /data/local/tmp/antiburn_poems.txt`
4. 重启

模块不修改系统文件、不打补丁，卸载后完全还原。

## 兼容性说明

- 状态栏图标容器通过多级候选 id（`status_bar_contents` / `status_bar_icons` / `notification_icon_area`）定位，适配不同 ROM 布局
- 通知保留通过子类名（`Notification*`）与 id `notification_icon_area` 识别，识别不到时自动回退整窗透明
- 抖音 / 红果各区域用实际容器坐标 + 布局变化事件驱动重扫，翻页即时压住新容器
- 若某机型下拉异常、导航栏不生效或抖音某区域漏藏，请抓 `logcat -s Antiburn AntiburnDy` 或开启诊断日志反馈，作者会跟进适配

## 许可证

[GPL-3.0](LICENSE)

本模块以 GNU GPL v3 开源：允许自由使用、修改与分发，但**任何衍生作品必须同样以 GPL 许可开源**，不得将本模块或其衍生代码闭源商用牟利。

## 支持捐赠

如果觉得好用，请作者喝杯奶茶。微信扫一扫即可，作者：@酷安锦无名。

<p align="center">
  <img src="donate_qr.webp" alt="微信捐赠二维码" width="240" />
</p>

## 致谢

- [LSPosed](https://github.com/LSPosed/LSPosed) / [LSposed (IT)](https://github.com/JingMatrix/LSposed) —— Xposed 框架
- [ZygiskNext](https://github.com/Dr-TSNG/ZygiskNext) —— Zygisk 实现
- [一言](https://hitokoto.cn/) —— 古诗/励志文案接口
- **整个 Xposed / LSPosed 社区** —— 致敬每一位让安卓模块生态生生不息的开发者
