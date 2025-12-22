package me.Luki.WalkieTalkiePlugin.radio;

import org.bukkit.NamespacedKey;

public final class RadioItemKeys {

    private RadioItemKeys() {
    }

    public static NamespacedKey channelKey(NamespacedKey pluginBaseKey) {
        // NamespacedKey does not allow nesting; we reuse plugin namespace
        return new NamespacedKey(pluginBaseKey.getNamespace(), "radio_channel");
    }
}
