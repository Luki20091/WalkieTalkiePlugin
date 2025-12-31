package me.Luki.WalkieTalkiePlugin.listeners;

import me.Luki.WalkieTalkiePlugin.WalkieTalkiePlugin;
import me.Luki.WalkieTalkiePlugin.radio.RadioChannel;
import me.Luki.WalkieTalkiePlugin.radio.RadioItemUtil;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
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

    private void restoreSnapshot(InventoryClickEvent event, ItemStack beforeCurrent, ItemStack beforeCursor) {
        try {
            event.setCurrentItem(beforeCurrent);
        } catch (Throwable ignored) {
        }
        try {
            event.getView().setCursor(beforeCursor);
        } catch (Throwable ignored) {
        }
    }

    private void resyncInventory(Player player) {
        try {
            player.updateInventory();
        } catch (Throwable ignored) {
        }
        plugin.runNextTick(() -> {
            try {
                player.updateInventory();
            } catch (Throwable ignored) {
            }
        });
    }

    private void updatePirateEavesdropForMainHand(Player player) {
        // Pirate eavesdrop activation: hold PIRACI_RANDOM radio in main hand.
        boolean wasEavesdropping = plugin.getRadioState().getEavesdroppingChannel(player.getUniqueId()) != null;
        RadioChannel inHand = itemUtil.getChannel(player.getInventory().getItemInMainHand());
        boolean hasPirateListenPerm = player.hasPermission(RadioChannel.PIRACI_RANDOM.listenPermission());
        boolean wantsEavesdrop = inHand == RadioChannel.PIRACI_RANDOM && hasPirateListenPerm;

        try {
            if (plugin.isDevMode() || plugin.getConfig().getBoolean("debug.enabled", false)) {
                plugin.getLogger()
                        .info("[WT-DEBUG] updatePirateEavesdrop player=" + player.getName() + " inHand="
                                + (inHand == null ? "<none>" : inHand.id()) + " hasPerm=" + hasPirateListenPerm
                                + " wasEavesdropping=" + wasEavesdropping + " wantsEavesdrop=" + wantsEavesdrop);
            }
        } catch (Throwable ignored) {
        }

        boolean changed = false;

        if (inHand == RadioChannel.PIRACI_RANDOM && !hasPirateListenPerm) {
            plugin.maybeNotifyNoListen(player, RadioChannel.PIRACI_RANDOM);
        }

        if (wasEavesdropping && !wantsEavesdrop) {
            plugin.getRadioState().stopPirateEavesdrop(player.getUniqueId());
            changed = true;
            // Pirate radio requirement: filterLong is a continuous "static" while
            // eavesdropping.
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

        try {
            if (plugin.isDevMode() || plugin.getConfig().getBoolean("debug.enabled", false))
                plugin.getLogger().info("[WT-DEBUG] PlayerJoin: " + player.getName() + " uuid=" + player.getUniqueId());
        } catch (Throwable ignored) {
        }

        plugin.markPlayerOnline(player.getUniqueId());

        // If the player logged out while transmitting, ensure we don't keep the
        // "talking" texture.
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

        // Ensure visuals are normalized immediately (some IA packs default to the last
        // texture).
        plugin.runNextTick(() -> plugin.refreshHotbarVisualsAssumeInactive(player));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        try {
            if (plugin.isDevMode() || plugin.getConfig().getBoolean("debug.enabled", false))
                plugin.getLogger().info("[WT-DEBUG] PlayerQuit: " + event.getPlayer().getName() + " uuid="
                        + event.getPlayer().getUniqueId());
        } catch (Throwable ignored) {
        }
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
        // Swapping hands (default key: F) does not fire hotbar change events, so we
        // must refresh caches here.
        Player player = event.getPlayer();
        // Fix for phantom transmit: immediately invalidate transmit cache
        plugin.clearTransmitCache(player.getUniqueId());

        plugin.runNextTick(() -> {
            plugin.normalizeRadiosForStorage(player);
            plugin.getRadioState().refreshHotbar(player);
            plugin.refreshTransmitCache(player);
            plugin.refreshPermissionCache(player);
            plugin.refreshPreferredListenChannel(player);

            // Re-evaluate pirate eavesdrop when swapping hands (F key).
            try {
                updatePirateEavesdropForMainHand(player);
            } catch (Throwable ignored) {
            }

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
        // Creative events are handled separately in onInventoryCreative.
        // InventoryCreativeEvent extends InventoryClickEvent, so we must explicitly
        // ignore it here
        // to avoid double-handling and interference (e.g. updateInventory spam).
        if (event instanceof InventoryCreativeEvent) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Fix for phantom transmit: immediately invalidate transmit cache
        plugin.clearTransmitCache(player.getUniqueId());

        if (plugin.isDevMode()) {
            plugin.getLogger()
                    .info("[WT-TRACE] Click: " + event.getEventName() + " type=" + event.getInventory().getType()
                            + " viewTop=" + event.getView().getTopInventory().getType() + " slot=" + event.getRawSlot()
                            + " action=" + event.getAction());
        }

        ItemStack beforeCurrent = null;
        ItemStack beforeCursor = null;
        try {
            var cur = event.getCurrentItem();
            beforeCurrent = cur == null ? null : cur.clone();
        } catch (Throwable ignored) {
        }
        try {
            var cur2 = event.getView().getCursor();
            beforeCursor = cur2 == null ? null : cur2.clone();
        } catch (Throwable ignored) {
        }

        // If this player is interacting with a Backpack-like GUI, block any
        // attempts to move or pick up radio items in the player inventory.
        try {
            UUID puid = player.getUniqueId();
            if (plugin.isPlayerInBackpackGui(puid)) {
                try {
                    var current = event.getCurrentItem();
                    var cursor = event.getView().getCursor();
                    if (itemUtil.isRadio(current) || itemUtil.isRadio(cursor)) {
                        event.setCancelled(true);
                        // Restore server-side view to avoid client-side phantom copy
                        try {
                            event.setCurrentItem(beforeCurrent);
                        } catch (Throwable ignored) {
                        }
                        try {
                            event.getView().setCursor(beforeCursor);
                        } catch (Throwable ignored) {
                        }
                        if (plugin.isDevMode())
                            plugin.getLogger().info("[WT-DEBUG] Blocked radio inventory click for " + player.getName()
                                    + " while backpack GUI open");
                        try {
                            player.updateInventory();
                        } catch (Throwable ignored) {
                        }
                        plugin.runNextTick(() -> {
                            try {
                                player.updateInventory();
                            } catch (Throwable ignored) {
                            }
                        });
                        return;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        // Global rule: if config enforces radios-only-in-player-inventory, block
        // attempts to place radios into any non-player top inventory. Allow taking
        // radios out of those inventories (i.e., extraction is permitted).
        try {
            if (plugin.radiosOnlyInPlayerInventory()) {
                var view = player.getOpenInventory();
                var top = view == null ? null : view.getTopInventory();
                var clicked = event.getClickedInventory();

                // Check if the top inventory is a "restricted" container (e.g. chest, barrel).
                // Player, Creative, and Crafting inventories are always safe.
                boolean isRestrictedTop = top != null
                        && top.getType() != org.bukkit.event.inventory.InventoryType.PLAYER
                        && top.getType() != org.bukkit.event.inventory.InventoryType.CREATIVE
                        && top.getType() != org.bukkit.event.inventory.InventoryType.CRAFTING;

                if (isRestrictedTop) {
                    // Case 1: Player clicked inside the restricted top inventory.
                    if (clicked == top) {
                        // Block placing radio via cursor
                        try {
                            var cursor = event.getView().getCursor();
                            if (itemUtil.isRadio(cursor)) {
                                if (plugin.isDevMode())
                                    plugin.getLogger().info("[WT-TRACE] Blocked by RestrictedTop (Cursor) in Click");
                                event.setCancelled(true);
                                restoreSnapshot(event, beforeCurrent, beforeCursor);
                                plugin.notifyRadiosOnlyInPlayerInventory(player);
                                resyncInventory(player);
                                return;
                            }
                        } catch (Throwable ignored) {
                        }

                        // Block hotbar swap (NUMBER_KEY) putting radio into restricted top
                        if (event.getClick() == ClickType.NUMBER_KEY) {
                            int hotbarSlot = event.getHotbarButton();
                            if (hotbarSlot >= 0) {
                                ItemStack itemInHotbar = player.getInventory().getItem(hotbarSlot);
                                if (itemUtil.isRadio(itemInHotbar)) {
                                    if (plugin.isDevMode())
                                        plugin.getLogger()
                                                .info("[WT-TRACE] Blocked by RestrictedTop (Hotbar) in Click");
                                    event.setCancelled(true);
                                    restoreSnapshot(event, beforeCurrent, beforeCursor);
                                    plugin.notifyRadiosOnlyInPlayerInventory(player);
                                    resyncInventory(player);
                                    return;
                                }
                            }
                        }
                    }

                    // Case 2: Player clicked in their own inventory (Bottom).
                    // Block moving radio TO the restricted top inventory (Shift-Click).
                    if (clicked != top && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                        var cur = event.getCurrentItem();
                        if (itemUtil.isRadio(cur)) {
                            // Moving from Player -> Top (Restricted)
                            if (plugin.isDevMode())
                                plugin.getLogger().info("[WT-TRACE] Blocked by RestrictedTop (Shift-Move) in Click");
                            event.setCancelled(true);
                            restoreSnapshot(event, beforeCurrent, beforeCursor);
                            plugin.notifyRadiosOnlyInPlayerInventory(player);
                            resyncInventory(player);
                            return;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // Fix duplication/ghost items on hotbar swaps (NUMBER_KEY).
        // These events often desync because the client predicts the swap, but the
        // server might
        // cancel it or modify the item (normalization).
        if (event.getClick() == ClickType.NUMBER_KEY) {
            // If a radio is involved (either in the clicked slot or the hotbar slot), force
            // a resync.
            boolean radioInvolved = false;
            try {
                if (itemUtil.isRadio(event.getCurrentItem()))
                    radioInvolved = true;
                else {
                    int hotbarSlot = event.getHotbarButton();
                    if (hotbarSlot >= 0 && itemUtil.isRadio(player.getInventory().getItem(hotbarSlot))) {
                        radioInvolved = true;
                    }
                }
            } catch (Throwable ignored) {
            }

            if (radioInvolved) {
                plugin.runNextTick(() -> {
                    try {
                        player.updateInventory();
                    } catch (Throwable ignored) {
                    }
                });
            }
        }

        // Immediate normalization: never allow an ON-stage radio to be moved around
        // inventories.
        // (Main-hand visual ON is handled by the UI task; when you click/move items, we
        // force OFF.)
        boolean normalizedChanged = false;
        try {
            var current = event.getCurrentItem();
            var cursor = event.getView().getCursor();
            if (itemUtil.isRadio(current)) {
                var normalized = plugin.normalizeTalkingVariantToBase(current);
                if (normalized != null) {
                    event.setCurrentItem(normalized);
                    normalizedChanged = true;
                }
            }
            if (itemUtil.isRadio(cursor)) {
                var normalized = plugin.normalizeTalkingVariantToBase(cursor);
                if (normalized != null) {
                    event.getView().setCursor(normalized); // force OFF variant on cursor
                    normalizedChanged = true;
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }

        // Resync client view only when we actually changed something (avoid noisy
        // updates for non-radio items).
        if (normalizedChanged) {
            // Fix ghost items: do not force updateInventory immediately.
            plugin.runNextTick(() -> {
                try {
                    player.updateInventory();
                } catch (Throwable ignored) {
                }
            });
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
                var cursor = event.getView().getCursor();
                boolean activeRadio = plugin.isRadioOnStage(current) || plugin.isRadioOnStage(cursor);
                boolean txUi = plugin.isTransmitUiActive(player.getUniqueId());
                boolean listenUi = plugin.isListenUiActive(player.getUniqueId());
                boolean eavesdrop = plugin.getRadioState().getEavesdroppingChannel(player.getUniqueId()) != null;
                boolean anyRadio = itemUtil.isRadio(current) || itemUtil.isRadio(cursor);

                if (activeRadio || (txUi && anyRadio)) {
                    // Pressing Q while in any radio mode should never leave sounds stuck.
                    if (anyRadio
                            && (txUi || listenUi || eavesdrop || plugin.isHoldToTalkActive(player.getUniqueId()))) {
                        plugin.forceStopAllRadioModes(player);
                    }
                    event.setCancelled(true);
                    try {
                        player.updateInventory();
                    } catch (Throwable ignored) {
                    }
                    plugin.runNextTick(() -> plugin.normalizeTalkingVariantInMainHand(player, true));
                    return;
                }

                // If the player is dropping a radio from inventory, force-stop all radio modes.
                // (Dropping may not trigger PlayerItemHeldEvent, so podsłuch can get stuck
                // otherwise.)
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Fix for phantom transmit: immediately invalidate transmit cache
        plugin.clearTransmitCache(player.getUniqueId());

        if (plugin.isDevMode()) {
            plugin.getLogger().info("[WT-TRACE] Creative: type=" + event.getInventory().getType() + " viewTop="
                    + event.getView().getTopInventory().getType() + " slot=" + event.getRawSlot());
        }

        // Enforce global radios-only-in-player-inventory rule for creative inventory
        // too.
        try {
            if (plugin.radiosOnlyInPlayerInventory()) {
                var view = player.getOpenInventory();
                var top = view == null ? null : view.getTopInventory();
                var clicked = event.getClickedInventory();
                if (top != null && top.getType() != org.bukkit.event.inventory.InventoryType.PLAYER
                        && top.getType() != org.bukkit.event.inventory.InventoryType.CREATIVE
                        && top.getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) {

                    // Case 1: Player clicked inside the restricted top inventory.
                    if (clicked == top) {
                        try {
                            var current = event.getCurrentItem();
                            var cursor = event.getCursor(); // Item being placed
                            if (itemUtil.isRadio(current) || itemUtil.isRadio(cursor)) {
                                if (plugin.isDevMode())
                                    plugin.getLogger().info("[WT-TRACE] Blocked by RestrictedTop in Creative");
                                event.setCancelled(true);
                                plugin.notifyRadiosOnlyInPlayerInventory(player);
                                plugin.runNextTick(() -> {
                                    try {
                                        player.updateInventory();
                                    } catch (Throwable ignored) {
                                    }
                                });
                                return;
                            }
                        } catch (Throwable ignored) {
                        }
                    }

                    // Case 2: Player clicked in their own inventory (Bottom) -> Shift-Click to Top
                    if (clicked != top && event.isShiftClick()) {
                        try {
                            var current = event.getCurrentItem();
                            if (itemUtil.isRadio(current)) {
                                event.setCancelled(true);
                                plugin.notifyRadiosOnlyInPlayerInventory(player);
                                plugin.runNextTick(() -> {
                                    try {
                                        player.updateInventory();
                                    } catch (Throwable ignored) {
                                    }
                                });
                                return;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // Creative can create/clone items; ensure any radio involved is OFF.
        boolean creativeNormalized = false;
        try {

            var cursor = event.getCursor(); // Item being placed

            // FIX: Only normalize the item being placed (cursor).
            // Do NOT normalize current item in slot, as that prevents removing/replacing it
            // (pickup).
            if (itemUtil.isRadio(cursor)) {
                var normalized = plugin.normalizeTalkingVariantToBase(cursor);
                if (normalized != null) {
                    event.setCurrentItem(normalized);
                    creativeNormalized = true;
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }

        // Resync client only when creative normalization changed something.
        if (creativeNormalized) {
            try {
                player.updateInventory();
            } catch (Throwable ignored) {
            }
            plugin.runNextTick(() -> {
                try {
                    player.updateInventory();
                } catch (Throwable ignored) {
                }
            });
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (plugin.isDevMode()) {
            plugin.getLogger().info("[WT-TRACE] Drag: type=" + event.getInventory().getType() + " viewTop="
                    + event.getView().getTopInventory().getType());
        }

        // Block drag of radio items while backpack GUI is open, or if config forbids
        // placing radios
        try {
            UUID puid = player.getUniqueId();
            boolean backpack = plugin.isPlayerInBackpackGui(puid);
            var view = player.getOpenInventory();
            var top = view == null ? null : view.getTopInventory();
            boolean configBlock = plugin.radiosOnlyInPlayerInventory() && top != null
                    && top.getType() != org.bukkit.event.inventory.InventoryType.PLAYER
                    && top.getType() != org.bukkit.event.inventory.InventoryType.CREATIVE
                    && top.getType() != org.bukkit.event.inventory.InventoryType.CRAFTING;

            if (backpack || configBlock) {
                try {
                    var newItems = event.getNewItems();
                    boolean radioInvolved = false;
                    for (var it : newItems.values()) {
                        if (itemUtil.isRadio(it)) {
                            radioInvolved = true;
                            break;
                        }
                    }

                    if (radioInvolved) {
                        boolean shouldCancel = false;
                        if (backpack) {
                            shouldCancel = true;
                        } else if (configBlock) {
                            // Only cancel if dragging INTO the top inventory
                            if (top != null) {
                                int topSize = top.getSize();
                                for (int slot : event.getRawSlots()) {
                                    if (slot < topSize) {
                                        if (plugin.isDevMode())
                                            plugin.getLogger().info("[WT-TRACE] Blocked by RestrictedTop in Drag");
                                        shouldCancel = true;
                                        break;
                                    }
                                }
                            }
                        }

                        if (shouldCancel) {
                            event.setCancelled(true);
                            if (plugin.isDevMode())
                                plugin.getLogger().info("[WT-DEBUG] Blocked radio drag for " + player.getName()
                                        + " (backpack=" + backpack + " configBlock=" + configBlock + ")");
                            try {
                                player.updateInventory();
                            } catch (Throwable ignored) {
                            }
                            plugin.runNextTick(() -> {
                                try {
                                    player.updateInventory();
                                } catch (Throwable ignored) {
                                }
                            });
                            return;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        // Normalize drag items immediately where possible (modify the newItems map),
        // then resync client to avoid leaving phantom cursor items.
        try {
            var newItems = event.getNewItems();
            boolean changed = false;
            for (var e : newItems.entrySet()) {
                var it = e.getValue();
                if (itemUtil.isRadio(it)) {
                    var normalized = plugin.normalizeTalkingVariantToBase(it);
                    if (normalized != null) {
                        e.setValue(normalized);
                        changed = true;
                    }
                }
            }
            if (changed) {
                // Fix ghost items: do not force updateInventory immediately.
                plugin.runNextTick(() -> {
                    try {
                        player.updateInventory();
                    } catch (Throwable ignored) {
                    }
                });
            }
        } catch (Throwable ignored) {
        }

        // Fallback normalization next tick for any remaining edge-cases.
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
                // Enforce config: do not allow automation to insert radios into non-player
                // inventories
                try {
                    var dest = event.getDestination();
                    if (plugin.radiosOnlyInPlayerInventory() && dest != null
                            && dest.getType() != org.bukkit.event.inventory.InventoryType.PLAYER
                            && dest.getType() != org.bukkit.event.inventory.InventoryType.CREATIVE
                            && dest.getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) {
                        event.setCancelled(true);
                        if (plugin.isDevMode())
                            plugin.getLogger()
                                    .info("[WT-DEBUG] Blocked automated transfer of radio into non-player inventory");
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        // Fix for phantom transmit: immediate invalidation
        plugin.clearTransmitCache(player.getUniqueId());

        // Q-drop should immediately end transmit/listen/eavesdrop to avoid stuck
        // looping sounds.
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
                    plugin.syncRadioDurability(stack, normalized);
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
        // Death drops can't be cancelled reliably; ensure radios are always OFF in
        // drops.
        try {
            var drops = event.getDrops();
            for (int i = 0; i < drops.size(); i++) {
                var stack = drops.get(i);
                var normalized = plugin.normalizeTalkingVariantToBase(stack);
                if (normalized != null) {
                    plugin.syncRadioDurability(stack, normalized);
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
            // If the player currently has a Backpack GUI open, block picking up radios.
            try {
                UUID puid = player.getUniqueId();
                if (plugin.isPlayerInBackpackGui(puid)) {
                    try {
                        var pickupStack = itemEntity.getItemStack();
                        if (itemUtil.isRadio(pickupStack)) {
                            event.setCancelled(true);
                            if (plugin.isDevMode())
                                plugin.getLogger().info("[WT-DEBUG] Blocked pickup of radio for " + player.getName()
                                        + " while backpack GUI open");
                            plugin.runNextTick(() -> {
                                try {
                                    player.updateInventory();
                                } catch (Throwable ignored) {
                                }
                            });
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            var stack = itemEntity.getItemStack();
            var normalized = plugin.normalizeTalkingVariantToBase(stack);
            if (normalized != null) {
                plugin.syncRadioDurability(stack, normalized);
                itemEntity.setItemStack(normalized);
            }

            // Picking up an item can place it directly into the currently selected hotbar
            // slot,
            // but that does NOT trigger PlayerItemHeldEvent. Re-evaluate eavesdrop next
            // tick.
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
        int previousSlot = event.getPreviousSlot();
        UUID uuid = player.getUniqueId();
        // Fix for phantom transmit: immediate invalidation
        plugin.clearTransmitCache(uuid);

        // Check if transmission was active BEFORE runNextTick, since forceStop will
        // clear it
        boolean wasTransmitting = plugin.isTransmitUiActive(uuid);

        plugin.runNextTick(() -> {
            try {
                if (plugin.isDevMode() || plugin.getConfig().getBoolean("debug.enabled", false))
                    plugin.getLogger().info("[WT-DEBUG] ItemHeld event for " + player.getName() + " from="
                            + previousSlot + " to=" + event.getNewSlot() + " wasTransmitting=" + wasTransmitting);
            } catch (Throwable ignored) {
            }

            // If player was transmitting, force stop and consume durability from previous
            // slot
            if (wasTransmitting) {
                plugin.decrementRadioDurabilityAtSlot(player, previousSlot);
                plugin.forceStopAllRadioModes(player);
                if (plugin.isDevMode())
                    plugin.getLogger().info("[WT-DEBUG] Forced stop due to slot switch from=" + previousSlot);
            }

            plugin.getRadioState().refreshHotbar(player);
            plugin.refreshTransmitCache(player);
            plugin.refreshPermissionCache(player);
            plugin.refreshPreferredListenChannel(player);

            // Visuals: holding a radio must remain OFF (_0) until real SVC mic packets
            // occur.
            plugin.refreshHotbarVisualsAssumeInactive(player);

            // Play a single click sound when switching to a radio / between radio channels.
            RadioChannel nowInHand = itemUtil.getChannel(player.getInventory().getItemInMainHand());
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRadioInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        org.bukkit.entity.Player player = event.getPlayer();
        org.bukkit.inventory.ItemStack item = player.getInventory().getItemInMainHand();
        if (!itemUtil.isRadio(item))
            return;

        // Blokuj PRAWE kliknięcie (nadawanie) oraz LEWE kliknięcie (np. atak), ale nie
        // blokuj innych akcji (np. scroll, drop)
        org.bukkit.event.block.Action action = event.getAction();
        switch (action) {
            case RIGHT_CLICK_BLOCK:
            case RIGHT_CLICK_AIR:
            case LEFT_CLICK_BLOCK:
            case LEFT_CLICK_AIR:
                event.setCancelled(true);
                break;
            default:
                // nie blokuj innych akcji
        }
    }

    // Blokuj użycie radia na owcach (strzyżenie)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRadioEntityInteract(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        org.bukkit.entity.Player player = event.getPlayer();
        org.bukkit.inventory.ItemStack item = player.getInventory().getItemInMainHand();
        if (!itemUtil.isRadio(item))
            return;
        // Jeśli kliknięto owcę radiem, anuluj event (blokuje strzyżenie)
        if (event.getRightClicked() instanceof org.bukkit.entity.Sheep) {
            event.setCancelled(true);
        }
    }

    // NOTE: Hold-to-talk is implemented using Paper start/stop using item events.
    // If these events are not present in the server build, we'll implement a
    // ProtocolLib-based fallback later.
}
