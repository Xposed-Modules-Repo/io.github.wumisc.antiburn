package io.github.wumisc.antiburn;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONObject;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * v3.2.10：公益找孩子数据层。
 *
 * <p>接口 = 自建数据仓库的规范化 JSON（宝贝回家寻子网「家寻宝贝」抓取，隔天更新）：
 * 主通道 raw.githubusercontent.com，备通道 jsDelivr；都失败回落本机缓存，再失败回落
 * APK 内置快照 assets/kids.json —— 任何情况下板块都有真实数据可显示。</p>
 *
 * <p>轮换（v3.2.11）：首页放两张卡，每 24 小时整对推进 —— 每天 0 点后两张卡各换一位，
 * 同一天里不会重复。items 已按注册日期倒序（最新发布优先），所以锚点当天显示的
 * 就是最新两条。数据更新（updated 变化）时锚点重置，新发布的第一时间顶到最前。</p>
 *
 * <p>刷新（v3.2.11）：联网拉取最多每 24 小时一次（fetch_at 记账）。同一天内直接读本机缓存，
 * 不再重复联网 —— 板块是给人看的公益信息，不是行情，一天一刷足够，也省流量与功耗。</p>
 *
 * <p>只读不写：不上传任何本机信息；照片按需下载并限尺寸解码，避免大图占内存。</p>
 */
public final class WelfareStore {

    /** 主通道：GitHub raw。 */
    public static final String URL_RAW =
            "https://raw.githubusercontent.com/wumisc/antiburn-missing-kids/main/kids.json";
    /** 备通道：jsDelivr CDN（raw 被墙 / 超时时的兜底）。 */
    public static final String URL_CDN =
            "https://cdn.jsdelivr.net/gh/wumisc/antiburn-missing-kids@main/kids.json";

    /** 两类启事的分类名，与上游 JSON 的 category 字段一致。 */
    public static final String CAT_FAMILY = "家寻宝贝";
    public static final String CAT_FIND_HOME = "宝贝寻家";

    private static final String CACHE = "welfare.json";
    private static final String PREF = "welfare_anchor";
    private static final String KEY_DAY = "anchor_day";
    /** 首页家寻宝贝卡的数量（slot 0 / 1 / 3），每天整体前进这么多位。 */
    private static final int FAMILY_CARDS = 3;
    private static final String KEY_SRC = "anchor_src";
    /** v3.4.2：宝贝寻家独立锚点，免得两套数据更新时互相重置轮换。 */
    private static final String KEY_DAY_FIND = "anchor_day_find";
    private static final String KEY_SRC_FIND = "anchor_src_find";
    private static final long DAY_MS = TimeUnit.DAYS.toMillis(1);
    /** v3.2.11：每 24 小时换一对（两张卡各换一位）。 */
    public static final long ROTATE_DAYS = 1;
    /** v3.2.11：联网刷新周期 = 24 小时。 */
    public static final long FETCH_DAYS = 1;
    private static final String KEY_FETCH_AT = "fetch_at";
    /** v3.2.12：照片缓存目录（filesDir 下，系统不会随手清掉）。 */
    private static final String PHOTO_DIR = "kids_photos";

    public static final class Kid {
        public int id;
        public String name = "";
        public String gender = "";
        public String birth = "";
        public String height = "";
        public String type = "";
        public String lostTime = "";
        public String lostPlace = "";
        public String feature = "";
        public String regDate = "";
        /** "家寻宝贝" / "宝贝寻家"：同一个板块里两类启事的唯一区分。 */
        public String category = "";
        public String url = "";
        public String photo = "";
    }

    public static final class Doc {
        public String updated = "";
        public String source = "";
        public String sourceUrl = "";
        public String notice = "";
        public String tel = "";
        public String mobile = "";
        public String email = "";
        public String qqGroup = "";
        public String serviceTime = "";
        public java.util.List<Kid> items = new java.util.ArrayList<>();
        /** v3.4.2：宝贝寻家（孩子在找家）。与 items 分开轮换，互不挤占。 */
        public java.util.List<Kid> itemsFindHome = new java.util.ArrayList<>();
    }

    private WelfareStore() {
    }

    private static volatile Doc memDoc;
    private static volatile long memAt;
    /** 后台刷新是否已在跑，避免两张首页卡并发拉同一份 JSON。 */
    private static volatile boolean refreshing;

    /**
     * 拉取 + 回落链。调用方须在 IO 线程。
     *
     * <p>v3.2.34：旧实现先联网（raw 6 秒 + jsDelivr 6 秒）再读缓存 / 内置快照，
     * 首页两张卡各自调用一次，网络被墙时两张卡会同时显示「正在加载寻亲信息…」十几秒；
     * 与类注释承诺的「任何情况下板块都有真实数据可显示」不符。现在改为
     * 缓存 / 内置快照先返回，过期数据由后台单线程刷新，下一轮自然换新。
     * 首次安装且无缓存时也不再阻塞首页。</p>
     */
    public static Doc load(Context ctx) {
        Doc mem = memDoc;
        if (mem != null && System.currentTimeMillis() - memAt < 600000) {
            return mem;   // 10 分钟内首页卡与二级页共用一份，不重复联网
        }
        Doc cached = readCache(ctx);
        if (cached != null) {
            memDoc = cached;
            memAt = System.currentTimeMillis();
            if (needsFetch(ctx)) {
                refreshAsync(ctx);
            }
            return cached;
        }
        Doc asset = readAsset(ctx);
        if (asset != null) {
            memDoc = asset;
            memAt = System.currentTimeMillis();
            if (needsFetch(ctx)) {
                refreshAsync(ctx);
            }
            return asset;
        }
        // 连内置快照都没有（异常构建）才退回同步联网，保证仍有最后一次机会。
        return loadInner(ctx);
    }

    private static boolean needsFetch(Context ctx) {
        long fetchedAt = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getLong(KEY_FETCH_AT, -1);
        return fetchedAt <= 0 || System.currentTimeMillis() - fetchedAt >= FETCH_DAYS * DAY_MS;
    }

    /**
     * 后台刷新：不占首页首帧，也不阻塞两张卡。成功才替换内存与缓存；
     * 失败不影响当前展示的缓存 / 快照。
     */
    private static void refreshAsync(Context ctx) {
        if (refreshing) {
            return;
        }
        refreshing = true;
        final Context app = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                String blob = httpGet(URL_RAW, 6000);
                if (blob == null) {
                    blob = httpGet(URL_CDN, 6000);
                }
                if (blob == null) {
                    return;
                }
                Doc d = parse(blob);
                if (d == null || d.items.isEmpty()) {
                    return;
                }
                try {
                    File f = new File(app.getFilesDir(), CACHE);
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                    fo.write(blob.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    fo.close();
                    app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                            .putLong(KEY_FETCH_AT, System.currentTimeMillis()).apply();
                } catch (Throwable ignored) {
                }
                memDoc = d;
                memAt = System.currentTimeMillis();
            } catch (Throwable ignored) {
            } finally {
                refreshing = false;
            }
        }, "antiburn-welfare-refresh");
        t.setDaemon(true);
        t.start();
    }

    /** 读 APK 内置快照；不联网、毫秒级。 */
    private static Doc readAsset(Context ctx) {
        try {
            java.io.InputStream in = ctx.getAssets().open("kids.json");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            in.close();
            return parse(bo.toString("UTF-8"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 兼容旧同步路径：仅在缓存与内置快照都读不到时作为最后兜底。 */
    private static Doc loadInner(Context ctx) {
        Doc cached = readCache(ctx);
        if (cached != null) {
            return cached;
        }
        // v3.2.11：24 小时内只认本机缓存，不再联网（缓存里的 updated 就是当天版本）
        android.content.SharedPreferences sp =
                ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long fetchedAt = sp.getLong(KEY_FETCH_AT, -1);
        if (fetchedAt > 0 && System.currentTimeMillis() - fetchedAt < FETCH_DAYS * DAY_MS) {
            return null;
        }
        String blob = httpGet(URL_RAW, 6000);
        if (blob == null) {
            blob = httpGet(URL_CDN, 6000);
        }
        if (blob != null) {
            Doc d = parse(blob);
            if (d != null && !d.items.isEmpty()) {
                try {
                    File f = new File(ctx.getFilesDir(), CACHE);
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                    fo.write(blob.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    fo.close();
                    sp.edit().putLong(KEY_FETCH_AT, System.currentTimeMillis()).apply();
                } catch (Throwable ignored) {
                }
                return d;
            }
        }
        return null;
    }

    /** 解析规范化 JSON；结构不对返回 null，绝不抛。 */
    public static Doc parse(String blob) {
        try {
            JSONObject o = new JSONObject(blob);
            Doc d = new Doc();
            d.updated = o.optString("updated");
            d.source = o.optString("source");
            d.sourceUrl = o.optString("source_url");
            d.notice = o.optString("notice");
            JSONObject c = o.optJSONObject("contact");
            if (c != null) {
                d.tel = c.optString("tel");
                d.mobile = c.optString("mobile");
                d.email = c.optString("email");
                d.qqGroup = c.optString("qq_group");
                d.serviceTime = c.optString("service_time");
            }
            org.json.JSONArray arr = o.optJSONArray("items");
            if (arr == null) {
                return null;
            }
            readKids(arr, d.items);
            // v3.4.2：宝贝寻家单独一段。老数据里没有这个字段，读到空列表即视为「暂无寻家启事」，
            // 卡片整块不渲染，不影响原有两张家寻卡。
            readKids(o.optJSONArray("items_findhome"), d.itemsFindHome);
            if (d.items.isEmpty() && d.itemsFindHome.isEmpty()) {
                return null;
            }
            return d;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 把一段 JSON 数组读成 Kid 列表，并按「最新发布优先」排序（不信任上游顺序）。 */
    private static void readKids(org.json.JSONArray arr, java.util.List<Kid> into) {
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject k = arr.optJSONObject(i);
            if (k == null) {
                continue;
            }
            Kid kid = new Kid();
            kid.id = k.optInt("id");
            kid.name = k.optString("name");
            kid.gender = k.optString("gender");
            kid.birth = k.optString("birth");
            kid.height = k.optString("height");
            kid.type = k.optString("type");
            kid.lostTime = k.optString("lost_time");
            kid.lostPlace = k.optString("lost_place");
            kid.feature = k.optString("feature");
            kid.regDate = k.optString("reg_date");
            kid.url = k.optString("url");
            kid.photo = k.optString("photo");
            kid.category = k.optString("category");
            if (kid.name.isEmpty() && kid.lostPlace.isEmpty()) {
                continue;
            }
            into.add(kid);
        }
        java.util.Collections.sort(into, (a, b) -> {
            int c2 = b.regDate.compareTo(a.regDate);
            return c2 != 0 ? c2 : Integer.compare(b.id, a.id);
        });
    }

    /** v3.4.2：slot 0/1/3 走家寻宝贝（三张卡），slot 2 走宝贝寻家（一张卡）。 */
    public static java.util.List<Kid> itemsFor(Doc d, int slot) {
        if (d == null) {
            return java.util.Collections.emptyList();
        }
        return slot == SLOT_FIND_HOME ? d.itemsFindHome : d.items;
    }

    /** 寻家固定占第 3 张卡；其余槽位都读家寻宝贝。 */
    public static final int SLOT_FIND_HOME = 2;

    /** 读本机缓存；不存在 / 解析空返回 null。 */
    private static Doc readCache(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), CACHE);
            if (!f.exists() || f.length() <= 0) {
                return null;
            }
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream fi = new java.io.FileInputStream(f);
            int n = fi.read(b);
            fi.close();
            Doc d = parse(new String(b, 0, Math.max(n, 0), java.nio.charset.StandardCharsets.UTF_8));
            return (d != null && (!d.items.isEmpty() || !d.itemsFindHome.isEmpty())) ? d : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * v3.2.11：首页两张卡各自该显示第几条。
     *
     * <p>锚点 = 首次看到这份数据的日期；每 24 小时整对前进 2 位（当天两张卡各换一位，
     * 同一天里不重复）。数据更新（updated 变化）则锚点重置，最新两条顶到最前。
     * slot=0 是上卡、slot=1 是下卡；条目只有 1 条时两卡指向同一条，不越界。</p>
     */
    public static int currentIndex(Context ctx, Doc d, long nowMs, int slot) {
        java.util.List<Kid> list = itemsFor(d, slot);
        if (list.isEmpty()) {
            return 0;
        }
        // v3.4.2：slot 2 是宝贝寻家。它只有一张卡，每天前进 1 位，锚点单独记。
        // v3.4.4：家寻加到三张，仍按「每天前进 3 位」保证同一天三张不重人。
        boolean find = slot == SLOT_FIND_HOME;
        long today = nowMs / DAY_MS;
        android.content.SharedPreferences sp =
                ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String src = sp.getString(find ? KEY_SRC_FIND : KEY_SRC, "");
        long anchor = sp.getLong(find ? KEY_DAY_FIND : KEY_DAY, -1);
        if (anchor < 0 || !src.equals(d.updated)) {
            anchor = today;
            if (find) {
                sp.edit().putLong(KEY_DAY_FIND, anchor).putString(KEY_SRC_FIND, d.updated).apply();
            } else {
                sp.edit().putLong(KEY_DAY, anchor).putString(KEY_SRC, d.updated).apply();
            }
        }
        int size = list.size();
        if (size == 1) {
            return 0;
        }
        int perDay = find ? 1 : FAMILY_CARDS;
        // 家寻三张卡的组内序号：0、1、3 号槽位依次排 0、1、2
        int offset = find ? 0 : (slot == 3 ? 2 : slot);
        long steps = Math.max(0, (today - anchor) / ROTATE_DAYS);
        int base = (int) ((steps * perDay) % size);
        return (base + Math.max(0, offset)) % size;
    }

    /** 兼容旧调用：等价于 slot=0。 */
    public static int currentIndex(Context ctx, Doc d, long nowMs) {
        return currentIndex(ctx, d, nowMs, 0);
    }

    /** 下载照片并限尺寸解码（最长边 ≤ 640），失败返回 null。调用方须在 IO 线程。 */
    public static Bitmap photo(String url, int maxSide) {
        byte[] b = photoBytes(url);
        return b == null ? null : decode(b, maxSide);
    }

    /**
     * v3.2.12：带本机缓存的照片加载 —— 同一张脸不会每次进页面都重新下。
     *
     * <p>原始字节存 {@code filesDir/kids_photos/p<id>.img}，卡片（320）与二级页（640）
     * 共用同一份原始数据，只按需要的尺寸解码。换人时由 {@link #syncToday} 删掉旧的。</p>
     */
    public static Bitmap photoCached(Context ctx, String url, int id, int maxSide) {
        File f = id > 0 ? photoFile(ctx, id) : null;
        if (f != null && f.exists() && f.length() > 0) {
            Bitmap b = decode(f, maxSide);
            if (b != null) {
                return b;
            }
            f.delete();   // 文件坏了就丢掉重下
        }
        byte[] bytes = photoBytes(url);
        if (bytes == null) {
            return null;
        }
        if (f != null) {
            saveBytes(f, bytes);
        }
        return decode(bytes, maxSide);
    }

    /**
     * v3.2.12：当天两张卡的数据与照片先落盘；轮到第二天换人时，删掉昨天那两位的照片
     * 再拉新的 —— 省流量，也不让缓存无限长大（最多留两张）。
     *
     * <p>幂等：照片已经在盘上就不再联网。调用方须在 IO 线程。</p>
     */
    public static void syncToday(Context ctx, Doc d, long nowMs) {
        if (d == null || (d.items.isEmpty() && d.itemsFindHome.isEmpty())) {
            return;
        }
        java.util.Set<Integer> keep = new java.util.HashSet<>();
        java.util.List<Kid> today = new java.util.ArrayList<>(3);
        int slots = d.itemsFindHome.isEmpty() ? 2 : 3;
        for (int slot = 0; slot < slots; slot++) {
            java.util.List<Kid> list = itemsFor(d, slot);
            if (list.isEmpty()) {
                continue;
            }
            int idx = currentIndex(ctx, d, nowMs, slot);
            Kid k = list.get(Math.max(0, Math.min(idx, list.size() - 1)));
            if (k == null) {
                continue;
            }
            keep.add(k.id);
            today.add(k);
        }
        prunePhotos(ctx, keep);
        for (Kid k : today) {
            if (!k.photo.isEmpty()) {
                photoCached(ctx, k.photo, k.id, 640);
            }
        }
    }

    /** 删掉当前不是「今天这两张」的照片文件。 */
    private static void prunePhotos(Context ctx, java.util.Set<Integer> keep) {
        try {
            File[] files = new File(ctx.getFilesDir(), PHOTO_DIR).listFiles();
            if (files == null) {
                return;
            }
            for (File f : files) {
                int id = idOfPhoto(f.getName());
                if (id <= 0 || !keep.contains(id)) {
                    f.delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static int idOfPhoto(String name) {
        if (name == null || !name.startsWith("p") || !name.endsWith(".img")) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(1, name.length() - 4));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static File photoFile(Context ctx, int id) {
        File dir = new File(ctx.getFilesDir(), PHOTO_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, "p" + id + ".img");
    }

    private static void saveBytes(File f, byte[] b) {
        try {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(b);
            fo.close();
        } catch (Throwable ignored) {
        }
    }

    private static byte[] photoBytes(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        HttpURLConnection cn = null;
        try {
            cn = (HttpURLConnection) new URL(url).openConnection();
            cn.setConnectTimeout(6000);
            cn.setReadTimeout(8000);
            cn.setRequestProperty("User-Agent", "antiburn-welfare/1.0");
            if (cn.getResponseCode() != 200) {
                return null;
            }
            java.io.InputStream in = cn.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 4 * 1024 * 1024) {
                    return null;   // 异常大图直接放弃，不占内存
                }
            }
            in.close();
            return bo.toByteArray();
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (cn != null) {
                cn.disconnect();
            }
        }
    }

    private static Bitmap decode(File f, int maxSide) {
        try {
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream fi = new java.io.FileInputStream(f);
            int n = fi.read(b);
            fi.close();
            if (n <= 0) {
                return null;
            }
            return decode(b, maxSide);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap decode(byte[] b, int maxSide) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, probe);
        int side = Math.max(probe.outWidth, probe.outHeight);
        int sample = 1;
        while (side / sample > maxSide) {
            sample *= 2;
        }
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        try {
            return BitmapFactory.decodeByteArray(b, 0, b.length, opt);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String httpGet(String url, int timeoutMs) {
        HttpURLConnection cn = null;
        try {
            cn = (HttpURLConnection) new URL(url).openConnection();
            cn.setConnectTimeout(timeoutMs);
            cn.setReadTimeout(timeoutMs);
            cn.setRequestProperty("User-Agent", "antiburn-welfare/1.0");
            if (cn.getResponseCode() != 200) {
                return null;
            }
            java.io.InputStream in = cn.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 2 * 1024 * 1024) {
                    return null;
                }
            }
            in.close();
            return bo.toString("UTF-8");
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (cn != null) {
                cn.disconnect();
            }
        }
    }
}
