package me.Luki.WalkieTalkiePlugin.listeners;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.wrappers.EnumWrappers;
import me.Luki.WalkieTalkiePlugin.WalkieTalkiePlugin;
import me.Luki.WalkieTalkiePlugin.radio.RadioChannel;
import me.Luki.WalkieTalkiePlugin.radio.RadioItemUtil;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * Reliable hold-to-talk for any item (e.g. disc fragment) using client packets.
 * Requires ProtocolLib at runtime.
 */
public final class RadioHoldToTalkProtocolLib {

    private final WalkieTalkiePlugin plugin;
    private final RadioItemUtil itemUtil;

    private ProtocolManager protocolManager;
    private PacketAdapter adapter;

    public RadioHoldToTalkProtocolLib(WalkieTalkiePlugin plugin, RadioItemUtil itemUtil) {
        this.plugin = plugin;
        this.itemUtil = itemUtil;
    }

    public void register() {
        protocolManager = ProtocolLibrary.getProtocolManager();

        adapter = new PacketAdapter(plugin, ListenerPriority.HIGH,
                // Press / use item
                PacketType.Play.Client.USE_ITEM,
                // Release use item (sent as a dig action)
                PacketType.Play.Client.BLOCK_DIG
        ) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                handle(event);
            }
        };

        protocolManager.addPacketListener(adapter);
        plugin.getLogger().info("ProtocolLib hold-to-talk enabled");
    }

    public void unregister() {
        if (protocolManager != null && adapter != null) {
            protocolManager.removePacketListener(adapter);
        }
        protocolManager = null;
        adapter = null;
    }

    private void handle(PacketEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        PacketType type = event.getPacketType();
        if (PacketType.Play.Client.USE_ITEM.equals(type)) {
            onUseItem(event, player);
            return;
        }
        if (PacketType.Play.Client.BLOCK_DIG.equals(type)) {
            onBlockDig(event, player);
        }
    }

    private void onUseItem(PacketEvent event, Player player) {
        // Enforce main hand only (offhand forbidden for radios)
        try {
            EnumWrappers.Hand hand = event.getPacket().getHands().readSafely(0);
            if (hand != null && hand != EnumWrappers.Hand.MAIN_HAND) {
                return;
            }
        } catch (Throwable ignored) {
            // if we can't read hand, default to allowing
        }

        // Always main-hand; we still enforce channel and permission.
        RadioChannel channel = itemUtil.getChannel(player.getInventory().getItemInMainHand());
        if (channel == null) {
            return;
        }
        if (!player.hasPermission(channel.usePermission())) {
            return;
        }

        // Pirate eavesdrop radio: do not transmit, only listen to a random channel while holding PPM
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

    private void onBlockDig(PacketEvent event, Player player) {
        EnumWrappers.PlayerDigType digType;
        try {
            digType = Objects.requireNonNull(event.getPacket().getPlayerDigTypes().readSafely(0));
        } catch (Exception ignored) {
            return;
        }

        if (digType != EnumWrappers.PlayerDigType.RELEASE_USE_ITEM) {
            return;
        }

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

            // Packet listeners may run off-thread; enforce main-thread inventory/UI updates.
            plugin.runNextTick(() -> plugin.forceStopTransmitVisuals(player));
        }
    }
}
