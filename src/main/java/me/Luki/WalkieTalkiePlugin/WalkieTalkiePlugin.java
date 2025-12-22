package me.Luki.WalkieTalkiePlugin;

import me.Luki.WalkieTalkiePlugin.listeners.RadioListeners;
import me.Luki.WalkieTalkiePlugin.listeners.CraftPermissionListener;
import me.Luki.WalkieTalkiePlugin.items.ItemsAdderBridge;
import me.Luki.WalkieTalkiePlugin.radio.RadioChannel;
import me.Luki.WalkieTalkiePlugin.radio.RadioItemUtil;
import me.Luki.WalkieTalkiePlugin.radio.RadioRegistry;
import me.Luki.WalkieTalkiePlugin.radio.RadioState;
import me.Luki.WalkieTalkiePlugin.recipes.RecipeRegistrar;
import me.Luki.WalkieTalkiePlugin.voice.VoicechatBridge;
import me.Luki.WalkieTalkiePlugin.commands.WalkieTalkieCommand;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import static me.Luki.WalkieTalkiePlugin.radio.RadioItemKeys.channelKey;

public final class WalkieTalkiePlugin extends JavaPlugin {

    private RadioState radioState;
    private RadioRegistry radioRegistry;
    private RadioItemUtil itemUtil;

    private final Map<UUID, Long> lastMicPacketAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPttHintAtMs = new ConcurrentHashMap<>();
    private BukkitRunnable radioUiTask;

    private volatile VoicechatBridge voicechatBridge;

    private static final Map<String, Sound> SOUND_LOOKUP = new ConcurrentHashMap<>();

    private final Map<UUID, RadioChannel> transmitChannelByPlayer = new ConcurrentHashMap<>();
    private final Set<UUID> pttHintCandidates = ConcurrentHashMap.newKeySet();

    private final Map<UUID, PermissionSnapshot> permissionSnapshots = new ConcurrentHashMap<>();

    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        NamespacedKey base = new NamespacedKey(this, "walkietalkie");
        ItemsAdderBridge itemsAdder = new ItemsAdderBridge();
        radioRegistry = new RadioRegistry(getConfig().getConfigurationSection("radios"));
        itemUtil = new RadioItemUtil(channelKey(base), radioRegistry, itemsAdder);
        radioState = new RadioState(itemUtil);

        getServer().getPluginManager().registerEvents(new RadioListeners(this, itemUtil), this);

        // Populate caches for already-online players (e.g. plugin reload while players are online)
        getServer().getOnlinePlayers().forEach(p -> {
            radioState.refreshHotbar(p);
            refreshTransmitCache(p);
            refreshPermissionCache(p);
        });

        // Register crafting recipes + permissions
        RecipeRegistrar registrar = new RecipeRegistrar(this, radioRegistry, itemsAdder, channelKey(base));
        var recipes = registrar.registerAll();
        getServer().getPluginManager().registerEvents(new CraftPermissionListener(recipes), this);

        // Transmit activation: hold radio in main hand + use Simple Voice Chat PTT.
        // (No additional hold-to-talk listener needed.)

        VoicechatBridge.tryRegister(this);

        startRadioUiTask();

        var command = getCommand("walkietalkie");
        if (command != null) {
            WalkieTalkieCommand executor = new WalkieTalkieCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

    }

    @Override
    public void onDisable() {
        // Clear all state to avoid stuck transmit flags after reload/disable
        if (radioState != null) {
            // Best-effort: clear all online players
            getServer().getOnlinePlayers().forEach(p -> radioState.clear(p.getUniqueId()));
        }

        if (radioUiTask != null) {
            radioUiTask.cancel();
            radioUiTask = null;
        }

        lastMicPacketAtMs.clear();
        lastPttHintAtMs.clear();
        transmitChannelByPlayer.clear();
        pttHintCandidates.clear();
        permissionSnapshots.clear();
    }

    public void setVoicechatBridge(VoicechatBridge bridge) {
        this.voicechatBridge = bridge;
    }

    public VoicechatBridge getVoicechatBridge() {
        return voicechatBridge;
    }

    public void reloadPlugin() {
        reloadConfig();

        if (radioRegistry != null) {
            radioRegistry.reload(getConfig().getConfigurationSection("radios"));
        }

        VoicechatBridge bridge = voicechatBridge;
        if (bridge != null) {
            bridge.reloadFromConfig();
        }

        // restart task to apply config changes
        if (radioUiTask != null) {
            radioUiTask.cancel();
            radioUiTask = null;
        }
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

            @Override
            public void run() {
                // If SVC isn't present, UI effects are misleading.
                if (getServer().getPluginManager().getPlugin("voicechat") == null) {
                    return;
                }

                elapsedTicks += periodTicks;
                boolean doHint = hintEnabled && (elapsedTicks % hintCheckEveryTicks == 0);
                boolean doFilter = filterEnabled && (elapsedTicks % filterEveryTicks == 0);
                if (!doHint && !doFilter) {
                    return;
                }

                long now = System.currentTimeMillis();
                var iterator = pttHintCandidates.iterator();
                while (iterator.hasNext()) {
                    UUID uuid = iterator.next();
                    Player player = getServer().getPlayer(uuid);
                    if (player == null) {
                        iterator.remove();
                        continue;
                    }

                    RadioChannel channel = transmitChannelByPlayer.get(uuid);
                    if (channel == null) {
                        iterator.remove();
                        continue;
                    }

                    long lastMic = lastMicPacketAtMs.getOrDefault(uuid, 0L);

                    if (doHint) {
                        if (now - lastMic >= hintAfterMs) {
                            long lastHint = lastPttHintAtMs.getOrDefault(uuid, 0L);
                            if (hintCooldownMs <= 0 || now - lastHint >= hintCooldownMs) {
                                playConfiguredNotification(player, "hints.pttRequired");
                                lastPttHintAtMs.put(uuid, now);
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
            }
        };

        radioUiTask.runTaskTimer(this, periodTicks, periodTicks);
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
        RadioChannel channel = itemUtil.getChannel(player.getInventory().getItemInMainHand());
        if (channel == null || channel == RadioChannel.PIRACI_RANDOM || !player.hasPermission(channel.usePermission())) {
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
            getLogger().warning("Invalid sound in config: " + path + ".sound='" + soundName + "'");
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
}
