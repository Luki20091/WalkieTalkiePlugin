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

        // If the player logged out while transmitting, ensure we don't keep the "talking" texture.
        plugin.normalizeTalkingVariantInMainHand(player, true);

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
        plugin.normalizeTalkingVariantInMainHand(event.getPlayer(), true);
        plugin.getRadioState().clear(event.getPlayer().getUniqueId());
        plugin.clearTransmitCache(event.getPlayer().getUniqueId());
        plugin.clearPermissionCache(event.getPlayer().getUniqueId());
        lastHeldRadioChannel.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        plugin.normalizeTalkingVariantInMainHand(event.getPlayer(), true);
        plugin.getRadioState().clear(event.getPlayer().getUniqueId());
        plugin.clearTransmitCache(event.getPlayer().getUniqueId());
        plugin.clearPermissionCache(event.getPlayer().getUniqueId());
        lastHeldRadioChannel.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        // Offhand is allowed. Transmit is still main-hand only.
        // Swapping hands (default key: F) does not fire hotbar change events, so we must refresh caches here.
        Player player = event.getPlayer();
        plugin.runNextTick(() -> {
            plugin.getRadioState().refreshHotbar(player);
            plugin.refreshTransmitCache(player);
            plugin.refreshPermissionCache(player);

            // Keep internal tracking consistent (used for hotbar switch sounds only).
            RadioChannel inHand = itemUtil.getChannel(player.getInventory().getItemInMainHand());
            if (inHand != null) {
                lastHeldRadioChannel.put(player.getUniqueId(), inHand);
            } else {
                lastHeldRadioChannel.remove(player.getUniqueId());
            }
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
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

        plugin.runNextTick(() -> {
            plugin.getRadioState().refreshHotbar(player);
            plugin.refreshTransmitCache(player);
            plugin.refreshPermissionCache(player);
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();

        // Prevent leaving behind the "talking" variant on the ground.
        try {
            var entity = event.getItemDrop();
            if (entity != null) {
                var stack = entity.getItemStack();
                var normalized = plugin.normalizeTalkingVariantToBase(stack);
                if (normalized != null) {
                    entity.setItemStack(normalized);
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }

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
                    // Radio was put away (switching away from a radio)
                    String holsterSound = plugin.getConfig().getString("sounds.holster.sound", "");
                    if (holsterSound != null && !holsterSound.isBlank()) {
                        plugin.playFeedbackSound(player, "sounds.holster");
                    } else {
                        // Fallback to switch sound if holster isn't configured
                        String switchSound = plugin.getConfig().getString("sounds.switch.sound", "");
                        if (switchSound != null && !switchSound.isBlank()) {
                            plugin.playFeedbackSound(player, "sounds.switch");
                        }
                    }
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
