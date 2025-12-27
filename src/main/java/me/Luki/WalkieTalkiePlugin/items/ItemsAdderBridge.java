package me.Luki.WalkieTalkiePlugin.items;

import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.lang.reflect.Field;

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

    private final Method getCustomModelData;

    public ItemsAdderBridge() {
        Class<?> cls = null;
        Method byItem = null;
        Method nsId = null;
        Method id = null;
        Method instS = null;
        Method instSI = null;
        Method itemStack = null;
        Method cmd = null;

        boolean ok = false;
        try {
            cls = Class.forName("dev.lone.itemsadder.api.CustomStack");

            byItem = cls.getMethod("byItemStack", ItemStack.class);

            // Different IA versions expose different getters
            nsId = findAnyMethod(cls, "getNamespacedID", "getNamespacedId", "getNamespacedIdentifier");
            id = findMethod(cls, "getId");

            // Different IA versions expose different factories
            instS = findStaticMethod(cls, "getInstance", String.class);
            instSI = findStaticMethod(cls, "getInstance", String.class, int.class);

            itemStack = findMethod(cls, "getItemStack");

            // Some IA versions expose base model data directly on CustomStack.
            cmd = findAnyMethod(cls, "getCustomModelData", "getCustomModelDataId", "getModelData", "getCustomModelDataValue");

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

        this.getCustomModelData = cmd;
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
                String asString = toNonBlankString(v);
                if (asString != null) {
                    return asString;
                }
            }
            if (getId != null) {
                Object v = getId.invoke(customStack);
                String asString = toNonBlankString(v);
                if (asString != null) {
                    return asString;
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

    /**
     * Best-effort: returns the base CustomModelData for an ItemsAdder custom item id.
     * This is more reliable than reading CMD from the returned ItemStack, because some
     * server setups or IA versions may return a non-default visual stage.
     */
    public int getBaseCustomModelData(String id) {
        if (!available || id == null || id.isBlank() || getCustomModelData == null) {
            return 0;
        }
        try {
            Object customStack;
            if (getInstanceStringInt != null) {
                customStack = getInstanceStringInt.invoke(null, id, 1);
            } else {
                customStack = getInstanceString.invoke(null, id);
            }
            if (customStack == null) {
                return 0;
            }
            Object v = getCustomModelData.invoke(customStack);
            if (v instanceof Integer i) {
                return i;
            }
            if (v instanceof Number n) {
                return n.intValue();
            }
            if (v != null) {
                try {
                    return Integer.parseInt(String.valueOf(v).trim());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
            return 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Best-effort: return configured max durability for a given ItemsAdder id.
     * Returns 0 if unknown.
     */
    public int getMaxDurabilityForId(String id) {
        if (!available || id == null || id.isBlank()) return 0;
        try {
            Object customStack;
            if (getInstanceStringInt != null) {
                customStack = getInstanceStringInt.invoke(null, id, 1);
            } else {
                customStack = getInstanceString.invoke(null, id);
            }
            if (customStack == null) return 0;

            // Try common getter method names
            String[] candidates = new String[] {"getMaxDurability", "getDefaultMaxDurability", "getDurability", "getDefaultDurability", "getMaxDurabilityValue", "getDurabilityMax"};
            for (String name : candidates) {
                Method m = findAnyMethod(customStack.getClass(), name);
                if (m != null) {
                    try {
                        Object v = m.invoke(customStack);
                        int parsed = parseIntSafe(v);
                        if (parsed > 0) return parsed;
                    } catch (Throwable ignored) {
                        // try next
                    }
                }
            }

            // Try a config/value map exposed by the CustomStack
            Method cfgM = findAnyMethod(customStack.getClass(), "getConfig", "getValues", "getData");
            if (cfgM != null) {
                try {
                    Object cfg = cfgM.invoke(customStack);
                    if (cfg instanceof java.util.Map<?, ?> map) {
                        Object v = map.get("max_durability");
                        if (v == null) v = map.get("durability");
                        if (v == null) v = map.get("durablity");
                        int parsed = parseIntSafe(v);
                        if (parsed > 0) return parsed;
                    }
                } catch (Throwable ignored) {
                    // ignore
                }
            }

            // Try reading simple numeric fields
            for (Field f : customStack.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(customStack);
                    int parsed = parseIntSafe(v);
                    if (parsed > 0) return parsed;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
            return 0;
        }
        return 0;
    }

    private static int parseIntSafe(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Method findMethod(Class<?> cls, String name) {
        try {
            return cls.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Method findAnyMethod(Class<?> cls, String... names) {
        if (names == null) {
            return null;
        }
        for (String n : names) {
            if (n == null || n.isBlank()) {
                continue;
            }
            Method m = findMethod(cls, n);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    private static Method findStaticMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            Method m = cls.getMethod(name, params);
            return m;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static String toNonBlankString(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            if (s.isBlank()) {
                return null;
            }
            return s;
        }
        String s = String.valueOf(v).trim();
        if (s.isBlank()) {
            return null;
        }
        return s;
    }
}
