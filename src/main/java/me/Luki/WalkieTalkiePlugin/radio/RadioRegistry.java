package me.Luki.WalkieTalkiePlugin.radio;

import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumMap;
import java.util.Map;

public final class RadioRegistry {

    private final Map<RadioChannel, RadioDefinition> definitions = new EnumMap<>(RadioChannel.class);

    public RadioRegistry(ConfigurationSection radiosSection) {
        // Minimal, hard-coded display names (ItemsAdder will provide visuals).
        register(radiosSection, RadioChannel.CZERWONI, "Radio Czerwonych", "czerwoni");
        register(radiosSection, RadioChannel.NIEBIESCY, "Radio Niebieskich", "niebiescy");
        register(radiosSection, RadioChannel.HANDLARZE, "Radio Handlarzy", "handlarze");
        register(radiosSection, RadioChannel.PIRACI, "Radio Piratów", "piraci");
        register(radiosSection, RadioChannel.TOHANDLARZE, "Radio Do Handlarzy", "tohandlarze");
        register(radiosSection, RadioChannel.PIRACI_RANDOM, "Stare Czarne Radio Piratów", "piraci_random");
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

    public Map<RadioChannel, RadioDefinition> all() {
        return definitions;
    }
}
