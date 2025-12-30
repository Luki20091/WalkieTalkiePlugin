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
import java.util.Set;

import static me.Luki.WalkieTalkiePlugin.radio.RadioItemKeys.channelKey;

public final class WalkieTalkiePlugin extends JavaPlugin {
    /**
     * Always decrements durability of the radio in player's main hand by 1,
     * regardless of listeners.
     * Use this after transmit ends to ensure durability is reduced.
     */
    public void decrementRadioDurability(Player player) {
        if (player == null || itemUtil == null)
            return;
        ItemStack stack = player.getInventory().getItemInMainHand();
        if (isDevMode()) {
            String ia = itemUtil != null ? itemUtil.debugGetItemsAdderId(stack) : "<no-ia>";
            getLogger().info(
                    "[WT-DEBUG] decrementRadioDurability called for " + (player == null ? "<null>" : player.getName())
                            + " ia=" + ia + " type=" + (stack == null ? "<null>" : stack.getType()));
        }
        if (stack == null || !itemUtil.isRadio(stack)) {
            if (isDevMode())
                getLogger().info("[WT-DEBUG] Not a radio or empty main hand.");
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            if (isDevMode())
                getLogger().info("[WT-DEBUG] ItemMeta is null.");
            return;
        }
        if (!(meta instanceof org.bukkit.inventory.meta.Damageable)) {
            if (isDevMode())
                getLogger().info("[WT-DEBUG] ItemMeta is not Damageable.");
            return;
        }
        org.bukkit.inventory.meta.Damageable damageable = (org.bukkit.inventory.meta.Damageable) meta;
        int current = damageable.getDamage();
        int max = stack.getType().getMaxDurability();
        String iaId = "<none>";
        int iaMax = 0;
        // If ItemsAdder provides a configured max durability for this custom id, prefer
        // it.
        try {
            if (itemsAdder != null && itemsAdder.isAvailable()) {
                iaId = itemsAdder.getCustomId(stack);
                iaMax = itemsAdder.getMaxDurabilityForId(iaId);
                if (iaMax > 0) {
                    max = iaMax;
                }
            }
        } catch (Throwable t) {
            if (isDevMode())
                t.printStackTrace();
        }
        if (isDevMode())
            getLogger().info("[WT-DEBUG] iaId=" + iaId + " iaMax=" + iaMax + " currentDamage=" + current
                    + " resolvedMax=" + max);

        int newDamage = current + 1;
        if (current < max) {
            damageable.setDamage(newDamage);
            stack.setItemMeta(meta);
            // Ensure inventory holds the mutated ItemStack instance.
            try {
                player.getInventory().setItemInMainHand(stack);
            } catch (Throwable ignored) {
            }
            if (isDevMode())
                getLogger().info("[WT-DEBUG] Durability decremented (new=" + newDamage + ") for ia=" + iaId);

            // If we've reached or exceeded the configured max, force removal and play break
            // sound.
            if (newDamage >= max) {
                try {
                    if (isDevMode())
                        getLogger().info(
                                "[WT-DEBUG] Reached max durability, forcing removal of item in main hand ia=" + iaId);
                    player.getInventory()
                            .setItemInMainHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR));
                    try {
                        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                    } catch (Throwable ignoredSound) {
                    }
                } catch (Throwable t) {
                    if (isDevMode())
                        t.printStackTrace();
                }
            }
        } else {
            if (isDevMode())
                getLogger().info("[WT-DEBUG] Damage at or above max; forcing removal ia=" + iaId);
            try {
                player.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR));
                try {
                    player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                } catch (Throwable ignored) {
                }
            } catch (Throwable t) {
                if (isDevMode())
                    t.printStackTrace();
            }
        }
    }

    /**
     * Reduces durability of the item in player's main hand by 1, synchronizing
     * durability between radio variants.
     * If durability reaches 0, the item will be removed automatically by
     * ItemsAdder/Paper.
     */
    public void reduceRadioDurability(Player player) {
        // Kept for backward compatibility. Forward to the unified implementation.
        decrementRadioDurability(player);
    }

    /**
     * Synchronizes durability between radio item variants (_0/_1/_2).
     * Should be called when swapping item variant to keep durability visually
     * correct.
     */
    public void syncRadioDurability(ItemStack from, ItemStack to) {
        if (from == null || to == null)
            return;
        ItemMeta fromMeta = from.getItemMeta();
        ItemMeta toMeta = to.getItemMeta();
        if (fromMeta instanceof org.bukkit.inventory.meta.Damageable
                && toMeta instanceof org.bukkit.inventory.meta.Damageable) {
            int damage = ((org.bukkit.inventory.meta.Damageable) fromMeta).getDamage();
            ((org.bukkit.inventory.meta.Damageable) toMeta).setDamage(damage);
            to.setItemMeta(toMeta);
        }
    }

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
    private final Map<UUID, Boolean> listenUiActive = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> listenClearTaskByPlayer = new ConcurrentHashMap<>();

    private volatile VoiceBridge voicechatBridge;

    private static final Map<String, Sound> SOUND_LOOKUP = new ConcurrentHashMap<>();

    private final Map<UUID, RadioChannel> transmitChannelByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, RadioChannel> preferredListenChannelByPlayer = new ConcurrentHashMap<>();

    // Flag: whether a player currently has a Backpack-like GUI open that should
    // block
    // moving/picking radio items. External plugins (e.g. BackpackPlus) should call
    // `markPlayerBackpackGuiOpen(player, true)` when opening such a GUI and
    // `markPlayerBackpackGuiOpen(player, false)` when closing it.
    private final Map<UUID, Boolean> backpackGuiOpen = new ConcurrentHashMap<>();

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
    private final Map<UUID, Long> lastNoTransmitHintAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastNoListenHintAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastNoAccessHintAtMs = new ConcurrentHashMap<>();
    private volatile boolean busyLineEnabled;
    private volatile long busyLineTimeoutMs;
    private volatile long busyLineHintCooldownMs;
    private volatile long permissionHintCooldownMs;

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

    // Off-thread safe list of online players. Updated on main thread in listeners.
    private final Set<UUID> onlinePlayerUuids = ConcurrentHashMap.newKeySet();

    // Debug/repair: if a receiver is denied due to stale caches, refresh their
    // caches at most once per interval.
    private final Map<UUID, Long> lastDeniedCacheRefreshAtMs = new ConcurrentHashMap<>();

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

            // Populate caches for already-online players (e.g. plugin reload while players
            // are online)
            getServer().getOnlinePlayers().forEach(p -> {
                onlinePlayerUuids.add(p.getUniqueId());
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

        onlinePlayerUuids.clear();

        getLogger().info("WalkieTalkiePlugin v" + version + " disabled.");
    }

    public void markPlayerOnline(UUID uuid) {
        if (uuid == null) {
            return;
        }
        onlinePlayerUuids.add(uuid);
    }

    public void markPlayerOffline(UUID uuid) {
        if (uuid == null) {
            return;
        }
        onlinePlayerUuids.remove(uuid);
    }

    public Set<UUID> getOnlinePlayerUuids() {
        return onlinePlayerUuids;
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
     * Marks whether the player is currently holding-to-talk (PPM/USE_ITEM) with a
     * transmit-capable radio.
     * This is used by filterLoop mode HOLD to align start/stop exactly to the
     * user's action.
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
     * This removes the perceived delay caused by the periodic scheduler tick
     * alignment.
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
     * Used to avoid waiting up to everyTicks before the first pulse after pressing
     * SVC PTT.
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
                        debugLog("loop:pttHint",
                                "pttRequired hint loop error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
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
                        debugLog("loop:filterLoop",
                                "filterLoop error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
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
                        try {
                            volume = Double.parseDouble(String.valueOf(vObj));
                        } catch (Exception ignored) {
                        }
                    }
                    if (pObj instanceof Number n) {
                        pitch = n.doubleValue();
                    } else if (pObj != null) {
                        try {
                            pitch = Double.parseDouble(String.valueOf(pObj));
                        } catch (Exception ignored) {
                        }
                    }

                    Sound sound = resolveSound(soundName);
                    if (sound == null) {
                        debugLog("sound:" + basePath,
                                "Invalid sound in config: " + basePath + ".sounds[].sound='" + soundName + "'");
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

        // NOTE: allow listening to multiple channels simultaneously if the player
        // has the corresponding radio in their hotbar. `preferredListenChannelByPlayer`
        // is only used for visuals/selection and must not prevent actual audio
        // delivery.

        PermissionSnapshot snapshot = permissionSnapshots.get(receiverUuid);
        if (snapshot == null) {
            try {
                debugLog("canListen", "canListenCached receiver=" + receiverUuid + " tx=" + transmitting.id()
                        + " result=false (no snapshot)");
            } catch (Throwable ignored) {
            }
            return false;
        }

        // Pirate eavesdrop: receiver listens while holding pirate radio.
        // The pirate radio now acts as a wildcard: it can catch any transmitting
        // channel.
        RadioChannel eavesdropTarget = radioState != null ? radioState.getEavesdroppingChannel(receiverUuid) : null;
        if (eavesdropTarget != null) {
            boolean isWildcard = eavesdropTarget == RadioChannel.PIRACI_RANDOM;
            boolean match = isWildcard || eavesdropTarget == transmitting;
            if (match) {
                boolean res = snapshot.hasPirateRandomUse && snapshot.hasListenPermission(transmitting);
                try {
                    debugLog("canListen", "canListenCached receiver=" + receiverUuid + " tx=" + transmitting.id()
                            + " result=" + res + " (eavesdrop match)");
                } catch (Throwable ignored) {
                }
                return res;
            }
        }

        if (!snapshot.hasListenPermission(transmitting)) {
            try {
                debugLog("canListen", "canListenCached receiver=" + receiverUuid + " tx=" + transmitting.id()
                        + " result=false (no listen perm)");
            } catch (Throwable ignored) {
            }
            return false;
        }

        boolean hasHotbar = radioState != null && radioState.hasHotbarRadio(receiverUuid, transmitting);
        try {
            debugLog("canListen",
                    "canListenCached receiver=" + receiverUuid + " tx=" + transmitting.id() + " result=" + hasHotbar
                            + " (hotbar=" + hasHotbar + ") :: "
                            + debugExplainListenDecision(receiverUuid, transmitting));
        } catch (Throwable ignored) {
        }
        return hasHotbar;
    }

    public void refreshPreferredListenChannel(Player player) {
        if (player == null || itemUtil == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        int heldSlot = player.getInventory().getHeldItemSlot();

        RadioChannel selected = null;
        RadioChannel firstDeniedListen = null;

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
                if (firstDeniedListen == null) {
                    firstDeniedListen = channel;
                }
                continue;
            }
            selected = channel;
            break;
        }

        if (selected == null && firstDeniedListen != null) {
            maybeNotifyNoListen(player, firstDeniedListen);
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
        boolean pirateRandomUse = player.hasPermission(RadioChannel.PIRACI_RANDOM.listenPermission());
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
        // This method may be called off-thread and also multiple times per packet (SVC
        // is per receiver),
        // so we only trigger on the transition from inactive -> active.
        boolean wasActive = Boolean.TRUE.equals(transmitUiActive.put(senderUuid, true));
        if (isDevMode()) {
            getLogger().info("[WT-DEBUG] recordMicPacket sender=" + senderUuid + " channel="
                    + (channel == null ? "<none>" : channel.id()) + " wasActive=" + wasActive);
        }
        if (!wasActive) {
            UUID uuid = senderUuid;
            RadioChannel txChannel = channel;
            runNextTick(() -> {
                Player player = getServer().getPlayer(uuid);
                if (player == null) {
                    return;
                }
                // filterLong is LISTEN/eavesdrop-only; make sure it is not audible during
                // transmit.
                stopFilterLongSound(player);
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

        boolean wasActive = Boolean.TRUE.equals(listenUiActive.put(receiverUuid, true));
        if (!wasActive) {
            UUID uuid = receiverUuid;
            runNextTick(() -> {
                Player player = getServer().getPlayer(uuid);
                if (player == null) {
                    return;
                }

                boolean txActive = Boolean.TRUE.equals(transmitUiActive.get(uuid));
                // filterLong is LISTEN/eavesdrop-only; do not start it while transmitting.
                boolean eavesdropping = radioState != null && radioState.getEavesdroppingChannel(uuid) != null;
                if (!txActive && !eavesdropping) {
                    playFilterLongSound(player);
                    playFeedbackSound(player, "sounds.start");
                }
            });
        }

        UUID uuid = receiverUuid;
        runNextTick(() -> {
            Player player = getServer().getPlayer(uuid);
            if (player == null) {
                return;
            }
            // applyHotbarVisuals reads lastRadioReceive* maps, so it will choose LISTEN
            // immediately.
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
        final boolean[] acquired = new boolean[] { false };
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

        this.permissionHintCooldownMs = Math.max(250L,
                getConfig().getLong("notifications.permissions.cooldownMs", 1500L));

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
        if (isDevMode()) {
            getLogger().info("[WT-DEBUG] ensureTransmitClearScheduled for " + playerUuid);
        }
        scheduleTransmitClearCheck(playerUuid, transmitVisualActiveForMs);
    }

    private void scheduleTransmitClearCheck(UUID playerUuid, long delayMs) {
        long delayTicks = Math.max(1L, (delayMs + 49L) / 50L);
        BukkitTask task = getServer().getScheduler().runTaskLater(this, () -> {
            if (isDevMode()) {
                getLogger().info("[WT-DEBUG] transmitClearTask running for " + playerUuid + " delayMs=" + delayMs);
            }
            transmitClearTaskByPlayer.remove(playerUuid);

            long last = lastMicPacketAtMs.getOrDefault(playerUuid, 0L);
            long now = System.currentTimeMillis();
            long dueAt = last + transmitVisualActiveForMs;
            if (isDevMode()) {
                getLogger().info("[WT-DEBUG] transmitClearTask last=" + last + " now=" + now + " dueAt=" + dueAt);
            }
            if (last > 0L && now < dueAt) {
                scheduleTransmitClearCheck(playerUuid, dueAt - now);
                return;
            }

            boolean wasActive = Boolean.TRUE.equals(transmitUiActive.get(playerUuid));
            transmitUiActive.put(playerUuid, false);

            Player player = getServer().getPlayer(playerUuid);
            if (player != null) {
                if (wasActive) {
                    // filterLong should never be audible during transmit.
                    // After transmit ends, we may resume it if the user is still LISTENing or
                    // eavesdropping.
                    stopFilterLongSound(player);
                    playTransmitStopSound(player);
                    playConfiguredNotification(player, "notifications.transmit.stop");

                    boolean listenActive = Boolean.TRUE.equals(listenUiActive.get(playerUuid));
                    boolean eavesdropping = radioState != null
                            && radioState.getEavesdroppingChannel(playerUuid) != null;
                    if (listenActive || eavesdropping) {
                        playFilterLongSound(player);
                    }
                    // Best-effort: if transmit visuals timed out (SVC stop), ensure transmit state
                    // is cleared
                    // and always decrement durability once for the transmit action.
                    try {
                        // Use the cached hotbar/main-hand transmit channel (Voicechat path
                        // sets/refreshes this),
                        // radioState.transmitting is only set by hold-to-talk listeners.
                        RadioChannel prev = transmitChannelByPlayer.get(playerUuid);
                        if (isDevMode()) {
                            getLogger().info("[WT-DEBUG] transmitClearTask cachedTxChannel="
                                    + (prev == null ? "<none>" : prev.id()) + " transmitUiActive="
                                    + Boolean.TRUE.equals(transmitUiActive.get(playerUuid)));
                        }
                        if (prev != null) {
                            // clear cached transmit channel and decrement durability once for this transmit
                            transmitChannelByPlayer.remove(playerUuid);
                            if (isDevMode() && player != null) {
                                ItemStack s = player.getInventory().getItemInMainHand();
                                String ia = itemUtil != null ? itemUtil.debugGetItemsAdderId(s) : "<no-ia>";
                                getLogger().info(
                                        "[WT-DEBUG] transmitClearTask about to decrement radio for " + player.getName()
                                                + " ia=" + ia + " type=" + (s == null ? "<null>" : s.getType()));
                            }
                            decrementRadioDurability(player);
                        }
                    } catch (Throwable ignored) {
                    }
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

            boolean wasActive = Boolean.TRUE.equals(listenUiActive.get(playerUuid));
            listenUiActive.put(playerUuid, false);

            Player player = getServer().getPlayer(playerUuid);
            if (player != null) {
                if (wasActive) {
                    boolean txActive = Boolean.TRUE.equals(transmitUiActive.get(playerUuid));
                    // Don't stop the filter/start-stop sounds if transmit is still active.
                    boolean eavesdropping = radioState != null
                            && radioState.getEavesdroppingChannel(playerUuid) != null;
                    if (!txActive && !eavesdropping) {
                        stopFilterLongSound(player);
                        playFeedbackSound(player, "sounds.stop");
                    }
                }
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

    public String debugGetMicCountersLine() {
        return "micEvents=" + dbgMicEvents.get()
                + " noSender=" + dbgMicNoSender.get()
                + " noReceiver=" + dbgMicNoReceiver.get()
                + " noTxChannel=" + dbgMicNoTransmitChannel.get()
                + " crossWorldCancel=" + dbgMicCrossWorldCancelled.get()
                + " notAllowedCancel=" + dbgMicNotAllowedCancelled.get()
                + " withinMinDistanceProx=" + dbgMicWithinMinDistanceProx.get()
                + " radioSent=" + dbgMicRadioSent.get();
    }

    public String debugExplainListenDecision(UUID receiverUuid, RadioChannel transmitting) {
        if (receiverUuid == null || transmitting == null) {
            return "receiverUuid/transmitting is null";
        }

        RadioChannel preferred = preferredListenChannelByPlayer.get(receiverUuid);
        boolean preferredBlocks = preferred != null && preferred != transmitting;

        PermissionSnapshot snapshot = permissionSnapshots.get(receiverUuid);
        boolean hasSnapshot = snapshot != null;

        RadioChannel eavesdropTarget = radioState != null ? radioState.getEavesdroppingChannel(receiverUuid) : null;
        boolean isEavesdropMatch = eavesdropTarget != null && eavesdropTarget == transmitting;

        boolean hasListenPerm = snapshot != null && snapshot.hasListenPermission(transmitting);
        boolean hasHotbarRadio = radioState != null && radioState.hasHotbarRadio(receiverUuid, transmitting);
        boolean pirateRandomAllowed = snapshot != null && snapshot.hasPirateRandomUse;

        return "preferred=" + (preferred == null ? "<none>" : preferred.id())
                + " preferredBlocks=" + preferredBlocks
                + " snapshot=" + hasSnapshot
                + " listenPerm=" + hasListenPerm
                + " hotbarRadio=" + hasHotbarRadio
                + " eavesdropTarget=" + (eavesdropTarget == null ? "<none>" : eavesdropTarget.id())
                + " eavesdropMatch=" + isEavesdropMatch
                + " pirateRandomAllowed=" + pirateRandomAllowed;
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
    public void debugMicNotAllowedCancelled(UUID senderUuid, UUID receiverUuid, RadioChannel transmitting) {
        dbgMicNotAllowedCancelled.incrementAndGet();
        if (senderUuid == null || receiverUuid == null || transmitting == null) {
            return;
        }
        debugLog(
                "mic:notAllowed:" + receiverUuid + ":" + transmitting.id(),
                "Denied radio delivery sender=" + senderUuid
                        + " receiver=" + receiverUuid
                        + " tx=" + transmitting.id()
                        + " :: " + debugExplainListenDecision(receiverUuid, transmitting));

        // Best-effort self-heal: receiver caches might be stale if an inventory event
        // was missed.
        // Refresh hotbar/permissions/selection on next tick, rate-limited.
        long now = System.currentTimeMillis();
        Long last = lastDeniedCacheRefreshAtMs.get(receiverUuid);
        if (last == null || (now - last) >= 750L) {
            lastDeniedCacheRefreshAtMs.put(receiverUuid, now);
            runNextTick(() -> {
                Player p = getServer().getPlayer(receiverUuid);
                if (p == null) {
                    return;
                }
                try {
                    if (radioState != null) {
                        radioState.refreshHotbar(p);
                    }
                    refreshPermissionCache(p);
                    refreshPreferredListenChannel(p);
                } catch (Throwable ignored) {
                    // best-effort
                }
            });
        }
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

        // Pirate eavesdrop visuals are special:
        // - PIRACI_RANDOM in main hand should show _1 while eavesdropping (idle)
        // - it should show _2 only while it "caught" a line (i.e. we actually receive
        // audio)
        boolean eavesdropping = radioState != null && radioState.getEavesdroppingChannel(uuid) != null;
        RadioChannel heldChannel = itemUtil.getChannel(player.getInventory().getItemInMainHand());
        boolean pirateInMainHand = heldChannel == RadioChannel.PIRACI_RANDOM;

        int listenSlot = -1;
        if (listenActive && listenChannel != null) {
            // If we're pirate-eavesdropping and holding the pirate radio, the "caught line"
            // visual belongs
            // on the pirate radio, not on the real channel radio (which may not even exist
            // in hotbar).
            if (eavesdropping && pirateInMainHand) {
                listenSlot = heldSlot;
            } else {
                for (int slot = 0; slot < 9; slot++) {
                    ItemStack stack = player.getInventory().getItem(slot);
                    RadioChannel itemChannel = itemUtil.getChannel(stack);
                    if (itemChannel == null || itemChannel != listenChannel) {
                        continue;
                    }

                    // Don't steal visuals from an active transmit item.
                    if (txActive && txChannel != null && slot == heldSlot && itemChannel == txChannel) {
                        continue;
                    }

                    listenSlot = slot;
                    break;
                }
            }
        }

        boolean anyChanged = false;
        if (hotbarDetailedLoggingEnabled()) {
            try {
                var top = player.getOpenInventory();
                var topType = top == null || top.getTopInventory() == null ? "<null>"
                        : String.valueOf(top.getTopInventory().getType());
                getLogger().info("[WT-DEBUG] applyHotbarVisuals for " + player.getName() + " tx="
                        + (txChannel == null ? "<none>" : txChannel.id()) + " txActive=" + txActive + " heldSlot="
                        + heldSlot + " listenSlot=" + listenSlot + " top=" + topType + " eavesdrop=" + eavesdropping);
            } catch (Throwable ignored) {
            }
        }

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            RadioChannel itemChannel = itemUtil.getChannel(stack);
            if (itemChannel == null) {
                continue;
            }

            RadioVisualStage desired;
            if (txActive && txChannel != null && slot == heldSlot && itemChannel == txChannel) {
                desired = RadioVisualStage.TRANSMIT;
            } else if (listenSlot >= 0 && slot == listenSlot) {
                desired = RadioVisualStage.LISTEN;
            } else if (eavesdropping && pirateInMainHand && slot == heldSlot
                    && itemChannel == RadioChannel.PIRACI_RANDOM) {
                // Visual-only: use stage _1 as "eavesdrop idle" for PIRACI_RANDOM.
                desired = RadioVisualStage.TRANSMIT;
            } else {
                desired = RadioVisualStage.OFF;
            }

            ItemStack updated = applyRadioVisualStage(stack, itemChannel, desired);
            if (updated != null) {
                if (hotbarDetailedLoggingEnabled()) {
                    try {
                        String curId = "<none>";
                        String newId = "<none>";
                        if (itemsAdder != null && itemsAdder.isAvailable()) {
                            curId = stack == null ? "<null>"
                                    : String.valueOf(itemsAdder.getCustomId(stack)) + "@"
                                            + (stack == null ? 0 : stack.getAmount());
                            newId = String.valueOf(itemsAdder.getCustomId(updated)) + "@"
                                    + (updated == null ? 0 : updated.getAmount());
                        } else {
                            curId = stack == null ? "<null>"
                                    : String.valueOf(stack.getType()) + "@" + stack.getAmount();
                            newId = updated == null ? "<null>"
                                    : String.valueOf(updated.getType()) + "@" + updated.getAmount();
                        }
                        getLogger().info("[WT-DEBUG] Hotbar slot=" + slot + " cur=" + curId + " -> new=" + newId
                                + " desiredStage=" + desired.name());
                    } catch (Throwable ignored) {
                    }
                }
                // Only perform server->client hotbar writes when it's safe (player not
                // interacting with a non-player top-inventory). Writing hotbar while a
                // custom GUI is open can produce client-side phantom cursor/slot states.
                try {
                    var openView = player.getOpenInventory();
                    var curTop = openView == null ? null : openView.getTopInventory();
                    boolean safeNow = (curTop == null)
                            || (curTop.getType() == org.bukkit.event.inventory.InventoryType.PLAYER)
                            || (curTop.getType() == org.bukkit.event.inventory.InventoryType.CRAFTING);
                    if (safeNow) {
                        // Even if the instance is the same (meta mutation), set it back to ensure
                        // Bukkit/clients refresh the slot reliably.
                        player.getInventory().setItem(slot, updated);
                        anyChanged = true;
                    } else {
                        if (hotbarDetailedLoggingEnabled()) {
                            try {
                                String topStr = curTop == null ? "<null>" : String.valueOf(curTop.getType());
                                getLogger().info("[WT-DEBUG] Skipping hotbar write for " + player.getName() + " slot="
                                        + slot + " top=" + topStr);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // best-effort: don't let hotbar-sync attempts crash visuals loop
                }
            }
        }

        // Some clients don't reliably refresh slot icons when only meta changes.
        // This is intentionally only done on transitions (when we actually changed
        // something).
        if (anyChanged) {
            try {
                player.updateInventory();
                if (hotbarDetailedLoggingEnabled()) {
                    getLogger().info("[WT-DEBUG] applyHotbarVisuals applied changes for " + player.getName());
                }
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
        // This avoids colliding with other IA items (e.g. internal icons/arrows) that
        // share CMD space.
        if (itemsAdder != null && itemsAdder.isAvailable()) {
            String targetId = getStageItemsAdderId(channel, stage);
            if (targetId != null && !targetId.isBlank()) {
                String curId = itemsAdder.getCustomId(stack);
                if (equalsIgnoreCase(curId, targetId)) {
                    return null;
                }
                ItemStack swapped = itemsAdder.createItemStack(targetId, stack.getAmount());
                if (swapped != null) {
                    syncRadioDurability(stack, swapped);
                    tagRadioChannel(swapped, channel);
                    return swapped;
                }

                // Compatibility: many packs only define staged items (<base>_0/_1/_2) and do
                // NOT provide the base item.
                // If config uses <base> without suffix, OFF would try to swap to <base> which
                // may not exist -> stuck _1.
                if (stage == RadioVisualStage.OFF && !targetId.endsWith("_0")) {
                    String altOff = targetId + "_0";
                    ItemStack swappedAlt = itemsAdder.createItemStack(altOff, stack.getAmount());
                    if (swappedAlt != null) {
                        syncRadioDurability(stack, swappedAlt);
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

        // Fallback for non-ItemsAdder radios (or if IA stage item is missing): minimal
        // three-stage CMD.
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

        // PIRACI_RANDOM is listen-only; never treat its staged visuals as an "active
        // transmit" radio.
        if (channel == RadioChannel.PIRACI_RANDOM) {
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
     * Used when we have an explicit "stop transmitting" signal (e.g. hold-to-talk
     * release),
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
     * Hard-stop any LISTEN visuals and related looping audio.
     * Used when we have an explicit signal that the player can't/doesn't want to
     * listen anymore
     * (e.g. dropping the radio with Q).
     */
    public void forceStopListenVisuals(Player player) {
        if (player == null) {
            return;
        }

        UUID uuid = player.getUniqueId();

        BukkitTask task = listenClearTaskByPlayer.remove(uuid);
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // best-effort
            }
        }

        lastRadioReceiveAtMs.remove(uuid);
        lastRadioReceiveChannel.remove(uuid);
        listenUiActive.put(uuid, false);

        // Stop static loop; if some other mode needs it, it will be restarted by that
        // mode.
        stopFilterLongSound(player);

        RadioChannel txChannel = transmitChannelByPlayer.get(uuid);
        boolean txActive = Boolean.TRUE.equals(transmitUiActive.get(uuid));
        applyHotbarVisuals(player, txChannel, txActive, System.currentTimeMillis());
    }

    /**
     * Emergency stop for all radio-related active states.
     * This is intentionally aggressive: it clears transmit/listen/eavesdrop flags
     * and stops looping sounds.
     */
    public void forceStopAllRadioModes(Player player) {
        if (player == null) {
            return;
        }

        UUID uuid = player.getUniqueId();

        try {
            if (radioState != null) {
                radioState.setTransmitting(uuid, null);
                radioState.stopPirateEavesdrop(uuid);
            }
        } catch (Throwable ignored) {
            // best-effort
        }

        setHoldToTalkActive(uuid, false);

        // These also ensure visuals can't get stuck.
        forceStopTransmitVisuals(player);
        forceStopListenVisuals(player);

        // Ensure static is definitely off after clearing all modes.
        stopFilterLongSound(player);
    }

    /**
     * Best-effort normalization: if the player is holding a talking variant
     * (<base>_1) in main hand,
     * swap it back to the base item. This prevents "stuck" textures on
     * logout/reload.
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
            syncRadioDurability(current, normalized);
            player.getInventory().setItemInMainHand(normalized);
        }
    }

    /**
     * If the given stack is a talking variant (<base>_1) of one of our radios,
     * returns a new base ItemStack.
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

    public boolean isListenUiActive(UUID uuid) {
        return uuid != null && Boolean.TRUE.equals(listenUiActive.get(uuid));
    }

    /**
     * Config: whether radios may only be stored in a player's own inventory.
     * If true, attempts to place radios into non-player inventories will be
     * blocked.
     */
    public boolean radiosOnlyInPlayerInventory() {
        try {
            return getConfig().getBoolean("radiosOnlyInPlayerInventory.enabled", true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * Send a configurable notification to the player when an action is blocked
     * due to the radios-only-in-player-inventory rule.
     */
    public void notifyRadiosOnlyInPlayerInventory(Player player) {
        if (player == null)
            return;
        String raw = getConfig().getString("radiosOnlyInPlayerInventory.actionBar",
                "&cRadios may only be stored in your inventory.");
        try {
            player.sendActionBar(legacy.deserialize(translateLegacyColors(raw)));
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    /**
     * Best-effort safety: ensure that radios stored outside the main hand are never
     * left in the ON stage.
     * This prevents "stealing" an active radio from inventories/chests.
     */
    public void normalizeRadiosForStorage(Player player) {
        if (player == null || itemUtil == null) {
            return;
        }

        boolean changed = false;

        // Hotbar (0-8) may show OFF/TRANSMIT/LISTEN visuals.
        // Only normalize storage slots.
        for (int slot = 9; slot < 36; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            ItemStack normalized = normalizeTalkingVariantToBase(item);
            if (normalized != null) {
                player.getInventory().setItem(slot, normalized);
                changed = true;
            }
        }

        // Offhand should never be ON.
        ItemStack offhand = player.getInventory().getItemInOffHand();
        ItemStack offhandNorm = normalizeTalkingVariantToBase(offhand);
        if (offhandNorm != null) {
            player.getInventory().setItemInOffHand(offhandNorm);
            changed = true;
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
                            changed = true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }

        if (changed) {
            try {
                player.updateInventory();
            } catch (Throwable ignored) {
            }
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

    /**
     * Mark that the given player has opened/closed a Backpack-like GUI.
     * Call with `open=true` when the custom GUI is shown and `false` when it
     * closes.
     * When set, listeners will block moving/picking radio items to avoid
     * duplication.
     */
    public void markPlayerBackpackGuiOpen(Player player, boolean open) {
        if (player == null)
            return;
        UUID uuid = player.getUniqueId();
        if (open) {
            backpackGuiOpen.put(uuid, true);
            if (isDevMode())
                getLogger().info("[WT-DEBUG] Marked player " + player.getName() + " as in Backpack GUI");
        } else {
            backpackGuiOpen.remove(uuid);
            if (isDevMode())
                getLogger().info("[WT-DEBUG] Unmarked player " + player.getName() + " from Backpack GUI");
        }
    }

    public boolean isPlayerInBackpackGui(UUID uuid) {
        return uuid != null && Boolean.TRUE.equals(backpackGuiOpen.get(uuid));
    }

    // syncDamageToAllVariants removed: do not copy damage across inventory
    // variants.

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
            boolean hasListen = player.hasPermission(channel.listenPermission());
            if (hasListen) {
                maybeNotifyNoTransmit(player, channel);
            } else {
                maybeNotifyNoAccess(player, channel);
            }
            if (debugEnabled) {
                debugLog("noTxPerm:" + uuid,
                        "Missing use permission for channel=" + channel.id() + " perm=" + channel.usePermission());
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

        debugLog("sound:" + path,
                "playFeedbackSound called; configured='" + soundName + "' volume=" + volume + " pitch=" + pitch);
        Sound sound = resolveSound(soundName);
        if (sound != null) {
            debugLog("sound:" + path, "Resolved enum Sound=" + String.valueOf(sound));
            player.playSound(player.getLocation(), sound, volume, pitch);
            return;
        }
        debugLog("sound:" + path,
                "Did not resolve enum Sound for='" + soundName + "', attempting resource-pack key fallback");

        // Fallback: allow custom resource-pack sound events (e.g. ItemsAdder:
        // "radio:tx_start").
        try {
            player.playSound(player.getLocation(), soundName, volume, pitch);
        } catch (Throwable t) {
            debugLog("sound:" + path, "Invalid sound in config: " + path + ".sound='" + soundName + "' ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
        }
    }

    public void playTransmitStartSound(Player player) {
        // TX uses only the dedicated sounds.transmitStart.
        // Generic sounds.start is reserved for LISTEN and pirate eavesdrop UX.
        String soundName = getConfig().getString("sounds.transmitStart.sound", "");
        if (soundName != null && !soundName.isBlank()) {
            debugLog("sound:transmitStart", "playTransmitStartSound called; configured='" + soundName + "'");
            playFeedbackSound(player, "sounds.transmitStart");
        }
    }

    public void playTransmitStopSound(Player player) {
        // TX uses only the dedicated sounds.transmitStop.
        // Generic sounds.stop is reserved for LISTEN and pirate eavesdrop UX.
        String soundName = getConfig().getString("sounds.transmitStop.sound", "");
        if (soundName != null && !soundName.isBlank()) {
            debugLog("sound:transmitStop", "playTransmitStopSound called; configured='" + soundName + "'");
            playFeedbackSound(player, "sounds.transmitStop");
        }
    }

    public void playFilterLongSound(Player player) {
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

        debugLog("sound:" + basePath,
                "playFilterLongSound called; configured='" + soundName + "' volume=" + volume + " pitch=" + pitch);

        try {
            // Plays a custom resource-pack sound event by key (e.g. "radio:filter_long").
            player.playSound(player.getLocation(), soundName, volume, pitch);
        } catch (Throwable t) {
            debugLog("sound:" + basePath, "Failed to play custom sound '" + soundName + "': "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public void stopFilterLongSound(Player player) {
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

        // 2) Accept registry keys: "minecraft:block.note_block.pling" or
        // "block.note_block.pling"
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

    public void maybeNotifyNoTransmit(Player player, RadioChannel channel) {
        maybeNotifyPermission(player, channel, "notifications.permissions.noTransmit", lastNoTransmitHintAtMs);
    }

    public void maybeNotifyNoListen(Player player, RadioChannel channel) {
        if (player != null && channel != null && !player.hasPermission(channel.usePermission())) {
            maybeNotifyNoAccess(player, channel);
            return;
        }
        maybeNotifyPermission(player, channel, "notifications.permissions.noListen", lastNoListenHintAtMs);
    }

    public void maybeNotifyNoAccess(Player player, RadioChannel channel) {
        maybeNotifyPermission(player, channel, "notifications.permissions.noAccess", lastNoAccessHintAtMs);
    }

    private void maybeNotifyPermission(Player player, RadioChannel channel, String path, Map<UUID, Long> lastMap) {
        if (player == null || channel == null || path == null || path.isBlank() || lastMap == null) {
            return;
        }

        // Only notify if the player is actively holding the relevant radio in main
        // hand.
        // This prevents spurious "no listen/no transmit" messages on unrelated hotbar
        // changes.
        try {
            if (itemUtil != null) {
                RadioChannel held = itemUtil.getChannel(player.getInventory().getItemInMainHand());
                if (held != channel) {
                    return;
                }
            } else {
                // If itemUtil unavailable, avoid noisy notifications.
                return;
            }
        } catch (Throwable ignored) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        Long last = lastMap.get(uuid);
        if (last != null && (nowMs - last) < permissionHintCooldownMs) {
            return;
        }
        lastMap.put(uuid, nowMs);

        boolean enabled = getConfig().getBoolean(path + ".enabled", true);
        if (!enabled) {
            return;
        }

        String raw = getConfig().getString(path + ".actionBar", "");
        if (raw == null || raw.isBlank()) {
            return;
        }

        String msg = raw.replace("{channel}", channel.id());
        player.sendActionBar(legacy.deserialize(translateLegacyColors(msg)));
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

        // If Simple Voice Chat isn't installed, we must not load classes that depend on
        // its API.
        try {
            Class.forName("de.maxhenkel.voicechat.api.VoicechatPlugin", false, getClassLoader());
        } catch (ClassNotFoundException e) {
            getLogger().warning(
                    "Simple Voice Chat API not found. Install the 'voicechat' plugin to enable voice features.");
            return;
        }

        try {
            Class<?> bridgeClass = Class.forName("me.Luki.WalkieTalkiePlugin.voice.VoicechatBridge", true,
                    getClassLoader());
            bridgeClass.getMethod("tryRegister", WalkieTalkiePlugin.class).invoke(null, this);
        } catch (Throwable t) {
            getLogger().severe("Failed to hook Simple Voice Chat. Voice features disabled.");
            t.printStackTrace();
        }
    }

    public boolean hotbarDetailedLoggingEnabled() {
        try {
            return getConfig().getBoolean("debug.hotbarDetailedLogging", false) || isDevMode();
        } catch (Throwable ignored) {
            return isDevMode();
        }
    }
}
