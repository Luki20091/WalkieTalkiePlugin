package me.Luki.WalkieTalkiePlugin;

import me.Luki.WalkieTalkiePlugin.listeners.RadioListeners;
import me.Luki.WalkieTalkiePlugin.listeners.CraftPermissionListener;
import me.Luki.WalkieTalkiePlugin.listeners.VoicechatAutoHookListener;
import me.Luki.WalkieTalkiePlugin.items.ItemsAdderBridge;
import me.Luki.WalkieTalkiePlugin.radio.RadioChannel;
import me.Luki.WalkieTalkiePlugin.radio.RadioItemUtil;
import me.Luki.WalkieTalkiePlugin.radio.RadioRegistry;
import me.Luki.WalkieTalkiePlugin.radio.RadioState;
import me.Luki.WalkieTalkiePlugin.recipes.RecipeRegistrar;
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

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static me.Luki.WalkieTalkiePlugin.radio.RadioItemKeys.channelKey;

public final class WalkieTalkiePlugin extends JavaPlugin {

    private RadioState radioState;
    private RadioRegistry radioRegistry;
    private RadioItemUtil itemUtil;

    private ItemsAdderBridge itemsAdder;
    private NamespacedKey radioChannelPdcKey;

    private final Map<UUID, Long> lastMicPacketAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPttHintAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> transmitUiActive = new ConcurrentHashMap<>();
    private BukkitRunnable radioUiTask;

    private volatile VoiceBridge voicechatBridge;

    private static final Map<String, Sound> SOUND_LOOKUP = new ConcurrentHashMap<>();

    private final Map<UUID, RadioChannel> transmitChannelByPlayer = new ConcurrentHashMap<>();
    private final Set<UUID> pttHintCandidates = ConcurrentHashMap.newKeySet();

    private final Map<UUID, PermissionSnapshot> permissionSnapshots = new ConcurrentHashMap<>();

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
            });

            // Register crafting recipes + permissions
            RecipeRegistrar registrar = new RecipeRegistrar(this, radioRegistry, this.itemsAdder, radioChannelPdcKey);
            var recipes = registrar.registerAll();
            getServer().getPluginManager().registerEvents(new CraftPermissionListener(recipes), this);

            // Transmit activation: hold radio in main hand + use Simple Voice Chat PTT.
            // (No additional hold-to-talk listener needed.)
            tryRegisterVoicechatIntegrationIfNeeded();

            startRadioUiTask();

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

        if (radioUiTask != null) {
            radioUiTask.cancel();
            radioUiTask = null;
        }

        lastMicPacketAtMs.clear();
        lastPttHintAtMs.clear();
        transmitUiActive.clear();
        transmitChannelByPlayer.clear();
        pttHintCandidates.clear();
        permissionSnapshots.clear();

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

        if (radioRegistry != null) {
            radioRegistry.reload(getConfig().getConfigurationSection("radios"));
        }

        VoiceBridge bridge = voicechatBridge;
        if (bridge != null) {
            bridge.reloadFromConfig();
        }

        // restart task to apply config changes
        if (radioUiTask != null) {
            radioUiTask.cancel();
            radioUiTask = null;
        }

        // Clear UI state to avoid stuck "transmitting" indicators
        transmitUiActive.clear();
        startRadioUiTask();

        // Refresh caches for online players
        getServer().getOnlinePlayers().forEach(p -> {
            if (radioState != null) {
                radioState.refreshHotbar(p);
            }
            refreshTransmitCache(p);
            refreshPermissionCache(p);
        });
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

    public void recordMicPacket(UUID senderUuid) {
        if (senderUuid == null) {
            return;
        }
        lastMicPacketAtMs.put(senderUuid, System.currentTimeMillis());
    }

    private void loadDebugConfig() {
        this.devMode = getConfig().getBoolean("dev", false);
        this.debugEnabled = devMode || getConfig().getBoolean("debug.enabled", false);
        long interval = getConfig().getLong("debug.logIntervalMs", 5000L);
        this.debugLogIntervalMs = Math.max(250L, interval);
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

    private void startRadioUiTask() {
        boolean svcEnabled = getConfig().getBoolean("svc.enabled", true);
        if (!svcEnabled) {
            return;
        }

        boolean hintEnabled = getConfig().getBoolean("hints.pttRequired.enabled", true);
        int hintCheckEveryTicks = Math.max(1, getConfig().getInt("hints.pttRequired.checkEveryTicks", 10));
        long hintAfterMs = Math.max(0L, getConfig().getLong("hints.pttRequired.afterMs", 750L));
        int hintCooldownTicks = Math.max(0, getConfig().getInt("hints.pttRequired.cooldownTicks", 40));
        long hintCooldownMs = hintCooldownTicks * 50L;

        boolean filterEnabled = getConfig().getBoolean("sounds.filterLoop.enabled", true);
        int filterEveryTicks = Math.max(1, getConfig().getInt("sounds.filterLoop.everyTicks", 10));
        long filterActiveForMs = Math.max(0L, getConfig().getLong("sounds.filterLoop.activeForMs", 350L));
        String modeRaw = String.valueOf(getConfig().getString("sounds.filterLoop.mode", "ALWAYS"));
        String mode = modeRaw.trim().toUpperCase(Locale.ROOT);
        boolean filterPttOnly = mode.equals("PTT");

        // Transmit UI (actionbar + start/stop sounds) is driven by actual mic packets.
        // Reuse the filter active window as a sensible default for "PTT is active".
        long transmitActiveForMs = Math.max(150L, filterActiveForMs);

        if (!hintEnabled && !filterEnabled) {
            return;
        }

        int periodTicks;
        if (hintEnabled && filterEnabled) {
            periodTicks = Math.min(hintCheckEveryTicks, filterEveryTicks);
        } else if (hintEnabled) {
            periodTicks = hintCheckEveryTicks;
        } else {
            periodTicks = filterEveryTicks;
        }

        radioUiTask = new BukkitRunnable() {
            private int elapsedTicks = 0;
            private long lastDebugStatsAt = 0L;
            private int selfHealCooldownTicks = 0;

            @Override
            public void run() {
                try {
                    // If SVC isn't present, UI effects are misleading.
                    if (getServer().getPluginManager().getPlugin("voicechat") == null) {
                        return;
                    }

                    // Self-heal in case candidate set got out-of-sync (reloads, missed events, etc.)
                    // This is main-thread only and does NOT poll positions.
                    if (selfHealCooldownTicks <= 0 && (pttHintCandidates.isEmpty() && !getServer().getOnlinePlayers().isEmpty())) {
                        for (Player p : getServer().getOnlinePlayers()) {
                            refreshTransmitCache(p);
                            refreshPermissionCache(p);
                        }
                        selfHealCooldownTicks = 40; // 2s
                        debugLog("selfheal", "Self-heal refreshed caches for online players (candidates were empty)");
                    } else {
                        selfHealCooldownTicks = Math.max(0, selfHealCooldownTicks - periodTicks);
                    }

                    elapsedTicks += periodTicks;
                    boolean doHint = hintEnabled && (elapsedTicks % hintCheckEveryTicks == 0);
                    boolean doFilter = filterEnabled && (elapsedTicks % filterEveryTicks == 0);
                    if (!doHint && !doFilter) {
                        return;
                    }

                    long now = System.currentTimeMillis();

                    if (debugEnabled && now - lastDebugStatsAt >= debugLogIntervalMs) {
                        long mic = dbgMicEvents.getAndSet(0);
                        long noSender = dbgMicNoSender.getAndSet(0);
                        long noReceiver = dbgMicNoReceiver.getAndSet(0);
                        long noTx = dbgMicNoTransmitChannel.getAndSet(0);
                        long crossWorld = dbgMicCrossWorldCancelled.getAndSet(0);
                        long notAllowed = dbgMicNotAllowedCancelled.getAndSet(0);
                        long withinMin = dbgMicWithinMinDistanceProx.getAndSet(0);
                        long sent = dbgMicRadioSent.getAndSet(0);
                        debugLog("stats", "mic=" + mic
                            + " noSender=" + noSender
                            + " noReceiver=" + noReceiver
                            + " noTx=" + noTx
                            + " crossWorldCancel=" + crossWorld
                            + " notAllowedCancel=" + notAllowed
                            + " withinMinProx=" + withinMin
                            + " radioSent=" + sent
                            + " candidates=" + pttHintCandidates.size());
                        lastDebugStatsAt = now;
                    }

                    var iterator = pttHintCandidates.iterator();
                    while (iterator.hasNext()) {
                        UUID uuid = iterator.next();
                        Player player = getServer().getPlayer(uuid);
                        if (player == null) {
                            iterator.remove();
                            transmitUiActive.remove(uuid);
                            continue;
                        }

                        RadioChannel channel = transmitChannelByPlayer.get(uuid);
                        if (channel == null) {
                            iterator.remove();
                            transmitUiActive.remove(uuid);
                            continue;
                        }

                        long lastMic = lastMicPacketAtMs.getOrDefault(uuid, 0L);

                        // Transmit feedback (start/stop) based on mic activity.
                        boolean isActive = lastMic > 0L && (now - lastMic <= transmitActiveForMs);
                        boolean wasActive = Boolean.TRUE.equals(transmitUiActive.get(uuid));
                        if (isActive != wasActive) {
                            if (isActive) {
                                playFeedbackSound(player, "sounds.start");
                                playConfiguredNotification(player, "notifications.transmit.start");
                                applyTransmitTextureVariant(player, channel, true);
                                debugLog("tx:" + uuid, "TX START channel=" + channel.id());
                            } else {
                                playFeedbackSound(player, "sounds.stop");
                                playConfiguredNotification(player, "notifications.transmit.stop");
                                applyTransmitTextureVariant(player, channel, false);
                                debugLog("tx:" + uuid, "TX STOP channel=" + channel.id());
                            }
                            transmitUiActive.put(uuid, isActive);
                        }

                        if (doHint) {
                            if (now - lastMic >= hintAfterMs) {
                                long lastHint = lastPttHintAtMs.getOrDefault(uuid, 0L);
                                if (hintCooldownMs <= 0 || now - lastHint >= hintCooldownMs) {
                                    playConfiguredNotification(player, "hints.pttRequired");
                                    lastPttHintAtMs.put(uuid, now);
                                    debugLog("hint:" + uuid, "PTT hint shown (no mic packets recently)");
                                }
                            }
                        }

                        if (doFilter) {
                            if (!filterPttOnly || filterActiveForMs <= 0 || now - lastMic <= filterActiveForMs) {
                                // Local-only pulse that gives a subtle "radio filter" feel.
                                playFeedbackSound(player, "sounds.filterLoop");
                            }
                        }
                    }
                } catch (Throwable t) {
                    getLogger().severe("[WT] radio UI task crashed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    t.printStackTrace();
                }
            }
        };

        radioUiTask.runTaskTimer(this, periodTicks, periodTicks);
    }

    private void applyTransmitTextureVariant(Player player, RadioChannel channel, boolean transmitting) {
        if (player == null || channel == null) {
            return;
        }
        if (itemUtil == null || radioRegistry == null || itemsAdder == null || !itemsAdder.isAvailable()) {
            return;
        }

        ItemStack current = player.getInventory().getItemInMainHand();
        if (current == null || current.getType().isAir()) {
            return;
        }

        // Only touch radios that belong to this channel.
        RadioChannel inHand = itemUtil.getChannel(current);
        if (inHand != channel) {
            return;
        }

        var def = radioRegistry.get(channel);
        if (def == null) {
            return;
        }
        String baseId = def.itemsAdderId();
        if (baseId == null || baseId.isBlank()) {
            return;
        }

        String talkingId = baseId.trim() + "_1";
        String currentId = itemUtil.debugGetItemsAdderId(current);

        if (transmitting) {
            if (equalsIgnoreCase(currentId, talkingId)) {
                return;
            }
            // Only swap if we're holding the base IA item (or if IA ID is unknown but channel is tagged).
            if (currentId != null && !equalsIgnoreCase(currentId, baseId)) {
                return;
            }
            ItemStack swapped = itemsAdder.createItemStack(talkingId, current.getAmount());
            if (swapped == null) {
                debugLog("txtex:" + player.getUniqueId(), "No ItemsAdder talking variant found: " + talkingId);
                return;
            }
            tagRadioChannel(swapped, channel);
            player.getInventory().setItemInMainHand(swapped);
        } else {
            if (!equalsIgnoreCase(currentId, talkingId)) {
                return;
            }
            ItemStack reverted = itemsAdder.createItemStack(baseId, current.getAmount());
            if (reverted == null) {
                debugLog("txtex:" + player.getUniqueId(), "No ItemsAdder base item found: " + baseId);
                return;
            }
            tagRadioChannel(reverted, channel);
            player.getInventory().setItemInMainHand(reverted);
        }
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

        if (itemUtil == null || radioRegistry == null || itemsAdder == null || !itemsAdder.isAvailable()) {
            return;
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
        if (itemUtil == null || radioRegistry == null || itemsAdder == null || !itemsAdder.isAvailable()) {
            return null;
        }

        RadioChannel channel = itemUtil.getChannel(stack);
        if (channel == null) {
            return null;
        }
        var def = radioRegistry.get(channel);
        if (def == null) {
            return null;
        }
        String baseId = def.itemsAdderId();
        if (baseId == null || baseId.isBlank()) {
            return null;
        }

        String talkingId = baseId.trim() + "_1";
        String currentId = itemUtil.debugGetItemsAdderId(stack);
        if (!equalsIgnoreCase(currentId, talkingId)) {
            return null;
        }

        ItemStack reverted = itemsAdder.createItemStack(baseId, stack.getAmount());
        if (reverted == null) {
            return null;
        }
        tagRadioChannel(reverted, channel);
        return reverted;
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
            pttHintCandidates.remove(uuid);
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
            pttHintCandidates.remove(uuid);
            return;
        }

        if (channel == RadioChannel.PIRACI_RANDOM) {
            transmitChannelByPlayer.remove(uuid);
            pttHintCandidates.remove(uuid);
            return;
        }

        if (!player.hasPermission(channel.usePermission())) {
            if (debugEnabled) {
                debugLog("noTxPerm:" + uuid, "Missing use permission for channel=" + channel.id() + " perm=" + channel.usePermission());
            }
            transmitChannelByPlayer.remove(uuid);
            pttHintCandidates.remove(uuid);
            return;
        }

        transmitChannelByPlayer.put(uuid, channel);
        pttHintCandidates.add(uuid);
    }

    public void clearTransmitCache(UUID uuid) {
        if (uuid == null) {
            return;
        }
        transmitChannelByPlayer.remove(uuid);
        pttHintCandidates.remove(uuid);
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
