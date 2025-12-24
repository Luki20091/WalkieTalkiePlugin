package me.Luki.WalkieTalkiePlugin.voice;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.ServerPlayer;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VoicechatBridge implements VoicechatPlugin, VoiceBridge {

    private final WalkieTalkiePlugin plugin;
    private volatile VoicechatServerApi serverApi;

    private final int microphoneEventPriority;
    private volatile boolean logHook;
    private volatile double radioMinDistanceBlocks;
    private volatile boolean sameWorldOnly;

    // DEV: SVC often doesn't emit a receiver==sender callback, so we do a best-effort loopback.
    // Rate-limited to avoid sending the loopback multiple times per microphone packet (event is per receiver).
    private final Map<UUID, Long> lastDevLoopbackAtNs = new ConcurrentHashMap<>();
    private static final long DEV_LOOPBACK_MIN_INTERVAL_NS = 15_000_000L; // ~15ms

    public VoicechatBridge(WalkieTalkiePlugin plugin) {
        this.plugin = plugin;

        this.microphoneEventPriority = plugin.getConfig().getInt("svc.microphoneEventPriority", 100);
        reloadFromConfig();
    }

    public void reloadFromConfig() {
        this.logHook = plugin.getConfig().getBoolean("svc.logHook", true);
        double minDistance = plugin.getConfig().getDouble("svc.radioMinDistanceBlocks", 20.0);
        this.radioMinDistanceBlocks = Math.max(0.0, minDistance);
        this.sameWorldOnly = plugin.getConfig().getBoolean("svc.sameWorldOnly", true);
    }

    @Override
    public boolean isHooked() {
        return serverApi != null;
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
        VoicechatBridge bridge = new VoicechatBridge(plugin);
        plugin.setVoicechatBridge(bridge);
        service.registerPlugin(bridge);
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
        plugin.debugMicEventCounted();
        VoicechatServerApi api = serverApi;
        if (api == null) {
            return;
        }

        VoicechatConnection senderConnection = event.getSenderConnection();
        if (senderConnection == null) {
            plugin.debugMicNoSender();
            return;
        }

        ServerPlayer senderPlayer = senderConnection.getPlayer();
        if (senderPlayer == null) {
            plugin.debugMicNoSender();
            return;
        }

        UUID senderUuid = senderPlayer.getUuid();
        if (senderUuid == null) {
            return;
        }

        // IMPORTANT: This event may be executed off the main server thread.
        // Do not touch Bukkit API here. Use cached state only.
        RadioChannel transmitting = plugin.getTransmitChannelCached(senderUuid);
        if (transmitting == null) {
            plugin.debugMicNoTransmitChannel();
            return; // normal behavior
        }

        // Sender is holding a radio (and has permission) and we received an audio packet.
        plugin.recordMicPacket(senderUuid);

        // DEV: always allow hearing yourself to verify that transmit detection + routing work.
        // This doesn't rely on SVC creating a receiver callback for the sender.
        if (plugin.isDevMode()) {
            long nowNs = System.nanoTime();
            Long lastNs = lastDevLoopbackAtNs.get(senderUuid);
            if (lastNs == null || (nowNs - lastNs) >= DEV_LOOPBACK_MIN_INTERVAL_NS) {
                lastDevLoopbackAtNs.put(senderUuid, nowNs);
                MicrophonePacket micPacket = event.getPacket();
                if (micPacket != null) {
                    StaticSoundPacket selfPacket = micPacket.staticSoundPacketBuilder()
                        .channelId(transmitting.voicechatChannelId())
                        .build();
                    api.sendStaticSoundPacketTo(senderConnection, selfPacket);
                }
            }
        }

        // SVC emits MicrophonePacketEvent per receiver. We can decide per receiver:
        // - close enough: allow proximity voice (do NOT cancel), do NOT send radio
        // - far enough: cancel proximity voice and send radio (direct)
        // - not allowed: cancel so they don't hear even prox while someone is on radio

        VoicechatConnection receiverConnection = event.getReceiverConnection();
        if (receiverConnection == null) {
            // Can't decide per-receiver; fall back to prox.
            plugin.debugMicNoReceiver();
            return;
        }

        ServerPlayer receiverPlayer = receiverConnection.getPlayer();
        if (receiverPlayer == null) {
            // Fail closed: while on radio, don't leak proximity.
            plugin.debugMicNoReceiver();
            event.cancel();
            return;
        }

        UUID receiverUuid = receiverPlayer.getUuid();
        if (receiverUuid == null) {
            // If we can't resolve receiver, safest is to prevent leaking proximity during radio mode.
            plugin.debugMicNoReceiver();
            event.cancel();
            return;
        }

        // DEV: allow hearing yourself to verify that radio routing works.
        // Some SVC setups include a receiver callback for the sender; if so we can loop back.
        if (receiverUuid.equals(senderUuid)) {
            if (!plugin.isDevMode()) {
                return;
            }

            event.cancel();
            MicrophonePacket micPacket = event.getPacket();
            StaticSoundPacket staticPacket = micPacket.staticSoundPacketBuilder()
                .channelId(transmitting.voicechatChannelId())
                .build();
            api.sendStaticSoundPacketTo(senderConnection, staticPacket);
            plugin.debugMicRadioSent();
            return;
        }

        if (sameWorldOnly) {
            ServerLevel senderLevel = senderPlayer.getServerLevel();
            ServerLevel receiverLevel = receiverPlayer.getServerLevel();
            Object senderWorld = senderLevel != null ? senderLevel.getServerLevel() : null;
            Object receiverWorld = receiverLevel != null ? receiverLevel.getServerLevel() : null;
            if (senderWorld == null || receiverWorld == null || senderWorld != receiverWorld) {
                plugin.debugMicCrossWorldCancelled();
                event.cancel();
                return;
            }
        }

        if (!plugin.canListenCached(receiverUuid, transmitting)) {
            plugin.debugMicNotAllowedCancelled();
            event.cancel();
            return;
        }

        // If receiver is closer than threshold, keep proximity voice (avoid overlap)
        if (radioMinDistanceBlocks > 0.0) {
            Position senderPos = senderPlayer.getPosition();
            Position receiverPos = receiverPlayer.getPosition();
            if (senderPos == null || receiverPos == null) {
                // Fail closed: while on radio, don't leak proximity.
                event.cancel();
                return;
            }

            if (isWithinDistance(senderPos, receiverPos, radioMinDistanceBlocks)) {
                plugin.debugMicWithinMinDistanceProx();
                return;
            }
        }

        // Far enough: suppress prox and deliver radio direct
        event.cancel();
        MicrophonePacket micPacket = event.getPacket();
        StaticSoundPacket staticPacket = micPacket.staticSoundPacketBuilder()
            .channelId(transmitting.voicechatChannelId())
            .build();

        api.sendStaticSoundPacketTo(receiverConnection, staticPacket);
        plugin.debugMicRadioSent();
    }

    private static boolean isWithinDistance(Position a, Position b, double blocks) {
        double maxDistSq = blocks * blocks;
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return (dx * dx + dy * dy + dz * dz) < maxDistSq;
    }

}
