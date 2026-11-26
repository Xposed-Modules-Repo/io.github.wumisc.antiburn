package io.github.wumisc.antiburn.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 实验分支（LSPosed API 102 / libxposed）兼容壳 —— legacy XposedHelpers 的替身。
 *
 * <p>只覆盖当前 Hook 代码真正调用的那几个：{@code findClassIfExists}、
 * {@code findAndHookMethod}（两种重载）、{@code callMethod}、{@code callStaticMethod}、
 * {@code getObjectField}。全部是纯反射 + {@link XposedBridge#hookMethod}，
 * 不依赖任何 framework 私有类。</p>
 */
public final class XposedHelpers {

    private XposedHelpers() {
    }

    public static Class<?> findClassIfExists(String className, ClassLoader cl) {
        if (className == null) {
            return null;
        }
        try {
            return Class.forName(className, false, cl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Class<?> findClass(String className, ClassLoader cl) {
        Class<?> c = findClassIfExists(className, cl);
        if (c == null) {
            throw new NoClassDefFoundError(className);
        }
        return c;
    }

    /**
     * legacy：{@code findAndHookMethod(clazz, name, 参数类型..., callback)}。
     * 参数类型可以是 {@code Class} 也可以是类名字符串。
     */
    public static Method findAndHookMethod(Class<?> clazz, String methodName,
                                          Object... parameterTypesAndCallback) {
        XC_MethodHook cb = extractCallback(parameterTypesAndCallback);
        Class<?>[] types = extractTypes(parameterTypesAndCallback);
        Method m = findMethodExact(clazz, methodName, types);
        if (m == null) {
            throw new NoSuchMethodError(clazz.getName() + "#" + methodName);
        }
        m.setAccessible(true);
        XposedBridge.hookMethod(m, cb);
        return m;
    }

    /** legacy：按类名 + ClassLoader 的变体。 */
    public static Method findAndHookMethod(String className, ClassLoader cl, String methodName,
                                          Object... parameterTypesAndCallback) {
        return findAndHookMethod(findClass(className, cl), methodName, parameterTypesAndCallback);
    }

    private static XC_MethodHook extractCallback(Object[] arr) {
        if (arr == null) {
            return null;
        }
        for (Object o : arr) {
            if (o instanceof XC_MethodHook) {
                return (XC_MethodHook) o;
            }
        }
        return null;
    }

    private static Class<?>[] extractTypes(Object[] arr) {
        List<Class<?>> out = new ArrayList<>();
        if (arr != null) {
            for (Object o : arr) {
                if (o instanceof XC_MethodHook) {
                    continue;
                }
                if (o instanceof Class) {
                    out.add((Class<?>) o);
                } else if (o instanceof String) {
                    out.add(primitiveOrObject((String) o));
                }
            }
        }
        return out.toArray(new Class<?>[0]);
    }

    private static Class<?> primitiveOrObject(String name) {
        switch (name) {
            case "int":
                return int.class;
            case "long":
                return long.class;
            case "boolean":
                return boolean.class;
            case "float":
                return float.class;
            case "double":
                return double.class;
            case "short":
                return short.class;
            case "byte":
                return byte.class;
            case "char":
                return char.class;
            case "void":
                return void.class;
            default:
                try {
                    return Class.forName(name);
                } catch (Throwable t) {
                    throw new IllegalArgumentException("unknown type " + name, t);
                }
        }
    }

    private static Method findMethodExact(Class<?> clazz, String name, Class<?>[] types) {
        if (clazz == null || name == null) {
            return null;
        }
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredMethod(name, types);
            } catch (Throwable ignored) {
                // 继续向父类找
            }
            c = c.getSuperclass();
        }
        return null;
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        if (obj == null) {
            throw new NullPointerException("callMethod target is null: " + methodName);
        }
        return invokeBest(obj.getClass(), obj, methodName, args);
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return invokeBest(clazz, null, methodName, args);
    }

    private static Object invokeBest(Class<?> clazz, Object receiver, String name, Object[] args) {
        int argc = args == null ? 0 : args.length;
        Class<?> c = clazz;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                if (m.getParameterTypes().length != argc) {
                    continue;
                }
                if (!assignable(m.getParameterTypes(), args)) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    return m.invoke(receiver, args);
                } catch (Throwable t) {
                    throw new RuntimeException("invoke failed " + c.getName() + "#" + name, t);
                }
            }
            c = c.getSuperclass();
        }
        throw new NoSuchMethodError(clazz.getName() + "#" + name + "/" + argc);
    }

    private static boolean assignable(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            Object a = args == null ? null : args[i];
            if (a == null) {
                if (types[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            Class<?> p = box(types[i]);
            if (!p.isInstance(a)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) {
            return c;
        }
        if (c == int.class) {
            return Integer.class;
        }
        if (c == long.class) {
            return Long.class;
        }
        if (c == boolean.class) {
            return Boolean.class;
        }
        if (c == float.class) {
            return Float.class;
        }
        if (c == double.class) {
            return Double.class;
        }
        if (c == short.class) {
            return Short.class;
        }
        if (c == byte.class) {
            return Byte.class;
        }
        if (c == char.class) {
            return Character.class;
        }
        return Void.class;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        if (obj == null) {
            throw new NullPointerException("getObjectField target is null: " + fieldName);
        }
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldError(fieldName);
    }
}
