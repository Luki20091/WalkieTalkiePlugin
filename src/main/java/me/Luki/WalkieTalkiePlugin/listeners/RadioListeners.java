package me.Luki.WalkieTalkiePlugin.listeners;

import me.Luki.WalkieTalkiePlugin.WalkieTalkiePlugin;
import me.Luki.WalkieTalkiePlugin.radio.RadioChannel;
import me.Luki.WalkieTalkiePlugin.radio.RadioItemUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
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

    private void updatePirateEavesdropForMainHand(Player player) {
        // Pirate eavesdrop activation: hold PIRACI_RANDOM radio in main hand.
        boolean wasEavesdropping = plugin.getRadioState().getEavesdroppingChannel(player.getUniqueId()) != null;
        RadioChannel inHand = itemUtil.getChannel(player.getInventory().getItemInMainHand());
        boolean hasPirateListenPerm = player.hasPermission(RadioChannel.PIRACI_RANDOM.listenPermission());
        boolean wantsEavesdrop = inHand == RadioChannel.PIRACI_RANDOM && hasPirateListenPerm;

        boolean changed = false;

        if (inHand == RadioChannel.PIRACI_RANDOM && !hasPirateListenPerm) {
            plugin.maybeNotifyNoListen(player, RadioChannel.PIRACI_RANDOM);
        }

        if (wasEavesdropping && !wantsEavesdrop) {
            plugin.getRadioState().stopPirateEavesdrop(player.getUniqueId());
            changed = true;
            // Pirate radio requirement: filterLong is a continuous "static" while eavesdropping.
            // Stop it only if no other mode (TX/LISTEN) is currently keeping it active.
            if (!plugin.isTransmitUiActive(player.getUniqueId()) && !plugin.isListenUiActive(player.getUniqueId())) {
                plugin.stopFilterLongSound(player);
            }
            plugin.playFeedbackSound(player, "sounds.stop");
            plugin.playConfiguredNotification(player, "notifications.eavesdrop.stop");
        } else if (!wasEavesdropping && wantsEavesdrop) {
            plugin.getRadioState().startPirateEavesdrop(player.getUniqueId());
            changed = true;
            // Start continuous pirate static immediately, even if nobody is talking.
            plugin.playFilterLongSound(player);
            plugin.playFeedbackSound(player, "sounds.start");
            plugin.playConfiguredNotification(player, "notifications.eavesdrop.start");
        }

        if (changed) {
            plugin.refreshHotbarVisualsNow(player);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        plugin.markPlayerOnline(player.getUniqueId());

        // If the player logged out while transmitting, ensure we don't keep the "talking" texture.
        plugin.normalizeTalkingVariantInMainHand(player, true);

        plugin.getRadioState().refreshHotbar(player);
        plugin.refreshTransmitCache(player);
        plugin.refreshPermissionCache(player);
        plugin.refreshPreferredListenChannel(player);

        RadioChannel inHand = itemUtil.getChannel(player.getInventory().getItemInMainHand());
        if (inHand != null) {
            lastHeldRadioChannel.put(player.getUniqueId(), inHand);
        } else {
            lastHeldRadioChannel.remove(player.getUniqueId());
        }

        // Ensure no stored radios are left in ON stage.
        plugin.normalizeRadiosForStorage(player);

        // Ensure visuals are normalized immediately (some IA packs default to the last texture).
        plugin.runNextTick(() -> plugin.refreshHotbarVisualsAssumeInactive(player));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.markPlayerOffline(event.getPlayer().getUniqueId());
        plugin.normalizeTalkingVariantInMainHand(event.getPlayer(), true);
        plugin.releaseBusyLineIfOwned(event.getPlayer().getUniqueId());
        plugin.getRadioState().clear(event.getPlayer().getUniqueId());
        plugin.clearTransmitCache(event.getPlayer().getUniqueId());
        plugin.clearPermissionCache(event.getPlayer().getUniqueId());
        plugin.clearPreferredListenChannel(event.getPlayer().getUniqueId());
        lastHeldRadioChannel.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        plugin.markPlayerOffline(event.getPlayer().getUniqueId());
        plugin.normalizeTalkingVariantInMainHand(event.getPlayer(), true);
        plugin.releaseBusyLineIfOwned(event.getPlayer().getUniqueId());
        plugin.getRadioState().clear(event.getPlayer().getUniqueId());
        plugin.clearTransmitCache(event.getPlayer().getUniqueId());
        plugin.clearPermissionCache(event.getPlayer().getUniqueId());
        plugin.clearPreferredListenChannel(event.getPlayer().getUniqueId());
        lastHeldRadioChannel.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        // Offhand is allowed. Transmit is still main-hand only.
        // Swapping hands (default key: F) does not fire hotbar change events, so we must refresh caches here.
        Player player = event.getPlayer();
        plugin.runNextTick(() -> {
            plugin.normalizeRadiosForStorage(player);
            plugin.getRadioState().refreshHotbar(player);
            plugin.refreshTransmitCache(player);
            plugin.refreshPermissionCache(player);
            plugin.refreshPreferredListenChannel(player);

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

        // Immediate normalization: never allow an ON-stage radio to be moved around inventories.
        // (Main-hand visual ON is handled by the UI task; when you click/move items, we force OFF.)
        try {
            var current = event.getCurrentItem();
            var cursor = event.getCursor();
            if (itemUtil.isRadio(current)) {
                var normalized = plugin.normalizeTalkingVariantToBase(current);
                if (normalized != null) {
                    event.setCurrentItem(normalized);
                }
            }
            if (itemUtil.isRadio(cursor)) {
                var normalized = plugin.normalizeTalkingVariantToBase(cursor);
                if (normalized != null) {
                    event.setCursor(normalized);
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }

        // Block any attempt to drop an ACTIVE radio (slot-drop or cursor-drop).
        try {
            ClickType click = event.getClick();
            InventoryAction action = event.getAction();
            boolean isDrop = click == ClickType.DROP
                    || click == ClickType.CONTROL_DROP
                    || action == InventoryAction.DROP_ALL_CURSOR
                    || action == InventoryAction.DROP_ONE_CURSOR
                    || action == InventoryAction.DROP_ALL_SLOT
                    || action == InventoryAction.DROP_ONE_SLOT;

            if (isDrop) {
                var current = event.getCurrentItem();
                var cursor = event.getCursor();
                boolean activeRadio = plugin.isRadioOnStage(current) || plugin.isRadioOnStage(cursor);
                boolean txUi = plugin.isTransmitUiActive(player.getUniqueId());
                boolean listenUi = plugin.isListenUiActive(player.getUniqueId());
                boolean eavesdrop = plugin.getRadioState().getEavesdroppingChannel(player.getUniqueId()) != null;
                boolean anyRadio = itemUtil.isRadio(current) || itemUtil.isRadio(cursor);

                if (activeRadio || (txUi && anyRadio)) {
                    // Pressing Q while in any radio mode should never leave sounds stuck.
                    if (anyRadio && (txUi || listenUi || eavesdrop || plugin.isHoldToTalkActive(player.getUniqueId()))) {
                        plugin.forceStopAllRadioModes(player);
                    }
                    event.setCancelled(true);
                    plugin.runNextTick(() -> plugin.normalizeTalkingVariantInMainHand(player, true));
                    return;
                }

                // If the player is dropping a radio from inventory, force-stop all radio modes.
                // (Dropping may not trigger PlayerItemHeldEvent, so podsłuch can get stuck otherwise.)
                if (anyRadio && (txUi || listenUi || eavesdrop || plugin.isHoldToTalkActive(player.getUniqueId()))) {
                    plugin.forceStopAllRadioModes(player);
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }

        // Any inventory change: refresh cached hotbar next tick
        plugin.runNextTick(() -> {
            plugin.normalizeRadiosForStorage(player);
            plugin.getRadioState().refreshHotbar(player);
            plugin.refreshTransmitCache(player);
            plugin.refreshPermissionCache(player);
            plugin.refreshPreferredListenChannel(player);
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInventoryCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Creative can create/clone items; ensure any radio involved is OFF.
        try {
            var current = event.getCurrentItem();
            var cursor = event.getCursor();
            if (itemUtil.isRadio(current)) {
                var normalized = plugin.normalizeTalkingVariantToBase(current);
                if (normalized != null) {
                    event.setCurrentItem(normalized);
                }
            }
            if (itemUtil.isRadio(cursor)) {
                var normalized = plugin.normalizeTalkingVariantToBase(cursor);
                if (normalized != null) {
                    event.setCursor(normalized);
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }

        plugin.runNextTick(() -> {
            plugin.normalizeRadiosForStorage(player);
            plugin.getRadioState().refreshHotbar(player);
            plugin.refreshTransmitCache(player);
            plugin.refreshPermissionCache(player);
            plugin.refreshPreferredListenChannel(player);
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Immediate normalization isn't reliable for drag events; normalize next tick.

        plugin.runNextTick(() -> {
            plugin.normalizeRadiosForStorage(player);
            plugin.getRadioState().refreshHotbar(player);
            plugin.refreshTransmitCache(player);
            plugin.refreshPermissionCache(player);
            plugin.refreshPreferredListenChannel(player);
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        plugin.runNextTick(() -> plugin.normalizeRadiosForStorage(player));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        plugin.runNextTick(() -> plugin.normalizeRadiosForStorage(player));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        try {
            var item = event.getItem();
            if (itemUtil.isRadio(item)) {
                // Hoppers/automation must never move ON-stage radios.
                var normalized = plugin.normalizeTalkingVariantToBase(item);
                if (normalized != null) {
                    event.setItem(normalized);
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();

        // Q-drop should immediately end transmit/listen/eavesdrop to avoid stuck looping sounds.
        boolean droppedRadio = false;
        try {
            var entity = event.getItemDrop();
            if (entity != null) {
                var stack = entity.getItemStack();
                droppedRadio = itemUtil.isRadio(stack);
            }
        } catch (Throwable ignored) {
            // best-effort
        }

        if (droppedRadio) {
            plugin.forceStopAllRadioModes(player);
        }

        // Never allow dropping an ACTIVE radio.
        try {
            var entity = event.getItemDrop();
            if (entity != null) {
                var stack = entity.getItemStack();
                if (itemUtil.isRadio(stack)) {
                    boolean activeRadio = plugin.isRadioOnStage(stack);
                    boolean txUi = plugin.isTransmitUiActive(player.getUniqueId());
                    if (activeRadio || txUi) {
                        event.setCancelled(true);
                        plugin.runNextTick(() -> plugin.normalizeTalkingVariantInMainHand(player, true));
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }

        // Prevent leaving behind any "active" visual state on the ground.
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
            plugin.normalizeRadiosForStorage(player);
            plugin.getRadioState().refreshHotbar(player);
            plugin.refreshTransmitCache(player);
            plugin.refreshPermissionCache(player);
            plugin.refreshPreferredListenChannel(player);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        // Death drops can't be cancelled reliably; ensure radios are always OFF in drops.
        try {
            var drops = event.getDrops();
            for (int i = 0; i < drops.size(); i++) {
                var stack = drops.get(i);
                var normalized = plugin.normalizeTalkingVariantToBase(stack);
                if (normalized != null) {
                    drops.set(i, normalized);
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent event) {
        // Ensure picked-up radios are never ON.
        try {
            var entity = event.getEntity();
            if (!(entity instanceof Player player)) {
                return;
            }
            var itemEntity = event.getItem();
            if (itemEntity == null) {
                return;
            }
            var stack = itemEntity.getItemStack();
            var normalized = plugin.normalizeTalkingVariantToBase(stack);
            if (normalized != null) {
                itemEntity.setItemStack(normalized);
            }

            // Picking up an item can place it directly into the currently selected hotbar slot,
            // but that does NOT trigger PlayerItemHeldEvent. Re-evaluate eavesdrop next tick.
            plugin.runNextTick(() -> updatePirateEavesdropForMainHand(player));
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDispense(BlockDispenseEvent event) {
        // Dispensers can "drop" items from storage; ensure radios dispensed are OFF.
        try {
            var item = event.getItem();
            var normalized = plugin.normalizeTalkingVariantToBase(item);
            if (normalized != null) {
                event.setItem(normalized);
                return;
            }
            // If it's a radio but no normalization change was needed, it's already safe.
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        plugin.runNextTick(() -> {
            plugin.getRadioState().refreshHotbar(player);
            plugin.refreshTransmitCache(player);
            plugin.refreshPermissionCache(player);
            plugin.refreshPreferredListenChannel(player);

            // Visuals: holding a radio must remain OFF (_0) until real SVC mic packets occur.
            plugin.refreshHotbarVisualsAssumeInactive(player);

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

            updatePirateEavesdropForMainHand(player);
        });
    }

    // NOTE: Hold-to-talk is implemented using Paper start/stop using item events.
    // If these events are not present in the server build, we'll implement a ProtocolLib-based fallback later.
}
