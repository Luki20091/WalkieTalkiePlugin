package me.Luki.WalkieTalkiePlugin.radio;

import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RadioState {
    // Track eavesdrop durability tasks per player
    private final Map<UUID, Integer> pirateEavesdropTaskIds = new ConcurrentHashMap<>();

    private final RadioItemUtil itemUtil;

    private final Map<UUID, RadioChannel> transmitting = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> transmitSlot = new ConcurrentHashMap<>();
    private final Map<UUID, RadioChannel> eavesdropping = new ConcurrentHashMap<>();
    private final Map<UUID, Set<RadioChannel>> hotbarCache = new ConcurrentHashMap<>();

    public RadioState(RadioItemUtil itemUtil) {
        this.itemUtil = itemUtil;
    }

    public void setTransmitting(UUID playerUuid, RadioChannel channel) {
        if (channel == null) {
            transmitting.remove(playerUuid);
            // We KEEP the transmitSlot so that asynchronous tasks (transmitClearTask)
            // can still know where the radio *was* when transmission started.
        } else {
            transmitting.put(playerUuid, channel);
        }
    }

    public void setTransmitSlot(UUID playerUuid, int slot) {
        transmitSlot.put(playerUuid, slot);
    }

    public Integer getTransmitSlot(UUID playerUuid) {
        return transmitSlot.get(playerUuid);
    }

    public RadioChannel getTransmittingChannel(UUID playerUuid) {
        return transmitting.get(playerUuid);
    }

    public void clear(UUID playerUuid) {
        transmitting.remove(playerUuid);
        transmitSlot.remove(playerUuid);
        eavesdropping.remove(playerUuid);
        hotbarCache.remove(playerUuid);
    }

    public void startPirateEavesdrop(UUID playerUuid) {
        // Old pirate radio: listen to all channels simultaneously while held.
        // Represent this by storing the PIRACI_RANDOM marker so checks elsewhere
        // can treat it as a wildcard (match any transmitting channel).
        eavesdropping.put(playerUuid, RadioChannel.PIRACI_RANDOM);

        // Start durability reduction task for pirate radio
        Player player = org.bukkit.Bukkit.getPlayer(playerUuid);
        if (player != null
                && itemUtil.getChannel(player.getInventory().getItemInMainHand()) == RadioChannel.PIRACI_RANDOM) {
            // Cancel previous task if exists
            stopPirateEavesdropTask(playerUuid);
            int taskId = org.bukkit.Bukkit.getScheduler().scheduleSyncRepeatingTask(
                    org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(RadioState.class),
                    () -> {
                        if (itemUtil.getChannel(player.getInventory().getItemInMainHand()) == RadioChannel.PIRACI_RANDOM
                                && eavesdropping.containsKey(playerUuid)) {
                            // Reduce durability by 1 every 5 seconds
                            me.Luki.WalkieTalkiePlugin.WalkieTalkiePlugin plugin = (me.Luki.WalkieTalkiePlugin.WalkieTalkiePlugin) org.bukkit.plugin.java.JavaPlugin
                                    .getProvidingPlugin(me.Luki.WalkieTalkiePlugin.WalkieTalkiePlugin.class);
                            plugin.decrementRadioDurability(player);
                        } else {
                            stopPirateEavesdropTask(playerUuid);
                        }
                    },
                    100L, // 5 seconds (20 ticks * 5)
                    100L);
            pirateEavesdropTaskIds.put(playerUuid, taskId);
        }
    }

    public void stopPirateEavesdrop(UUID playerUuid) {
        eavesdropping.remove(playerUuid);
        stopPirateEavesdropTask(playerUuid);
    }

    private void stopPirateEavesdropTask(UUID playerUuid) {
        Integer taskId = pirateEavesdropTaskIds.remove(playerUuid);
        if (taskId != null) {
            org.bukkit.Bukkit.getScheduler().cancelTask(taskId);
        }
    }

    public RadioChannel getEavesdroppingChannel(UUID playerUuid) {
        return eavesdropping.get(playerUuid);
    }

    public void refreshHotbar(Player player) {
        EnumSet<RadioChannel> channels = EnumSet.noneOf(RadioChannel.class);
        for (int slot = 0; slot < 9; slot++) {
            RadioChannel channel = itemUtil.getChannel(player.getInventory().getItem(slot));
            if (channel != null) {
                channels.add(channel);
            }
        }

        // Offhand counts for listening as well (but transmit is still main-hand only).
        RadioChannel offhand = itemUtil.getChannel(player.getInventory().getItemInOffHand());
        if (offhand != null) {
            channels.add(offhand);
        }
        // Store as an immutable set to avoid accidental mutation across threads.
        hotbarCache.put(player.getUniqueId(), Collections.unmodifiableSet(channels));
        try {
            var plugin = (me.Luki.WalkieTalkiePlugin.WalkieTalkiePlugin) org.bukkit.plugin.java.JavaPlugin
                    .getProvidingPlugin(me.Luki.WalkieTalkiePlugin.WalkieTalkiePlugin.class);
            if (plugin != null && plugin.isDevMode()) {
                plugin.getLogger()
                        .info("[WT-DEBUG] refreshHotbar player=" + player.getName() + " channels=" + channels);
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean hasHotbarRadio(UUID playerUuid, RadioChannel channel) {
        Set<RadioChannel> channels = hotbarCache.get(playerUuid);
        boolean res = channels != null && channels.contains(channel);
        try {
            var plugin = (me.Luki.WalkieTalkiePlugin.WalkieTalkiePlugin) org.bukkit.plugin.java.JavaPlugin
                    .getProvidingPlugin(me.Luki.WalkieTalkiePlugin.WalkieTalkiePlugin.class);
            if (plugin != null && plugin.isDevMode()) {
                plugin.getLogger().info("[WT-DEBUG] hasHotbarRadio player=" + playerUuid + " channel="
                        + (channel == null ? "<null>" : channel.id()) + " result=" + res + " cache=" + channels);
            }
        } catch (Throwable ignored) {
        }
        return res;
    }
}
