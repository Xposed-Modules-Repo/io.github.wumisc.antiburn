package io.github.wumisc.antiburn.compat;

import java.lang.reflect.Member;

/**
 * 实验分支（LSPosed API 102 / libxposed）兼容壳 —— 回调基类。
 *
 * <p>目的：让 3.2.24 那套用 legacy API 写的 Hook 代码（Controller / DouyinHook /
 * IslandWatch / VideoAppProbe / XposedInit）**一行都不用改**，只在下面这层做桥接。
 * 本分支实际走的是 {@code io.github.libxposed}，legacy 的 XposedBridge 只在这里存在。</p>
 *
 * <p>只实现当前代码真正用到的语义：{@code afterHookedMethod}、{@code beforeHookedMethod}、
 * {@code param.thisObject}、{@code param.args}、{@code param.method}、{@code setResult}。
 * 没用到的一律不写 —— 实验分支不背 legacy 的全套包袱。</p>
 */
public abstract class XC_MethodHook {

    /** 回调优先级：数值越小越先执行（legacy 语义，当前代码没用，保留字段供兼容）。 */
    public static final int PRIORITY_HIGHEST = 10000;

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    /** 让 XposedBridge 能在同包内调用 protected 回调（同包可见）。 */
    static void callBefore(XC_MethodHook cb, MethodHookParam p) throws Throwable {
        cb.beforeHookedMethod(p);
    }

    static void callAfter(XC_MethodHook cb, MethodHookParam p) throws Throwable {
        cb.afterHookedMethod(p);
    }

    /** 一次调用的上下文：等价于 legacy 的 XC_MethodHook.MethodHookParam。 */
    public static final class MethodHookParam {
        /** 被 Hook 的成员（方法 / 构造器）。 */
        public Member method;
        /** 调用者实例；静态方法为 null。 */
        public Object thisObject;
        /** 入参（可改，改了会传给原方法）。 */
        public Object[] args;

        private Object result;
        private boolean hasResult;
        private Throwable throwable;

        public Object getResult() {
            return result;
        }

        public void setResult(Object r) {
            this.result = r;
            this.hasResult = true;
        }

        public boolean hasResult() {
            return hasResult;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        void setThrowable(Throwable t) {
            this.throwable = t;
        }
    }
}
