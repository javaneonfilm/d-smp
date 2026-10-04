package com.cartroyale.cmd;

import com.cartroyale.CartRoyale;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /team, /kitready and /spectate. */
public final class SmallCommands implements TabExecutor {

    private final CartRoyale plugin;

    public SmallCommands(CartRoyale plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) {
            plugin.msg().send(s, "cmd.players-only");
            return true;
        }
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "kitready" -> plugin.kits().kitReady(p);
            case "spectate" -> plugin.spectators().openPicker(p);
            case "team" -> {
                String sub = a.length == 0 ? "menu" : a[0].toLowerCase(Locale.ROOT);
                switch (sub) {
                    case "invite" -> {
                        if (a.length < 2) {
                            plugin.msg().send(p, "cmd.usage", "usage", "/team invite <player>");
                            return true;
                        }
                        Player t = Bukkit.getPlayerExact(a[1]);
                        if (t == null) plugin.msg().send(p, "cmd.unknown-player", "player", a[1]);
                        else plugin.teams().invite(p, t);
                    }
                    case "accept" -> plugin.teams().accept(p, a.length > 1 ? a[1] : null);
                    case "leave" -> plugin.teams().leave(p, true);
                    case "disband" -> plugin.teams().disband(p);
                    case "list" -> plugin.teams().list(p);
                    default -> plugin.teams().openMenu(p);
                }
            }
            default -> {
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!cmd.getName().equalsIgnoreCase("team")) return out;
        if (a.length == 1) {
            for (String o : List.of("invite", "accept", "leave", "list")) if (o.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(o);
        } else if (a.length == 2 && (a[0].equalsIgnoreCase("invite") || a[0].equalsIgnoreCase("accept"))) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
        }
        return out;
    }
}
