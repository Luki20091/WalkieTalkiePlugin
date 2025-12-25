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

        // Pirate eavesdrop radio: in Paper fallback this may not stop correctly for disc fragments.
        if (channel == RadioChannel.PIRACI_RANDOM) {
            if (plugin.getRadioState().getEavesdroppingChannel(player.getUniqueId()) != null) {
                return;
            }
            plugin.getRadioState().startPirateEavesdrop(player.getUniqueId());
            plugin.playFeedbackSound(player, "sounds.start");
            plugin.playConfiguredNotification(player, "notifications.eavesdrop.start");
            return;
        }
        RadioChannel already = plugin.getRadioState().getTransmittingChannel(player.getUniqueId());
        if (already == channel) {
            return;
        }
        plugin.getRadioState().setTransmitting(player.getUniqueId(), channel);
        plugin.setHoldToTalkActive(player.getUniqueId(), true);
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

        if (plugin.getRadioState().getTransmittingChannel(player.getUniqueId()) != null) {
            plugin.getRadioState().setTransmitting(player.getUniqueId(), null);
            changed = true;
        }
        if (plugin.getRadioState().getEavesdroppingChannel(player.getUniqueId()) != null) {
            plugin.getRadioState().stopPirateEavesdrop(player.getUniqueId());
            changed = true;
        }

        if (changed) {
            plugin.setHoldToTalkActive(player.getUniqueId(), false);
            plugin.playFeedbackSound(player, "sounds.stop");
            if (previousTransmit != null) {
                plugin.playConfiguredNotification(player, "notifications.transmit.stop");
            }
            if (previousEavesdropTarget != null) {
                plugin.playConfiguredNotification(player, "notifications.eavesdrop.stop");
            }

            // Ensure transmit visuals can't get stuck in _1 after releasing the item.
            plugin.runNextTick(() -> plugin.forceStopTransmitVisuals(player));
        }
    }
}
