import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2.7 五厂商适配回归测试（纯 JVM，不依赖 Android）。
 *
 * 编译运行：
 *   SRC=/tmp/v22/antiburn-lite/app/src/main/java/io/github/wumisc/antiburn
 *   javac -d /tmp/uitest "$SRC/UiStyle.java" UiStyleTest.java
 *   java -cp /tmp/uitest UiStyleTest "$SRC"
 *
 * 覆盖成员审计指出的三类真问题：
 *  - S3：五厂商此前只有 aosp / 非 aosp 两种行为（setStyle 里 !"aosp".equals 二值）
 *  - 归一：App(AbConf.normUiStyle) / Config(normStyle) / UiStyle 三份实现曾各自维护
 *  - 映射：App 写入的 conf key 与 Config.parse 的 case 是否一一对应
 */
public class UiStyleTest {

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

    public static void main(String[] args) throws Exception {
        String src = args.length > 0 ? args[0]
                : "/tmp/v22/antiburn-lite/app/src/main/java/io/github/wumisc/antiburn";
        Path dir = Path.of(src);

        System.out.println("== T-01 七个合法 id 原样归一 ==");
        String[] ids = {"hyperos", "magicos", "coloros", "originos", "harmonyos", "flyme", "aosp"};
        for (String id : ids) {
            check(id.equals(io.github.wumisc.antiburn.UiStyle.norm(id)), "T-01 " + id,
                    "norm -> " + io.github.wumisc.antiburn.UiStyle.norm(id));
        }

        System.out.println("== T-02 大小写 / 空白归一 ==");
        check("hyperos".equals(io.github.wumisc.antiburn.UiStyle.norm(" HyperOS ")), "T-02a 空白+大写",
                "' HyperOS ' -> " + io.github.wumisc.antiburn.UiStyle.norm(" HyperOS "));
        check("harmonyos".equals(io.github.wumisc.antiburn.UiStyle.norm("\tHARMONYOS\n")), "T-02b 制表+换行",
                "-> " + io.github.wumisc.antiburn.UiStyle.norm("\tHARMONYOS\n"));

        System.out.println("== T-03 脏值 / 空 / null 一律回退 aosp ==");
        for (String bad : new String[]{null, "", "   ", "miui", "oneui", "flymeos", "aosp1", "<script>"}) {
            check("aosp".equals(io.github.wumisc.antiburn.UiStyle.norm(bad)), "T-03 " + String.valueOf(bad),
                    "-> " + io.github.wumisc.antiburn.UiStyle.norm(bad));
        }

        System.out.println("== T-04 aosp 不避让中置胶囊、其余五家避让 ==");
        check(!io.github.wumisc.antiburn.UiStyle.guardCenter("aosp"), "T-04a aosp guardCenter=false", "");
        for (String id : new String[]{"hyperos", "magicos", "coloros", "originos", "harmonyos", "flyme"}) {
            check(io.github.wumisc.antiburn.UiStyle.guardCenter(id), "T-04 " + id + " guardCenter=true", "");
        }

        System.out.println("== T-05 六家参数确实差异化（不再是非 aosp 全等价）==");
        Set<String> signatures = new LinkedHashSet<>();
        for (String id : ids) {
            String sig = io.github.wumisc.antiburn.UiStyle.guardCenter(id) + "/"
                    + io.github.wumisc.antiburn.UiStyle.centerTolerancePct(id) + "/"
                    + io.github.wumisc.antiburn.UiStyle.centerWidthPct(id);
            signatures.add(sig);
            System.out.println("        " + id + " -> " + sig);
        }
        // 旧版只有 {false/0/0, true/8/55} 两种；新版七个 id 应各自不同
        check(signatures.size() == ids.length, "T-05 七个 id 参数互不相同",
                "实际 " + signatures.size() + " 种：" + signatures);

        System.out.println("== T-06 describe 含归一 id 且参数落在合理区间 ==");
        for (String id : ids) {
            String d = io.github.wumisc.antiburn.UiStyle.describe(id);
            int tol = io.github.wumisc.antiburn.UiStyle.centerTolerancePct(id);
            int w = io.github.wumisc.antiburn.UiStyle.centerWidthPct(id);
            boolean ok = d.contains(id) && tol >= 0 && tol <= 30 && w >= 0 && w <= 100;
            check(ok, "T-06 " + id, d);
        }
        check(io.github.wumisc.antiburn.UiStyle.describe("脏值").contains("aosp"),
                "T-06 脏值 describe 归一", io.github.wumisc.antiburn.UiStyle.describe("脏值"));

        System.out.println("== T-07 源码级：IDS 与 UI_STYLE_LABELS 七个 id 一致 ==");
        String kt = Files.readString(dir.resolve("MainActivity.kt"));
        Matcher labelM = Pattern.compile("\"([a-z]+)\" to \"[^\"]+\"").matcher(
                kt.substring(kt.indexOf("UI_STYLE_LABELS"), kt.indexOf("UI_STYLE_LABELS") + 1000));
        Set<String> labelIds = new LinkedHashSet<>();
        while (labelM.find()) labelIds.add(labelM.group(1));
        check(labelIds.equals(new LinkedHashSet<>(io.github.wumisc.antiburn.UiStyle.IDS)),
                "T-07 UI 标签 id 集合 == UiStyle.IDS", "labels=" + labelIds
                        + " IDS=" + io.github.wumisc.antiburn.UiStyle.IDS);

        System.out.println("== T-08 源码级：App 与 Config 的归一都委托 UiStyle（防两份列表漂移）==");
        String cfg = Files.readString(dir.resolve("Config.java"));
        check(cfg.contains("return UiStyle.norm(v);"), "T-08a Config.normStyle 委托 UiStyle", "");
        check(kt.contains("fun normUiStyle(v: String?): String = UiStyle.norm(v)"),
                "T-08b AbConf.normUiStyle 委托 UiStyle", "");
        check(kt.contains("val UI_STYLE_IDS: List<String> = UiStyle.IDS"),
                "T-08c AbConf.UI_STYLE_IDS 复用 UiStyle.IDS", "");

        System.out.println("== T-09 源码级：App 写入的 conf key == Config.parse 的 case ==");
        // 取 AbConf.diffOf / save 里出现的 enable_* key，与 Config.parse 的 case 对照
        Set<String> appKeys = extract(kt, "putB\\(\"(enable_[a-z_]+)\"");
        appKeys.addAll(extract(kt, "lines\\[\"(enable_[a-z_]+)\"\\]"));
        Set<String> parseKeys = extract(cfg, "case \"(enable_[a-z_]+)\":");
        Set<String> missing = new LinkedHashSet<>(appKeys);
        missing.removeAll(parseKeys);
        check(missing.isEmpty(), "T-09 App 写入的 enable_* 都被 Config.parse 识别",
                "app=" + appKeys.size() + " parse=" + parseKeys.size() + " 未被解析=" + missing);

        System.out.println("== T-10 机型自动识别（首次加载用）==");
        check("flyme".equals(io.github.wumisc.antiburn.UiStyle.detect("Meizu", "meizu", "Flyme 11.2", "m2391")),
                "T-10a 魅族 → flyme", io.github.wumisc.antiburn.UiStyle.detect("Meizu", "meizu", "Flyme 11.2", "m2391"));
        check("flyme".equals(io.github.wumisc.antiburn.UiStyle.detect("meizu", "MEIZU", null, null)),
                "T-10b 只有厂商字段也能认出", "");
        check("hyperos".equals(io.github.wumisc.antiburn.UiStyle.detect("Xiaomi", "Redmi", "HyperOS 2.0", "2312")),
                "T-10c 小米/红米 → hyperos", "");
        check("magicos".equals(io.github.wumisc.antiburn.UiStyle.detect("HONOR", "HONOR", "MagicOS 9", "PGT-AN00")),
                "T-10d 荣耀 → magicos", "");
        check("coloros".equals(io.github.wumisc.antiburn.UiStyle.detect("OPPO", "OnePlus", "ColorOS 15", "PKH110")),
                "T-10e 一加/OPPO → coloros", "");
        check("originos".equals(io.github.wumisc.antiburn.UiStyle.detect("vivo", "iQOO", "OriginOS 5", "V2405A")),
                "T-10f vivo/iQOO → originos", "");
        check("harmonyos".equals(io.github.wumisc.antiburn.UiStyle.detect("HUAWEI", "HUAWEI", "HarmonyOS 5", "ALN-AL00")),
                "T-10g 华为 → harmonyos", "");
        check("aosp".equals(io.github.wumisc.antiburn.UiStyle.detect("Google", "google", "AP4A", "husky")),
                "T-10h 原生/未知 → aosp", "");
        check("aosp".equals(io.github.wumisc.antiburn.UiStyle.detect(null, null, null, null)),
                "T-10i 全空 → aosp（JVM 下不 NPE）", "");

        System.out.println("\n== 结果：" + pass + " passed, " + fail + " failed ==");
        if (fail > 0) System.exit(1);
    }

    private static Set<String> extract(String text, String regex) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
