package com.cartroyale.mod;

import com.cartroyale.CartRoyale;
import com.cartroyale.gui.Gui;
import com.cartroyale.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /offence                     pick an online player
 * /offence <player>            their offence menu: one button per type, history, undo
 * /offence <player> <type> [reason]   apply straight away (console too, e.g. Vulcan)
 * /offence undo <id>           undo one offence (it stops counting, the mute/ban is lifted)
 */
public final class OffenceGui implements TabExecutor {

    private static final String PERM = "cartzone.offence";
    private final CartRoyale plugin;

    public OffenceGui(CartRoyale plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission(PERM)) {
            plugin.msg().send(s, "cmd.no-permission");
            return true;
        }
        Offences off = plugin.offences();
        if (a.length >= 2 && a[0].equalsIgnoreCase("undo")) {
            try {
                off.undo(Long.parseLong(a[1].replace("#", "")), s);
            } catch (NumberFormatException e) {
                plugin.msg().send(s, "cmd.bad-number", "value", a[1]);
            }
            return true;
        }
        if (a.length == 1 && a[0].equalsIgnoreCase("reload")) {
            off.reload();
            plugin.chatFilter().reload();
            plugin.msg().send(s, "offence.reloaded");
            return true;
        }
        if (a.length == 0) {
            if (s instanceof Player p) openPlayers(p);
            else plugin.msg().send(s, "cmd.usage", "usage", "/offence <player> <type> [reason]");
            return true;
        }
        OfflinePlayer target = find(a[0]);
        if (target == null) {
            plugin.msg().send(s, "cmd.unknown-player", "player", a[0]);
            return true;
        }
        String name = target.getName() == null ? a[0] : target.getName();
        if (a.length >= 2) {
            Offences.Type t = off.type(a[1]);
            if (t == null) {
                plugin.msg().send(s, "offence.unknown-type", "types", String.join(", ", off.types().stream().map(Offences.Type::id).toList()));
                return true;
            }
            String reason = a.length > 2 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : null;
            off.apply(target, name, t, s.getName(), reason, s);
            return true;
        }
        if (s instanceof Player p) openPlayer(p, target, name);
        else plugin.msg().send(s, "cmd.usage", "usage", "/offence <player> <type> [reason]");
        return true;
    }

    private static OfflinePlayer find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission(PERM)) return List.of();
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            out.add("undo");
        } else if (a.length == 2 && !a[0].equalsIgnoreCase("undo")) {
            for (Offences.Type t : plugin.offences().types()) out.add(t.id());
        }
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ------------------------------------------------------------------ menus

    private void openPlayers(Player staff) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        int rows = Math.max(1, Math.min(6, (online.size() + 8) / 9));
        Gui gui = new Gui(rows, plugin.msg().get("offence.gui-pick-title"));
        for (int i = 0; i < online.size() && i < rows * 9; i++) {
            Player t = online.get(i);
            gui.set(i, head(t, plugin.msg().item("offence.gui-pick-name", "player", t.getName()), plugin.msg().lore("offence.gui-pick-lore")),
                e -> openPlayer(staff, t, t.getName()));
        }
        gui.open(staff);
    }

    private void openPlayer(Player staff, OfflinePlayer target, String name) {
        plugin.offences().history(target.getUniqueId()).thenAccept(hist -> Bukkit.getScheduler().runTask(plugin, () -> {
            Gui gui = new Gui(5, plugin.msg().get("offence.gui-title", "player", name));
            List<Component> info = new ArrayList<>();
            long active = hist.stream().filter(Offences.Record::active).count();
            info.add(plugin.msg().item("offence.gui-head-total", "total", hist.stream().filter(r -> !r.revoked()).count(), "active", active));
            for (Offences.Record r : hist) {
                if (r.active()) info.add(plugin.msg().item("offence.gui-head-active", "action", r.action() == Offences.Action.MUTE ? "Muted" : "Banned",
                    "until", r.until() < 0 ? "permanent" : date(r.until())));
            }
            gui.set(4, head(target, plugin.msg().item("offence.gui-head", "player", name), info));
            List<Offences.Type> types = plugin.offences().types();
            int[] slots = {20, 21, 22, 23, 24, 29, 30, 31, 32, 33};
            for (int i = 0; i < types.size() && i < slots.length; i++) {
                Offences.Type t = types.get(i);
                int prior = Offences.count(hist, t.id());
                long next = t.lengthFor(prior);
                String what = Offences.pretty(next) + " " + (t.action() == Offences.Action.MUTE ? "mute" : "ban");
                gui.set(slots[i], Items.make(t.icon(), plugin.msg().item("offence.gui-type", "type", t.name()),
                    plugin.msg().lore("offence.gui-type-lore", "count", prior, "nth", ordinal(prior + 1), "next", what)),
                    e -> confirm(staff, target, name, t, what, ordinal(prior + 1)));
            }
            gui.set(40, Items.make(Material.BOOK, plugin.msg().item("offence.gui-history", "count", hist.size()), plugin.msg().lore("offence.gui-history-lore")),
                e -> openHistory(staff, target, name, hist));
            gui.fill(Items.make(Material.GRAY_STAINED_GLASS_PANE, plugin.msg().item("gui.filler"), List.of()));
            gui.open(staff);
        }));
    }

    private void confirm(Player staff, OfflinePlayer target, String name, Offences.Type t, String what, String nth) {
        Gui gui = new Gui(3, plugin.msg().get("offence.gui-confirm-title", "player", name));
        gui.set(11, Items.make(Material.LIME_CONCRETE, plugin.msg().item("offence.gui-confirm", "type", t.name(), "what", what),
            plugin.msg().lore("offence.gui-confirm-lore", "player", name, "nth", nth)), e -> {
            staff.closeInventory();
            plugin.offences().apply(target, name, t, staff.getName(), null, staff);
        });
        gui.set(15, Items.make(Material.RED_CONCRETE, plugin.msg().item("offence.gui-cancel"), List.of()), e -> openPlayer(staff, target, name));
        gui.fill(Items.make(Material.GRAY_STAINED_GLASS_PANE, plugin.msg().item("gui.filler"), List.of()));
        gui.open(staff);
    }

    private void openHistory(Player staff, OfflinePlayer target, String name, List<Offences.Record> hist) {
        Gui gui = new Gui(6, plugin.msg().get("offence.gui-history-title", "player", name));
        for (int i = 0; i < hist.size() && i < 45; i++) {
            Offences.Record r = hist.get(i);
            Offences.Type t = plugin.offences().type(r.type());
            String status = r.revoked() ? "undone" : r.active() ? "active" : "served";
            Material icon = r.revoked() ? Material.GRAY_DYE : r.active() ? (t == null ? Material.PAPER : t.icon()) : Material.PAPER;
            List<Component> lore = new ArrayList<>(plugin.msg().lore("offence.gui-record-lore", "date", date(r.at()), "length", Offences.pretty(r.lengthMs()),
                "action", r.action() == Offences.Action.MUTE ? "mute" : "ban", "by", r.by(), "reason", r.reason() == null ? "-" : r.reason(), "status", status, "id", r.id()));
            if (!r.revoked()) lore.addAll(plugin.msg().lore("offence.gui-record-undo"));
            gui.set(i, Items.make(icon, plugin.msg().item("offence.gui-record", "type", t == null ? r.type() : t.name(), "id", r.id()), lore), e -> {
                if (r.revoked() || !e.isShiftClick()) return;
                staff.closeInventory();
                plugin.offences().undo(r.id(), staff);
            });
        }
        gui.set(49, Items.make(Material.OAK_DOOR, plugin.msg().item("gui.back"), List.of()), e -> openPlayer(staff, target, name));
        gui.open(staff);
    }

    private static ItemStack head(OfflinePlayer p, Component name, List<Component> lore) {
        ItemStack it = Items.make(Material.PLAYER_HEAD, name, lore);
        it.editMeta(m -> {
            if (m instanceof SkullMeta sm) sm.setOwningPlayer(p);
        });
        return it;
    }

    private String date(long ms) {
        return plugin.offences().date(ms);
    }

    private static String ordinal(int n) {
        int m = n % 100;
        String suf = m >= 11 && m <= 13 ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suf;
    }
}
