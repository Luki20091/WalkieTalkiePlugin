package me.Luki.WalkieTalkiePlugin.listeners;

import me.Luki.WalkieTalkiePlugin.WalkieTalkiePlugin;
import me.Luki.WalkieTalkiePlugin.radio.RadioChannel;
import me.Luki.WalkieTalkiePlugin.radio.RadioItemUtil;
import org.bukkit.event.block.Action;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

// Paper API event
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;

public final class RadioHoldToTalkPaperListener implements Listener {

    private final WalkieTalkiePlugin plugin;
    private final RadioItemUtil itemUtil;

    public RadioHoldToTalkPaperListener(WalkieTalkiePlugin plugin, RadioItemUtil itemUtil) {
        this.plugin = plugin;
        this.itemUtil = itemUtil;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // ignore offhand
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        RadioChannel channel = itemUtil.getChannel(player.getInventory().getItemInMainHand());
        if (channel == null) {
            return;
        }

        // Pirate eavesdrop radio (listen-only)
        if (channel == RadioChannel.PIRACI_RANDOM) {
            if (!player.hasPermission(channel.listenPermission())) {
                plugin.maybeNotifyNoListen(player, channel);
                return;
            }
        } else {
            if (!player.hasPermission(channel.usePermission())) {
                plugin.maybeNotifyNoTransmit(player, channel);
                return;
            }
        }

        // Pirate eavesdrop radio: in Paper fallback this may not stop correctly for
        // disc fragments.
        if (channel == RadioChannel.PIRACI_RANDOM) {
            if (plugin.getRadioState().getEavesdroppingChannel(player.getUniqueId()) != null) {
                return;
            }
            plugin.getRadioState().startPirateEavesdrop(player.getUniqueId());
            plugin.playFeedbackSound(player, "sounds.start");
            plugin.playConfiguredNotification(player, "notifications.eavesdrop.start");
            // Synchronizuj durability po zmianie wariantu
            plugin.runNextTick(() -> plugin.refreshHotbarVisualsNow(player));
            return;
        }
        RadioChannel already = plugin.getRadioState().getTransmittingChannel(player.getUniqueId());
        if (already == channel) {
            return;
        }
        // Synchronizuj durability po zmianie wariantu (bez zmiany durability na
        // starcie)
        plugin.runNextTick(() -> plugin.refreshHotbarVisualsNow(player));
        plugin.getRadioState().setTransmitting(player.getUniqueId(), channel);
        plugin.getRadioState().setTransmitSlot(player.getUniqueId(), player.getInventory().getHeldItemSlot());
        plugin.setHoldToTalkActive(player.getUniqueId(), true);
        // filterLong is LISTEN/eavesdrop-only; ensure it is not audible during
        // transmit.
        plugin.stopFilterLongSound(player);
        plugin.maybePlayFilterLoopPulseNow(player);
        plugin.playTransmitStartSound(player);
        plugin.playConfiguredNotification(player, "notifications.transmit.start");
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onStopUsing(PlayerStopUsingItemEvent event) {
        Player player = event.getPlayer();
        boolean changed = false;

        RadioChannel previousTransmit = plugin.getRadioState().getTransmittingChannel(player.getUniqueId());
        RadioChannel previousEavesdropTarget = plugin.getRadioState().getEavesdroppingChannel(player.getUniqueId());

        boolean endedTransmit = false;
        boolean endedEavesdrop = false;
        if (plugin.getRadioState().getTransmittingChannel(player.getUniqueId()) != null) {
            plugin.getRadioState().setTransmitting(player.getUniqueId(), null);
            changed = true;
            endedTransmit = true;
        }
        if (plugin.getRadioState().getEavesdroppingChannel(player.getUniqueId()) != null) {
            plugin.getRadioState().stopPirateEavesdrop(player.getUniqueId());
            changed = true;
            endedEavesdrop = true;
        }

        if (changed) {
            plugin.setHoldToTalkActive(player.getUniqueId(), false);
            if (previousTransmit != null) {
                plugin.playTransmitStopSound(player);
                plugin.playConfiguredNotification(player, "notifications.transmit.stop");
            }
            if (previousEavesdropTarget != null) {
                if (previousTransmit == null) {
                    plugin.playFeedbackSound(player, "sounds.stop");
                }
                plugin.playConfiguredNotification(player, "notifications.eavesdrop.stop");
            }

            // Najpierw zakończ tryb TRANSMIT (przywróć OFF)
            final boolean endedTransmitFinal = endedTransmit;
            final boolean endedEavesdropFinal = endedEavesdrop;
            // Odbierz durability na itemie w main hand gracza przed zmianą wariantu
            if ((endedTransmitFinal || endedEavesdropFinal)) {
                // Use saved slot for durability - prevents phantom transmit on moved radios
                Integer savedSlot = plugin.getRadioState().getTransmitSlot(player.getUniqueId());
                if (savedSlot != null) {
                    ItemStack radioAtSlot = player.getInventory().getItem(savedSlot);
                    // Debug: log item state just before decrement
                    if (plugin.isDevMode()) {
                        String ia = plugin.getItemUtil() != null
                                ? plugin.getItemUtil().debugGetItemsAdderId(radioAtSlot)
                                : "<no-ia>";
                        plugin.getLogger()
                                .info("[WT-DEBUG] onStopUsing about to decrement radio for " + player.getName()
                                        + " savedSlot=" + savedSlot + " ia=" + ia + " type="
                                        + (radioAtSlot == null ? "<null>" : radioAtSlot.getType()));
                    }
                    // Only apply durability if radio is still in the saved slot
                    if (plugin.getItemUtil().isRadio(radioAtSlot)) {
                        plugin.decrementRadioDurabilityAtSlot(player, savedSlot);
                    } else if (plugin.isDevMode()) {
                        plugin.getLogger().info(
                                "[WT-DEBUG] Radio no longer in saved slot " + savedSlot + ", skipping durability");
                    }
                } else {
                    // Fallback to current main hand (should not happen normally)
                    if (plugin.isDevMode()) {
                        ItemStack s = player.getInventory().getItemInMainHand();
                        String ia = plugin.getItemUtil() != null ? plugin.getItemUtil().debugGetItemsAdderId(s)
                                : "<no-ia>";
                        plugin.getLogger().info("[WT-DEBUG] onStopUsing fallback to main hand for " + player.getName()
                                + " ia=" + ia + " type=" + (s == null ? "<null>" : s.getType()));
                    }
                    plugin.decrementRadioDurability(player);
                }
            }
            plugin.runNextTick(() -> {
                plugin.forceStopTransmitVisuals(player);
                plugin.refreshHotbarVisualsNow(player);
            });
        }
    }
}
