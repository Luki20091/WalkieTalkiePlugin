package me.Luki.WalkieTalkiePlugin.listeners;

import me.Luki.WalkieTalkiePlugin.WalkieTalkiePlugin;
import me.Luki.WalkieTalkiePlugin.radio.RadioChannel;
import me.Luki.WalkieTalkiePlugin.radio.RadioItemUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

// Paper-only events may be available; we keep them reflective-safe by using PlayerInteractEvent fallback later.
import org.bukkit.event.player.PlayerKickEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RadioListeners implements Listener {

    private final WalkieTalkiePlugin plugin;
    private final RadioItemUtil itemUtil;

    private final Map<UUID, RadioChannel> lastHeldRadioChannel = new ConcurrentHashMap<>();

    public RadioListeners(WalkieTalkiePlugin plugin, RadioItemUtil itemUtil) {
        this.plugin = plugin;
        this.itemUtil = itemUtil;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Enforce: radios cannot exist in offhand
        if (itemUtil.isRadioInOffhand(player)) {
            var offhand = player.getInventory().getItemInOffHand();
            player.getInventory().setItemInOffHand(null);

            // Try move to inventory, else drop at feet
            var leftover = player.getInventory().addItem(offhand);
            leftover.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        }

        plugin.getRadioState().refreshHotbar(player);
        plugin.refreshTransmitCache(player);
        plugin.refreshPermissionCache(player);

        RadioChannel inHand = itemUtil.getChannel(player.getInventory().getItemInMainHand());
        if (inHand != null) {
            lastHeldRadioChannel.put(player.getUniqueId(), inHand);
        } else {
            lastHeldRadioChannel.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getRadioState().clear(event.getPlayer().getUniqueId());
        plugin.clearTransmitCache(event.getPlayer().getUniqueId());
        plugin.clearPermissionCache(event.getPlayer().getUniqueId());
        lastHeldRadioChannel.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        plugin.getRadioState().clear(event.getPlayer().getUniqueId());
        plugin.clearTransmitCache(event.getPlayer().getUniqueId());
        plugin.clearPermissionCache(event.getPlayer().getUniqueId());
        lastHeldRadioChannel.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        // Prevent radio in offhand (either from main to offhand or offhand to main)
        if (itemUtil.isRadio(event.getMainHandItem()) || itemUtil.isRadio(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Offhand slot index differs per view; easiest: block when raw slot equals 45 in player inventory view.
        // Additionally block when clicking the offhand slot in the bottom inventory.
        int rawSlot = event.getRawSlot();
        if (rawSlot == 45) {
            if (itemUtil.isRadio(event.getCursor()) || itemUtil.isRadio(event.getCurrentItem())) {
                event.setCancelled(true);
                return;
            }
        }

        // Any inventory change: refresh cached hotbar next tick
        plugin.runNextTick(() -> {
            plugin.getRadioState().refreshHotbar(player);
            plugin.refreshTransmitCache(player);
            plugin.refreshPermissionCache(player);
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Offhand raw slot 45
        if (event.getRawSlots().contains(45) && itemUtil.isRadio(event.getOldCursor())) {
            event.setCancelled(true);
            return;
        }

        plugin.runNextTick(() -> {
            plugin.getRadioState().refreshHotbar(player);
            plugin.refreshTransmitCache(player);
            plugin.refreshPermissionCache(player);
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        plugin.runNextTick(() -> {
            plugin.getRadioState().refreshHotbar(player);
            plugin.refreshTransmitCache(player);
            plugin.refreshPermissionCache(player);
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        plugin.runNextTick(() -> {
            plugin.getRadioState().refreshHotbar(player);
            plugin.refreshTransmitCache(player);
            plugin.refreshPermissionCache(player);

            // Play a single click sound when switching to a radio / between radio channels.
            RadioChannel nowInHand = itemUtil.getChannel(player.getInventory().getItemInMainHand());
            UUID uuid = player.getUniqueId();
            RadioChannel previously = lastHeldRadioChannel.get(uuid);
            if (nowInHand != previously) {
                if (nowInHand != null) {
                    String switchSound = plugin.getConfig().getString("sounds.switch.sound", "");
                    if (switchSound != null && !switchSound.isBlank()) {
                        plugin.playFeedbackSound(player, "sounds.switch");
                    } else {
                        // Backwards-compatible fallback for existing configs.
                        plugin.playFeedbackSound(player, "sounds.start");
                    }
                    lastHeldRadioChannel.put(uuid, nowInHand);
                } else {
                    lastHeldRadioChannel.remove(uuid);
                }
            }

            // Pirate eavesdrop activation: hold PIRACI_RANDOM radio in main hand.
            boolean wasEavesdropping = plugin.getRadioState().getEavesdroppingChannel(player.getUniqueId()) != null;
            RadioChannel inHand = itemUtil.getChannel(player.getInventory().getItemInMainHand());
            boolean wantsEavesdrop = inHand == RadioChannel.PIRACI_RANDOM && player.hasPermission(RadioChannel.PIRACI_RANDOM.usePermission());

            if (wasEavesdropping && !wantsEavesdrop) {
                plugin.getRadioState().stopPirateEavesdrop(player.getUniqueId());
                plugin.playFeedbackSound(player, "sounds.stop");
                plugin.playConfiguredNotification(player, "notifications.eavesdrop.stop");
            } else if (!wasEavesdropping && wantsEavesdrop) {
                plugin.getRadioState().startPirateEavesdrop(player.getUniqueId());
                plugin.playFeedbackSound(player, "sounds.start");
                plugin.playConfiguredNotification(player, "notifications.eavesdrop.start");
            }
        });
    }

    // NOTE: Hold-to-talk is implemented using Paper start/stop using item events.
    // If these events are not present in the server build, we'll implement a ProtocolLib-based fallback later.
}
