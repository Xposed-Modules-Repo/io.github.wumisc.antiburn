package io.github.wumisc.antiburn;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口
 *  - com.android.systemui：状态栏 / 导航栏周期透明化
 *  - com.ss.android.ugc.aweme（抖音）：视频页各区域周期透明化
 */
public class XposedInit implements IXposedHookLoadPackage {

    public static final String TAG = "Antiburn";

    /**
     * 确定性文件诊断：不依赖 logd / LSPosed 日志采集时机，
     * 只要模块在目标进程执行到，就会把关键事件写进文本文件，用户可直接查看。
     * 可通过设置界面"日志"开关关闭（省电/省存储）。默认开启。
     */
    public static void diag(String s) {
        try {
            if (!Config.enableLog) {
                return; // 日志开关关闭时不写文件（也跳过兜底 Log）
            }
        } catch (Throwable ignored) {
            // Config 未初始化时默认开启
        }
        String line = System.currentTimeMillis() + " [" + Thread.currentThread().getName() + "] " + s + "\n";
        String[] paths = {
                "/sdcard/Download/antiburn_diag.log",
                "/sdcard/antiburn_diag.log",
                "/data/local/tmp/antiburn_diag.log"
        };
        for (String p : paths) {
            try {
                File f = new File(p);
                FileOutputStream fo = new FileOutputStream(f, true);
                fo.write(line.getBytes());
                fo.close();
                return; // 写成功即返回
            } catch (Throwable ignored) {
            }
        }
        // 兜底：Log（若 logd 可用也能进 LSPosed 日志）
        Log.i(TAG, "DIAG " + s);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        String proc = lpparam.processName;
        if ("com.android.systemui".equals(pkg)) {
            diag("INJECTED SystemUI process=" + proc);
            Log.i(TAG, "loaded into SystemUI, starting...");
            try {
                Controller.init(lpparam);
            } catch (Throwable t) {
                Log.e(TAG, "init failed", t);
            }
        } else if ("com.ss.android.ugc.aweme".equals(pkg)) {
            diag("INJECTED Douyin process=" + proc + " class=" + getClass().getName());
            Log.i(TAG, "loaded into Douyin, starting...");
            try {
                DouyinHook.init(lpparam);
            } catch (Throwable t) {
                Log.e(TAG, "douyin init failed", t);
            }
        } else if ("com.phoenix.read".equals(pkg) || "com.phoenix.read.oversea.gp".equals(pkg)) {
            // 红果短剧（抖音旗下，国内版 com.phoenix.read / 谷歌版 com.phoenix.read.oversea.gp）：
            // 复用抖音同一套视频页各区域隐藏逻辑
            diag("INJECTED Drama process=" + proc + " class=" + getClass().getName());
            Log.i(TAG, "loaded into Hongguo Drama, starting...");
            try {
                DouyinHook.init(lpparam);
            } catch (Throwable t) {
                Log.e(TAG, "drama init failed", t);
            }
        }
    }
}
