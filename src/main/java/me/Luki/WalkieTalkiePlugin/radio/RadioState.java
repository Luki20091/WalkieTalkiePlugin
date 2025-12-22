package me.Luki.WalkieTalkiePlugin.radio;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RadioState {

    private final RadioItemUtil itemUtil;

    private final Map<UUID, RadioChannel> transmitting = new ConcurrentHashMap<>();
    private final Map<UUID, RadioChannel> eavesdropping = new ConcurrentHashMap<>();
    private final Map<UUID, Set<RadioChannel>> hotbarCache = new ConcurrentHashMap<>();

    public RadioState(RadioItemUtil itemUtil) {
        this.itemUtil = itemUtil;
    }

    public void setTransmitting(UUID playerUuid, RadioChannel channel) {
        if (channel == null) {
            transmitting.remove(playerUuid);
        } else {
            transmitting.put(playerUuid, channel);
        }
    }

    public RadioChannel getTransmittingChannel(UUID playerUuid) {
        return transmitting.get(playerUuid);
    }

    public void clear(UUID playerUuid) {
        transmitting.remove(playerUuid);
        eavesdropping.remove(playerUuid);
        hotbarCache.remove(playerUuid);
    }

    public void startPirateEavesdrop(UUID playerUuid) {
        // Pick a random target channel to listen to (excluding PIRACI_RANDOM itself)
        RadioChannel[] targets = new RadioChannel[]{
                RadioChannel.CZERWONI,
                RadioChannel.NIEBIESCY,
                RadioChannel.HANDLARZE,
                RadioChannel.PIRACI,
                RadioChannel.TOHANDLARZE
        };
        RadioChannel selected = targets[ThreadLocalRandom.current().nextInt(targets.length)];
        eavesdropping.put(playerUuid, selected);
    }

    public void stopPirateEavesdrop(UUID playerUuid) {
        eavesdropping.remove(playerUuid);
    }

    public RadioChannel getEavesdroppingChannel(UUID playerUuid) {
        return eavesdropping.get(playerUuid);
    }

    public void refreshHotbar(Player player) {
        Set<RadioChannel> channels = ConcurrentHashMap.newKeySet();
        for (int slot = 0; slot < 9; slot++) {
            RadioChannel channel = itemUtil.getChannel(player.getInventory().getItem(slot));
            if (channel != null) {
                channels.add(channel);
            }
        }
        hotbarCache.put(player.getUniqueId(), channels);
    }

    public boolean hasHotbarRadio(UUID playerUuid, RadioChannel channel) {
        Set<RadioChannel> channels = hotbarCache.get(playerUuid);
        return channels != null && channels.contains(channel);
    }
}
