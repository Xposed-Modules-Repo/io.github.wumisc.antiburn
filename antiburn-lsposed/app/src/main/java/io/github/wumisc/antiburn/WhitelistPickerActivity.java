package io.github.wumisc.antiburn;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 图形化黑名单选择：搜索 + 勾选应用，新用户无需知道包名。
 * 黑名单语义：仅这些应用前台时，系统栏周期性隐藏（防烧屏生效）。
 * Android 11+ 包可见性：枚举已安装应用依赖 QUERY_ALL_PACKAGES 权限
 * （HyperOS 上表现为"获取应用列表"运行时授权）。此处主动申请并在授权后重载，
 * 避免授权了仍看不到应用列表。
 */
public final class WhitelistPickerActivity extends Activity {

    private static final String PREFS = "antiburn_prefs";
    private static final String KEY = "blacklist_apps";
    private static final String QUERY_PERM = "android.permission.QUERY_ALL_PACKAGES";
    private static final int REQ_QUERY_ALL = 1;

    private static class AppItem {
        String pkg;
        CharSequence label;
        Drawable icon;
        boolean system;
    }

    private final List<AppItem> all = new ArrayList<>();
    private final List<AppItem> shown = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private AppAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 小窗：宽度约 90% 屏宽，高度贴合内容（顶部不占用状态栏空间）
        getWindow().setLayout(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.9f),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        Set<String> saved = getPrefs().getStringSet(KEY, null);
        if (saved != null) {
            selected.addAll(saved);
        }
        setContentView(R.layout.activity_whitelist_picker);
        setTitle("选择黑名单应用");

        ListView lv = findViewById(R.id.wl_list);
        // 空列表提示：未获取到应用时引导检查权限
        lv.setEmptyView(findViewById(R.id.wl_empty));
        adapter = new AppAdapter();
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((parent, view, pos, id) -> {
            AppItem a = shown.get(pos);
            if (selected.contains(a.pkg)) selected.remove(a.pkg);
            else selected.add(a.pkg);
            adapter.notifyDataSetChanged();
        });

        EditText search = findViewById(R.id.wl_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                applyFilter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        CheckBox sysCb = findViewById(R.id.wl_show_system);
        sysCb.setOnCheckedChangeListener((btn, on) ->
                applyFilter(search.getText().toString()));

        findViewById(R.id.wl_btn_save).setOnClickListener(v -> {
            getPrefs().edit().putStringSet(KEY, new HashSet<>(selected)).apply();
            Toast.makeText(this, "已保存 " + selected.size() + " 个黑名单应用", Toast.LENGTH_SHORT).show();
            finish();
        });

        requestQueryPermIfNeeded();
        loadApps(); // 首次加载
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 权限授权后返回/重新进入都刷新，避免"授权了仍空白"
        loadApps();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_QUERY_ALL) {
            loadApps();
        }
    }

    /** 引导：未授予 QUERY_ALL_PACKAGES（HyperOS"获取应用列表"）则请求 */
    private void requestQueryPermIfNeeded() {
        try {
            if (checkSelfPermission(QUERY_PERM) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{QUERY_PERM}, REQ_QUERY_ALL);
            }
        } catch (Throwable ignored) {
        }
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void loadApps() {
        all.clear();
        PackageManager pm = getPackageManager();
        boolean granted = checkSelfPermission(QUERY_PERM) == PackageManager.PERMISSION_GRANTED;

        // 主路径：带桌面入口的应用（图标直观、无需包名）
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        for (ResolveInfo ri : list) {
            try {
                ApplicationInfo ai = ri.activityInfo.applicationInfo;
                AppItem a = new AppItem();
                a.pkg = ri.activityInfo.packageName;
                a.label = ri.loadLabel(pm);
                a.icon = ri.loadIcon(pm);
                a.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                all.add(a);
            } catch (Throwable ignored) { }
        }
        // 兜底：queryIntentActivities 受限时改用 getInstalledApplications 过滤出有启动入口的
        if (all.isEmpty()) {
            try {
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    try {
                        Intent li = pm.getLaunchIntentForPackage(ai.packageName);
                        if (li == null) continue;
                        AppItem a = new AppItem();
                        a.pkg = ai.packageName;
                        a.label = ai.loadLabel(pm);
                        a.icon = ai.loadIcon(pm);
                        a.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        all.add(a);
                    } catch (Throwable ignored) { }
                }
            } catch (Throwable t) {
                // ignore
            }
        }
        // 同包多入口去重，按名称排序
        Set<String> seen = new HashSet<>();
        List<AppItem> uniq = new ArrayList<>();
        for (AppItem a : all) if (seen.add(a.pkg)) uniq.add(a);
        all.clear();
        all.addAll(uniq);
        Collections.sort(all, (x, y) ->
                String.valueOf(x.label).compareToIgnoreCase(String.valueOf(y.label)));

        applyFilter("");

        // 空提示文案：区分"没权限"与"确实没应用"
        TextView empty = findViewById(R.id.wl_empty);
        if (all.isEmpty()) {
            empty.setText(granted
                    ? "未获取到应用（可能无带桌面入口的应用）"
                    : "未获取到应用：请授予本 App「获取应用列表」权限后重试");
        }
    }

    private void applyFilter(String q) {
        shown.clear();
        boolean showSys = ((CheckBox) findViewById(R.id.wl_show_system)).isChecked();
        String query = q == null ? "" : q.trim().toLowerCase();
        for (AppItem a : all) {
            if (!showSys && a.system) continue;
            if (query.isEmpty()
                    || String.valueOf(a.label).toLowerCase().contains(query)
                    || a.pkg.toLowerCase().contains(query)) {
                shown.add(a);
            }
        }
        adapter.notifyDataSetChanged();
        TextView empty = findViewById(R.id.wl_empty);
        if (shown.isEmpty() && !all.isEmpty()) {
            empty.setText("无匹配应用");
        }
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View cv, ViewGroup parent) {
            if (cv == null) {
                cv = LayoutInflater.from(WhitelistPickerActivity.this)
                        .inflate(R.layout.item_whitelist_pick, parent, false);
            }
            AppItem a = shown.get(i);
            ((ImageView) cv.findViewById(R.id.wl_item_icon)).setImageDrawable(a.icon);
            ((TextView) cv.findViewById(R.id.wl_item_label)).setText(a.label);
            TextView pkg = cv.findViewById(R.id.wl_item_pkg);
            pkg.setText(a.pkg + (a.system ? "  · 系统" : ""));
            ((CheckBox) cv.findViewById(R.id.wl_item_check)).setChecked(selected.contains(a.pkg));
            return cv;
        }
    }
}
