import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 适配包导出（计划 §8.2「导出适配包」）。
 * 只导结构：类名 / 资源 id / 层级，不含文本、不含包名、不含用户数据 —— 即脱敏。
 */
public class DumpExport {

    static void node(V v, StringBuilder sb, int indent) {
        String pad = " ".repeat(indent * 2);
        sb.append(pad).append("{\"class\":\"").append(esc(v.cls)).append("\"");
        if (v.id != null) sb.append(",\"id\":\"").append(esc(v.id)).append("\"");
        if (!v.children.isEmpty()) {
            sb.append(",\"children\":[\n");
            for (int i = 0; i < v.children.size(); i++) {
                node(v.children.get(i), sb, indent + 2);
                sb.append(i == v.children.size() - 1 ? "\n" : ",\n");
            }
            sb.append(pad).append("]");
        }
        sb.append("}");
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String dump(TreeFixtures.Sample s) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schemaVersion\": 1,\n");
        sb.append("  \"redacted\": true,\n");
        sb.append("  \"device\": {");
        sb.append("\"brand\":\"").append(TreeFixtures.brand).append("\",");
        sb.append("\"family\":\"").append(s.family).append("\",");
        sb.append("\"romVersion\":\"").append(TreeFixtures.props.values().stream().findFirst().orElse("")).append("\",");
        sb.append("\"navMode\":\"").append(TreeFixtures.navMode).append("\",");
        sb.append("\"systemUiVersionName\":\"").append(TreeFixtures.systemUiVersion).append("\"},\n");
        sb.append("  \"statusBar\": ");
        node(s.root, sb, 1);
        sb.append(",\n  \"navBar\": ");
        node(s.navRoot, sb, 1);
        sb.append("\n}\n");
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        File dir = new File("/tmp/adapt/fixtures");
        dir.mkdirs();
        // 注意：TreeFixtures 的设备属性是静态字段，必须"建树后立刻导出"，
        // 否则五份 dump 会共用最后一个样本的 brand/props。
        write(dir, "hyperos-4", TreeFixtures.hyperos4());
        write(dir, "coloros-14-17", TreeFixtures.coloros15());
        write(dir, "originos-4-7", TreeFixtures.originos5());
        write(dir, "magicos-8-11", TreeFixtures.magicOs11());
        write(dir, "generic-aosp", TreeFixtures.aosp16());
    }

    static void write(File dir, String name, TreeFixtures.Sample s) throws Exception {
        String json = dump(s);
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(new File(dir, name + ".json")), StandardCharsets.UTF_8)) {
            w.write(json);
        }
        System.out.println("wrote " + name + ".json " + json.length() + " bytes  brand="
                + TreeFixtures.brand + " props=" + TreeFixtures.props);
    }
}
