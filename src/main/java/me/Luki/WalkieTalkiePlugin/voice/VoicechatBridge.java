package me.Luki.WalkieTalkiePlugin.voice;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;
import me.Luki.WalkieTalkiePlugin.WalkieTalkiePlugin;
import me.Luki.WalkieTalkiePlugin.radio.RadioChannel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.UUID;

public final class VoicechatBridge implements VoicechatPlugin {

    private final WalkieTalkiePlugin plugin;
    private volatile VoicechatServerApi serverApi;

    private final int microphoneEventPriority;
    private final boolean logHook;
    private final double radioMinDistanceBlocks;

    public VoicechatBridge(WalkieTalkiePlugin plugin) {
        this.plugin = plugin;

        this.microphoneEventPriority = plugin.getConfig().getInt("svc.microphoneEventPriority", 100);
        this.logHook = plugin.getConfig().getBoolean("svc.logHook", true);
        double minDistance = plugin.getConfig().getDouble("svc.radioMinDistanceBlocks", 20.0);
        this.radioMinDistanceBlocks = Math.max(0.0, minDistance);
    }

    public static void tryRegister(WalkieTalkiePlugin plugin) {
        if (!plugin.getConfig().getBoolean("svc.enabled", true)) {
            plugin.getLogger().info("SVC integration disabled in config (svc.enabled=false). Voice features disabled.");
            return;
        }

        BukkitVoicechatService service = Bukkit.getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            plugin.getLogger().warning("Simple Voice Chat service not found. Voice features disabled.");
            return;
        }
        service.registerPlugin(new VoicechatBridge(plugin));
    }

    @Override
    public String getPluginId() {
        // Must be unique; keep stable.
        return "walkietalkieplugin";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket, microphoneEventPriority);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        serverApi = event.getVoicechat();
        if (logHook) {
            plugin.getLogger().info("Simple Voice Chat API hooked");
        }
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        VoicechatServerApi api = serverApi;
        if (api == null) {
            return;
        }

        UUID senderUuid = event.getSenderConnection().getPlayer().getUuid();
        Player sender = Bukkit.getPlayer(senderUuid);
        if (sender == null) {
            return;
        }

        RadioChannel transmitting = plugin.getTransmitChannel(sender);
        if (transmitting == null) {
            return; // normal behavior
        }

        // Sender is holding a radio (and has permission) and we received an audio packet.
        plugin.recordMicPacket(senderUuid);

        // SVC emits MicrophonePacketEvent per receiver. We can decide per receiver:
        // - close enough: allow proximity voice (do NOT cancel), do NOT send radio
        // - far enough: cancel proximity voice and send radio (direct)
        // - not allowed: cancel so they don't hear even prox while someone is on radio

        VoicechatConnection receiverConnection = event.getReceiverConnection();
        if (receiverConnection == null) {
            // Can't decide per-receiver; fall back to prox.
            return;
        }

        UUID receiverUuid = receiverConnection.getPlayer().getUuid();
        if (receiverUuid == null || receiverUuid.equals(senderUuid)) {
            // If we can't resolve receiver, safest is to prevent leaking proximity during radio mode.
            event.cancel();
            return;
        }

        Player receiver = Bukkit.getPlayer(receiverUuid);
        if (receiver == null) {
            event.cancel();
            return;
        }

        boolean allowedToHear = isAllowedToHear(receiver, transmitting);
        if (!allowedToHear) {
            event.cancel();
            return;
        }

        // If receiver is closer than threshold, keep proximity voice (avoid overlap)
        if (radioMinDistanceBlocks > 0.0 && isWithinDistance(sender, receiver, radioMinDistanceBlocks)) {
            return;
        }

        // Far enough: suppress prox and deliver radio direct
        event.cancel();
        MicrophonePacket micPacket = event.getPacket();
        StaticSoundPacket staticPacket = micPacket.staticSoundPacketBuilder()
            .channelId(transmitting.voicechatChannelId())
            .build();

        api.sendStaticSoundPacketTo(receiverConnection, staticPacket);
    }

    private boolean isAllowedToHear(Player receiver, RadioChannel transmitting) {
        // Pirate eavesdrop: receiver listens to a random chosen channel while holding PPM with pirate radio.
        RadioChannel eavesdropTarget = plugin.getRadioState().getEavesdroppingChannel(receiver.getUniqueId());
        if (eavesdropTarget != null && eavesdropTarget == transmitting) {
            return receiver.hasPermission(RadioChannel.PIRACI_RANDOM.usePermission());
        }

        if (!receiver.hasPermission(transmitting.listenPermission())) {
            return false;
        }
        return plugin.getRadioState().hasHotbarRadio(receiver.getUniqueId(), transmitting);
    }

    private static boolean isWithinDistance(Player a, Player b, double blocks) {
        if (a.getWorld() == null || b.getWorld() == null) {
            return false;
        }
        if (!a.getWorld().equals(b.getWorld())) {
            return false;
        }
        double maxDistSq = blocks * blocks;
        return a.getLocation().distanceSquared(b.getLocation()) < maxDistSq;
    }

}
