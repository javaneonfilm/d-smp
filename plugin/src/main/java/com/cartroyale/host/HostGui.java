package com.cartroyale.host;

import com.cartroyale.CartRoyale;
import com.cartroyale.game.GameRow;
import com.cartroyale.game.GameSettings;
import com.cartroyale.game.Modifier;
import com.cartroyale.gui.Gui;
import com.cartroyale.rank.Ranks;
import com.cartroyale.util.Items;
import com.cartroyale.util.Times;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HOST-1/4. Mode, modifiers, squad size, purge cap, map and start time. Admins can start from
 * one minute ahead; ticket holders from host.min-lead-minutes ahead, capped by host.limits, and
 * never overlapping another game. Finish queues the game, posts HOOK-1 and spends the ticket.
 */
public final class HostGui {

    private final CartRoyale plugin;
    private final Map<UUID, GameSettings> drafts = new HashMap<>();

    public HostGui(CartRoyale plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        boolean admin = Ranks.isAdmin(p);
        plugin.store().tickets(p.getUniqueId()).thenAccept(tickets -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!admin && tickets <= 0) {
                noTicket(p);
                return;
            }
            GameSettings s = drafts.computeIfAbsent(p.getUniqueId(), u -> {
                GameSettings d = plugin.defaults().copy();
                d.hosterUuid = p.getUniqueId();
                d.hosterName = p.getName();
                d.startAt = plugin.games().firstFreeSlot(System.currentTimeMillis() + minLead(admin));
                return d;
            });
            render(p, s, admin, tickets);
        }));
    }

    private long minLead(boolean admin) {
        return admin ? 60_000L : plugin.getConfig().getInt("host.min-lead-minutes", 10) * 60_000L;
    }

    private void render(Player p, GameSettings s, boolean admin, int tickets) {
        Gui gui = new Gui(6, plugin.msg().get(admin ? "gui.host.title-admin" : "gui.host.title", "tickets", tickets));
        var a = plugin.announcer();
        int min = plugin.getConfig().getInt("host.limits.min-cap", 10), max = plugin.getConfig().getInt("host.limits.max-cap", 120);
        if (admin) max = plugin.getConfig().getInt("host.limits.admin-max-cap", 200);
        final int capMin = min, capMax = max;

        gui.set(10, button(s.mode == com.cartroyale.game.Mode.LT ? Material.MINECART : Material.TNT_MINECART, "gui.host.mode",
            "gui.host.mode-lore", "value", a.modeName(s)), e -> {
            s.mode = s.mode.next();
            render(p, s, admin, tickets);
        });

        int slot = 12;
        Material[] icons = {Material.FEATHER, Material.GOLDEN_APPLE, Material.ENDER_PEARL};
        for (Modifier m : Modifier.values()) {
            boolean on = s.has(m);
            ItemStack it = button(icons[m.ordinal()], "gui.host.modifier", on ? "gui.host.modifier-on" : "gui.host.modifier-off",
                "name", plugin.msg().plain("modifiers." + m.name().toLowerCase() + ".name"),
                "desc", plugin.msg().plain("modifiers." + m.name().toLowerCase() + ".desc"));
            if (on) it.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            gui.set(slot++, it, e -> {
                if (!s.modifiers.remove(m)) s.modifiers.add(m);
                render(p, s, admin, tickets);
            });
        }

        gui.set(16, button(Material.PLAYER_HEAD, "gui.host.squad", "gui.host.squad-lore", "value", a.teamSize(s.squad)), e -> {
            List<Integer> sizes = plugin.getConfig().getIntegerList("teams.sizes");
            if (sizes.isEmpty()) sizes = List.of(1, 2, 4);
            int i = sizes.indexOf(s.squad);
            s.squad = sizes.get((i + 1) % sizes.size());
            render(p, s, admin, tickets);
        });

        int minLo = plugin.getConfig().getInt("host.limits.min-minutes", 20), minHi = plugin.getConfig().getInt("host.limits.max-minutes", 40);
        gui.set(29, button(Material.RECOVERY_COMPASS, "gui.host.duration", "gui.host.duration-lore", "value", s.minutes, "min", minLo, "max", minHi), e -> {
            int step = e.isShiftClick() ? 1 : 5;
            s.minutes = plugin.clampMinutes(s.minutes + (e.isRightClick() ? -step : step));
            render(p, s, admin, tickets);
        });

        gui.set(28, button(Material.IRON_BARS, "gui.host.cap", "gui.host.cap-lore", "value", s.purgeCap, "min", capMin, "max", capMax), e -> {
            int step = e.isShiftClick() ? 1 : 10;
            s.purgeCap += e.isRightClick() ? -step : step;
            s.purgeCap = Math.max(capMin, Math.min(capMax, s.purgeCap));
            render(p, s, admin, tickets);
        });

        List<String> maps = new ArrayList<>();
        maps.add(null);
        maps.addAll(plugin.arenas().maps());
        gui.set(30, button(Material.FILLED_MAP, "gui.host.map", "gui.host.map-lore", "value",
            s.map == null ? plugin.msg().plain("discord.random-map") : s.map), e -> {
            int i = maps.indexOf(s.map);
            s.map = maps.get((i + (e.isRightClick() ? maps.size() - 1 : 1)) % maps.size());
            render(p, s, admin, tickets);
        });

        long earliest = System.currentTimeMillis() + minLead(admin);
        if (s.startAt < earliest) s.startAt = plugin.games().firstFreeSlot(earliest);
        boolean free = plugin.games().slotFree(s.startAt, -1);
        gui.set(32, button(Material.CLOCK, "gui.host.time", free ? "gui.host.time-lore" : "gui.host.time-lore-taken",
            "value", Times.dayClock(s.startAt, plugin.zoneId()), "rel", Times.relative(s.startAt)), e -> {
            long step = (e.isShiftClick() ? 60 : 5) * 60_000L;
            long next = s.startAt + (e.isRightClick() ? -step : step);
            s.startAt = Math.max(next, earliest);
            render(p, s, admin, tickets);
        });
        gui.set(33, button(Material.COMPASS, "gui.host.next-free", "gui.host.next-free-lore"), e -> {
            s.startAt = plugin.games().firstFreeSlot(Math.max(earliest, s.startAt + 5 * 60_000L));
            render(p, s, admin, tickets);
        });

        gui.set(34, button(Material.PAPER, "gui.host.summary", "gui.host.summary-lore",
            "mode", a.modeName(s), "team_size", a.teamSize(s.squad), "cap", s.purgeCap,
            "modifiers", s.modifiers.isEmpty() ? plugin.msg().plain("discord.no-modifiers") : a.modifiers(s).replace("`", ""),
            "map", s.map == null ? plugin.msg().plain("discord.random-map") : s.map, "time", Times.dayClock(s.startAt, plugin.zoneId()),
            "duration", s.minutes));

        gui.set(45, button(Material.BARRIER, "gui.host.cancel", "gui.host.cancel-lore"), e -> {
            drafts.remove(p.getUniqueId());
            p.closeInventory();
        });
        gui.set(49, button(free ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE, "gui.host.finish",
            admin ? "gui.host.finish-lore-admin" : "gui.host.finish-lore", "tickets", tickets), e -> finish(p, s, admin, e));
        gui.fill(Items.make(Material.GRAY_STAINED_GLASS_PANE, plugin.msg().item("gui.filler"), List.of()));
        gui.open(p);
    }

    private ItemStack button(Material m, String nameKey, String loreKey, Object... kv) {
        return Items.make(m, plugin.msg().item(nameKey, kv), plugin.msg().lore(loreKey, kv));
    }

    private void finish(Player p, GameSettings s, boolean admin, InventoryClickEvent e) {
        long earliest = System.currentTimeMillis() + minLead(admin) - 30_000;
        if (s.startAt < earliest) {
            plugin.msg().send(p, "host.too-soon", "minutes", minLead(admin) / 60_000);
            return;
        }
        if (!plugin.games().slotFree(s.startAt, -1)) {
            plugin.msg().send(p, "host.slot-taken", "minutes", plugin.getConfig().getInt("host.slot-minutes", 30));
            return;
        }
        if (plugin.kits().has(s.mode) == false) plugin.msg().send(p, "host.kit-missing-warning", "mode", s.mode.name());
        GameSettings g = s.copy();
        g.hosterUuid = p.getUniqueId();
        g.hosterName = p.getName();
        p.closeInventory();
        drafts.remove(p.getUniqueId());
        if (admin) {
            plugin.games().create(g, "admin").thenAccept(row -> done(p, row));
            return;
        }
        plugin.store().spendTicketAndQueue(p.getUniqueId(), g).thenAccept(id -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (id < 0) {
                noTicket(p);
                return;
            }
            done(p, plugin.games().queued(id, g, "ticket"));
        }));
    }

    private void done(Player p, GameRow row) {
        if (row == null) {
            plugin.msg().send(p, "host.failed");
            return;
        }
        plugin.msg().send(p, "host.queued", "id", row.id, "start", plugin.announcer().startLine(row));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        plugin.lobby().giveHotbar(p);
    }

    private void noTicket(Player p) {
        plugin.msg().send(p, "host.no-ticket");
        String store = plugin.getConfig().getString("host.store-url", "");
        if (store != null && !store.isBlank()) plugin.msg().send(p, "host.no-ticket-store", "store", com.cartroyale.core.Msg.link(store));
    }
}
