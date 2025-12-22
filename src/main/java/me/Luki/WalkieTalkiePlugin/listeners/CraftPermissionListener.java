package me.Luki.WalkieTalkiePlugin.listeners;

import me.Luki.WalkieTalkiePlugin.radio.RadioChannel;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Recipe;
import org.bukkit.Keyed;

import java.util.Map;

public final class CraftPermissionListener implements Listener {

    private final Map<NamespacedKey, RadioChannel> recipes;

    public CraftPermissionListener(Map<NamespacedKey, RadioChannel> recipes) {
        this.recipes = recipes;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(PrepareItemCraftEvent event) {
        HumanEntity who = event.getView().getPlayer();
        if (!(who instanceof Player player)) {
            return;
        }
        Recipe recipe = event.getRecipe();
        RadioChannel channel = getChannel(recipe);
        if (channel == null) {
            return;
        }
        if (!player.hasPermission(channel.craftPermission())) {
            CraftingInventory inv = event.getInventory();
            inv.setResult(null);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        RadioChannel channel = getChannel(event.getRecipe());
        if (channel == null) {
            return;
        }
        if (!player.hasPermission(channel.craftPermission())) {
            event.setCancelled(true);
        }
    }

    private RadioChannel getChannel(Recipe recipe) {
        if (!(recipe instanceof Keyed keyed)) {
            return null;
        }
        return recipes.get(keyed.getKey());
    }
}
