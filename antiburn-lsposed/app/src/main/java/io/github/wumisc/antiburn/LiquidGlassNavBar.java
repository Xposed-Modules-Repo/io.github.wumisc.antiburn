/*
 * LiquidGlassNavBar
 *
 * 底部悬浮液态玻璃导航栏。
 * 渲染器 vendored 自 QmDeve/AndroidLiquidGlassView（MIT，见
 * assets/qmdeve_liquidglass_MIT_LICENSE.txt），交互（玻璃滴滑动/拉伸/
 * 回弹/手指拖拽吸附）参考 Kyant0/AndroidLiquidGlass 的 LiquidBottomTabs
 * （Apache-2.0）。
 *
 * 全 GPU、零位图：玻璃本体由 com.qmdeve.liquidglass.LiquidGlass 使用
 * RenderNode + RenderEffect(AGSL RuntimeShader) 实时折射背后内容，
 * API 33+ 生效；以下自动降级为半透明深色圆角，不崩溃。
 */
package io.github.wumisc.antiburn;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.qmdeve.liquidglass.Config;
import com.qmdeve.liquidglass.LiquidGlass;

public class LiquidGlassNavBar extends FrameLayout {

    /** 一个 tab 的图标与文字。 */
    public static class TabItem {
        public final int iconRes;
        public final String label;
        public TabItem(int iconRes, String label) {
            this.iconRes = iconRes;
            this.label = label;
        }
    }

    public interface OnTabSelectedListener {
        void onTabSelected(int index);
    }

    private static final int COLOR_SELECTED = 0xFFFF8A00; // 橙
    private static final int COLOR_NORMAL = 0xB21A1A1A;   // 深灰（白色玻璃上可读）

    private final float density;
    private final boolean mUseAgsl;

    private LiquidGlass glass;
    private Config qm;
    private View droplet;
    private LinearLayout tabRow;
    private final ImageView[] icons;
    private final TextView[] labels;
    private final TabItem[] tabs;
    private final OnTabSelectedListener listener;

    private final int barHeightDp = 64;
    private final int dropletHeightDp = 48;
    private final int iconDp = 24;

    private int selected = 0;
    /** 玻璃滴连续位置（0..tabsCount-1），可停在小数（拖拽中）。 */
    private float pos = 0f;
    private float tabWidth = 1f;
    private ValueAnimator glideAnimator;

    // 拖拽状态
    private float lastX;
    private float downX;
    private float lastT;
    private float stretch; // 当前横向拉伸量
    private boolean dragStarted;
    private final int touchSlop;

    public LiquidGlassNavBar(Context context, ViewGroup backdropTarget,
                             TabItem[] tabs, OnTabSelectedListener listener) {
        super(context);
        this.density = context.getResources().getDisplayMetrics().density;
        this.tabs = tabs;
        this.listener = listener;
        this.icons = new ImageView[tabs.length];
        this.labels = new TextView[tabs.length];
        this.mUseAgsl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
        this.touchSlop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();

        setWillNotDraw(false);
        setClipToPadding(false);

        buildGlass(context, backdropTarget);
        buildDroplet();
        buildTabs();
        if (!mUseAgsl) forceFallback();
    }

    private void buildGlass(Context context, ViewGroup target) {
        qm = new Config();
        applyParams(qm, density);
        glass = new LiquidGlass(context, qm);
        addView(glass, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        try {
            glass.init(target);
        } catch (Throwable t) {
            // 渲染器初始化失败（RuntimeShader 异常等），强制半透明兜底，保证不透明/不崩
            forceFallback();
        }
    }

    /** HeyBox 验证过的透镜参数（applyQmParams）。 */
    static void applyParams(Config c, float d) {
        c.REFRACTION_HEIGHT = 20f * d;
        c.REFRACTION_OFFSET = -40f * d;
        c.DISPERSION = 0.35f;
        c.BLUR_RADIUS = 1.2f;
        c.DEPTH_EFFECT = 0.3f;
        c.CHROMA_MULTIPLIER = 1.0f;
        c.CONTRAST = 0f;
        c.WHITE_POINT = 0.18f;   // 提亮，让玻璃偏白
        // 白色浅玻璃：白色 tint，让背后内容透出明亮层次
        c.TINT_COLOR_RED = 1f;
        c.TINT_COLOR_GREEN = 1f;
        c.TINT_COLOR_BLUE = 1f;
        c.TINT_ALPHA = 0.18f;
    }

    private void buildDroplet() {
        droplet = new View(getContext());
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dropletHeightDp * density / 2f);
        // 白色液态玻璃：顶部高光、底部略透；淡深灰描边 + 阴影保证浅色底上可见
        d.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        d.setColors(new int[]{0xFFFFFFFF, 0xE6FFFFFF});
        d.setStroke(Math.max(1, (int) (0.8f * density)), 0x1F000000);
        droplet.setBackground(d);
        droplet.setElevation(dp(4) > 0 ? dp(4) : 1);
        // 玻璃滴不吃触摸，触摸由本容器统一处理
        droplet.setClickable(false);
        droplet.setFocusable(false);
        LayoutParams lp = new LayoutParams(
                LayoutParams.MATCH_PARENT, dp(dropletHeightDp), Gravity.CENTER_VERTICAL);
        addView(droplet, lp);
    }

    private void buildTabs() {
        tabRow = new LinearLayout(getContext());
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabRow.setWeightSum(tabs.length);

        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(getContext());
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setClickable(true);
            item.setFocusable(true);
            item.setOnClickListener(v -> setSelected(idx, true));

            ImageView ic = new ImageView(getContext());
            int icSz = dp(iconDp);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(icSz, icSz);
            ilp.gravity = Gravity.CENTER_HORIZONTAL;
            ic.setImageResource(tabs[i].iconRes);
            ic.setClickable(false);
            ic.setFocusable(false);
            item.addView(ic, ilp);

            TextView tv = new TextView(getContext());
            tv.setText(tabs[i].label);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            tv.setIncludeFontPadding(false);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setClickable(false);
            tv.setFocusable(false);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(2);
            tlp.gravity = Gravity.CENTER_HORIZONTAL;
            item.addView(tv, tlp);

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, LayoutParams.MATCH_PARENT, 1f);
            tabRow.addView(item, clp);
            icons[i] = ic;
            labels[i] = tv;
        }

        addView(tabRow, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /** API < 33 或渲染器异常时的兜底：半透明深色圆角。 */
    private void forceFallback() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        d.setColors(new int[]{0xE63A3A3E, 0xE6222226});
        d.setCornerRadius(barHeightDp * density / 2f);
        d.setStroke(Math.max(1, (int) (0.6f * density)), 0x24FFFFFF);
        setBackground(d);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        tabWidth = (float) w / tabs.length;
        // 玻璃滴宽度略小于一个 tab（左右各留 4dp 缝）
        ViewGroup.LayoutParams dlp = droplet.getLayoutParams();
        int dropW = Math.max(1, (int) tabWidth - dp(8));
        dlp.width = dropW;
        droplet.setLayoutParams(dlp);
        // 缩放中心固定为玻璃滴自身中心（像素，不能用 0.5）
        droplet.setPivotX(dropW / 2f);
        droplet.setPivotY(dp(dropletHeightDp) / 2f);

        // 同步渲染器尺寸（QmDeve：WIDTH/HEIGHT 必须 >0，否则 shader 出 NaN 黑屏）
        if (glass != null && qm != null && w > 0 && h > 0) {
            qm.WIDTH = w;
            qm.HEIGHT = h;
            qm.CORNER_RADIUS_PX = Math.min(h * 0.5f, barHeightDp * density / 2f);
            glass.updateParameters();
        }
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(),
                        barHeightDp * density / 2f);
            }
        });
        setClipToOutline(true);
        positionDroplet(false);
        updateTabColors();
    }

    /** 玻璃滴横向定位。 */
    private void positionDroplet(boolean animate) {
        float dropW = Math.max(1f, tabWidth - dp(8));
        float x = pos * tabWidth + (tabWidth - dropW) / 2f;
        if (animate) {
            ValueAnimator a = ValueAnimator.ofFloat(droplet.getTranslationX(), x);
            a.setDuration(300);
            a.setInterpolator(new OvershootInterpolator(1.6f));
            a.addUpdateListener(an ->
                    droplet.setTranslationX((Float) an.getAnimatedValue()));
            a.start();
        } else {
            droplet.setTranslationX(x);
        }
    }

    /** 外部/点击选中。 */
    public void setSelected(int index, boolean animate) {
        if (index < 0 || index >= tabs.length) return;
        if (index == selected && !animate) {
            updateTabColors();
            return;
        }
        glideTo(index, true);
    }

    public int getSelected() {
        return selected;
    }

    private void glideTo(final int target, boolean notify) {
        if (glideAnimator != null && glideAnimator.isRunning()) glideAnimator.cancel();
        final float start = pos;
        glideAnimator = ValueAnimator.ofFloat(start, (float) target);
        glideAnimator.setDuration(320);
        glideAnimator.setInterpolator(new OvershootInterpolator(1.4f));
        glideAnimator.addUpdateListener(a -> {
            pos = (Float) a.getAnimatedValue();
            float dropW = Math.max(1f, tabWidth - dp(8));
            droplet.setTranslationX(pos * tabWidth + (tabWidth - dropW) / 2f);
        });
        glideAnimator.start();
        selected = target;
        // 拉伸回弹
        droplet.animate().scaleX(1f).scaleY(1f).setDuration(300)
                .setInterpolator(new OvershootInterpolator(2.0f)).start();
        updateTabColors();
        if (notify && listener != null) listener.onTabSelected(target);
    }

    private void updateTabColors() {
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == selected;
            int c = on ? COLOR_SELECTED : COLOR_NORMAL;
            icons[i].setColorFilter(c);
            labels[i].setTextColor(c);
        }
    }

    /* ---------------- 拖拽（玻璃滴跟手 + 拉伸 + 吸附） ---------------- */

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        // DOWN 交给 tab（可点击）；只有横向滑动超过 touchSlop 才拦截为拖拽
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                lastX = e.getRawX();
                lastT = System.nanoTime();
                dragStarted = false;
                if (glideAnimator != null && glideAnimator.isRunning()) glideAnimator.cancel();
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!dragStarted && Math.abs(e.getRawX() - downX) > touchSlop) {
                    dragStarted = true;
                    droplet.animate().cancel();
                    droplet.setScaleX(1.06f);
                    droplet.setScaleY(0.96f);
                }
                return dragStarted;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragStarted = false;
                return false;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!dragStarted && e.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                if (!dragStarted) return false;
                float x = e.getRawX();
                float dx = x - lastX;
                float now = System.nanoTime();
                float dt = Math.max(1f, (now - lastT) / 1e9f);
                float v = Math.abs(dx / dt); // px/s
                lastX = x;
                lastT = now;

                pos = clamp(pos + dx / tabWidth, 0f, tabs.length - 1f);
                float dropW = Math.max(1f, tabWidth - dp(8));
                droplet.setTranslationX(pos * tabWidth + (tabWidth - dropW) / 2f);

                // 按速度横向拉长、纵向压扁（液滴被拖动）
                float target = clamp(v / 2600f, 0f, 0.22f) * Math.signum(dx);
                stretch += (target - stretch) * 0.4f;
                droplet.setScaleX(1f + Math.abs(stretch));
                droplet.setScaleY(1f - Math.abs(stretch) * 0.45f);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragStarted) return false;
                dragStarted = false;
                int target = Math.round(pos);
                target = Math.max(0, Math.min(tabs.length - 1, target));
                glideTo(target, target != selected);
                return true;
            }
        }
        return false;
    }

    private int dp(int v) {
        return (int) (v * density + 0.5f);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 供外部代码创建底部导航的悬浮容器高度（像素）。 */
    public int getBarHeightPx() {
        return dp(barHeightDp);
    }

    @Override
    public int getSolidColor() {
        return Color.TRANSPARENT;
    }
}
