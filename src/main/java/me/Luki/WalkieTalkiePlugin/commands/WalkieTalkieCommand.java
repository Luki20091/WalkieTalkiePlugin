package me.Luki.WalkieTalkiePlugin.commands;

import me.Luki.WalkieTalkiePlugin.WalkieTalkiePlugin;
import me.Luki.WalkieTalkiePlugin.voice.VoiceBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class WalkieTalkieCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "walkietalkie.admin";

    private final WalkieTalkiePlugin plugin;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    public WalkieTalkieCommand(WalkieTalkiePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            send(sender, prefix() + noPermission());
            return true;
        }

        if (args.length == 0) {
            send(sender, prefix() + usage(label));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                plugin.reloadPlugin();
                send(sender, prefix() + plugin.getConfig().getString("commands.messages.reloaded", "&aPrzeładowano konfigurację."));
                return true;
            }
            case "info" -> {
                String version = plugin.getPluginMeta().getVersion();
                boolean svcEnabled = plugin.getConfig().getBoolean("svc.enabled", true);
                boolean sameWorldOnly = plugin.getConfig().getBoolean("svc.sameWorldOnly", true);
                double radioMinDistance = plugin.getConfig().getDouble("svc.radioMinDistanceBlocks", 20.0);
                int svcPort = plugin.getConfig().getInt("svc.port", 24454);
                String svcHost = String.valueOf(plugin.getConfig().getString("svc.host", "127.0.0.1")).trim();
                String svcAddressLegacy = String.valueOf(plugin.getConfig().getString("svc.address", "")).trim();
                String svcAddress;
                if (!svcHost.isBlank()) {
                    svcAddress = svcHost + ":" + svcPort;
                } else if (!svcAddressLegacy.isBlank()) {
                    svcAddress = svcAddressLegacy;
                } else {
                    svcAddress = "127.0.0.1:" + svcPort;
                }
                boolean svcHooked = false;
                try {
                    VoiceBridge bridge = plugin.getVoicechatBridge();
                    svcHooked = bridge != null && bridge.isHooked();
                } catch (Throwable ignored) {
                    svcHooked = false;
                }
                String radioScope = plugin.isRadioGlobalScope() ? "GLOBAL" : "WORLD";

                List<String> lines = plugin.getConfig().getStringList("commands.messages.info");
                if (lines == null || lines.isEmpty()) {
                    lines = List.of(
                        "&bWalkieTalkiePlugin &7v{version}",
                        "&7SVC: &f{svcEnabled} &7worldOnly: &f{sameWorldOnly}",
                        "&7radioMinDistance: &f{radioMinDistance}",
                        "&7voiceHost: &f{svcHost}",
                        "&7voicePort: &f{svcPort}",
                        "&7SVC hook: &f{svcHooked}",
                        "&7radioScope: &f{radioScope}"
                    );
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
                        .replace("{svcPort}", String.valueOf(svcPort))
                        .replace("{svcHost}", String.valueOf(svcHost))
                        .replace("{svcAddress}", String.valueOf(svcAddress))
                        .replace("{svcHooked}", String.valueOf(svcHooked))
                        .replace("{radioScope}", String.valueOf(radioScope));
                    send(sender, prefix() + rendered);
                }
                return true;
            }
            case "debug" -> {
                // /wt debug
                // /wt debug player <name>
                if (args.length == 1) {
                    send(sender, prefix() + "&7[WT] &f" + plugin.debugGetMicCountersLine());
                    return true;
                }

                if (args.length >= 3 && "player".equalsIgnoreCase(args[1])) {
                    String name = args[2];
                    Player target = Bukkit.getPlayerExact(name);
                    if (target == null) {
                        send(sender, prefix() + "&cGracz nie jest online: &f" + name);
                        return true;
                    }

                    var uuid = target.getUniqueId();
                    var inHand = plugin.getItemUtil() != null ? plugin.getItemUtil().getChannel(target.getInventory().getItemInMainHand()) : null;
                    var offHand = plugin.getItemUtil() != null ? plugin.getItemUtil().getChannel(target.getInventory().getItemInOffHand()) : null;

                    send(sender, prefix() + "&7[WT] &bDebug player &f" + target.getName());
                    send(sender, prefix() + "&7uuid: &f" + uuid);
                    send(sender, prefix() + "&7mainHandChannel: &f" + (inHand == null ? "<none>" : inHand.id()));
                    send(sender, prefix() + "&7offHandChannel: &f" + (offHand == null ? "<none>" : offHand.id()));
                    send(sender, prefix() + "&7txCached: &f" + (plugin.getTransmitChannelCached(uuid) == null ? "<none>" : plugin.getTransmitChannelCached(uuid).id()));

                    // Show listen decision for each channel vs current selected state.
                    for (var c : me.Luki.WalkieTalkiePlugin.radio.RadioChannel.values()) {
                        if (c == me.Luki.WalkieTalkiePlugin.radio.RadioChannel.PIRACI_RANDOM) {
                            continue;
                        }
                        send(sender, prefix() + "&7canListen(tx=" + c.id() + "): &f" + plugin.debugExplainListenDecision(uuid, c));
                    }
                    return true;
                }

                send(sender, prefix() + "&7Użycie: /" + label + " debug [player <nick>]");
                return true;
            }
            default -> {
                send(sender, prefix() + usage(label));
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            String start = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if ("reload".startsWith(start)) {
                out.add("reload");
            }
            if ("info".startsWith(start)) {
                out.add("info");
            }
            if ("debug".startsWith(start)) {
                out.add("debug");
            }
            return out;
        }
        if (args.length == 2 && "debug".equalsIgnoreCase(args[0])) {
            String start = args[1].toLowerCase(Locale.ROOT);
            if ("player".startsWith(start)) {
                return List.of("player");
            }
        }
        if (args.length == 3 && "debug".equalsIgnoreCase(args[0]) && "player".equalsIgnoreCase(args[1])) {
            String start = args[2].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(start)) {
                    out.add(p.getName());
                }
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

    private String usage(String label) {
        String raw = plugin.getConfig().getString("commands.messages.usage", "&7Użycie: /{label} <reload|info>");
        if (raw == null) {
            raw = "";
        }
        return raw.replace("{label}", label == null ? "walkietalkie" : label);
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
