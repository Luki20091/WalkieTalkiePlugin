package me.Luki.WalkieTalkiePlugin.listeners;

import me.Luki.WalkieTalkiePlugin.WalkieTalkiePlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;

/**
 * If Simple Voice Chat loads after this plugin, automatically retry hooking.
 * Uses only Bukkit events and calls into the plugin's reflective hook method.
 */
public final class VoicechatAutoHookListener implements Listener {

    private final WalkieTalkiePlugin plugin;

    public VoicechatAutoHookListener(WalkieTalkiePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (event == null || event.getPlugin() == null) {
            return;
        }

        String name = event.getPlugin().getName();
        if (name == null || !name.equalsIgnoreCase("voicechat")) {
            return;
        }

        // Give Voice Chat a tick to register its services.
        plugin.runNextTick(plugin::tryRegisterVoicechatIntegrationIfNeeded);
    }
}
