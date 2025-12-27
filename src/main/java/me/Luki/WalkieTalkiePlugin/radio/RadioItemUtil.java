package me.Luki.WalkieTalkiePlugin.radio;

import me.Luki.WalkieTalkiePlugin.items.ItemsAdderBridge;
import me.Luki.WalkieTalkiePlugin.WalkieTalkiePlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Method;

public final class RadioItemUtil {

    private static final Method HAS_CUSTOM_MODEL_DATA;
    private static final Method GET_CUSTOM_MODEL_DATA;
    private static final Method SET_CUSTOM_MODEL_DATA;

    static {
        Method has = null;
        Method get = null;
        Method set = null;
        try {
            has = ItemMeta.class.getMethod("hasCustomModelData");
        } catch (NoSuchMethodException ignored) {
        }
        try {
            get = ItemMeta.class.getMethod("getCustomModelData");
        } catch (NoSuchMethodException ignored) {
        }
        try {
            set = ItemMeta.class.getMethod("setCustomModelData", Integer.class);
        } catch (NoSuchMethodException ignored) {
            try {
                set = ItemMeta.class.getMethod("setCustomModelData", int.class);
            } catch (NoSuchMethodException ignored2) {
            }
        }
        HAS_CUSTOM_MODEL_DATA = has;
        GET_CUSTOM_MODEL_DATA = get;
        SET_CUSTOM_MODEL_DATA = set;
    }

    private final NamespacedKey channelKey;
    private final RadioRegistry registry;
    private final ItemsAdderBridge itemsAdder;

    public RadioItemUtil(NamespacedKey channelKey, RadioRegistry registry, ItemsAdderBridge itemsAdder) {
        this.channelKey = channelKey;
        this.registry = registry;
        this.itemsAdder = itemsAdder;
    }

    private WalkieTalkiePlugin getPlugin() {
        try {
            return (WalkieTalkiePlugin) JavaPlugin.getProvidingPlugin(WalkieTalkiePlugin.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean isDebugEnabled() {
        WalkieTalkiePlugin p = getPlugin();
        if (p == null) return false;
        try {
            return p.isDevMode() || p.getConfig().getBoolean("debug.enabled", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public RadioChannel getChannel(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return null;
        }

        // Prefer ItemsAdder ID if configured
        if (itemsAdder != null && itemsAdder.isAvailable() && registry != null) {
            String id = itemsAdder.getCustomId(itemStack);
            if (id != null && !id.isBlank()) {
                if (isDebugEnabled()) {
                    WalkieTalkiePlugin p = getPlugin();
                    if (p != null) p.getLogger().info("[WT-DEBUG] RadioItemUtil.getChannel found ItemsAdder id=" + id);
                    else org.bukkit.Bukkit.getLogger().info("[WT-DEBUG] RadioItemUtil.getChannel found ItemsAdder id=" + id);
                }
                RadioChannel byId = registry.getChannelByItemsAdderId(id);
                if (byId != null) {
                    if (isDebugEnabled()) {
                        WalkieTalkiePlugin p = getPlugin();
                        if (p != null) p.getLogger().info("[WT-DEBUG] RadioItemUtil.getChannel mapped id=" + id + " -> " + byId.id());
                        else org.bukkit.Bukkit.getLogger().info("[WT-DEBUG] RadioItemUtil.getChannel mapped id=" + id + " -> " + byId.id());
                    }
                    return byId;
                }
            }
        }

        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            if (isDebugEnabled()) {
                WalkieTalkiePlugin p = getPlugin();
                if (p != null) p.getLogger().info("[WT-DEBUG] RadioItemUtil.getChannel no ItemMeta for stack");
                else org.bukkit.Bukkit.getLogger().info("[WT-DEBUG] RadioItemUtil.getChannel no ItemMeta for stack");
            }
            return null;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String id = pdc.get(channelKey, PersistentDataType.STRING);
        if (isDebugEnabled()) {
            WalkieTalkiePlugin p = getPlugin();
            if (p != null) p.getLogger().info("[WT-DEBUG] RadioItemUtil.getChannel pdc id=" + id);
            else org.bukkit.Bukkit.getLogger().info("[WT-DEBUG] RadioItemUtil.getChannel pdc id=" + id);
        }
        return RadioChannel.fromId(id);
    }

    public boolean isRadio(ItemStack itemStack) {
        return getChannel(itemStack) != null;
    }

    public int getCustomModelData(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return 0;
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null || HAS_CUSTOM_MODEL_DATA == null || GET_CUSTOM_MODEL_DATA == null) {
            return 0;
        }

        try {
            Object has = HAS_CUSTOM_MODEL_DATA.invoke(meta);
            if (!(has instanceof Boolean) || !((Boolean) has)) {
                return 0;
            }

            Object cmd = GET_CUSTOM_MODEL_DATA.invoke(meta);
            if (cmd instanceof Integer) {
                return (Integer) cmd;
            }
            if (cmd instanceof Number) {
                return ((Number) cmd).intValue();
            }
            return 0;
        } catch (ReflectiveOperationException ignored) {
            return 0;
        }
    }

    /**
     * Sets CustomModelData directly.
     * Returns true if the item was changed.
     */
    public boolean setCustomModelData(ItemStack itemStack, int customModelData) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return false;
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null || SET_CUSTOM_MODEL_DATA == null) {
            return false;
        }

        if (getCustomModelData(itemStack) == customModelData) {
            return false;
        }

        try {
            Class<?>[] params = SET_CUSTOM_MODEL_DATA.getParameterTypes();
            if (params.length == 1 && params[0] == int.class) {
                SET_CUSTOM_MODEL_DATA.invoke(meta, customModelData);
            } else {
                SET_CUSTOM_MODEL_DATA.invoke(meta, Integer.valueOf(customModelData));
            }
            itemStack.setItemMeta(meta);
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    public boolean hasChannelInHotbar(Player player, RadioChannel channel) {
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack item = inv.getItem(slot);
            if (channel == getChannel(item)) {
                return true;
            }
        }
        return false;
    }

    public boolean isRadioInOffhand(Player player) {
        return isRadio(player.getInventory().getItemInOffHand());
    }

    public boolean isItemsAdderAvailable() {
        boolean res = itemsAdder != null && itemsAdder.isAvailable();
        if (isDebugEnabled()) {
            WalkieTalkiePlugin p = getPlugin();
            if (p != null) p.getLogger().info("[WT-DEBUG] RadioItemUtil.isItemsAdderAvailable=" + res);
            else org.bukkit.Bukkit.getLogger().info("[WT-DEBUG] RadioItemUtil.isItemsAdderAvailable=" + res);
        }
        return res;
    }

    public String debugGetItemsAdderId(ItemStack itemStack) {
        if (itemsAdder == null || !itemsAdder.isAvailable()) {
            return null;
        }
        String id = itemsAdder.getCustomId(itemStack);
        if (isDebugEnabled()) {
            WalkieTalkiePlugin p = getPlugin();
            if (p != null) p.getLogger().info("[WT-DEBUG] debugGetItemsAdderId -> " + id);
            else org.bukkit.Bukkit.getLogger().info("[WT-DEBUG] debugGetItemsAdderId -> " + id);
        }
        return id;
    }
}
