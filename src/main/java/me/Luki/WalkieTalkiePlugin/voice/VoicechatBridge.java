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
import de.maxhenkel.voicechat.api.packets.LocationalSoundPacket;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;
import me.Luki.WalkieTalkiePlugin.WalkieTalkiePlugin;
import me.Luki.WalkieTalkiePlugin.radio.RadioChannel;
import org.bukkit.Bukkit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VoicechatBridge implements VoicechatPlugin, VoiceBridge {

    private final WalkieTalkiePlugin plugin;
    private volatile VoicechatServerApi serverApi;

    private final int microphoneEventPriority;
    private volatile boolean logHook;
    private volatile double radioMinDistanceBlocks;
    private volatile boolean sameWorldOnly;

    private volatile boolean radioSpatialEnabled;
    private volatile double radioSpatialOffsetBlocks;
    private volatile float radioSpatialDistanceBlocks;

    private volatile boolean hearSelfInDev;

    // DEV: SVC often doesn't emit a receiver==sender callback, so we do a best-effort loopback.
    // Rate-limited to avoid sending the loopback multiple times per microphone packet (event is per receiver).
    private final Map<UUID, Long> lastDevLoopbackAtNs = new ConcurrentHashMap<>();
    private static final long DEV_LOOPBACK_MIN_INTERVAL_NS = 15_000_000L; // ~15ms

    // If SVC fires MicrophonePacketEvent per receiver, we must avoid broadcasting multiple times per audio frame.
    private final Map<UUID, Long> lastBroadcastAtNs = new ConcurrentHashMap<>();
    private static final long BROADCAST_MIN_INTERVAL_NS = 15_000_000L; // ~15ms

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

        this.radioSpatialEnabled = plugin.getConfig().getBoolean("svc.radioSpatial.enabled", true);
        this.radioSpatialOffsetBlocks = Math.max(0.0, plugin.getConfig().getDouble("svc.radioSpatial.offsetBlocks", 5.0));
        double dist = plugin.getConfig().getDouble("svc.radioSpatial.distanceBlocks", 8.0);
        // Must be > offset, otherwise clients may never hear the packet.
        dist = Math.max(dist, this.radioSpatialOffsetBlocks + 1.0);
        this.radioSpatialDistanceBlocks = (float) Math.max(1.0, dist);

        // DEV-only debug: optionally allow hearing yourself when transmitting.
        this.hearSelfInDev = plugin.getConfig().getBoolean("svc.hearSelfInDev", false);
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

        // Enforce "busy line": at most one transmitter per channel.
        // This is off-thread safe (uses cached state only).
        Object senderWorldToken = null;
        try {
            ServerLevel senderLevel = senderPlayer.getServerLevel();
            senderWorldToken = senderLevel != null ? senderLevel.getServerLevel() : null;
        } catch (Throwable ignored) {
            senderWorldToken = null;
        }

        if (!plugin.tryAcquireBusyLine(senderUuid, transmitting, senderWorldToken)) {
            // While attempting radio transmit, do not leak proximity voice as a bypass.
            event.cancel();
            return;
        }

        // Sender is holding a radio (and has permission) and we received an audio packet.
        // Pass channel so the plugin can update visuals immediately without waiting for cache refresh.
        plugin.recordMicPacket(senderUuid, transmitting);

        // IMPORTANT: MicrophonePacketEvent is often fired only for in-range receivers.
        // To make radio work beyond proximity range, we broadcast the radio packet ourselves.
        // Rate-limited so we do it once per audio frame even if the event is per-receiver.
        boolean broadcasted = tryBroadcastRadio(api, senderPlayer, senderUuid, transmitting, event.getPacket());

        // DEV: always allow hearing yourself to verify that transmit detection + routing work.
        // This doesn't rely on SVC creating a receiver callback for the sender.
        if (plugin.isDevMode() && hearSelfInDev) {
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
                    // Also trigger LISTEN visuals for the sender (useful when testing with multiple radios in hotbar).
                    plugin.recordRadioReceive(senderUuid, transmitting);
                }
            }
        }

        // SVC emits MicrophonePacketEvent per receiver. We can decide per receiver:
        // - close enough: allow proximity voice (do NOT cancel), do NOT send radio
        // - far enough: cancel proximity voice and send radio (direct)
        // - not allowed: cancel so they don't hear even prox while someone is on radio

        VoicechatConnection receiverConnection = event.getReceiverConnection();
        if (receiverConnection == null) {
            // If this is a single global event (no receiver), suppress proximity while on radio.
            // The radio audio was already broadcast above.
            plugin.debugMicNoReceiver();
            if (broadcasted) {
                event.cancel();
            }
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
            if (!(plugin.isDevMode() && hearSelfInDev)) {
                return;
            }

            event.cancel();
            MicrophonePacket micPacket = event.getPacket();
            StaticSoundPacket staticPacket = micPacket.staticSoundPacketBuilder()
                .channelId(transmitting.voicechatChannelId())
                .build();
            api.sendStaticSoundPacketTo(senderConnection, staticPacket);
            plugin.recordRadioReceive(senderUuid, transmitting);
            plugin.debugMicRadioSent();
            return;
        }

        if (sameWorldOnly && !plugin.isRadioGlobalScope()) {
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
            plugin.debugMicNotAllowedCancelled(senderUuid, receiverUuid, transmitting);
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

        // Far enough: suppress prox.
        event.cancel();

        // If we already broadcasted this audio frame, avoid sending duplicates.
        if (!broadcasted) {
            MicrophonePacket micPacket = event.getPacket();
            if (micPacket != null) {
                sendRadioPacket(api, senderPlayer, receiverConnection, receiverPlayer, transmitting, micPacket);
                plugin.recordRadioReceive(receiverUuid, transmitting);
                plugin.debugMicRadioSent();
            }
        }
    }

    private boolean tryBroadcastRadio(VoicechatServerApi api, ServerPlayer senderPlayer, UUID senderUuid, RadioChannel transmitting, MicrophonePacket micPacket) {
        if (api == null || senderPlayer == null || senderUuid == null || transmitting == null || micPacket == null) {
            return false;
        }

        long nowNs = System.nanoTime();
        Long lastNs = lastBroadcastAtNs.get(senderUuid);
        if (lastNs != null && (nowNs - lastNs) < BROADCAST_MIN_INTERVAL_NS) {
            // Already broadcasted for this sender very recently (same audio frame).
            // Treat as handled so we don't fall back to per-receiver sending and duplicate audio.
            return true;
        }
        lastBroadcastAtNs.put(senderUuid, nowNs);

        Set<UUID> receivers = plugin.getOnlinePlayerUuids();
        if (receivers == null || receivers.isEmpty()) {
            return false;
        }

        try {
            if (plugin.isDevMode() || plugin.getConfig().getBoolean("debug.enabled", false)) {
                plugin.getLogger().info("[WT-DEBUG] Broadcast init sender=" + senderUuid + " channel=" + (transmitting == null ? "<null>" : transmitting.id()) + " onlineReceivers=" + receivers.size());
            }
        } catch (Throwable ignored) {
        }

        // Cache sender world token once.
        Object senderWorld = null;
        try {
            ServerLevel senderLevel = senderPlayer.getServerLevel();
            senderWorld = senderLevel != null ? senderLevel.getServerLevel() : null;
        } catch (Throwable ignored) {
            senderWorld = null;
        }

        for (UUID receiverUuid : receivers) {
            if (receiverUuid == null || receiverUuid.equals(senderUuid)) {
                continue;
            }

            VoicechatConnection receiverConnection = api.getConnectionOf(receiverUuid);
            if (receiverConnection == null) {
                try {
                    if (plugin.isDevMode() || plugin.getConfig().getBoolean("debug.enabled", false)) {
                        plugin.getLogger().info("[WT-DEBUG] broadcast:skip:noConnection:" + receiverUuid + ":" + transmitting.id() + " Skipping receiver (no connection).");
                    }
                } catch (Throwable ignored) {}
                continue;
            }

            ServerPlayer receiverPlayer;
            try {
                receiverPlayer = receiverConnection.getPlayer();
            } catch (Throwable ignored) {
                receiverPlayer = null;
            }
            if (receiverPlayer == null) {
                try {
                    if (plugin.isDevMode() || plugin.getConfig().getBoolean("debug.enabled", false)) {
                        plugin.getLogger().info("[WT-DEBUG] broadcast:skip:noPlayer:" + receiverUuid + ":" + transmitting.id() + " Skipping receiver (no player).");
                    }
                } catch (Throwable ignored) {}
                continue;
            }

            if (sameWorldOnly && !plugin.isRadioGlobalScope()) {
                Object receiverWorld = null;
                try {
                    ServerLevel receiverLevel = receiverPlayer.getServerLevel();
                    receiverWorld = receiverLevel != null ? receiverLevel.getServerLevel() : null;
                } catch (Throwable ignored) {
                    receiverWorld = null;
                }
                if (senderWorld == null || receiverWorld == null || senderWorld != receiverWorld) {
                    try {
                        if (plugin.isDevMode() || plugin.getConfig().getBoolean("debug.enabled", false)) {
                            plugin.getLogger().info("[WT-DEBUG] broadcast:skip:worldMismatch:" + receiverUuid + ":" + transmitting.id() + " Skipping receiver (different world).");
                        }
                    } catch (Throwable ignored) {}
                    continue;
                }
            }

            if (!plugin.canListenCached(receiverUuid, transmitting)) {
                try {
                    if (plugin.isDevMode() || plugin.getConfig().getBoolean("debug.enabled", false)) {
                        plugin.getLogger().info("[WT-DEBUG] broadcast:skip:notAllowed:" + receiverUuid + ":" + transmitting.id() + " Skipping receiver (canListenCached=false) :: " + plugin.debugExplainListenDecision(receiverUuid, transmitting));
                    }
                } catch (Throwable ignored) {}
                continue;
            }

            // If receiver is closer than threshold, keep proximity voice (avoid overlap)
            if (radioMinDistanceBlocks > 0.0) {
                Position senderPos = senderPlayer.getPosition();
                Position receiverPos = receiverPlayer.getPosition();
                if (senderPos != null && receiverPos != null && isWithinDistance(senderPos, receiverPos, radioMinDistanceBlocks)) {
                    try {
                        if (plugin.isDevMode() || plugin.getConfig().getBoolean("debug.enabled", false)) {
                            plugin.getLogger().info("[WT-DEBUG] broadcast:skip:proxCloser:" + receiverUuid + ":" + transmitting.id() + " Skipping receiver (closer than minDistance, keep prox).");
                        }
                    } catch (Throwable ignored) {}
                    continue;
                }
            }

            try {
                if (plugin.isDevMode() || plugin.getConfig().getBoolean("debug.enabled", false)) {
                    plugin.getLogger().info("[WT-DEBUG] broadcast:send:" + receiverUuid + ":" + transmitting.id() + " Sending radio packet to receiver=" + receiverUuid + " channel=" + transmitting.id());
                }
            } catch (Throwable ignored) {}
            sendRadioPacket(api, senderPlayer, receiverConnection, receiverPlayer, transmitting, micPacket);
            plugin.recordRadioReceive(receiverUuid, transmitting);
            plugin.debugMicRadioSent();
        }

        return true;
    }

    private void sendRadioPacket(VoicechatServerApi api, ServerPlayer senderPlayer, VoicechatConnection receiverConnection, ServerPlayer receiverPlayer, RadioChannel transmitting, MicrophonePacket micPacket) {
        if (api == null || receiverConnection == null || transmitting == null || micPacket == null) {
            return;
        }

        if (radioSpatialEnabled) {
            Position senderPos = senderPlayer != null ? senderPlayer.getPosition() : null;
            Position receiverPos = receiverPlayer != null ? receiverPlayer.getPosition() : null;
            if (senderPos != null && receiverPos != null) {
                Position perceived = offsetTowards(api, receiverPos, senderPos, radioSpatialOffsetBlocks);
                LocationalSoundPacket locPacket = micPacket.locationalSoundPacketBuilder()
                    .channelId(transmitting.voicechatChannelId())
                    .position(perceived)
                    .distance(radioSpatialDistanceBlocks)
                    .build();
                api.sendLocationalSoundPacketTo(receiverConnection, locPacket);
                return;
            }
        }

        StaticSoundPacket staticPacket = micPacket.staticSoundPacketBuilder()
            .channelId(transmitting.voicechatChannelId())
            .build();
        api.sendStaticSoundPacketTo(receiverConnection, staticPacket);
    }

    private static Position offsetTowards(VoicechatServerApi api, Position from, Position towards, double blocks) {
        if (api == null || from == null || towards == null || blocks <= 0.0) {
            return from;
        }

        double dx = towards.getX() - from.getX();
        double dy = towards.getY() - from.getY();
        double dz = towards.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6) {
            return from;
        }

        double ux = dx / len;
        double uy = dy / len;
        double uz = dz / len;

        return api.createPosition(
            from.getX() + ux * blocks,
            from.getY() + uy * blocks,
            from.getZ() + uz * blocks
        );
    }

    private static boolean isWithinDistance(Position a, Position b, double blocks) {
        double maxDistSq = blocks * blocks;
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return (dx * dx + dy * dy + dz * dz) < maxDistSq;
    }

}
