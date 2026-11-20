import java.util.*;

/** 最小 View 模型：只保留 Controller.java 依赖的语义（类名/id/父子/alpha/可见性/资源表） */
public class V {
    public final String cls;          // 对应 v.getClass().getName()
    public String id;                 // 对应 v.getId()
    public V parent;
    public final List<V> children = new ArrayList<>();
    public float alpha = 1f;
    public int visibility = 0;        // 0=VISIBLE 4=INVISIBLE 8=GONE
    public boolean attached = false;
    public int attachOrder = -1;      // 模拟 onAttachedToWindow 触发顺序
    public String text;               // 便于断言/日志

    public V(String cls) { this.cls = cls; }

    public V(String cls, String id) { this.cls = cls; this.id = id; }

    public V add(V... cs) {
        for (V c : cs) {
            c.parent = this;
            children.add(c);
        }
        return this;
    }

    /** 对应 root.getResources().getIdentifier(name,"id","com.android.systemui") */
    public int getIdentifier(String name) {
        return TreeFixtures.resIdOrZero(name);
    }

    /** 对应 ViewGroup.findViewById：先序深度优先，返回第一个命中 */
    public V findViewById(String wantId) {
        if (wantId == null) return null;
        if (wantId.equals(this.id)) return this;
        for (V c : children) {
            V r = c.findViewById(wantId);
            if (r != null) return r;
        }
        return null;
    }

    /** 自身 alpha 与所有祖先的乘积：alpha 语义是叠加的 */
    public float effAlpha() {
        float a = alpha;
        V p = parent;
        while (p != null) {
            a *= p.alpha;
            p = p.parent;
        }
        return a;
    }

    public int depth() {
        int d = 0;
        V p = parent;
        while (p != null) { d++; p = p.parent; }
        return d;
    }

    /** 判断 other 是否为本节点的后代（含自身） */
    public boolean isAncestorOf(V other) {
        V p = other;
        while (p != null) {
            if (p == this) return true;
            p = p.parent;
        }
        return false;
    }

    public boolean intersects(V o) {
        return this.isAncestorOf(o) || o.isAncestorOf(this);
    }

    public String path() {
        Deque<String> st = new ArrayDeque<>();
        V p = this;
        while (p != null) {
            st.push(p.id != null ? p.id : p.cls.substring(p.cls.lastIndexOf('.') + 1));
            p = p.parent;
        }
        return String.join(">", st);
    }

    /** 模拟 attach 传播：addView 到已 attach 的父 → 子先 onAttachedToWindow */
    public List<V> attachSequence(int[] counter) {
        List<V> out = new ArrayList<>();
        walkAttach(this, out, counter);
        return out;
    }

    private static void walkAttach(V v, List<V> out, int[] c) {
        v.attached = true;
        v.attachOrder = c[0]++;
        out.add(v);              // 简化：父先于子（setContentView 路径）
        for (V ch : v.children) walkAttach(ch, out, c);
    }

    /** 模拟"已 attach 父节点上 addView 一个新子树"：子先触发 */
    public List<V> attachSubtree(V subtree, int[] counter) {
        add(subtree);
        List<V> out = new ArrayList<>();
        walkAttach(subtree, out, counter);
        return out;
    }
}
