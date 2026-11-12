import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2.9 UI 视觉审计：把「护眼 / 白字 / 颜色统一」这类主观要求变成可计算的断言。
 *
 * <p>对应 v2.6 用户的三条明确诉求：</p>
 * <ul>
 *   <li>「深色模式下字体必须是白色不能是黑色」→ 深底上的文字色亮度下限</li>
 *   <li>「UI 尽量护眼」→ WCAG 对比度下限（正文 4.5:1，大字/图标 3:1）</li>
 *   <li>「别一会紫色一会蓝色一会粉色」→ 所有彩色收敛到同一色相区间</li>
 * </ul>
 *
 * <p>不加载 Compose 运行时：直接从 UiPalette.kt 解析颜色字面量并用 WCAG 公式计算。
 * 源码改色后断言会跟着检查新值，不是对旧值的快照。</p>
 *
 * <pre>
 *   javac -d /tmp/uitest2 UiContrastTest.java
 *   java -cp /tmp/uitest2 UiContrastTest "$SRC"
 * </pre>
 */
public class UiContrastTest {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(boolean cond, String name, String detail) {
        if (cond) {
            pass++;
            System.out.println("  PASS  " + name + (detail.isEmpty() ? "" : "  — " + detail));
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "  — " + detail);
        }
    }

    private static String read(Path p) throws Exception {
        return Files.readString(p);
    }

    private static String slice(String text, String from, String to) {
        int a = text.indexOf(from);
        if (a < 0) {
            return "";
        }
        int b = text.indexOf(to, a + from.length());
        return b < 0 ? text.substring(a) : text.substring(a, b);
    }

    /** 从片段里抓 `名字 = Color(0xAARRGGBB)` 的 RGB */
    private static Map<String, Integer> colors(String text) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(\\w+) = Color\\(0x[0-9A-Fa-f]{2}([0-9A-Fa-f]{6})\\)").matcher(text);
        while (m.find()) {
            out.put(m.group(1), Integer.parseInt(m.group(2), 16));
        }
        return out;
    }

    /** WCAG 相对亮度 */
    private static double luminance(int rgb) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = ((rgb >> (16 - 8 * i)) & 0xFF) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    /** WCAG 对比度（1:1 ~ 21:1） */
    private static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** @return {H 0..360, S 0..1, V 0..1} */
    private static double[] hsv(int rgb) {
        double r = ((rgb >> 16) & 0xFF) / 255.0;
        double g = ((rgb >> 8) & 0xFF) / 255.0;
        double b = (rgb & 0xFF) / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double d = max - min;
        double h;
        if (d == 0) {
            h = 0;
        } else if (max == r) {
            h = 60 * (((g - b) / d) % 6);
        } else if (max == g) {
            h = 60 * ((b - r) / d + 2);
        } else {
            h = 60 * ((r - g) / d + 4);
        }
        if (h < 0) {
            h += 360;
        }
        double s = max == 0 ? 0 : d / max;
        return new double[]{h, s, max};
    }

    private static String hex(int rgb) {
        return String.format("#%06X", rgb);
    }

    public static void main(String[] args) throws Exception {
        String src = args.length > 0 ? args[0]
                : "/tmp/v22/antiburn-lite/app/src/main/java/io/github/wumisc/antiburn";
        Path dir = Path.of(src);
        String palette = read(dir.resolve("UiPalette.kt"));

        String scheme = slice(palette, "fun scheme(dark: Boolean)", "fun background(");
        String dark = slice(scheme, "if (dark) {", "} else {");
        String light = slice(scheme, "} else {", "\n    }");
        String bgFn = slice(palette, "fun background(", "fun sheen(");

        Map<String, Integer> darkC = colors(dark);
        Map<String, Integer> lightC = colors(light);

        // 渐变色是匿名字面量（listOf(Color(0xFF1A1C21), ...)），且深浅两套在同一个函数里，
        // 必须按分支切开，否则会拿深色正文去比对浅色底，得出无意义的 1.07:1
        String bgDarkFn = slice(bgFn, "if (dark) {", "} else {");
        String bgLightFn = slice(bgFn, "} else {", "\n    }");
        Map<String, Integer> bgC = new LinkedHashMap<>();
        Matcher gm = Pattern.compile("Color\\(0x[0-9A-Fa-f]{2}([0-9A-Fa-f]{6})\\)").matcher(bgDarkFn);
        while (gm.find()) {
            bgC.put("grad" + bgC.size(), Integer.parseInt(gm.group(1), 16));
        }
        Map<String, Integer> bgLightC = new LinkedHashMap<>();
        Matcher gm2 = Pattern.compile("Color\\(0x[0-9A-Fa-f]{2}([0-9A-Fa-f]{6})\\)").matcher(bgLightFn);
        while (gm2.find()) {
            bgLightC.put("grad" + bgLightC.size(), Integer.parseInt(gm2.group(1), 16));
        }

        int inkDark = colors(slice(palette, "private val InkDark", "fun accent")).get("InkDark");
        int inkLight = colors(slice(palette, "private val InkLight", "fun accent")).get("InkLight");
        int accentDark = colors(slice(palette, "private val AccentDark", "private val AccentLight")).get("AccentDark");
        int accentLight = colors(slice(palette, "private val AccentLight", "private val InkDark")).get("AccentLight");
        // 用 Integer 再接 0 兜底：解析不到时直接拆箱会 NPE，测试框架崩掉看起来像「代码坏了」
        Integer warnDarkC = colors(slice(palette, "private val WarnDark", "private val WarnLight")).get("WarnDark");
        Integer warnLightC = colors(slice(palette, "private val WarnLight", "fun accent")).get("WarnLight");
        int warnDark = warnDarkC == null ? 0 : warnDarkC;
        int warnLight = warnLightC == null ? 0 : warnLightC;

        // scheme 里 onBackground / onSurface 是常量引用（= InkDark），不是字面量，补进来
        darkC.putIfAbsent("onBackground", inkDark);
        darkC.putIfAbsent("onSurface", inkDark);
        lightC.putIfAbsent("onBackground", inkLight);
        lightC.putIfAbsent("onSurface", inkLight);

        System.out.println("== 解析结果 ==");
        System.out.println("  dark " + darkC.size() + " 色 · light " + lightC.size() + " 色 · 渐变 " + bgC.size() + " 色");
        System.out.println("  ink: dark=" + hex(inkDark) + " light=" + hex(inkLight)
                + " · accent: dark=" + hex(accentDark) + " light=" + hex(accentLight));

        // ---------- T-01 深色正文对比度 ----------
        System.out.println("== T-01 深色模式正文可读性 ==");
        int darkBg = darkC.get("background");
        check(contrast(inkDark, darkBg) >= 4.5, "T-01a 正文 vs 背景 ≥ 4.5:1",
                String.format("%s on %s = %.2f", hex(inkDark), hex(darkBg), contrast(inkDark, darkBg)));
        double worst = 99;
        int worstColor = 0;
        for (int c : bgC.values()) {
            double r = contrast(inkDark, c);
            if (r < worst) {
                worst = r;
                worstColor = c;
            }
        }
        check(worst >= 4.5, "T-01b 正文 vs 渐变最亮段仍 ≥ 4.5:1",
                String.format("最差 %s = %.2f", hex(worstColor), worst));
        check(contrast(darkC.get("onSurfaceVariant"), darkC.get("surface")) >= 4.5,
                "T-01c 次要文字 vs 卡片面 ≥ 4.5:1",
                String.format("%.2f", contrast(darkC.get("onSurfaceVariant"), darkC.get("surface"))));

        // ---------- T-02 深色模式不出现黑字 ----------
        System.out.println("== T-02 深色模式文字必须是浅色 ==");
        String[] darkTexts = {"onBackground", "onSurface", "onSurfaceVariant"};
        for (String k : darkTexts) {
            int c = darkC.get(k);
            double l = luminance(c);
            check(l >= 0.35, "T-02 " + k + " 亮度 ≥ 0.35（不出现黑字）",
                    hex(c) + " L=" + String.format("%.3f", l));
        }
        check(luminance(inkDark) >= 0.35, "T-02b InkDark 是浅色", hex(inkDark));

        // ---------- T-03 浅色模式 ----------
        System.out.println("== T-03 浅色模式正文可读性 ==");
        int lightBg = lightC.get("background");
        check(contrast(inkLight, lightBg) >= 4.5, "T-03a 正文 vs 背景 ≥ 4.5:1",
                String.format("%s on %s = %.2f", hex(inkLight), hex(lightBg), contrast(inkLight, lightBg)));
        check(contrast(lightC.get("onSurfaceVariant"), lightC.get("surface")) >= 4.5,
                "T-03b 次要文字 vs 卡片面 ≥ 4.5:1",
                String.format("%.2f", contrast(lightC.get("onSurfaceVariant"), lightC.get("surface"))));

        // ---------- T-04 强调色可辨识 ----------
        System.out.println("== T-04 强调色对比度 ==");
        check(contrast(accentDark, darkBg) >= 3.0, "T-04a 深色强调色 vs 背景 ≥ 3:1（链接/图标可辨）",
                String.format("%.2f", contrast(accentDark, darkBg)));
        check(contrast(accentLight, lightBg) >= 4.5, "T-04b 浅色强调色 vs 背景 ≥ 4.5:1",
                String.format("%.2f", contrast(accentLight, lightBg)));

        // 警示橙是「关功能前必须先开无障碍」那条提示专用色。它压在 12% 同色淡底上，
        // 视觉上接近直接压在卡片面，所以按底面对比度判定。
        check(warnDark != 0, "T-04c 解析到警示橙（深色档）", hex(warnDark));
        check(warnLight != 0, "T-04d 解析到警示橙（浅色档）", hex(warnLight));
        check(contrast(warnDark, darkBg) >= 4.5, "T-04e 警示橙 vs 深色底 ≥ 4.5:1",
                String.format("%s on %s = %.2f", hex(warnDark), hex(darkBg), contrast(warnDark, darkBg)));
        check(contrast(warnLight, lightBg) >= 4.5, "T-04f 警示橙 vs 浅色底 ≥ 4.5:1",
                String.format("%s on %s = %.2f", hex(warnLight), hex(lightBg), contrast(warnLight, lightBg)));

        // ---------- T-05 色相统一（不紫不蓝不粉）----------
        System.out.println("== T-05 配色统一在同一色相区间 ==");
        int checked = 0;
        StringBuilder hues = new StringBuilder();
        for (Map.Entry<String, Integer> e : darkC.entrySet()) {
            String name = e.getKey();
            if (!name.contains("primary") && !name.contains("secondary") && !name.contains("tertiary")) {
                continue;
            }
            double[] hsv = hsv(e.getValue());
            if (hsv[1] < 0.10) {
                continue; // 近中性灰不参与色相判定
            }
            checked++;
            hues.append(name).append('=').append(Math.round(hsv[0])).append("° ");
            check(hsv[0] >= 140 && hsv[0] <= 210,
                    "T-05 深色 " + name + " 色相在青绿区间 [140°,210°]",
                    hex(e.getValue()) + " H=" + Math.round(hsv[0]) + "° S=" + String.format("%.2f", hsv[1]));
        }
        check(checked >= 4, "T-05a 参与判定的彩色数量充足（防解析漏项）", "checked=" + checked);
        System.out.println("  色相一览：" + hues);

        // 反向：整套调色板里不允许出现紫 / 品红 / 蓝
        // 只查「可感知的彩色」。背景 / 卡片面 / 描边是近中性深灰（RGB 差 4~8 个灰阶），
        // HSV 饱和度会算到 0.13~0.20、色相落在 220° 附近，这是数字噪声不是蓝色，
        // 若一并判定会天天误报，反而让断言失去意义。
        StringBuilder bad = new StringBuilder();
        for (Map<String, Integer> set : new Map[]{darkC, lightC}) {
            for (Map.Entry<String, Integer> e : set.entrySet()) {
                String name = e.getKey();
                if (!name.contains("primary") && !name.contains("secondary")
                        && !name.contains("tertiary") && !name.contains("accent")) {
                    continue;
                }
                double[] hsv = hsv(e.getValue());
                if (hsv[1] < 0.25) {
                    continue;
                }
                double h = hsv[0];
                if ((h >= 200 && h <= 345)) {
                    bad.append(name).append('=').append(Math.round(h)).append("° ");
                }
            }
        }
        check(bad.length() == 0, "T-05b 强调色中无紫/粉/蓝色相",
                bad.length() == 0 ? "无（均在青绿区间）" : bad.toString());

        // ---------- T-06 底色不刺眼 ----------
        System.out.println("== T-06 底色不用纯黑纯白 ==");
        check(darkBg != 0x000000 && luminance(darkBg) <= 0.02,
                "T-06a 深色底为近黑而非纯黑", hex(darkBg) + " L=" + String.format("%.4f", luminance(darkBg)));
        check(lightBg != 0xFFFFFF && luminance(lightBg) >= 0.85,
                "T-06b 浅色底为近白而非纯白", hex(lightBg) + " L=" + String.format("%.4f", luminance(lightBg)));
        for (int c : bgC.values()) {
            check(c != 0x000000 && c != 0xFFFFFF, "T-06c 深色渐变不含纯黑/纯白", hex(c));
        }
        for (int c : bgLightC.values()) {
            check(c != 0x000000, "T-06d 浅色渐变不含纯黑", hex(c));
        }
        check(bgLightC.containsValue(0xFFFFFF), "T-06e 浅色渐变保留纯白高光（「白丝」质感）",
                bgLightC.values().stream().map(UiContrastTest::hex)
                        .collect(java.util.stream.Collectors.joining(" ")));

        System.out.println();
        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
