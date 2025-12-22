package me.Luki.WalkieTalkiePlugin.items;

import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

/**
 * Reflection-based bridge for ItemsAdder.
 * This avoids compile-time dependency on ItemsAdder API.
 */
public final class ItemsAdderBridge {

    private final boolean available;
    private final Method byItemStack;

    private final Method getNamespacedId;
    private final Method getId;

    private final Method getInstanceString;
    private final Method getInstanceStringInt;

    private final Method getItemStack;

    public ItemsAdderBridge() {
        Class<?> cls = null;
        Method byItem = null;
        Method nsId = null;
        Method id = null;
        Method instS = null;
        Method instSI = null;
        Method itemStack = null;

        boolean ok = false;
        try {
            cls = Class.forName("dev.lone.itemsadder.api.CustomStack");

            byItem = cls.getMethod("byItemStack", ItemStack.class);

            // Different IA versions expose different getters
            nsId = findMethod(cls, "getNamespacedID");
            id = findMethod(cls, "getId");

            // Different IA versions expose different factories
            instS = findStaticMethod(cls, "getInstance", String.class);
            instSI = findStaticMethod(cls, "getInstance", String.class, int.class);

            itemStack = findMethod(cls, "getItemStack");

            ok = (byItem != null) && (itemStack != null) && (instS != null || instSI != null);
        } catch (ClassNotFoundException ignored) {
            ok = false;
        } catch (Throwable ignored) {
            ok = false;
        }

        this.available = ok;
        this.byItemStack = byItem;
        this.getNamespacedId = nsId;
        this.getId = id;
        this.getInstanceString = instS;
        this.getInstanceStringInt = instSI;
        this.getItemStack = itemStack;
    }

    public boolean isAvailable() {
        return available;
    }

    public String getCustomId(ItemStack itemStack) {
        if (!available || itemStack == null) {
            return null;
        }
        try {
            Object customStack = byItemStack.invoke(null, itemStack);
            if (customStack == null) {
                return null;
            }
            if (getNamespacedId != null) {
                Object v = getNamespacedId.invoke(customStack);
                if (v instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
            if (getId != null) {
                Object v = getId.invoke(customStack);
                if (v instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public ItemStack createItemStack(String id, int amount) {
        if (!available || id == null || id.isBlank()) {
            return null;
        }
        try {
            Object customStack;
            if (getInstanceStringInt != null) {
                customStack = getInstanceStringInt.invoke(null, id, amount);
            } else {
                customStack = getInstanceString.invoke(null, id);
            }
            if (customStack == null) {
                return null;
            }
            Object itemStack = getItemStack.invoke(customStack);
            if (itemStack instanceof ItemStack stack) {
                if (amount > 0) {
                    stack.setAmount(amount);
                }
                return stack;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> cls, String name) {
        try {
            return cls.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Method findStaticMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            Method m = cls.getMethod(name, params);
            return m;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
