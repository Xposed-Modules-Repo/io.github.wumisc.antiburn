package android.util;

/** 测试桩：Base64 只实现 ConfStore 用到的 encodeToString。 */
public class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int URL_SAFE = 8;

    public static String encodeToString(byte[] input, int flags) {
        java.util.Base64.Encoder e = java.util.Base64.getEncoder();
        if ((flags & NO_PADDING) != 0) {
            e = e.withoutPadding();
        }
        return e.encodeToString(input);
    }

    public static byte[] decode(String str, int flags) {
        return java.util.Base64.getDecoder().decode(str);
    }
}
