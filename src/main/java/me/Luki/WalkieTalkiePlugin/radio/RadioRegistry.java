package me.Luki.WalkieTalkiePlugin.radio;

import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class RadioRegistry {

    private final Map<RadioChannel, RadioDefinition> definitions = new EnumMap<>(RadioChannel.class);
    private final Map<String, RadioChannel> itemsAdderIdToChannel = new HashMap<>();

    public RadioRegistry(ConfigurationSection radiosSection) {
        reload(radiosSection);
    }

    public void reload(ConfigurationSection radiosSection) {
        definitions.clear();
        itemsAdderIdToChannel.clear();

        // Minimal, hard-coded display names (ItemsAdder will provide visuals).
        register(radiosSection, RadioChannel.CZERWONI, "Radio Czerwonych", "czerwoni");
        register(radiosSection, RadioChannel.NIEBIESCY, "Radio Niebieskich", "niebiescy");
        register(radiosSection, RadioChannel.HANDLARZE, "Radio Handlarzy", "handlarze");
        register(radiosSection, RadioChannel.PIRACI, "Radio Piratów", "piraci");
        register(radiosSection, RadioChannel.STREAMCRAFT, "StreamCraft", "streamcraft");
        register(radiosSection, RadioChannel.PIRACI_RANDOM, "Stare Radio Piratów", "piraci_random");

        for (RadioDefinition def : definitions.values()) {
            String id = def.itemsAdderId();
            if (id == null || id.isBlank()) {
                continue;
            }
            String raw = id.trim();

            // Version 2 convention: separate ItemsAdder items for stages.
            // Support both:
            // - base id:   radio:radio_czerwoni (OFF)
            // - staged id: radio:radio_czerwoni_0 (OFF)
            // In both cases, we accept _0/_1/_2 as the same channel.
            String base = stripStageSuffix(raw);

            itemsAdderIdToChannel.put(raw.toLowerCase(Locale.ROOT), def.channel());
            itemsAdderIdToChannel.put(base.toLowerCase(Locale.ROOT), def.channel());
            itemsAdderIdToChannel.put((base + "_0").toLowerCase(Locale.ROOT), def.channel());
            itemsAdderIdToChannel.put((base + "_1").toLowerCase(Locale.ROOT), def.channel());
            itemsAdderIdToChannel.put((base + "_2").toLowerCase(Locale.ROOT), def.channel());
        }
    }

    private static String stripStageSuffix(String id) {
        if (id == null) {
            return "";
        }
        String s = id.trim();
        if (s.endsWith("_0") || s.endsWith("_1") || s.endsWith("_2")) {
            return s.substring(0, s.length() - 2);
        }
        return s;
    }

    private void register(ConfigurationSection section, RadioChannel channel, String name, String key) {
        String id = section != null ? section.getString(key, "") : "";
        if (id != null) {
            id = id.trim();
        }
        definitions.put(channel, new RadioDefinition(channel, name, id));
    }

    public RadioDefinition get(RadioChannel channel) {
        return definitions.get(channel);
    }

    public RadioChannel getChannelByItemsAdderId(String itemsAdderId) {
        if (itemsAdderId == null) {
            return null;
        }
        return itemsAdderIdToChannel.get(itemsAdderId.trim().toLowerCase(Locale.ROOT));
    }

    public Map<RadioChannel, RadioDefinition> all() {
        return definitions;
    }
}
