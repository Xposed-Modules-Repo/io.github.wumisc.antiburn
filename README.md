# 防止烧屏（Antiburn）

防 OLED 烧屏的 LSPosed 模块：周期性透明化固定 UI（不点亮像素、保留触摸），支持状态栏 / 导航栏 / 抖音 / 红果分区防烧屏、励志古诗滚动、白名单、悬浮球与快捷磁贴。

**当前版本** v2.5（总开关总闸 + 冷启动拒旧配置 + 全代码审计 38 处加固）｜包名 `io.github.wumisc.antiburn`｜作者 @酷安锦无名

## 功能特性

- **周期防烧屏**：状态栏图标、底部导航栏按周期透明化，透明化而非隐藏，下拉/触摸不受影响；通知图标区保留显示
- **像素偏移**：与 AOSP `BurnInProtectionController` 同思路，把状态栏/导航栏元素缓慢平移 1~2px，轮廓不再固定压在同一批像素上，视觉几乎无感；与「隐藏」互补，可单独关闭
- **界面适配**：首页卡片一行选择 HyperOS / MagicOS / ColorOS / OriginOS / HarmonyOS / 原生 AOSP，非原生方案自动避让中置胶囊（流体云 / 灵动胶囊 / 原子岛 / 实况窗），不会把胶囊一起平移
- **磁贴式功能页**：分「通用 / 抖音 / 红果」三个子页，每个开关一张磁贴，点一下即切换，选中态高亮
  - 抖音页：顶部导航、底部导航、右侧按钮列、右上角、左上角、直播间隐藏 —— 六张磁贴独立控制
  - 红果页：顶部区域、右侧按钮、右上角、左上角 —— 独立磁贴；底部「完整观看 / 选集」入口为保证可点不参与隐藏（磁贴置灰说明）
- **白名单**：前台命中白名单的应用不隐藏（游戏 / 视频 / 导航防误伤）；熄屏自动暂停；可用选择器勾选，也可直接填包名
- **励志古诗滚动**：状态栏横向滚动，联网每 3 天换一批，内置库兜底，可自定义诗句
- **悬浮球**：屏幕右侧小球，点击一键切换隐藏控件显示 / 消失，可拖动
- **保存与生效**：底部状态行实时显示保存结果与 root 状态；「日志」一键导出诊断日志，「重启系统界面」让新配置立刻生效
- **界面**：Compose 液态玻璃导航栏（首页 / 功能 / 关于 3 Tab），深色模式自适应；下拉快捷磁贴一键启停

## 环境要求

| 依赖 | 说明 |
|---|---|
| KernelSU / Magisk | 二选一，需 root |
| LSPosed（IT 版） | 推荐支持 HyperOS 4 / Android 17 的版本 |
| 系统 | 已在澎湃 HyperOS 4 / Android 17（Redmi K90 Ultra）实测；本版 minSdk 33 / targetSdk 37 |

## 安装

1. 下载 [antiburn-v2.5.apk](https://github.com/Xposed-Modules-Repo/io.github.wumisc.antiburn/releases/download/2.5/antiburn-v2.5.apk) 并安装（若装有旧包 `com.antiburn.lite`，先在 LSPosed 取消勾选避免冲突）
2. LSPosed Manager → 模块 → 启用「防止烧屏」
3. 勾选作用域：**SystemUI**（必勾）、**抖音** `com.ss.android.ugc.aweme`、**红果短剧** `com.phoenix.read`
4. 重启手机，或装好后在 App 内点「重启系统界面」

## 使用要点

- **改完就保存**：点一下开关就同步落盘并回读校验，状态行显示「已保存 · 时:分:秒」；写完会把整份配置重新读回来渲染界面，磁盘是什么界面就是什么，不再出现「显示已保存、实际没存住」。保存失败会点名是哪几个键没落盘。
- **配置版本号**：每次写入带 `rev`（严格单调，时间回拨不受影响），模块侧按版本号仲裁并落盘 `appliedRev`，SystemUI 冷启动不会被旧副本覆盖成「关闭」。
- **配置何时生效**：模块侧每 2 秒内轮询一次配置，正常情况下改完最多 2 秒生效；想立刻看到效果就点「重启系统界面」，SystemUI 会被拉起并重新注入。
- **磁贴页**：功能页顶部切换「通用 / 抖音 / 红果」，切走再切回会停留在原来的子页；红果「底部入口」磁贴是置灰的，那里是「完整观看 / 选集」按钮，隐藏会导致无法追剧，所以固定常驻。

## 配置存储

- **应用内镜像** `files/antiburn.conf`：UI 与 Hook 侧都以它为权威来源，不需要 root，卸载前一直存在；
- **系统配置** `/data/local/tmp/antiburn.conf`：root 可用时按「写临时文件 → 原子替换 → 读回校验」同步一份，写入失败不影响应用内保存；
- **Hook 侧读取**：`content://io.github.wumisc.antiburn.config`（ContentProvider）。`/data/local/tmp` 属于 SELinux `shell_data_file`，SystemUI / 抖音进程直读会失败，这是旧版「保存了却不生效」的根因。

## 构建

```bash
# 需求：JDK 21 + Gradle 9.6+ + Android SDK Platform 37
cd antiburn-lite
gradle assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
```

工程为 AGP 9.4.1（内置 Kotlin）+ Compose 编译器插件 2.4.21，版本号写在 `antiburn-lite/app/build.gradle`。

## 目录结构

```
antiburn-lite/
├── app/src/main/java/io/github/wumisc/antiburn/   # 模块主体
│   ├── XposedInit.java          # 入口，按进程分发
│   ├── Controller.java          # SystemUI 侧：状态栏/导航栏周期透明 + 古诗滚动
│   ├── DouyinHook.java          # 抖音 / 红果侧：五区隐藏 + 悬浮球 + 直播间
│   ├── ConfStore.java           # 配置读写：应用内镜像 + root 原子写 + 重启 SystemUI
│   ├── Config.java              # Hook 侧配置解析与预取
│   ├── ConfigProvider.java      # 导出配置给被 Hook 进程
│   ├── MainActivity.kt          # Compose 界面（磁贴功能页 / 保存条）
│   └── AntiburnTile.java        # 下拉快捷磁贴
├── app/src/main/java/com/kyant/backdrop/          # 液态玻璃导航组件（移植自 Kyant0）
└── classes.jar                                    # 组件依赖
```

## 更新日志

**v2.5（2026-11-11）**

依据 19:31 真机日志定位 4 个新问题，并对全代码做一次审计，共修复 **38 处**：

- **总开关变总闸**：关闭即恢复隐藏、像素位移归零（旧版总开关关了偏移还会跑 25 分钟）、移除古诗、停止全部保护动作
- **冷启动拒旧配置**：`appliedRev` 落盘到 SystemUI files，冷启动首帧不再吃 28 分钟前的旧副本（打 `CONF reject stale` 日志）；只有 provider 来源才刷新本地快照
- **配置链路加固**：`rev` 改严格单调（时间回拨不再卡死保存）；App 启动同步建镜像（零 su、不再 ANR）；后台迁移不再覆盖用户刚点的开关；23 个配置字段全部 `volatile`
- **日志与审计**：导出日志按时间戳排序去重、留最近 4000 行（旧版 `>>` 追加导致乱序重复）；WRITE 行补 `ch=键=值` 变更明细；审计轮转先 rename 后删，不再丢备份
- **线程与内存**：Binder 超时线程在途上限 3 + 5 分钟冷却 + `interrupt()`，不再无限泄漏；`pkill` 等非幂等 su 命令禁止超时重试（旧版会把 SystemUI 杀两次）；`PoemScrollView` 移除失败不再泄漏 25fps 循环
- **界面修复**：配置未加载完先存 `pendingSave` 待补写（防覆盖）；「重启系统界面」协程取消后按钮不再永久失效；开关拖拽取消时进度复位到磁盘真值；状态恢复逐项 `as?` 兜底，跨版本升级不崩；白名单页应用图标不再重复加载两遍
- **抖音直播判定修复**：旧版先清空 live 列表再判定，除右上角特征外永远判不出直播间 —— 改为先收集临时表再判定；树遍历去掉无条件强转、节点计数真正生效
- **安全**：Provider `call()` 加 token 校验，第三方 App 读不到配置全文；快捷磁贴读不到配置统一按「关」；FeatureGuard 熔断 60 秒自动重试且配置换版即复位

**v2.4（2026-11-11）**

- **关于页新增「捐赠名单」**：只展示头像与昵称，金额不入库不展示（本版第一位：`*辉`）
- 修复 provider 预取线程被 Binder `call()` 永久阻塞（无超时，HyperOS 冻结 provider 时 `src=conf` 全程）：调用加超时 + 状态可观测
- 快照写入不再被 `myUid()==SYSTEM_UID` 硬判断挡掉（真机 `cache=none` 全程的根因）；文件兜底不再误标 `src=provider`
- 磁贴 `flipStatus` 与 `readEnabled` 语义统一；`cache` 读取改可重试

**v2.3（2026-11-11）**

- 修复「总开关 / 功能页开关记不住」三个真因：SystemUI 冷启动 5~15 秒内 provider 未就绪、退回读 `/data/local/tmp` 的旧 su 副本 → 现在写入带 `rev` 版本号 + SystemUI 本地快照 + 按版本号仲裁，旧副本一律丢弃
- 保存成功后整份回读磁盘并据此渲染界面，配置漂移会明确提示「已被其它入口改写」，不再谎报成功
- 补齐 `enable_shift` / `enable_icon` 的读写通道（此前这两个键无法保存），新增配置键覆盖自检
- 新增保存审计日志 `WRITE ok rev=… status=… by=<调用方>`，定位「谁把开关改回去了」
- 修复快捷磁贴读不到配置时写成关闭（首次点击应为开启）
- 新增「像素偏移」引擎与 `FeatureGuard` 熔断；新增首页「界面适配」卡片（五大厂商 + 原生），非原生时避让中置胶囊
- 诊断日志改到 `/storage/emulated/0/Android/Prevent burn-in/antiburn.log`（真实写探针 + 兜底目录）
- 移除无效的「导航栏微移间隔」滑条；新图标；版本号 2.3 / versionCode 13

**v2.0（2026-11-08）**
- 功能页改为磁贴式开关，拆成「通用 / 抖音 / 红果」三个子页；红果五区由「一个总开关管全部」改为逐项独立控制
- 新增「保存设置」「重新读取」「重启系统界面」按钮，保存条显示未保存改动、root 状态与三种保存结果
- 配置链路重写：应用内镜像 + root 原子替换 + ContentProvider，修复「保存失败 / 保存了却不生效」
- 修复：并发写互相覆盖导致配置丢失、连点磁贴丢改动、白名单页取消返回清空主页改动、快捷磁贴读改写竞态、旧版升级不回填配置、诗句缓存写不进导致反复联网、只开「直播间隐藏」时功能失效、日志开关仍把白名单写进 logcat 等
- 版本号 2.0 / versionCode 10；移除旧版 `antiburn-lsposed/` 源码（可在 git 历史中查看）

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
