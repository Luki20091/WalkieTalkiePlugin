package me.Luki.WalkieTalkiePlugin.commands;

import me.Luki.WalkieTalkiePlugin.WalkieTalkiePlugin;
import me.Luki.WalkieTalkiePlugin.voice.VoiceBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class WalkieTalkieCommand implements CommandExecutor, TabCompleter {

    private final WalkieTalkiePlugin plugin;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    public WalkieTalkieCommand(WalkieTalkiePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            send(sender, prefix() + "&7Użycie: /" + label + " <reload|info>");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("walkietalkie.reload")) {
                    send(sender, prefix() + noPermission());
                    return true;
                }
                plugin.reloadPlugin();
                send(sender, prefix() + plugin.getConfig().getString("commands.messages.reloaded", "&aPrzeładowano konfigurację."));
                return true;
            }
            case "info" -> {
                if (!sender.hasPermission("walkietalkie.info")) {
                    send(sender, prefix() + noPermission());
                    return true;
                }

                String version = plugin.getPluginMeta().getVersion();
                boolean svcEnabled = plugin.getConfig().getBoolean("svc.enabled", true);
                boolean sameWorldOnly = plugin.getConfig().getBoolean("svc.sameWorldOnly", true);
                double radioMinDistance = plugin.getConfig().getDouble("svc.radioMinDistanceBlocks", 20.0);
                int svcPort = plugin.getConfig().getInt("svc.port", 24454);

                List<String> lines = plugin.getConfig().getStringList("commands.messages.info");
                if (lines == null || lines.isEmpty()) {
                    send(sender, prefix() + "&bWalkieTalkiePlugin &7v" + version);
                    send(sender, prefix() + "&7SVC: &f" + svcEnabled + " &7worldOnly: &f" + sameWorldOnly);
                    send(sender, prefix() + "&7radioMinDistance: &f" + radioMinDistance);
                    VoiceBridge bridge = plugin.getVoicechatBridge();
                    send(sender, prefix() + "&7SVC hook: &f" + (bridge != null && bridge.isHooked()));
                    return true;
                }

                for (String raw : lines) {
                    if (raw == null) {
                        continue;
                    }
                    String rendered = raw
                        .replace("{version}", version)
                        .replace("{svcEnabled}", String.valueOf(svcEnabled))
                        .replace("{sameWorldOnly}", String.valueOf(sameWorldOnly))
                        .replace("{radioMinDistance}", String.valueOf(radioMinDistance))
                        .replace("{svcPort}", String.valueOf(svcPort));
                    send(sender, prefix() + rendered);
                }
                return true;
            }
            default -> {
                send(sender, prefix() + "&7Użycie: /" + label + " <reload|info>");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String start = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if ("reload".startsWith(start) && sender.hasPermission("walkietalkie.reload")) {
                out.add("reload");
            }
            if ("info".startsWith(start) && sender.hasPermission("walkietalkie.info")) {
                out.add("info");
            }
            return out;
        }
        return List.of();
    }

    private String prefix() {
        return plugin.getConfig().getString("commands.messages.prefix", "");
    }

    private String noPermission() {
        return plugin.getConfig().getString("commands.messages.noPermission", "&cBrak permisji.");
    }

    private void send(CommandSender sender, String message) {
        if (sender == null) {
            return;
        }
        sender.sendMessage(toComponent(message));
    }

    private Component toComponent(String input) {
        if (input == null) {
            return Component.empty();
        }
        return legacy.deserialize(input.replace('&', '§'));
    }
}
