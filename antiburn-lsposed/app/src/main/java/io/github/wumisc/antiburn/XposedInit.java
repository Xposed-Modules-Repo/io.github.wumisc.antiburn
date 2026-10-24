package io.github.wumisc.antiburn;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口
 * 只处理 SystemUI 进程
 */
public class XposedInit implements IXposedHookLoadPackage {

    public static final String TAG = "Antiburn";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) {
            return;
        }
        Log.i(TAG, "loaded into SystemUI, starting...");
        try {
            Controller.init(lpparam);
        } catch (Throwable t) {
            Log.e(TAG, "init failed", t);
        }
    }
}
