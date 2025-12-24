package me.Luki.WalkieTalkiePlugin.recipes;

import me.Luki.WalkieTalkiePlugin.WalkieTalkiePlugin;
import me.Luki.WalkieTalkiePlugin.items.ItemsAdderBridge;
import me.Luki.WalkieTalkiePlugin.radio.RadioChannel;
import me.Luki.WalkieTalkiePlugin.radio.RadioDefinition;
import me.Luki.WalkieTalkiePlugin.radio.RadioRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.HashMap;
import java.util.Map;

public final class RecipeRegistrar {

    private final WalkieTalkiePlugin plugin;
    private final RadioRegistry registry;
    private final ItemsAdderBridge itemsAdder;
    private final NamespacedKey pdcChannelKey;

    private final Map<NamespacedKey, RadioChannel> recipeKeys = new HashMap<>();

    public RecipeRegistrar(WalkieTalkiePlugin plugin, RadioRegistry registry, ItemsAdderBridge itemsAdder, NamespacedKey pdcChannelKey) {
        this.plugin = plugin;
        this.registry = registry;
        this.itemsAdder = itemsAdder;
        this.pdcChannelKey = pdcChannelKey;
    }

    public Map<NamespacedKey, RadioChannel> registerAll() {
        registerRadio(RadioChannel.CZERWONI, Material.RED_WOOL);
        registerRadio(RadioChannel.NIEBIESCY, Material.BLUE_WOOL);
        registerRadio(RadioChannel.HANDLARZE, Material.YELLOW_WOOL);
        registerRadio(RadioChannel.PIRACI, Material.GREEN_WOOL);
        registerRadio(RadioChannel.TOHANDLARZE, Material.PURPLE_WOOL);
        registerRadio(RadioChannel.PIRACI_RANDOM, Material.BLACK_WOOL);

        return recipeKeys;
    }

    private void registerRadio(RadioChannel channel, Material wool) {
        RadioDefinition def = registry.get(channel);
        if (def == null) {
            return;
        }

        NamespacedKey key = new NamespacedKey(plugin, "craft_" + channel.id().replace('.', '_'));
        ItemStack result = createResultItem(def);

        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape("I I", "IRI", " W ");
        recipe.setIngredient('I', Material.IRON_INGOT);
        recipe.setIngredient('R', Material.REDSTONE);
        recipe.setIngredient('W', wool);

        Bukkit.addRecipe(recipe);
        recipeKeys.put(key, channel);
    }

    private ItemStack createResultItem(RadioDefinition def) {
        // If ItemsAdder ID exists and IA is available, craft the real custom item.
        if (itemsAdder != null && itemsAdder.isAvailable() && def.itemsAdderId() != null && !def.itemsAdderId().isBlank()) {
            ItemStack ia = itemsAdder.createItemStack(def.itemsAdderId(), 1);
            if (ia != null) {
                // Also tag with our PDC so the plugin can identify the channel even if IA lookup fails.
                ItemMeta meta = ia.getItemMeta();
                if (meta != null) {
                    meta.getPersistentDataContainer().set(pdcChannelKey, PersistentDataType.STRING, def.channel().id());
                    ia.setItemMeta(meta);
                }
                return ia;
            }
        }

        // Fallback: disc fragment with PDC tag and name.
        ItemStack stack = new ItemStack(Material.DISC_FRAGMENT_5, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(def.displayName(), NamedTextColor.GOLD));
            meta.getPersistentDataContainer().set(pdcChannelKey, PersistentDataType.STRING, def.channel().id());
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
