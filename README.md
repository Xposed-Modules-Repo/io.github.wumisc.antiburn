# 防止烧屏（Antiburn）

防 OLED 烧屏的 LSPosed 模块：周期性透明化固定 UI（不点亮像素、保留触摸），支持状态栏 / 导航栏 / 抖音 / 红果分区防烧屏、黑白名单、像素偏移、悬浮球与快捷磁贴。

**当前版本** v2.9.1（versionCode 20）｜包名 `io.github.wumisc.antiburn`｜作者 @酷安锦无名

## 功能特性

- **周期防烧屏**：状态栏图标、底部导航栏按周期透明化，透明化而非隐藏，下拉/触摸不受影响；通知图标区保留显示
- **像素偏移**：状态栏 / 导航栏 item 每 8 秒平移 1~2px（AOSP BurnInProtection 同思路），轮廓边缘被磨平但人眼几乎无感，可与周期透明同时使用
- **磁贴式功能页**：分「通用 / 抖音 / 红果」三个子页，每个开关一张磁贴，点一下即切换，选中态高亮
  - 通用：导航栏、状态栏偏移、诊断日志、悬浮球
  - 抖音：顶部导航、底部导航、右侧按钮列、右上角、左上角、直播间隐藏 —— 六张磁贴独立控制
  - 红果：顶部区域、右侧按钮、右上角、左上角 —— 独立磁贴；底部「完整观看 / 选集」入口为保证可点不参与隐藏（磁贴置灰说明）
- **名单模式（白名单 / 黑名单 / 关闭）**：三态可选，两份名单独立保存，来回切换不互相覆盖
  - 白名单：名单内应用前台时不干预（游戏 / 视频 / 导航防误伤）
  - 黑名单：只在名单内应用前台时干预；名单为空时退回全部干预，不会让模块静默失效
  - 关闭：不考虑前台应用，所有应用都干预
  - 用选择器勾选，也可直接填包名（中英文逗号、分号均可作分隔符）
- **界面适配**：按厂商标注 UI 形态（HyperOS / MagicOS / ColorOS / OriginOS / HarmonyOS / AOSP），模块据此避让中置胶囊与导航栏，避免误隐藏灵动岛、原子岛
- **左右滑动 + 液态玻璃导航栏**：三页横向翻页，底部导航指示器随翻页进度逐帧跟随，支持拖拽切换
- **悬浮球**：屏幕右侧小球，点击一键切换隐藏控件显示 / 消失，可拖动
- **保存与生效**：底部保存条实时显示「✓ 所有改动已保存 / ● 有未保存的改动」、root 状态与保存结果；「保存设置」立即落盘、「重新读取」重新载入、「重启系统界面」一键让配置立刻生效

## 环境要求

| 依赖 | 说明 |
|---|---|
| KernelSU / Magisk | 二选一，需 root |
| LSPosed（IT 版） | 推荐支持 HyperOS 4 / Android 17 的版本 |
| 系统 | 已在澎湃 HyperOS 4 / Android 17（Redmi K90 Ultra）实测；本版 minSdk 33 / targetSdk 37 |

> 六厂商的胶囊容差与宽度参数是保守默认值，不同 ROM 版本的实际视图树可能有差异。切换界面适配后若发现状态栏图标被误隐藏或漏隐藏，请在「诊断日志」打开日志，把 `diag.log` 里的 `SYS` 行一并反馈。

## 安装

1. 从 [Releases](https://github.com/Xposed-Modules-Repo/io.github.wumisc.antiburn/releases) 下载最新 `antiburn-*.apk` 并安装（若装有旧包 `com.antiburn.lite`，先在 LSPosed 取消勾选避免冲突）
2. LSPosed Manager → 模块 → 启用「防止烧屏」
3. 勾选作用域：**SystemUI**（必勾）、**抖音** `com.ss.android.ugc.aweme`、**红果短剧** `com.phoenix.read`
4. 重启手机，或装好后在 App 内点「重启系统界面」

## 使用要点

- **改完就保存**：所有开关改动会自动保存（700ms 防抖），也可以按「保存设置」立即写入；保存条会明确告诉你写入成功、只写进了应用内配置，还是失败。
- **配置何时生效**：模块侧每 2 秒内轮询一次配置，正常情况下改完最多 2 秒生效；想立刻看到效果就点「重启系统界面」，SystemUI 会被拉起并重新注入。
- **名单怎么选**：默认白名单。想让模块「只在某几个 App 里生效」就切黑名单；想彻底不管名单就切关闭。切换只改变解释方式，两份名单内容都保留。
- **周期时长**：首页「周期时长」一行点进二级页，分别调隐藏时长与显示时长，改完点「保存时长设置」一次落盘。
- **磁贴页**：功能页顶部切换「通用 / 抖音 / 红果」，切走再切回会停留在原来的子页；红果「底部入口」磁贴是置灰的，那里是「完整观看 / 选集」按钮，隐藏会导致无法追剧，所以固定常驻。

## 配置存储

- **应用内镜像** `files/antiburn.conf`：UI 与 Hook 侧都以它为权威来源，不需要 root，卸载前一直存在；
- **系统配置** `/data/local/tmp/antiburn.conf`：root 可用时按「写临时文件 → 原子替换 → 读回校验」同步一份，写入失败不影响应用内保存；
- **Hook 侧读取**：`content://io.github.wumisc.antiburn.config`（ContentProvider）。`/data/local/tmp` 属于 SELinux `shell_data_file`，SystemUI / 抖音进程直读会失败，这是旧版「保存了却不生效」的根因。
- **三通道仲裁**：ContentProvider、应用内镜像、`/data/local/tmp` 直读三路各带单调递增的 `rev`，取最大者生效并落盘 `appliedRev`，避免进程重启后吃到过期副本。

## 构建

```bash
# 需求：JDK 21 + Gradle 9.6+ + Android SDK Platform 37
cd antiburn-lite
gradle assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
```

工程为 AGP 9.4.1（内置 Kotlin）+ Compose 编译器插件 2.4.21，版本号写在 `antiburn-lite/app/build.gradle`。

## 测试

纯 JVM 测试，不需要真机、不需要 Android 运行时，直接加载 Gradle 编译产物或解析源码：

```bash
cd antiburn-lite
gradle :app:compileDebugKotlin :app:compileDebugJavaWithJavac   # 先生成测试所需的 classes
cd ..
./antiburn-tests/run-all.sh                                      # 五套件全跑
```

| 套件 | 覆盖内容 |
|---|---|
| `list-mode-test` | 名单模式真值表（反射调用真实 `Config.parse` / `Config.shouldIntervene`）、界面入口唯一性、导航跟随接线 |
| `ui-style-test` | 六厂商参数互异性、`ui_style` 归一、App 写入键与 Hook 解析键一致 |
| `feature-audit-test` | 字段 ↔ 落盘双向一致、每个开关都有 Hook 消费与界面入口、磁贴不重复绑定、二级路由不断链 |
| `ui-contrast-test` | WCAG 对比度（正文 ≥ 4.5:1）、深色模式文字亮度下限、强调色相统一在青绿区间 |
| `confstore-test` | 模式归一、名单分隔符、旧配置迁移、多行值编解码往返 |

## 目录结构

```
antiburn-lite/
├── app/src/main/java/io/github/wumisc/antiburn/   # 模块主体
│   ├── XposedInit.java          # 入口，按进程分发
│   ├── Controller.java          # SystemUI 侧：状态栏/导航栏周期透明 + 像素偏移调度
│   ├── PixelShift.java          # 像素偏移引擎（状态栏/导航栏 item 微移）
│   ├── DouyinHook.java          # 抖音 / 红果侧：五区隐藏 + 悬浮球 + 直播间
│   ├── ConfStore.java           # 配置读写：应用内镜像 + root 原子写 + 重启 SystemUI
│   ├── Config.java              # Hook 侧配置解析与三通道仲裁
│   ├── ConfigProvider.java      # 导出配置给被 Hook 进程
│   ├── UiStyle.java             # 六厂商胶囊/导航栏参数唯一真源
│   ├── UiPalette.kt             # 全 App 唯一配色源（雾青 + 中性灰）
│   ├── MainActivity.kt          # Compose 界面（三页滑动 / 磁贴功能页 / 保存条）
│   └── AntiburnTile.java        # 下拉快捷磁贴
├── app/src/main/java/com/kyant/backdrop/          # 液态玻璃导航组件（移植自 Kyant0）
└── classes.jar                                    # 组件依赖
```

## 更新日志

**v2.9.1（2026-11-12）**
- 修复：名单分隔符只认英文逗号，中文输入法下粘贴的包名会被整串当成一个包名，名单静默失效。现 App 侧与 Hook 侧统一认中英文逗号与分号
- 新增五套纯 JVM 测试共 185 项，覆盖名单判定、厂商适配、功能接线、UI 对比度、配置编解码
- 文档：补全 README 至当前版本

**v2.9（2026-11-12）**
- 名单卡片合并为一处：首页保留唯一卡片（含包名输入框），功能页不再重复渲染同一份设置
- 修复：补回「状态栏偏移」磁贴。像素偏移的 Hook 实现一直在跑，但 v2.7 删「状态栏图标」磁贴时把唯一入口一起删了，`enable_shift` 永远停在默认开启，用户既关不掉也不知道它存在
- 修复：左右滑动时底部导航栏指示器不跟随。原实现读整数页号，翻页过半才跳一次；现由翻页进度（页号 + 页内偏移）逐帧驱动，拖拽导航栏期间暂停外部写入避免冲突
- 技术债：`LiquidBottomTabs` 新增可选参数 `tabProgress`，不传时行为与旧版一致

**v2.8（2026-11-12）**
- 新增名单模式三态 `list_mode`：白名单 / 黑名单 / 关闭，默认白名单，升级后行为不变
- 两份名单从「同一集合的两个键名」升级为独立数据，来回切模式互不覆盖
- 黑名单为空时退回全局干预，避免「切过去没勾选 → 模块看起来坏了」
- 判定收敛为单一入口 `Config.shouldIntervene(pkg)`，`Controller` 里并列的多个暂停条件合并
- 修复：切模式时 Hook 沿用上一模式的集合导致语义反转（从白名单切黑名单，原白名单内容直接变成黑名单）
- 修复：保存后回读比对整份配置造成「已被其他入口改写」误报
- 修复：选择页「读另一半 → 合并 → 写回」的竞态

**v2.7（2026-11-12）**
- 新增三页左右滑动切换，液态玻璃导航栏跟随
- 新增「致敬名单」二级页，过往借鉴的开源项目以超链接列出；关于页新增「项目地址」
- 删除功能页「周期时长」磁贴，首页保留唯一入口
- 修复：抖音 / 红果进程缺总开关判断，总开关关闭后隐藏动作照跑
- 修复：直播间三套判定互相打架且始终判不出（底部区域含 EditText/Compose 被排除，导致条件永假），收敛为单一判定源
- 修复：功能页「状态栏图标」磁贴错绑总开关，点一下等于关掉整个模块

**v2.6（2026-11-12）**
- 界面重做：全 App 唯一配色源（雾青 + 中性灰），深浅两套柔雾渐变底，深色模式正文为浅色
- 感谢名单改单行列表 + 二级页；新增捐赠二维码（支付宝 / 微信）
- 周期时长独立磁贴 + 二级页调节，改完一次落盘
- 删除自定义诗句卡片与励志古诗磁贴
- 修复：三通道配置仲裁只在 provider 与 cache 皆空时才用直读通道，导致 SystemUI 长时间吃不到新配置（「开关记不住 / 白名单不生效」的根因）

**v2.5（2026-11-11）**
- 总开关作为总闸：关闭后隐藏、导航、像素偏移全部停止；另含 38 处加固

**v2.0（2026-11-08）**
- 功能页改为磁贴式开关，拆成「通用 / 抖音 / 红果」三个子页；红果五区由「一个总开关管全部」改为逐项独立控制
- 新增「保存设置」「重新读取」「重启系统界面」按钮，保存条显示未保存改动、root 状态与三种保存结果
- 配置链路重写：应用内镜像 + root 原子替换 + ContentProvider，修复「保存失败 / 保存了却不生效」
- 修复：并发写互相覆盖导致配置丢失、连点磁贴丢改动、白名单页取消返回清空主页改动、快捷磁贴读改写竞态、旧版升级不回填配置、诗句缓存写不进导致反复联网、只开「直播间隐藏」时功能失效、日志开关仍把白名单写进 logcat 等
- 版本号 2.0 / versionCode 10；移除旧版 `antiburn-lsposed/` 源码（可在 git 历史中查看）

**历史**：v2.1~v2.4 为功能迭代与稳定性修复，细节见 git 提交历史；v1.6 包名统一为 `io.github.wumisc.antiburn`

## 许可证

[AGPL-3.0](LICENSE) —— 允许自由使用、修改与分发，衍生作品须同样以 AGPL 开源，不得闭源商用。

## 致谢

[LSPosed](https://github.com/LSPosed/LSPosed) / [LSposed (IT)](https://github.com/JingMatrix/LSposed)、[ZygiskNext](https://github.com/Dr-TSNG/ZygiskNext)、[一言](https://hitokoto.cn/) 与整个 Xposed 社区。

**界面致敬**：液态玻璃导航栏（`LiquidBottomTabs`）组件移植自 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0，基于 backdrop-android）；控件质感参考 [Miuix](https://github.com/YuKongA/Miuix)（Apache-2.0）——向作者致敬。

> 如果觉得好用，欢迎支持作者：<img src="donate_qr.webp" alt="微信捐赠二维码" width="120" />
