package me.Luki.WalkieTalkiePlugin;

import me.Luki.WalkieTalkiePlugin.listeners.RadioListeners;
import me.Luki.WalkieTalkiePlugin.listeners.VoicechatAutoHookListener;
import me.Luki.WalkieTalkiePlugin.items.ItemsAdderBridge;
import me.Luki.WalkieTalkiePlugin.radio.RadioChannel;
import me.Luki.WalkieTalkiePlugin.radio.RadioItemUtil;
import me.Luki.WalkieTalkiePlugin.radio.RadioRegistry;
import me.Luki.WalkieTalkiePlugin.radio.RadioState;
import me.Luki.WalkieTalkiePlugin.voice.VoiceBridge;
import me.Luki.WalkieTalkiePlugin.commands.WalkieTalkieCommand;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static me.Luki.WalkieTalkiePlugin.radio.RadioItemKeys.channelKey;

public final class WalkieTalkiePlugin extends JavaPlugin {

    private enum RadioVisualStage {
        OFF(0),
        TRANSMIT(1),
        LISTEN(2);

        final int offset;

        RadioVisualStage(int offset) {
            this.offset = offset;
        }
    }

    private enum RadioScope {
        WORLD,
        GLOBAL
    }

    private static final Object GLOBAL_WORLD_TOKEN = new Object();

    private RadioState radioState;
    private RadioRegistry radioRegistry;
    private RadioItemUtil itemUtil;

    private ItemsAdderBridge itemsAdder;
    private NamespacedKey radioChannelPdcKey;

    private final Map<UUID, Long> lastMicPacketAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> transmitUiActive = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> transmitClearTaskByPlayer = new ConcurrentHashMap<>();

    private final Map<UUID, Long> lastRadioReceiveAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, RadioChannel> lastRadioReceiveChannel = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> listenClearTaskByPlayer = new ConcurrentHashMap<>();

    private volatile VoiceBridge voicechatBridge;

    private static final Map<String, Sound> SOUND_LOOKUP = new ConcurrentHashMap<>();

    private final Map<UUID, RadioChannel> transmitChannelByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, RadioChannel> preferredListenChannelByPlayer = new ConcurrentHashMap<>();

    private final Map<UUID, PermissionSnapshot> permissionSnapshots = new ConcurrentHashMap<>();

    private static final class BusyLine {
        final UUID owner;
        final long lastPacketAtMs;

        private BusyLine(UUID owner, long lastPacketAtMs) {
            this.owner = owner;
            this.lastPacketAtMs = lastPacketAtMs;
        }
    }

    private static final class BusyLineKey {
        final Object worldToken;
        final RadioChannel channel;
        final int hash;

        private BusyLineKey(Object worldToken, RadioChannel channel) {
            this.worldToken = worldToken;
            this.channel = channel;
            int worldHash = worldToken == null ? 0 : System.identityHashCode(worldToken);
            this.hash = (worldHash * 31) + (channel == null ? 0 : channel.hashCode());
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof BusyLineKey other)) {
                return false;
            }
            return this.channel == other.channel && this.worldToken == other.worldToken;
        }
    }

    private final Map<BusyLineKey, BusyLine> busyLineByChannel = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBusyHintAtMs = new ConcurrentHashMap<>();
    private volatile boolean busyLineEnabled;
    private volatile long busyLineTimeoutMs;
    private volatile long busyLineHintCooldownMs;

    private volatile RadioScope radioScope = RadioScope.WORLD;

    private volatile boolean listenVisualsEnabled;
    private volatile long listenVisualActiveForMs;
    private volatile long transmitVisualActiveForMs;

    public boolean isRadioGlobalScope() {
        return radioScope == RadioScope.GLOBAL;
    }

    private final Map<String, Long> debugLastLogAtMs = new ConcurrentHashMap<>();
    private volatile boolean debugEnabled;
    private volatile boolean devMode;
    private volatile long debugLogIntervalMs;

    private final AtomicLong dbgMicEvents = new AtomicLong();
    private final AtomicLong dbgMicNoSender = new AtomicLong();
    private final AtomicLong dbgMicNoReceiver = new AtomicLong();
    private final AtomicLong dbgMicNoTransmitChannel = new AtomicLong();
    private final AtomicLong dbgMicCrossWorldCancelled = new AtomicLong();
    private final AtomicLong dbgMicNotAllowedCancelled = new AtomicLong();
    private final AtomicLong dbgMicWithinMinDistanceProx = new AtomicLong();
    private final AtomicLong dbgMicRadioSent = new AtomicLong();

    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    // Repeating loops (hints + filter pulse)
    private BukkitTask pttRequiredHintTask;
    private BukkitTask filterLoopTask;
    private final Map<UUID, Long> lastPttRequiredHintAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> holdToTalkActive = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        String version = "?";
        try {
            version = String.valueOf(getPluginMeta().getVersion());
        } catch (Throwable ignored) {
            // best-effort
        }

        getLogger().info("WalkieTalkiePlugin v" + version + " is starting...");

        try {
            saveDefaultConfig();

            loadDebugConfig();

            NamespacedKey base = new NamespacedKey(this, "walkietalkie");
            this.radioChannelPdcKey = channelKey(base);
            this.itemsAdder = new ItemsAdderBridge();
            radioRegistry = new RadioRegistry(getConfig().getConfigurationSection("radios"));
            itemUtil = new RadioItemUtil(radioChannelPdcKey, radioRegistry, this.itemsAdder);
            radioState = new RadioState(itemUtil);

            getServer().getPluginManager().registerEvents(new RadioListeners(this, itemUtil), this);
            getServer().getPluginManager().registerEvents(new VoicechatAutoHookListener(this), this);

            // Populate caches for already-online players (e.g. plugin reload while players are online)
            getServer().getOnlinePlayers().forEach(p -> {
                radioState.refreshHotbar(p);
                refreshTransmitCache(p);
                refreshPermissionCache(p);
                refreshPreferredListenChannel(p);
            });

            // Crafting is handled by ItemsAdder (recipes + permissions).

            // Transmit activation: hold radio in main hand + use Simple Voice Chat PTT.
            // (No additional hold-to-talk listener needed.)
            tryRegisterVoicechatIntegrationIfNeeded();

            // Optional repeating loops (hints + filter pulse)
            restartLoopTasksFromConfig();

            var command = getCommand("walkietalkie");
            if (command != null) {
                WalkieTalkieCommand executor = new WalkieTalkieCommand(this);
                command.setExecutor(executor);
                command.setTabCompleter(executor);
            }

            getLogger().info("WalkieTalkiePlugin v" + version + " enabled successfully.");
        } catch (Throwable t) {
            getLogger().severe("WalkieTalkiePlugin failed to enable. Disabling plugin...");
            t.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        String version = "?";
        try {
            version = String.valueOf(getPluginMeta().getVersion());
        } catch (Throwable ignored) {
            // best-effort
        }
        getLogger().info("WalkieTalkiePlugin v" + version + " is disabling...");

        // Clear all state to avoid stuck transmit flags after reload/disable
        if (radioState != null) {
            // Best-effort: clear all online players
            getServer().getOnlinePlayers().forEach(p -> radioState.clear(p.getUniqueId()));
        }

        // Best-effort: revert any "talking" variant held in main hand
        // so players don't get stuck with the transmitting texture after reload/stop.
        getServer().getOnlinePlayers().forEach(p -> normalizeTalkingVariantInMainHand(p, true));

        lastMicPacketAtMs.clear();
        transmitUiActive.clear();
        transmitChannelByPlayer.clear();
        permissionSnapshots.clear();
        lastRadioReceiveAtMs.clear();
        lastRadioReceiveChannel.clear();

        transmitClearTaskByPlayer.values().forEach(t -> {
            try {
                t.cancel();
            } catch (Throwable ignored) {
            }
        });
        transmitClearTaskByPlayer.clear();

        listenClearTaskByPlayer.values().forEach(t -> {
            try {
                t.cancel();
            } catch (Throwable ignored) {
            }
        });
        listenClearTaskByPlayer.clear();
        busyLineByChannel.clear();
        lastBusyHintAtMs.clear();

        stopLoopTasks();

        holdToTalkActive.clear();

        getLogger().info("WalkieTalkiePlugin v" + version + " disabled.");
    }

    public void setVoicechatBridge(VoiceBridge bridge) {
        this.voicechatBridge = bridge;
    }

    public VoiceBridge getVoicechatBridge() {
        return voicechatBridge;
    }

    public void reloadPlugin() {
        reloadConfig();

        loadDebugConfig();

        stopLoopTasks();

        if (radioRegistry != null) {
            radioRegistry.reload(getConfig().getConfigurationSection("radios"));
        }

        lastRadioReceiveAtMs.clear();
        lastRadioReceiveChannel.clear();
        busyLineByChannel.clear();
        lastBusyHintAtMs.clear();

        VoiceBridge bridge = voicechatBridge;
        if (bridge != null) {
            bridge.reloadFromConfig();
        }

        // Clear UI state to avoid stuck "transmitting" indicators
        transmitUiActive.clear();

        // Best-effort: stop any long filter sound for online players on reload.
        getServer().getOnlinePlayers().forEach(this::stopFilterLongSound);

        transmitClearTaskByPlayer.values().forEach(t -> {
            try {
                t.cancel();
            } catch (Throwable ignored) {
            }
        });
        transmitClearTaskByPlayer.clear();

        listenClearTaskByPlayer.values().forEach(t -> {
            try {
                t.cancel();
            } catch (Throwable ignored) {
            }
        });
        listenClearTaskByPlayer.clear();

        // Refresh caches for online players
        getServer().getOnlinePlayers().forEach(p -> {
            if (radioState != null) {
                radioState.refreshHotbar(p);
            }
            refreshTransmitCache(p);
            refreshPermissionCache(p);
            refreshPreferredListenChannel(p);
        });

        restartLoopTasksFromConfig();
    }

    private void stopLoopTasks() {
        BukkitTask hint = pttRequiredHintTask;
        pttRequiredHintTask = null;
        if (hint != null) {
            try {
                hint.cancel();
            } catch (Throwable ignored) {
            }
        }

        BukkitTask loop = filterLoopTask;
        filterLoopTask = null;
        if (loop != null) {
            try {
                loop.cancel();
            } catch (Throwable ignored) {
            }
        }

        lastPttRequiredHintAtMs.clear();
    }

    private String resolveFilterLoopBasePath() {
        return getConfig().isConfigurationSection("hints.filterLoop")
                ? "hints.filterLoop"
                : "sounds.filterLoop";
    }

    /**
     * Marks whether the player is currently holding-to-talk (PPM/USE_ITEM) with a transmit-capable radio.
     * This is used by filterLoop mode HOLD to align start/stop exactly to the user's action.
     */
    public void setHoldToTalkActive(UUID playerUuid, boolean active) {
        if (playerUuid == null) {
            return;
        }
        if (active) {
            holdToTalkActive.put(playerUuid, true);
        } else {
            holdToTalkActive.remove(playerUuid);
        }
    }

    public boolean isHoldToTalkActive(UUID playerUuid) {
        return playerUuid != null && Boolean.TRUE.equals(holdToTalkActive.get(playerUuid));
    }

    /**
     * If filterLoop is enabled and in HOLD mode, play one pulse immediately.
     * This removes the perceived delay caused by the periodic scheduler tick alignment.
     */
    public void maybePlayFilterLoopPulseNow(Player player) {
        if (player == null) {
            return;
        }
        String basePath = resolveFilterLoopBasePath();
        if (!getConfig().getBoolean(basePath + ".enabled", true)) {
            return;
        }

        String modeRaw = String.valueOf(getConfig().getString(basePath + ".mode", "PTT"));
        String mode = modeRaw.trim().toUpperCase(Locale.ROOT);
        if (!mode.equals("HOLD") && !mode.equals("HOLD_TO_TALK") && !mode.equals("USE_ITEM")) {
            return;
        }

        playConfiguredSoundPulse(player, basePath);
    }

    /**
     * Plays one filterLoop pulse immediately for PTT mode.
     * Used to avoid waiting up to everyTicks before the first pulse after pressing SVC PTT.
     */
    private void maybePlayFilterLoopPulseNowOnMicStart(Player player) {
        if (player == null) {
            return;
        }
        String basePath = resolveFilterLoopBasePath();
        if (!getConfig().getBoolean(basePath + ".enabled", true)) {
            return;
        }

        String modeRaw = String.valueOf(getConfig().getString(basePath + ".mode", "PTT"));
        String mode = modeRaw.trim().toUpperCase(Locale.ROOT);
        if (!mode.equals("PTT")) {
            return;
        }

        playConfiguredSoundPulse(player, basePath);
    }

    private void restartLoopTasksFromConfig() {
        // Always cancel first to avoid duplicates (enable/reload safety)
        stopLoopTasks();

        // 1) PTT required hint loop
        boolean pttHintEnabled = getConfig().getBoolean("hints.pttRequired.enabled", true);
        if (pttHintEnabled) {
            long afterMs = Math.max(0L, getConfig().getLong("hints.pttRequired.afterMs", 2000L));
            int everyTicks = Math.max(1, getConfig().getInt("hints.pttRequired.checkEveryTicks", 20));
            int cooldownTicks = Math.max(1, getConfig().getInt("hints.pttRequired.cooldownTicks", 100));
            long cooldownMs = cooldownTicks * 50L;

            pttRequiredHintTask = getServer().getScheduler().runTaskTimer(this, () -> {
                long now = System.currentTimeMillis();

                for (Player player : getServer().getOnlinePlayers()) {
                    try {
                        UUID uuid = player.getUniqueId();

                        // Only show when the player is holding a transmit-capable radio.
                        RadioChannel txChannel = getTransmitChannelFast(player);
                        if (txChannel == null) {
                            continue;
                        }

                        long lastMic = lastMicPacketAtMs.getOrDefault(uuid, 0L);
                        boolean shouldHint = (lastMic == 0L) || (now - lastMic >= afterMs);
                        if (!shouldHint) {
                            continue;
                        }

                        long lastShown = lastPttRequiredHintAtMs.getOrDefault(uuid, 0L);
                        if (lastShown > 0L && now - lastShown < cooldownMs) {
                            continue;
                        }

                        lastPttRequiredHintAtMs.put(uuid, now);
                        playConfiguredNotification(player, "hints.pttRequired");
                    } catch (Throwable t) {
                        debugLog("loop:pttHint", "pttRequired hint loop error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                }
            }, everyTicks, everyTicks);
        }

        // 2) Filter loop pulse (sound)
        String filterBasePath = resolveFilterLoopBasePath();
        boolean filterEnabled = getConfig().getBoolean(filterBasePath + ".enabled", true);
        if (filterEnabled) {
            String modeRaw = String.valueOf(getConfig().getString(filterBasePath + ".mode", "PTT"));
            String mode = modeRaw.trim().toUpperCase(Locale.ROOT);
            boolean always = mode.equals("ALWAYS");
            boolean hold = mode.equals("HOLD") || mode.equals("HOLD_TO_TALK") || mode.equals("USE_ITEM");
            long activeForMs = Math.max(0L, getConfig().getLong(filterBasePath + ".activeForMs", 350L));
            int everyTicks = Math.max(1, getConfig().getInt(filterBasePath + ".everyTicks", 20));

            filterLoopTask = getServer().getScheduler().runTaskTimer(this, () -> {
                long now = System.currentTimeMillis();

                for (Player player : getServer().getOnlinePlayers()) {
                    try {
                        UUID uuid = player.getUniqueId();

                        // Only play when holding a transmit-capable radio.
                        RadioChannel txChannel = getTransmitChannelFast(player);
                        if (txChannel == null) {
                            continue;
                        }

                        if (!always) {
                            if (hold) {
                                if (!isHoldToTalkActive(uuid)) {
                                    continue;
                                }
                            } else {
                            long lastMic = lastMicPacketAtMs.getOrDefault(uuid, 0L);
                            if (lastMic <= 0L || now - lastMic > activeForMs) {
                                continue;
                            }
                            }
                        }

                        playConfiguredSoundPulse(player, filterBasePath);
                    } catch (Throwable t) {
                        debugLog("loop:filterLoop", "filterLoop error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                }
            }, 0L, everyTicks);
        }
    }

    private void playConfiguredSoundPulse(Player player, String basePath) {
        if (player == null || basePath == null || basePath.isBlank()) {
            return;
        }

        // New format: <basePath>.sounds: list of {sound, volume, pitch}
        try {
            var list = getConfig().getMapList(basePath + ".sounds");
            if (list != null && !list.isEmpty()) {
                for (var entry : list) {
                    if (!(entry instanceof java.util.Map<?, ?> map)) {
                        continue;
                    }
                    Object sObj = map.get("sound");
                    String soundName = sObj == null ? "" : String.valueOf(sObj);
                    if (soundName.isBlank()) {
                        continue;
                    }

                    double volume = 1.0;
                    double pitch = 1.0;
                    Object vObj = map.get("volume");
                    Object pObj = map.get("pitch");
                    if (vObj instanceof Number n) {
                        volume = n.doubleValue();
                    } else if (vObj != null) {
                        try { volume = Double.parseDouble(String.valueOf(vObj)); } catch (Exception ignored) {}
                    }
                    if (pObj instanceof Number n) {
                        pitch = n.doubleValue();
                    } else if (pObj != null) {
                        try { pitch = Double.parseDouble(String.valueOf(pObj)); } catch (Exception ignored) {}
                    }

                    Sound sound = resolveSound(soundName);
                    if (sound == null) {
                        debugLog("sound:" + basePath, "Invalid sound in config: " + basePath + ".sounds[].sound='" + soundName + "'");
                        // Best-effort: stop any long filter sound for online players.
                        getServer().getOnlinePlayers().forEach(this::stopFilterLongSound);
                        continue;
                    }

                    player.playSound(player.getLocation(), sound, (float) volume, (float) pitch);
                }
                return;
            }
        } catch (Throwable ignored) {
            // best-effort; fall back to single sound
        }

        // Legacy format: <basePath>.sound/volume/pitch
        playFeedbackSound(player, basePath);
    }

    public RadioChannel getTransmitChannelCached(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }
        return transmitChannelByPlayer.get(playerUuid);
    }

    public boolean canListenCached(UUID receiverUuid, RadioChannel transmitting) {
        if (receiverUuid == null || transmitting == null) {
            return false;
        }

        // If multiple radios are in hotbar, we only listen to a single selected channel:
        // the first radio found in hotbar excluding the main-hand slot.
        RadioChannel preferred = preferredListenChannelByPlayer.get(receiverUuid);
        if (preferred != null && preferred != transmitting) {
            // Pirate random eavesdrop is handled below.
            return false;
        }

        PermissionSnapshot snapshot = permissionSnapshots.get(receiverUuid);
        if (snapshot == null) {
            return false;
        }

        // Pirate eavesdrop: receiver listens to a random chosen channel while holding pirate radio.
        RadioChannel eavesdropTarget = radioState != null ? radioState.getEavesdroppingChannel(receiverUuid) : null;
        if (eavesdropTarget != null && eavesdropTarget == transmitting) {
            return snapshot.hasPirateRandomUse;
        }

        if (!snapshot.hasListenPermission(transmitting)) {
            return false;
        }
        return radioState != null && radioState.hasHotbarRadio(receiverUuid, transmitting);
    }

    public void refreshPreferredListenChannel(Player player) {
        if (player == null || itemUtil == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        int heldSlot = player.getInventory().getHeldItemSlot();
        RadioChannel selected = null;

        for (int slot = 0; slot < 9; slot++) {
            if (slot == heldSlot) {
                continue;
            }
            ItemStack stack = player.getInventory().getItem(slot);
            RadioChannel channel = itemUtil.getChannel(stack);
            if (channel == null) {
                continue;
            }
            // Only select channels the player can actually listen to.
            if (!player.hasPermission(channel.listenPermission())) {
                continue;
            }
            selected = channel;
            break;
        }

        if (selected == null) {
            preferredListenChannelByPlayer.remove(uuid);
        } else {
            preferredListenChannelByPlayer.put(uuid, selected);
        }
    }

    public void clearPreferredListenChannel(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        preferredListenChannelByPlayer.remove(playerUuid);
    }

    public void refreshPermissionCache(Player player) {
        if (player == null) {
            return;
        }

        int listenMask = 0;
        for (RadioChannel c : RadioChannel.values()) {
            if (player.hasPermission(c.listenPermission())) {
                listenMask |= (1 << c.ordinal());
            }
        }
        boolean pirateRandomUse = player.hasPermission(RadioChannel.PIRACI_RANDOM.usePermission());
        permissionSnapshots.put(player.getUniqueId(), new PermissionSnapshot(listenMask, pirateRandomUse));
    }

    public void clearPermissionCache(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        permissionSnapshots.remove(playerUuid);
    }

    public static final class PermissionSnapshot {
        private final int listenMask;
        public final boolean hasPirateRandomUse;

        private PermissionSnapshot(int listenMask, boolean hasPirateRandomUse) {
            this.listenMask = listenMask;
            this.hasPirateRandomUse = hasPirateRandomUse;
        }
        public boolean hasListenPermission(RadioChannel channel) {
            if (channel == null) {
                return false;
            }
            return (listenMask & (1 << channel.ordinal())) != 0;
        }
    }

    public void recordMicPacket(UUID senderUuid, RadioChannel channel) {
        if (senderUuid == null) {
            return;
        }

        long now = System.currentTimeMillis();
        lastMicPacketAtMs.put(senderUuid, now);

        // Keep caches warm even if some listener missed an inventory event.
        if (channel != null) {
            transmitChannelByPlayer.put(senderUuid, channel);
        }

        // Remove perceived "first PTT lag": don't wait for the periodic UI task tick.
        // This method may be called off-thread and also multiple times per packet (SVC is per receiver),
        // so we only trigger on the transition from inactive -> active.
        boolean wasActive = Boolean.TRUE.equals(transmitUiActive.put(senderUuid, true));
        if (!wasActive) {
            UUID uuid = senderUuid;
            RadioChannel txChannel = channel;
            runNextTick(() -> {
                Player player = getServer().getPlayer(uuid);
                if (player == null) {
                    return;
                }
                playTransmitStartSound(player);
                playConfiguredNotification(player, "notifications.transmit.start");
                // Start filterLoop immediately on mic start (PTT mode)
                maybePlayFilterLoopPulseNowOnMicStart(player);
                if (getConfig().getBoolean("visuals.transmit.enabled", true) && txChannel != null) {
                    applyHotbarVisuals(player, txChannel, true, System.currentTimeMillis());
                }
            });
        }

        // Ensure a one-shot clear is scheduled (main thread).
        UUID uuid = senderUuid;
        runNextTick(() -> ensureTransmitClearScheduled(uuid));
    }

    public void recordMicPacket(UUID senderUuid) {
        recordMicPacket(senderUuid, null);
    }

    // Called from VoicechatBridge (may be off-thread)
    public void recordRadioReceive(UUID receiverUuid, RadioChannel channel) {
        if (receiverUuid == null || channel == null) {
            return;
        }
        long now = System.currentTimeMillis();
        lastRadioReceiveAtMs.put(receiverUuid, now);
        lastRadioReceiveChannel.put(receiverUuid, channel);

        UUID uuid = receiverUuid;
        runNextTick(() -> {
            Player player = getServer().getPlayer(uuid);
            if (player == null) {
                return;
            }
            // applyHotbarVisuals reads lastRadioReceive* maps, so it will choose LISTEN immediately.
            RadioChannel txChannel = transmitChannelByPlayer.get(uuid);
            boolean txActive = Boolean.TRUE.equals(transmitUiActive.get(uuid));
            applyHotbarVisuals(player, txChannel, txActive, System.currentTimeMillis());
        });

        runNextTick(() -> ensureListenClearScheduled(uuid));
    }

    // Called from VoicechatBridge (may be off-thread)
    public boolean tryAcquireBusyLine(UUID senderUuid, RadioChannel channel, Object worldToken) {
        if (!busyLineEnabled) {
            return true;
        }
        if (senderUuid == null || channel == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        Object token = (radioScope == RadioScope.GLOBAL) ? GLOBAL_WORLD_TOKEN : worldToken;
        BusyLineKey key = new BusyLineKey(token, channel);
        final boolean[] acquired = new boolean[]{false};
        busyLineByChannel.compute(key, (k, cur) -> {
            if (cur == null || senderUuid.equals(cur.owner) || (now - cur.lastPacketAtMs) > busyLineTimeoutMs) {
                acquired[0] = true;
                return new BusyLine(senderUuid, now);
            }
            acquired[0] = false;
            return cur;
        });

        if (!acquired[0]) {
            maybeNotifyBusyLine(senderUuid, now);
        }
        return acquired[0];
    }

    public void releaseBusyLineIfOwned(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }

        busyLineByChannel.entrySet().removeIf(e -> e.getValue() != null && playerUuid.equals(e.getValue().owner));
        lastBusyHintAtMs.remove(playerUuid);
    }

    private void maybeNotifyBusyLine(UUID senderUuid, long nowMs) {
        Long last = lastBusyHintAtMs.get(senderUuid);
        if (last != null && (nowMs - last) < busyLineHintCooldownMs) {
            return;
        }
        lastBusyHintAtMs.put(senderUuid, nowMs);
        runNextTick(() -> {
            Player p = getServer().getPlayer(senderUuid);
            if (p == null) {
                return;
            }
            playFeedbackSound(p, "sounds.busy");
            playConfiguredNotification(p, "notifications.transmit.busy");
        });
    }

    private void loadDebugConfig() {
        this.devMode = getConfig().getBoolean("dev", false);
        this.debugEnabled = devMode || getConfig().getBoolean("debug.enabled", false);
        long interval = getConfig().getLong("debug.logIntervalMs", 5000L);
        this.debugLogIntervalMs = Math.max(250L, interval);

        String scopeRaw = String.valueOf(getConfig().getString("radio.scope", "WORLD"));
        String scope = scopeRaw.trim().toUpperCase(Locale.ROOT);
        if (scope.equals("GLOBAL")) {
            this.radioScope = RadioScope.GLOBAL;
        } else {
            this.radioScope = RadioScope.WORLD;
        }

        this.busyLineEnabled = getConfig().getBoolean("radio.busyLine.enabled", true);
        this.busyLineTimeoutMs = Math.max(100L, getConfig().getLong("radio.busyLine.timeoutMs", 350L));
        this.busyLineHintCooldownMs = Math.max(100L, getConfig().getLong("radio.busyLine.hintCooldownMs", 750L));

        this.listenVisualsEnabled = getConfig().getBoolean("visuals.listen.enabled", true);
        this.listenVisualActiveForMs = Math.max(100L, getConfig().getLong("visuals.listen.activeForMs", 300L));

        // How long to keep TRANSMIT visuals after last mic packet.
        // Backwards-compatible fallback to the old filterLoop timing.
        long txMs = getConfig().getLong("visuals.transmit.activeForMs", -1L);
        if (txMs <= 0L) {
            txMs = Math.max(0L, getConfig().getLong("sounds.filterLoop.activeForMs", 350L));
        }
        this.transmitVisualActiveForMs = Math.max(150L, txMs);
    }

    private void ensureTransmitClearScheduled(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        if (!Boolean.TRUE.equals(transmitUiActive.get(playerUuid))) {
            return;
        }
        if (transmitClearTaskByPlayer.containsKey(playerUuid)) {
            return;
        }
        scheduleTransmitClearCheck(playerUuid, transmitVisualActiveForMs);
    }

    private void scheduleTransmitClearCheck(UUID playerUuid, long delayMs) {
        long delayTicks = Math.max(1L, (delayMs + 49L) / 50L);
        BukkitTask task = getServer().getScheduler().runTaskLater(this, () -> {
            transmitClearTaskByPlayer.remove(playerUuid);

            long last = lastMicPacketAtMs.getOrDefault(playerUuid, 0L);
            long now = System.currentTimeMillis();
            long dueAt = last + transmitVisualActiveForMs;
            if (last > 0L && now < dueAt) {
                scheduleTransmitClearCheck(playerUuid, dueAt - now);
                return;
            }

            boolean wasActive = Boolean.TRUE.equals(transmitUiActive.get(playerUuid));
            transmitUiActive.put(playerUuid, false);

            Player player = getServer().getPlayer(playerUuid);
            if (player != null) {
                if (wasActive) {
                    stopFilterLongSound(player);
                    playFeedbackSound(player, "sounds.stop");
                    playConfiguredNotification(player, "notifications.transmit.stop");
                }
                RadioChannel txChannel = transmitChannelByPlayer.get(playerUuid);
                applyHotbarVisuals(player, txChannel, false, System.currentTimeMillis());
            }
        }, delayTicks);
        transmitClearTaskByPlayer.put(playerUuid, task);
    }

    private void ensureListenClearScheduled(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        if (!listenVisualsEnabled) {
            return;
        }
        if (listenClearTaskByPlayer.containsKey(playerUuid)) {
            return;
        }
        scheduleListenClearCheck(playerUuid, listenVisualActiveForMs);
    }

    private void scheduleListenClearCheck(UUID playerUuid, long delayMs) {
        long delayTicks = Math.max(1L, (delayMs + 49L) / 50L);
        BukkitTask task = getServer().getScheduler().runTaskLater(this, () -> {
            listenClearTaskByPlayer.remove(playerUuid);

            long last = lastRadioReceiveAtMs.getOrDefault(playerUuid, 0L);
            long now = System.currentTimeMillis();
            long dueAt = last + listenVisualActiveForMs;
            if (last > 0L && now < dueAt) {
                scheduleListenClearCheck(playerUuid, dueAt - now);
                return;
            }

            lastRadioReceiveAtMs.remove(playerUuid);
            lastRadioReceiveChannel.remove(playerUuid);

            Player player = getServer().getPlayer(playerUuid);
            if (player != null) {
                RadioChannel txChannel = transmitChannelByPlayer.get(playerUuid);
                boolean txActive = Boolean.TRUE.equals(transmitUiActive.get(playerUuid));
                applyHotbarVisuals(player, txChannel, txActive, System.currentTimeMillis());
            }
        }, delayTicks);
        listenClearTaskByPlayer.put(playerUuid, task);
    }

    // Safe to call off-thread (reads a volatile)
    public boolean isDevMode() {
        return devMode;
    }

    private void debugLog(String key, String message) {
        if (!debugEnabled) {
            return;
        }

        long now = System.currentTimeMillis();
        Long last = debugLastLogAtMs.get(key);
        if (last != null && now - last < debugLogIntervalMs) {
            return;
        }
        debugLastLogAtMs.put(key, now);
        getLogger().info("[WT-DEBUG] " + message);
    }

    // Called from VoicechatBridge (may be off-thread)
    public void debugMicEventCounted() {
        dbgMicEvents.incrementAndGet();
    }

    // Called from VoicechatBridge (may be off-thread)
    public void debugMicNoSender() {
        dbgMicNoSender.incrementAndGet();
    }

    // Called from VoicechatBridge (may be off-thread)
    public void debugMicNoReceiver() {
        dbgMicNoReceiver.incrementAndGet();
    }

    // Called from VoicechatBridge (may be off-thread)
    public void debugMicNoTransmitChannel() {
        dbgMicNoTransmitChannel.incrementAndGet();
    }

    // Called from VoicechatBridge (may be off-thread)
    public void debugMicCrossWorldCancelled() {
        dbgMicCrossWorldCancelled.incrementAndGet();
    }

    // Called from VoicechatBridge (may be off-thread)
    public void debugMicNotAllowedCancelled() {
        dbgMicNotAllowedCancelled.incrementAndGet();
    }

    // Called from VoicechatBridge (may be off-thread)
    public void debugMicWithinMinDistanceProx() {
        dbgMicWithinMinDistanceProx.incrementAndGet();
    }

    // Called from VoicechatBridge (may be off-thread)
    public void debugMicRadioSent() {
        dbgMicRadioSent.incrementAndGet();
    }



    private void applyHotbarVisuals(Player player, RadioChannel txChannel, boolean txActive, long nowMs) {
        if (player == null || itemUtil == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        boolean listenActive = false;
        RadioChannel listenChannel = null;
        if (listenVisualsEnabled) {
            long lastHeard = lastRadioReceiveAtMs.getOrDefault(uuid, 0L);
            if (lastHeard > 0L && (nowMs - lastHeard) <= listenVisualActiveForMs) {
                listenActive = true;
                listenChannel = lastRadioReceiveChannel.get(uuid);
            }
        }

        int heldSlot = player.getInventory().getHeldItemSlot();
        boolean anyChanged = false;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            RadioChannel itemChannel = itemUtil.getChannel(stack);
            if (itemChannel == null) {
                continue;
            }

            RadioVisualStage desired;
            if (txActive && txChannel != null && slot == heldSlot && itemChannel == txChannel) {
                desired = RadioVisualStage.TRANSMIT;
            } else if (listenActive && listenChannel != null && itemChannel == listenChannel) {
                desired = RadioVisualStage.LISTEN;
            } else {
                desired = RadioVisualStage.OFF;
            }

            ItemStack updated = applyRadioVisualStage(stack, itemChannel, desired);
            if (updated != null) {
                // Even if the instance is the same (meta mutation), set it back to ensure
                // Bukkit/clients refresh the slot reliably.
                player.getInventory().setItem(slot, updated);
                anyChanged = true;
            }
        }

        // Some clients don't reliably refresh slot icons when only meta changes.
        // This is intentionally only done on transitions (when we actually changed something).
        if (anyChanged) {
            try {
                player.updateInventory();
            } catch (Throwable ignored) {
                // best-effort
            }
        }
    }

    private String getStageItemsAdderId(RadioChannel channel, RadioVisualStage stage) {
        if (channel == null || stage == null || radioRegistry == null) {
            return null;
        }
        var def = radioRegistry.get(channel);
        if (def == null) {
            return null;
        }
        String raw = def.itemsAdderId();
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String configured = raw.trim();

        // Support both conventions:
        // - configured = "radio:radio_czerwoni" (OFF), stages => _1/_2
        // - configured = "radio:radio_czerwoni_0" (OFF), stages => _1/_2
        String base = configured;
        String offId = configured;
        if (configured.endsWith("_0")) {
            base = configured.substring(0, configured.length() - 2);
            offId = configured;
        } else if (configured.endsWith("_1") || configured.endsWith("_2")) {
            base = configured.substring(0, configured.length() - 2);
            // If someone misconfigured the base as a stage id, treat base itself as OFF.
            offId = base;
        }

        if (stage == RadioVisualStage.OFF) {
            return offId;
        }
        if (stage == RadioVisualStage.TRANSMIT) {
            return base + "_1";
        }
        return base + "_2";
    }

    private ItemStack applyRadioVisualStage(ItemStack stack, RadioChannel channel, RadioVisualStage stage) {
        if (stack == null || channel == null || stage == null || itemUtil == null) {
            return null;
        }
        if (itemUtil.getChannel(stack) != channel) {
            return null;
        }

        // Version 2: swap ItemsAdder item id instead of changing CMD.
        // This avoids colliding with other IA items (e.g. internal icons/arrows) that share CMD space.
        if (itemsAdder != null && itemsAdder.isAvailable()) {
            String targetId = getStageItemsAdderId(channel, stage);
            if (targetId != null && !targetId.isBlank()) {
                String curId = itemsAdder.getCustomId(stack);
                if (equalsIgnoreCase(curId, targetId)) {
                    return null;
                }
                ItemStack swapped = itemsAdder.createItemStack(targetId, stack.getAmount());
                if (swapped != null) {
                    tagRadioChannel(swapped, channel);
                    return swapped;
                }

                // Compatibility: many packs only define staged items (<base>_0/_1/_2) and do NOT provide the base item.
                // If config uses <base> without suffix, OFF would try to swap to <base> which may not exist -> stuck _1.
                if (stage == RadioVisualStage.OFF && !targetId.endsWith("_0")) {
                    String altOff = targetId + "_0";
                    ItemStack swappedAlt = itemsAdder.createItemStack(altOff, stack.getAmount());
                    if (swappedAlt != null) {
                        tagRadioChannel(swappedAlt, channel);
                        return swappedAlt;
                    }
                }

                // If this is an ItemsAdder radio, never fall back to CMD mutations.
                // Missing staged items should degrade to "no visual change" rather than jumping
                // into other IA items that share CMD space.
                if (curId != null && !curId.isBlank()) {
                    return null;
                }
            }
        }

        // Fallback for non-ItemsAdder radios (or if IA stage item is missing): minimal three-stage CMD.
        int desired = 1 + stage.offset;
        boolean changed = itemUtil.setCustomModelData(stack, desired);
        return changed ? stack : null;
    }

    public boolean isRadioOnStage(ItemStack stack) {
        if (stack == null || itemUtil == null) {
            return false;
        }
        RadioChannel channel = itemUtil.getChannel(stack);
        if (channel == null) {
            return false;
        }

        // "Active" for anti-drop/anti-steal is TRANSMIT only.
        if (itemsAdder != null && itemsAdder.isAvailable()) {
            String txId = getStageItemsAdderId(channel, RadioVisualStage.TRANSMIT);
            if (txId == null || txId.isBlank()) {
                return false;
            }
            String curId = itemsAdder.getCustomId(stack);
            return equalsIgnoreCase(curId, txId);
        }

        int cmd = itemUtil.getCustomModelData(stack);
        return cmd == (1 + RadioVisualStage.TRANSMIT.offset);
    }

    public void refreshHotbarVisualsNow(Player player) {
        if (player == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        RadioChannel txChannel = transmitChannelByPlayer.get(uuid);
        long now = System.currentTimeMillis();

        long lastMic = lastMicPacketAtMs.getOrDefault(uuid, 0L);
        boolean txActive = lastMic > 0L && (now - lastMic <= transmitVisualActiveForMs);

        applyHotbarVisuals(player, txChannel, txActive, now);
    }

    public void refreshHotbarVisualsAssumeInactive(Player player) {
        if (player == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        RadioChannel txChannel = transmitChannelByPlayer.get(uuid);
        long now = System.currentTimeMillis();
        applyHotbarVisuals(player, txChannel, false, now);
    }

    /**
     * Hard-stop any transmit visuals for this player.
     * Used when we have an explicit "stop transmitting" signal (e.g. hold-to-talk release),
     * to avoid stuck _1 ItemsAdder variants if silence timeouts aren't scheduled.
     */
    public void forceStopTransmitVisuals(Player player) {
        if (player == null) {
            return;
        }

        UUID uuid = player.getUniqueId();

        // Cancel any pending silence timeout.
        BukkitTask task = transmitClearTaskByPlayer.remove(uuid);
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // best-effort
            }
        }

        // Clear the "active" flags/timestamps so visuals are definitely OFF.
        transmitUiActive.put(uuid, false);
        lastMicPacketAtMs.remove(uuid);

        stopFilterLongSound(player);

        // Apply OFF stage immediately.
        applyHotbarVisuals(player, transmitChannelByPlayer.get(uuid), false, System.currentTimeMillis());
    }

    /**
     * Best-effort normalization: if the player is holding a talking variant (<base>_1) in main hand,
     * swap it back to the base item. This prevents "stuck" textures on logout/reload.
     */
    public void normalizeTalkingVariantInMainHand(Player player, boolean force) {
        if (player == null) {
            return;
        }
        if (!force) {
            UUID uuid = player.getUniqueId();
            if (Boolean.TRUE.equals(transmitUiActive.get(uuid))) {
                return;
            }
        }

        ItemStack current = player.getInventory().getItemInMainHand();
        if (current == null || current.getType().isAir()) {
            return;
        }

        ItemStack normalized = normalizeTalkingVariantToBase(current);
        if (normalized != null) {
            player.getInventory().setItemInMainHand(normalized);
        }
    }

    /**
     * If the given stack is a talking variant (<base>_1) of one of our radios, returns a new base ItemStack.
     * Otherwise returns null.
     */
    public ItemStack normalizeTalkingVariantToBase(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }

        if (itemUtil == null) {
            return null;
        }

        RadioChannel channel = itemUtil.getChannel(stack);
        if (channel == null) {
            return null;
        }

        // Always force inactive visual when we normalize (inventory/chest/drop safety).
        ItemStack revertedOff = applyRadioVisualStage(stack, channel, RadioVisualStage.OFF);
        if (revertedOff != null) {
            return revertedOff;
        }

        // No visual change needed; keep stack as-is.
        // (For non-IA items, applyRadioVisualStage already handled CMD changes.)
        // We preserve legacy behavior: return null if nothing changed.
        // 'changed' remains false here.

        return null;
    }

    public boolean isTransmitUiActive(UUID uuid) {
        return uuid != null && Boolean.TRUE.equals(transmitUiActive.get(uuid));
    }

    /**
     * Best-effort safety: ensure that radios stored outside the main hand are never left in the ON stage.
     * This prevents "stealing" an active radio from inventories/chests.
     */
    public void normalizeRadiosForStorage(Player player) {
        if (player == null || itemUtil == null) {
            return;
        }

        // Hotbar (0-8) may show OFF/TRANSMIT/LISTEN visuals.
        // Only normalize storage slots.
        for (int slot = 9; slot < 36; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            ItemStack normalized = normalizeTalkingVariantToBase(item);
            if (normalized != null) {
                player.getInventory().setItem(slot, normalized);
            }
        }

        // Offhand should never be ON.
        ItemStack offhand = player.getInventory().getItemInOffHand();
        ItemStack offhandNorm = normalizeTalkingVariantToBase(offhand);
        if (offhandNorm != null) {
            player.getInventory().setItemInOffHand(offhandNorm);
        }

        try {
            var view = player.getOpenInventory();
            if (view != null) {
                var top = view.getTopInventory();
                if (top != null) {
                    ItemStack[] contents = top.getContents();
                    for (int i = 0; i < contents.length; i++) {
                        ItemStack normalized = normalizeTalkingVariantToBase(contents[i]);
                        if (normalized != null) {
                            top.setItem(i, normalized);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    private void tagRadioChannel(ItemStack stack, RadioChannel channel) {
        if (stack == null || channel == null || radioChannelPdcKey == null) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(radioChannelPdcKey, PersistentDataType.STRING, channel.id());
        stack.setItemMeta(meta);
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().equalsIgnoreCase(b.trim());
    }

    public RadioState getRadioState() {
        return radioState;
    }

    public RadioRegistry getRadioRegistry() {
        return radioRegistry;
    }

    public RadioItemUtil getItemUtil() {
        return itemUtil;
    }

    public RadioChannel getTransmitChannel(Player player) {
        return getTransmitChannelFast(player);
    }

    public RadioChannel getTransmitChannelFast(Player player) {
        if (player == null || itemUtil == null) {
            return null;
        }

        UUID uuid = player.getUniqueId();
        RadioChannel cached = transmitChannelByPlayer.get(uuid);
        if (cached != null) {
            // Permission could change at runtime; keep this cheap correctness check.
            if (player.hasPermission(cached.usePermission())) {
                return cached;
            }
            transmitChannelByPlayer.remove(uuid);
        }

        refreshTransmitCache(player);
        return transmitChannelByPlayer.get(uuid);
    }

    public void refreshTransmitCache(Player player) {
        if (player == null || itemUtil == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        var inHand = player.getInventory().getItemInMainHand();
        RadioChannel channel = itemUtil.getChannel(inHand);
        if (channel == null) {
            if (debugEnabled && inHand != null && !inHand.getType().isAir()) {
                String iaId = itemUtil.debugGetItemsAdderId(inHand);
                debugLog("noTx:" + uuid,
                    "No transmit channel from main-hand item type=" + inHand.getType()
                        + " iaAvailable=" + itemUtil.isItemsAdderAvailable()
                        + " iaId=" + (iaId == null ? "null" : iaId));
            }
            transmitChannelByPlayer.remove(uuid);
            return;
        }

        if (channel == RadioChannel.PIRACI_RANDOM) {
            transmitChannelByPlayer.remove(uuid);
            return;
        }

        if (!player.hasPermission(channel.usePermission())) {
            if (debugEnabled) {
                debugLog("noTxPerm:" + uuid, "Missing use permission for channel=" + channel.id() + " perm=" + channel.usePermission());
            }
            transmitChannelByPlayer.remove(uuid);
            return;
        }

        transmitChannelByPlayer.put(uuid, channel);
    }

    public void clearTransmitCache(UUID uuid) {
        if (uuid == null) {
            return;
        }
        transmitChannelByPlayer.remove(uuid);
    }

    public void runNextTick(Runnable runnable) {
        new BukkitRunnable() {
            @Override
            public void run() {
                runnable.run();
            }
        }.runTask(this);
    }

    public void playFeedbackSound(Player player, String path) {
        boolean enabled = getConfig().getBoolean(path + ".enabled", true);
        if (!enabled) {
            return;
        }
        String soundName = getConfig().getString(path + ".sound", "");
        float volume = (float) getConfig().getDouble(path + ".volume", 1.0);
        float pitch = (float) getConfig().getDouble(path + ".pitch", 1.0);

        if (soundName == null || soundName.isBlank()) {
            return;
        }

        Sound sound = resolveSound(soundName);
        if (sound == null) {
            debugLog("sound:" + path, "Invalid sound in config: " + path + ".sound='" + soundName + "'");
            return;
        }

        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    public void playTransmitStartSound(Player player) {
        playFilterLongSound(player);

        // User requirement: play two sounds on transmit start.
        // 1) Dedicated TX start (block place by default)
        // 2) Legacy generic start sound (for backwards compatibility and stacking)
        String soundName = getConfig().getString("sounds.transmitStart.sound", "");
        if (soundName != null && !soundName.isBlank()) {
            playFeedbackSound(player, "sounds.transmitStart");
        }

        playFeedbackSound(player, "sounds.start");
    }

    private void playFilterLongSound(Player player) {
        if (player == null) {
            return;
        }

        String basePath = "sounds.filterLong";
        if (!getConfig().getBoolean(basePath + ".enabled", false)) {
            return;
        }

        String soundName = String.valueOf(getConfig().getString(basePath + ".sound", "")).trim();
        if (soundName.isBlank()) {
            return;
        }

        float volume = (float) getConfig().getDouble(basePath + ".volume", 1.0);
        float pitch = (float) getConfig().getDouble(basePath + ".pitch", 1.0);

        // Prevent overlap if we retrigger quickly.
        stopFilterLongSound(player);

        try {
            // Plays a custom resource-pack sound event by key (e.g. "radio:filter_long").
            player.playSound(player.getLocation(), soundName, volume, pitch);
        } catch (Throwable t) {
            debugLog("sound:" + basePath, "Failed to play custom sound '" + soundName + "': " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void stopFilterLongSound(Player player) {
        if (player == null) {
            return;
        }

        String basePath = "sounds.filterLong";
        if (!getConfig().getBoolean(basePath + ".enabled", false)) {
            return;
        }

        String soundName = String.valueOf(getConfig().getString(basePath + ".sound", "")).trim();
        if (soundName.isBlank()) {
            return;
        }

        try {
            // Stops that specific sound key for the player.
            player.stopSound(soundName);
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    private static Sound resolveSound(String configValue) {
        if (configValue == null) {
            return null;
        }
        String raw = configValue.trim();
        if (raw.isBlank()) {
            return null;
        }

        String cacheKey = raw.toLowerCase(Locale.ROOT);
        Sound cached = SOUND_LOOKUP.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Sound resolved = resolveSoundUncached(raw);
        if (resolved != null) {
            SOUND_LOOKUP.put(cacheKey, resolved);
        }
        return resolved;
    }

    private static Sound resolveSoundUncached(String raw) {
        // 1) Accept legacy enum-like names e.g. "BLOCK_NOTE_BLOCK_PLING"
        String enumLike = raw.toUpperCase(Locale.ROOT);
        try {
            Field field = Sound.class.getField(enumLike);
            Object value = field.get(null);
            if (value instanceof Sound sound) {
                return sound;
            }
        } catch (NoSuchFieldException ignored) {
            // Not a constant name
        } catch (ReflectiveOperationException ignored) {
            // Unexpected reflection issue
        }

        // 2) Accept registry keys: "minecraft:block.note_block.pling" or "block.note_block.pling"
        String keyString = raw.trim();
        if (!keyString.contains(":")) {
            // Vanilla sound keys are typically dot-separated without namespace
            if (keyString.contains(".")) {
                keyString = "minecraft:" + keyString.toLowerCase(Locale.ROOT);
            }
        }

        Sound resolved = tryResolveRegistrySound(keyString);
        if (resolved != null) {
            return resolved;
        }

        // Common user mistake: prefixing with "ambient." (e.g. ambient.weather.rain)
        // Try removing the first "ambient." segment.
        String lowered = keyString.toLowerCase(Locale.ROOT);
        if (lowered.startsWith("minecraft:ambient.")) {
            String alt = "minecraft:" + lowered.substring("minecraft:ambient.".length());
            resolved = tryResolveRegistrySound(alt);
            if (resolved != null) {
                return resolved;
            }
        } else if (lowered.contains(".ambient.")) {
            // less common, but handle "foo.ambient.bar"
            String alt = lowered.replaceFirst("\\.ambient\\.", ".");
            resolved = tryResolveRegistrySound(alt);
            if (resolved != null) {
                return resolved;
            }
        } else if (lowered.startsWith("ambient.")) {
            String alt = "minecraft:" + lowered.substring("ambient.".length());
            resolved = tryResolveRegistrySound(alt);
            if (resolved != null) {
                return resolved;
            }
        }

        return null;
    }

    private static Sound tryResolveRegistrySound(String keyString) {
        NamespacedKey key = NamespacedKey.fromString(keyString.toLowerCase(Locale.ROOT));
        if (key == null) {
            return null;
        }

        try {
            return Registry.SOUNDS.get(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void playConfiguredNotification(Player player, String path) {
        if (player == null || path == null || path.isBlank()) {
            return;
        }

        boolean enabled = getConfig().getBoolean(path + ".enabled", true);
        if (!enabled) {
            return;
        }

        String rawActionBar = getConfig().getString(path + ".actionBar", "");
        String actionBar = rawActionBar;

        if (actionBar == null || actionBar.isBlank()) {
            return;
        }

        // Per user requirement: use ActionBar only (no Title/Subtitle)
        player.sendActionBar(legacy.deserialize(translateLegacyColors(actionBar)));
    }

    private String translateLegacyColors(String input) {
        // Accept &-colors in config (common for MC configs)
        return input.replace('&', '§');
    }

    public void tryRegisterVoicechatIntegrationIfNeeded() {
        VoiceBridge existing = voicechatBridge;
        if (existing != null && existing.isHooked()) {
            return;
        }

        if (!getConfig().getBoolean("svc.enabled", true)) {
            getLogger().info("SVC integration disabled in config (svc.enabled=false). Voice features disabled.");
            return;
        }

        // If Simple Voice Chat isn't installed, we must not load classes that depend on its API.
        try {
            Class.forName("de.maxhenkel.voicechat.api.VoicechatPlugin", false, getClassLoader());
        } catch (ClassNotFoundException e) {
            getLogger().warning("Simple Voice Chat API not found. Install the 'voicechat' plugin to enable voice features.");
            return;
        }

        try {
            Class<?> bridgeClass = Class.forName("me.Luki.WalkieTalkiePlugin.voice.VoicechatBridge", true, getClassLoader());
            bridgeClass.getMethod("tryRegister", WalkieTalkiePlugin.class).invoke(null, this);
        } catch (Throwable t) {
            getLogger().severe("Failed to hook Simple Voice Chat. Voice features disabled.");
            t.printStackTrace();
        }
    }
}
