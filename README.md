# -
卸载
LSPosed Manager 中停用 / 移除模块
卸载 App
删除配置文件：rm /data/local/tmp/antiburn.conf
重启
模块不修改系统文件、不打补丁，卸载后完全还原。

兼容性说明
状态栏图标容器通过多级候选 id（status_bar_contents / status_bar_icons / notification_icon_area）定位，适配不同 ROM 布局
若你的机型下拉异常或导航栏不生效，请抓取 logcat -s Antiburn 日志反馈，作者会跟进适配
许可证
GPL-3.0

致谢
LSPosed / LSposed (IT) —— Xposed 框架
ZygiskNext —— Zygisk 实现
