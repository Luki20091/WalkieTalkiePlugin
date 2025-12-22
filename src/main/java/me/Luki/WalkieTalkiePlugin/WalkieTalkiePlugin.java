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
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Map;
import java.util.Locale;
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
    private BukkitRunnable pttHintTask;

    private static final Map<String, Sound> SOUND_LOOKUP = new ConcurrentHashMap<>();

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

        // Register crafting recipes + permissions
        RecipeRegistrar registrar = new RecipeRegistrar(this, radioRegistry, itemsAdder, channelKey(base));
        var recipes = registrar.registerAll();
        getServer().getPluginManager().registerEvents(new CraftPermissionListener(recipes), this);

        // Transmit activation: hold radio in main hand + use Simple Voice Chat PTT.
        // (No additional hold-to-talk listener needed.)

        VoicechatBridge.tryRegister(this);

        startPttHintTask();

    }

    @Override
    public void onDisable() {
        // Clear all state to avoid stuck transmit flags after reload/disable
        if (radioState != null) {
            // Best-effort: clear all online players
            getServer().getOnlinePlayers().forEach(p -> radioState.clear(p.getUniqueId()));
        }

        if (pttHintTask != null) {
            pttHintTask.cancel();
            pttHintTask = null;
        }
        lastMicPacketAtMs.clear();
        lastPttHintAtMs.clear();
    }

    public void recordMicPacket(UUID senderUuid) {
        if (senderUuid == null) {
            return;
        }
        lastMicPacketAtMs.put(senderUuid, System.currentTimeMillis());
    }

    private void startPttHintTask() {
        boolean enabled = getConfig().getBoolean("hints.pttRequired.enabled", true);
        boolean svcEnabled = getConfig().getBoolean("svc.enabled", true);
        if (!enabled || !svcEnabled) {
            return;
        }

        int checkEveryTicks = Math.max(1, getConfig().getInt("hints.pttRequired.checkEveryTicks", 10));
        long afterMs = Math.max(0L, getConfig().getLong("hints.pttRequired.afterMs", 750L));
        int cooldownTicks = Math.max(0, getConfig().getInt("hints.pttRequired.cooldownTicks", 40));
        long cooldownMs = cooldownTicks * 50L;

        pttHintTask = new BukkitRunnable() {
            @Override
            public void run() {
                // If SVC isn't present, hint is misleading.
                if (getServer().getPluginManager().getPlugin("voicechat") == null) {
                    return;
                }

                long now = System.currentTimeMillis();
                for (Player player : getServer().getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();
                    if (radioState == null || itemUtil == null) {
                        continue;
                    }

                    // Only when holding a transmit radio in main hand
                    RadioChannel channel = getTransmitChannel(player);
                    if (channel == null) {
                        continue;
                    }

                    long lastMic = lastMicPacketAtMs.getOrDefault(uuid, 0L);
                    if (now - lastMic < afterMs) {
                        continue;
                    }

                    long lastHint = lastPttHintAtMs.getOrDefault(uuid, 0L);
                    if (cooldownMs > 0 && now - lastHint < cooldownMs) {
                        continue;
                    }

                    playConfiguredNotification(player, "hints.pttRequired");
                    lastPttHintAtMs.put(uuid, now);
                }
            }
        };

        pttHintTask.runTaskTimer(this, checkEveryTicks, checkEveryTicks);
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
        if (player == null || itemUtil == null) {
            return null;
        }
        RadioChannel channel = itemUtil.getChannel(player.getInventory().getItemInMainHand());
        if (channel == null) {
            return null;
        }
        // Pirate random radio is eavesdrop-only
        if (channel == RadioChannel.PIRACI_RANDOM) {
            return null;
        }
        if (!player.hasPermission(channel.usePermission())) {
            return null;
        }
        return channel;
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
