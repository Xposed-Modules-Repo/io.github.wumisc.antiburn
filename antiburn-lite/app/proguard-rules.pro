# v3.2.3 体积治理：R8 只碰「我们自己没写的东西」。
#
# 模块本体（含 Xposed 入口、Hook 逻辑、反射调用的宿主类名）一律原样保留 ——
# 这些类里有大量对宿主（抖音/快手/SystemUI）类名、方法名的字符串反射，
# 一旦被 R8 改掉符号或裁掉方法，表现就是「装上去什么都不发生」，且几乎无法从日志定位。
-keep class io.github.wumisc.antiburn.** { *; }

# Xposed API 是 compileOnly（不进包），但 Hook 代码引用了它的类型，R8 需知道它们不是缺失。
-dontwarn de.robv.android.xposed.**

# Compose / Miuix 依赖反射读取 @Composable 的元信息与默认参数。
-keep class kotlin.Metadata { *; }

# v3.2.3：只做「删除未用代码」，不做改名。
# 模块内有大量 Class.forName / 字符串类名反射，第三方 UI 库（Miuix 液态玻璃）
# 也靠 @Composable 元信息工作；混淆能把包压到 3 MB，但一旦某条反射路径被改名，
# 表现就是启动即崩、且日志里看不出原因。收益 5 MB，代价是稳定性 —— 不换。
-dontobfuscate
