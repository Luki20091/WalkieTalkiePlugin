package me.Luki.WalkieTalkiePlugin.radio;

import me.Luki.WalkieTalkiePlugin.items.ItemsAdderBridge;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class RadioItemUtil {

    private final NamespacedKey channelKey;
    private final RadioRegistry registry;
    private final ItemsAdderBridge itemsAdder;

    public RadioItemUtil(NamespacedKey channelKey, RadioRegistry registry, ItemsAdderBridge itemsAdder) {
        this.channelKey = channelKey;
        this.registry = registry;
        this.itemsAdder = itemsAdder;
    }

    public RadioChannel getChannel(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return null;
        }

        // Prefer ItemsAdder ID if configured
        if (itemsAdder != null && itemsAdder.isAvailable() && registry != null) {
            String id = itemsAdder.getCustomId(itemStack);
            if (id != null && !id.isBlank()) {
                for (RadioDefinition def : registry.all().values()) {
                    if (def.itemsAdderId() != null && !def.itemsAdderId().isBlank() && def.itemsAdderId().equalsIgnoreCase(id)) {
                        return def.channel();
                    }
                }
            }
        }

        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return null;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String id = pdc.get(channelKey, PersistentDataType.STRING);
        return RadioChannel.fromId(id);
    }

    public boolean isRadio(ItemStack itemStack) {
        return getChannel(itemStack) != null;
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
}
