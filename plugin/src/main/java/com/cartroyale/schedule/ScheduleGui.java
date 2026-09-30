package com.cartroyale.schedule;

import com.cartroyale.CartRoyale;
import com.cartroyale.game.GameSettings;
import com.cartroyale.game.Modifier;
import com.cartroyale.gui.Gui;
import com.cartroyale.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** HOST-3: /cart schedule. Lists recurring games; each one can be edited, toggled or deleted. */
public final class ScheduleGui {

    private static final List<Set<DayOfWeek>> DAY_PRESETS = List.of(
        EnumSet.allOf(DayOfWeek.class),
        EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
        EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
        EnumSet.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
        EnumSet.of(DayOfWeek.MONDAY), EnumSet.of(DayOfWeek.TUESDAY), EnumSet.of(DayOfWeek.WEDNESDAY), EnumSet.of(DayOfWeek.THURSDAY),
        EnumSet.of(DayOfWeek.FRIDAY), EnumSet.of(DayOfWeek.SATURDAY), EnumSet.of(DayOfWeek.SUNDAY));

    private final CartRoyale plugin;

    public ScheduleGui(CartRoyale plugin) {
        this.plugin = plugin;
    }

    private Scheduler sch() {
        return plugin.scheduler();
    }

    private String line(ScheduleEntry e) {
        GameSettings s = new GameSettings();
        s.mode = e.mode;
        s.squad = e.squad;
        return plugin.msg().plain("gui.schedule.entry-line", "time", e.time.toString(), "days", ScheduleEntry.daysToString(e.days),
            "team_size", plugin.announcer().teamSize(e.squad), "mode", plugin.announcer().modeName(s));
    }

    public void open(Player p) {
        Gui gui = new Gui(6, plugin.msg().get("gui.schedule.title", "zone", plugin.zoneId().getId()));
        int slot = 0;
        List<ScheduleEntry> list = new ArrayList<>(sch().entries().values());
        list.sort((a, b) -> a.time.compareTo(b.time));
        for (ScheduleEntry e : list) {
            if (slot >= 45) break;
            ItemStack it = Items.make(e.enabled ? Material.LIME_DYE : Material.GRAY_DYE, plugin.msg().item("gui.schedule.entry", "line", line(e)),
                plugin.msg().lore("gui.schedule.entry-lore", "cap", e.purgeCap, "map", e.map == null ? "random" : e.map,
                    "modifiers", e.modifiers.isEmpty() ? "-" : String.join(", ", e.modifiers.stream().map(m -> plugin.msg().plain("modifiers." + m.name().toLowerCase() + ".name")).toList()),
                    "enabled", e.enabled ? "on" : "off"));
            gui.set(slot++, it, c -> edit(p, e));
        }
        gui.set(49, Items.make(Material.EMERALD, plugin.msg().item("gui.schedule.add"), plugin.msg().lore("gui.schedule.add-lore")), c -> {
            ScheduleEntry e = sch().add();
            sch().save();
            edit(p, e);
        });
        gui.set(53, Items.make(Material.BOOK, plugin.msg().item("gui.schedule.today"), plugin.msg().lore("gui.schedule.today-lore")), c -> {
            p.closeInventory();
            var today = sch().gamesOn(java.time.LocalDate.now(plugin.zoneId()), plugin.zoneId());
            plugin.msg().send(p, "schedule.today-head", "count", today.size());
            int n = 1;
            for (var g : today) {
                plugin.msg().send(p, "schedule.today-line", "n", n++, "time", com.cartroyale.util.Times.clock(g.s.startAt, plugin.zoneId()),
                    "team_size", plugin.announcer().teamSize(g.s.squad), "mode", plugin.announcer().modeName(g.s), "id", g.id == 0 ? "-" : "#" + g.id);
            }
        });
        gui.open(p);
    }

    private void edit(Player p, ScheduleEntry e) {
        Gui gui = new Gui(4, plugin.msg().get("gui.schedule.edit-title", "id", e.id));
        GameSettings s = new GameSettings();
        s.mode = e.mode;
        gui.set(10, btn(Material.CLOCK, "gui.schedule.time", "gui.schedule.time-lore", "value", e.time.toString(), "zone", plugin.zoneId().getId()), c -> {
            int step = c.isShiftClick() ? 60 : 15;
            e.time = e.time.plusMinutes(c.isRightClick() ? -step : step);
            changed(p, e);
        });
        gui.set(11, btn(Material.PAPER, "gui.schedule.days", "gui.schedule.days-lore", "value", ScheduleEntry.daysToString(e.days)), c -> {
            int i = DAY_PRESETS.indexOf(e.days);
            Set<DayOfWeek> next = DAY_PRESETS.get((i + (c.isRightClick() ? DAY_PRESETS.size() - 1 : 1) + DAY_PRESETS.size()) % DAY_PRESETS.size());
            e.days.clear();
            e.days.addAll(next);
            changed(p, e);
        });
        gui.set(12, btn(Material.TNT_MINECART, "gui.host.mode", "gui.host.mode-lore", "value", plugin.announcer().modeName(s)), c -> {
            e.mode = e.mode.next();
            changed(p, e);
        });
        gui.set(13, btn(Material.PLAYER_HEAD, "gui.host.squad", "gui.host.squad-lore", "value", plugin.announcer().teamSize(e.squad)), c -> {
            List<Integer> sizes = plugin.getConfig().getIntegerList("teams.sizes");
            if (sizes.isEmpty()) sizes = List.of(1, 2, 4);
            e.squad = sizes.get((sizes.indexOf(e.squad) + 1) % sizes.size());
            changed(p, e);
        });
        gui.set(14, btn(Material.IRON_BARS, "gui.host.cap", "gui.host.cap-lore", "value", e.purgeCap, "min", 2,
            "max", plugin.getConfig().getInt("host.limits.admin-max-cap", 200)), c -> {
            int step = c.isShiftClick() ? 1 : 10;
            e.purgeCap = Math.max(2, Math.min(plugin.getConfig().getInt("host.limits.admin-max-cap", 200), e.purgeCap + (c.isRightClick() ? -step : step)));
            changed(p, e);
        });
        List<String> maps = new ArrayList<>();
        maps.add(null);
        maps.addAll(plugin.arenas().maps());
        gui.set(15, btn(Material.FILLED_MAP, "gui.host.map", "gui.host.map-lore", "value", e.map == null ? "random" : e.map), c -> {
            e.map = maps.get((maps.indexOf(e.map) + 1) % maps.size());
            changed(p, e);
        });
        int slot = 19;
        for (Modifier m : Modifier.values()) {
            boolean on = e.modifiers.contains(m);
            ItemStack it = btn(Material.LEVER, "gui.host.modifier", on ? "gui.host.modifier-on" : "gui.host.modifier-off",
                "name", plugin.msg().plain("modifiers." + m.name().toLowerCase() + ".name"), "desc", plugin.msg().plain("modifiers." + m.name().toLowerCase() + ".desc"));
            if (on) it.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            gui.set(slot++, it, c -> {
                if (!e.modifiers.remove(m)) e.modifiers.add(m);
                changed(p, e);
            });
        }
        gui.set(24, btn(e.enabled ? Material.LIME_DYE : Material.GRAY_DYE, "gui.schedule.enabled", "gui.schedule.enabled-lore", "value", e.enabled ? "on" : "off"), c -> {
            e.enabled = !e.enabled;
            changed(p, e);
        });
        gui.set(25, btn(Material.LAVA_BUCKET, "gui.schedule.delete", "gui.schedule.delete-lore"), c -> {
            if (!c.isShiftClick()) return;
            sch().remove(e.id);
            sch().save();
            open(p);
        });
        gui.set(27, Items.make(Material.OAK_DOOR, plugin.msg().item("gui.back"), List.of()), c -> open(p));
        gui.fill(Items.make(Material.GRAY_STAINED_GLASS_PANE, plugin.msg().item("gui.filler"), List.of()));
        gui.open(p);
    }

    private void changed(Player p, ScheduleEntry e) {
        sch().save();
        edit(p, e);
    }

    private ItemStack btn(Material m, String name, String lore, Object... kv) {
        return Items.make(m, plugin.msg().item(name, kv), plugin.msg().lore(lore, kv));
    }
}
