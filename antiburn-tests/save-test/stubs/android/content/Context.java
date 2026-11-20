package android.content;

import java.io.File;

/**
 * 测试桩：只实现 ConfStore 实际用到的两个方法。
 * 用系统属性 conf.test.dir 指定「应用私有目录」。
 */
public class Context {
    public Context getApplicationContext() {
        return this;
    }

    public File getFilesDir() {
        File d = new File(System.getProperty("conf.test.dir", "/tmp/lt/files"));
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }
}
