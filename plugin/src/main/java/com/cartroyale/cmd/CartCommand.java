package com.cartroyale.cmd;

import com.cartroyale.CartRoyale;
import com.cartroyale.arena.MapConfig;
import com.cartroyale.game.GameRow;
import com.cartroyale.game.Match;
import com.cartroyale.game.Mode;
import com.cartroyale.gui.Gui;
import com.cartroyale.host.Tickets;
import com.cartroyale.rank.Ranks;
import com.cartroyale.shield.OneTimeShield;
import com.cartroyale.util.Items;
import com.cartroyale.util.Times;
import com.cartroyale.zone.ZoneMath;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Section 5.19. Everything except /cart host and /cart help needs cartzone.admin. */
public final class CartCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("help", "host", "start", "stop", "queue", "cancel", "schedule", "kit", "arena", "zone",
        "chests", "loot", "ticket", "coins", "reload", "setlobby", "play", "build", "spectate", "free", "diag", "selftest", "test", "shield", "shop", "book", "webhook", "npc");

    private final CartRoyale plugin;

    public CartCommand(CartRoyale plugin) {
        this.plugin = plugin;
    }

    private void m(CommandSender s, String key, Object... kv) {
        plugin.msg().send(s, key, kv);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("help")) {
            for (String line : plugin.msg().rawList(s.hasPermission(Ranks.ADMIN) ? "help.admin" : "help.player")) {
                s.sendMessage(plugin.msg().parse(line));
            }
            return true;
        }
        if (sub.equals("host")) {
            if (!(s instanceof Player p)) return notPlayer(s);
            plugin.hostGui().open(p);
            return true;
        }
        if (!s.hasPermission(Ranks.ADMIN)) {
            m(s, "cmd.no-permission");
            return true;
        }
        try {
            switch (sub) {
                case "start" -> plugin.games().startSoon(a.length > 1 && a[1].equalsIgnoreCase("now"), s instanceof Player p ? p : null);
                case "stop" -> {
                    // "/cart stop <id>" stops a queued game; without an id it stops the running one,
                    // or lists the queue (with Cancel buttons) when nothing is running.
                    if (a.length > 1) {
                        long id;
                        try {
                            id = Long.parseLong(a[1].replace("#", ""));
                        } catch (NumberFormatException ex) {
                            return usage(s, "/cart stop [id]");
                        }
                        if (plugin.games().cancelQueued(id, plugin.msg().plain("game.reason-cancelled", "by", s.getName()))) m(s, "admin.queued-stopped", "id", id);
                        else m(s, "admin.no-such-game", "id", id);
                    } else if (plugin.games().match() == null) {
                        if (plugin.games().queue().isEmpty()) m(s, "admin.no-game");
                        else {
                            m(s, "admin.stop-which");
                            queue(s);
                        }
                    } else plugin.games().abort(plugin.msg().plain("game.reason-stopped", "by", s.getName()));
                }
                case "queue" -> queue(s);
                case "cancel" -> {
                    if (a.length < 2) return usage(s, "/cart cancel <id>");
                    long id = Long.parseLong(a[1].replace("#", ""));
                    if (!plugin.games().cancelQueued(id, plugin.msg().plain("game.reason-cancelled", "by", s.getName()))) m(s, "admin.no-such-game", "id", id);
                    else m(s, "admin.queued-stopped", "id", id);
                }
                case "schedule" -> {
                    // /cart schedule [pause [12h|3d]|resume]: no argument opens the GUI.
                    if (a.length >= 2 && a[1].equalsIgnoreCase("pause")) {
                        long len = a.length > 2 ? com.cartroyale.mod.Offences.parseLength(a[2]) : -1;
                        long until = len < 0 ? Long.MAX_VALUE : System.currentTimeMillis() + len;
                        int n = plugin.scheduler().pause(until, s.getName());
                        m(s, "schedule.paused", "until", until == Long.MAX_VALUE ? plugin.msg().plain("gui.schedule.until-resumed")
                            : Times.dayClock(until, plugin.zoneId()), "cancelled", n);
                        return true;
                    }
                    if (a.length >= 2 && a[1].equalsIgnoreCase("resume")) {
                        plugin.scheduler().resume();
                        m(s, "schedule.resumed");
                        return true;
                    }
                    if (!(s instanceof Player p)) return notPlayer(s);
                    plugin.scheduleGui().open(p);
                }
                case "kit" -> kit(s, a);
                case "arena" -> arena(s, a);
                case "zone" -> zone(s, a);
                case "chests" -> chests(s, a);
                case "loot" -> {
                    if (!(s instanceof Player p)) return notPlayer(s);
                    plugin.lootEditor().openTiers(p);
                }
                case "ticket" -> ticket(s, a);
                case "coins" -> coins(s, a);
                case "reload" -> {
                    plugin.reloadAll();
                    m(s, "admin.reloaded");
                }
                case "setlobby" -> {
                    if (!(s instanceof Player p)) return notPlayer(s);
                    plugin.lobby().setSpawn(p.getLocation());
                    m(s, "admin.lobby-set");
                }
                case "play" -> {
                    if (!(s instanceof Player p)) return notPlayer(s);
                    m(s, plugin.games().toggleAdminPlay(p.getUniqueId()) ? "admin.play-on" : "admin.play-off");
                }
                case "build" -> {
                    if (!(s instanceof Player p)) return notPlayer(s);
                    boolean on = plugin.rank().toggleBuild(p);
                    if (on) p.setGameMode(GameMode.CREATIVE);
                    else plugin.lobby().reset(p);
                    m(s, on ? "admin.build-on" : "admin.build-off");
                }
                case "spectate" -> {
                    if (!(s instanceof Player p)) return notPlayer(s);
                    if (plugin.arenas().world() == null) {
                        m(s, "admin.no-arena");
                        return true;
                    }
                    plugin.spectators().make(p, null);
                }
                case "free" -> {
                    if (!(s instanceof Player p)) return notPlayer(s);
                    m(s, plugin.rank().toggleFree(p) ? "admin.free-on" : "admin.free-off");
                }
                case "diag" -> diag(s);
                case "npc" -> {
                    // /cart npc set <1-3> | remove <1-3> | refresh: the lobby leaderboard statues.
                    if (a.length < 2) return usage(s, "/cart npc <set|remove|refresh> [1-3]");
                    if (a[1].equalsIgnoreCase("refresh")) {
                        plugin.leaderboard().refresh();
                        m(s, "leaderboard.refreshed");
                        return true;
                    }
                    int n = a.length > 2 ? Integer.parseInt(a[2]) : 0;
                    if (n < 1 || n > 3) return usage(s, "/cart npc <set|remove> <1-3>");
                    if (a[1].equalsIgnoreCase("set")) {
                        if (!(s instanceof Player p)) return notPlayer(s);
                        Location l = p.getLocation();
                        l.setPitch(0);
                        plugin.leaderboard().setSpot(n, l);
                        m(s, "leaderboard.set", "place", n);
                    } else if (a[1].equalsIgnoreCase("remove")) {
                        plugin.leaderboard().removeSpot(n);
                        m(s, "leaderboard.removed", "place", n);
                    } else {
                        return usage(s, "/cart npc <set|remove|refresh> [1-3]");
                    }
                }
                case "testperm" -> {
                    // Hidden, for testing perks without LuckPerms: /cart testperm <node> [player]. Lasts until they log out.
                    if (a.length < 2) return usage(s, "/cart testperm <node> [player]");
                    Player p = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : s instanceof Player self ? self : null;
                    if (p == null) return usage(s, "/cart testperm <node> [player]");
                    p.addAttachment(plugin, a[1], true);
                    plugin.lobby().reset(p);
                    s.sendMessage("Gave " + p.getName() + " " + a[1] + " until they log out.");
                }
                case "webhook" -> {
                    // /cart webhook test [ping]: one embed to Discord, then what Discord said.
                    if (a.length < 2 || !a[1].equalsIgnoreCase("test")) return usage(s, "/cart webhook test [ping]");
                    plugin.hooks().test(s, a.length > 2 && a[2].equalsIgnoreCase("ping"));
                }
                case "test" -> {
                    // /cart test [player] [minutes]: a practice game on the live server, solo is fine.
                    // Minutes default to the normal match length; 3-40 so a quick check stays possible.
                    int minutes = 0;
                    String who = null;
                    for (int i = 1; i < a.length; i++) {
                        if (a[i].chars().allMatch(Character::isDigit)) minutes = Math.max(3, Math.min(40, Integer.parseInt(a[i])));
                        else who = a[i];
                    }
                    Player p = who != null ? Bukkit.getPlayerExact(who) : s instanceof Player self ? self : null;
                    if (p == null) return usage(s, "/cart test [player] [minutes]");
                    plugin.games().startTest(s, p, minutes);
                }
                case "selftest" -> new SelfTest(plugin).run(s);
                case "shield" -> {
                    // /cart shield [n] [player] - the player is put on the offhand, ready to block.
                    int n = a.length > 1 ? Integer.parseInt(a[1]) : 16;
                    Player p = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : s instanceof Player self ? self : null;
                    if (p == null) return usage(s, "/cart shield [n] [player]");
                    if (a.length > 2) p.getInventory().setItemInOffHand(OneTimeShield.create(plugin, n));
                    else p.getInventory().addItem(OneTimeShield.create(plugin, n));
                    m(s, "admin.shield-given", "n", n);
                }
                case "book" -> {
                    // /cart book <armor|sharpness|quickcharge> [n] [player]
                    if (a.length < 2) return usage(s, "/cart book <armor|sharpness|quickcharge> [n] [player]");
                    com.cartroyale.loot.UpgradeBooks.Kind k = switch (a[1].toLowerCase()) {
                        case "armor" -> com.cartroyale.loot.UpgradeBooks.Kind.ARMOR;
                        case "sharpness" -> com.cartroyale.loot.UpgradeBooks.Kind.SHARPNESS;
                        case "quickcharge" -> com.cartroyale.loot.UpgradeBooks.Kind.QUICKCHARGE;
                        default -> null;
                    };
                    int n = a.length > 2 ? Integer.parseInt(a[2]) : 1;
                    Player p = a.length > 3 ? Bukkit.getPlayerExact(a[3]) : s instanceof Player self ? self : null;
                    if (k == null || p == null) return usage(s, "/cart book <armor|sharpness|quickcharge> [n] [player]");
                    p.getInventory().addItem(com.cartroyale.loot.UpgradeBooks.create(plugin, k, n));
                    plugin.books().queue(p);
                    m(s, "admin.book-given", "player", p.getName(), "n", n, "book", k.id.replace('_', ' '));
                }
                case "shop" -> {
                    if (!(s instanceof Player p)) return notPlayer(s);
                    plugin.cosmeticsGui().openMain(p);
                }
                default -> m(s, "cmd.unknown", "sub", sub);
            }
        } catch (NumberFormatException e) {
            m(s, "cmd.bad-number", "value", e.getMessage());
        }
        return true;
    }

    private boolean notPlayer(CommandSender s) {
        m(s, "cmd.players-only");
        return true;
    }

    private boolean usage(CommandSender s, String u) {
        m(s, "cmd.usage", "usage", u);
        return true;
    }

    // ---------------- queue ----------------

    private void queue(CommandSender s) {
        Match mt = plugin.games().match();
        if (mt != null) m(s, "admin.queue-live", "id", mt.id(), "state", mt.state.name(), "players", mt.players.size(), "alive", mt.aliveCount());
        List<GameRow> q = plugin.games().queue();
        if (q.isEmpty()) m(s, "admin.queue-empty");
        for (GameRow g : q) {
            m(s, "admin.queue-line", "id", g.id, "start", plugin.announcer().startLine(g), "hoster", g.s.hosterName,
                "mode", plugin.announcer().modeName(g.s), "team_size", plugin.announcer().teamSize(g.s.squad), "cap", g.s.purgeCap,
                "source", g.source == null ? "-" : g.source.split(":")[0],
                "cancel", com.cartroyale.core.Msg.command(plugin.msg().get("admin.cancel-button"), "/cart cancel " + g.id));
        }
    }

    // ---------------- kits ----------------

    private boolean kit(CommandSender s, String[] a) {
        // One kit for every game. "/cart kit set [player]" saves another player's inventory (usable
        // from the console); an old "lt"/"ht" argument before the name is still accepted.
        int at = a.length > 2 && (a[2].equalsIgnoreCase("lt") || a[2].equalsIgnoreCase("ht")) ? 3 : 2;
        Player p = a.length > at ? Bukkit.getPlayerExact(a[at]) : s instanceof Player self ? self : null;
        if (p == null) return a.length > at ? usage(s, "/cart kit <set|preview> [player]") : notPlayer(s);
        if (a.length < 2) return usage(s, "/cart kit <set|preview>");
        Mode mode = Mode.LT;
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "set" -> {
                plugin.kits().save(mode, p);
                m(s, "kits.saved", "mode", mode.name());
                plugin.lobby().suspendHotbar(p.getUniqueId(), false);
                plugin.lobby().reset(p);
            }
            case "preview" -> {
                if (!plugin.kits().has(mode)) {
                    m(s, "kits.missing-admin", "mode", mode.name());
                    return true;
                }
                Gui gui = new Gui(5, plugin.msg().get("gui.kit.title", "mode", mode.name()));
                ItemStack[] items = plugin.kits().preview(mode);
                for (int i = 0; i < 36; i++) if (items[i] != null) gui.set(i < 9 ? 27 + i : i - 9, items[i]);
                for (int i = 0; i < 4; i++) if (items[36 + i] != null) gui.set(36 + (3 - i), items[36 + i]);
                if (items[40] != null) gui.set(41, items[40]);
                gui.open(p);
            }
            default -> usage(s, "/cart kit <set|preview>");
        }
        return true;
    }

    // ---------------- arena ----------------

    private boolean arena(CommandSender s, String[] a) {
        String op = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "info";
        MapConfig mc = plugin.arenas().map();
        switch (op) {
            case "list" -> m(s, "arena.list", "maps", String.join(", ", plugin.arenas().maps()), "loaded", String.valueOf(plugin.arenas().loadedMap()));
            case "save" -> {
                if (a.length < 3) return usage(s, "/cart arena save <name>");
                String name = a[2].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
                if (plugin.games().match() != null && plugin.games().match().state.inArena()) {
                    m(s, "arena.busy");
                    return true;
                }
                m(s, "arena.saving", "name", name);
                plugin.arenas().saveTemplate(name).whenComplete((n, t) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (t != null) m(s, "arena.save-failed", "error", String.valueOf(t.getMessage()));
                    else m(s, "arena.saved", "name", n);
                }));
            }
            case "load" -> {
                if (a.length < 3) return usage(s, "/cart arena load <name>");
                if (plugin.games().match() != null) {
                    m(s, "arena.busy");
                    return true;
                }
                String name = a[2].toLowerCase(Locale.ROOT);
                m(s, "arena.loading", "name", name);
                plugin.arenas().swap(name).whenComplete((v, t) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (t != null) m(s, "arena.load-failed", "error", String.valueOf(t.getMessage()));
                    else {
                        m(s, "arena.loaded", "name", name);
                        if (s instanceof Player p) p.teleport(plugin.arenas().map().spawn(plugin.arenas().world()));
                    }
                }));
            }
            case "tp" -> {
                if (!(s instanceof Player p)) return notPlayer(s);
                if (mc == null) return true;
                p.setGameMode(GameMode.CREATIVE);
                p.teleport(mc.spawn(plugin.arenas().world()));
            }
            case "setspawn", "setcenter", "setdeploy", "setborder" -> {
                if (!(s instanceof Player p)) return notPlayer(s);
                if (mc == null || !plugin.arenas().isArena(p.getWorld())) {
                    m(s, "arena.stand-in-arena");
                    return true;
                }
                Location l = p.getLocation();
                switch (op) {
                    case "setspawn" -> {
                        mc.spawnX = l.getX();
                        mc.spawnY = l.getY();
                        mc.spawnZ = l.getZ();
                        mc.spawnYaw = l.getYaw();
                    }
                    case "setcenter" -> {
                        mc.centerX = l.getBlockX() + 0.5;
                        mc.centerZ = l.getBlockZ() + 0.5;
                    }
                    case "setdeploy" -> {
                        mc.deployX = l.getX();
                        mc.deployY = l.getY();
                        mc.deployZ = l.getZ();
                        mc.deployYaw = Math.round(l.getYaw() / 5f) * 5f;
                    }
                    case "setborder" -> {
                        if (a.length < 3) return usage(s, "/cart arena setborder <radius>");
                        mc.borderRadius = Double.parseDouble(a[2]);
                    }
                    default -> {
                    }
                }
                mc.save();
                plugin.arenas().applyRules(p.getWorld(), mc);
                plugin.arenas().invalidateSpots(mc.name);
                m(s, "arena.set", "what", op.substring(3), "where", com.cartroyale.util.Locs.pretty(l));
            }
            default -> {
                if (mc == null) {
                    m(s, "admin.no-arena");
                    return true;
                }
                m(s, "arena.info", "name", mc.name, "center", (int) mc.centerX + ", " + (int) mc.centerZ, "border", (int) mc.borderRadius,
                    "spawn", (int) mc.spawnX + ", " + (int) mc.spawnY + ", " + (int) mc.spawnZ,
                    "deploy", (int) mc.deployX + ", " + (int) mc.deployY + ", " + (int) mc.deployZ + " yaw " + (int) mc.deployYaw,
                    "template", plugin.arenas().hasTemplate(mc.name) ? "saved" : "NOT saved");
            }
        }
        return true;
    }

    // ---------------- zone ----------------

    private boolean zone(CommandSender s, String[] a) {
        MapConfig mc = plugin.arenas().map();
        if (mc == null) {
            m(s, "admin.no-arena");
            return true;
        }
        String op = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "info";
        switch (op) {
            case "start" -> {
                if (!(s instanceof Player p) || !plugin.arenas().isArena(p.getWorld())) {
                    m(s, "arena.stand-in-arena");
                    return true;
                }
                mc.zoneX = p.getLocation().getBlockX() + 0.5;
                mc.zoneZ = p.getLocation().getBlockZ() + 0.5;
                if (a.length > 2) mc.zoneR = Double.parseDouble(a[2]);
                mc.save();
                m(s, "zone.start-set", "x", (int) mc.zoneX, "z", (int) mc.zoneZ, "r", (int) mc.zoneR);
            }
            case "final" -> {
                if (!(s instanceof Player p) || !plugin.arenas().isArena(p.getWorld())) {
                    m(s, "arena.stand-in-arena");
                    return true;
                }
                mc.finalX = p.getLocation().getBlockX() + 0.5;
                mc.finalZ = p.getLocation().getBlockZ() + 0.5;
                mc.save();
                m(s, "zone.final-set", "x", (int) mc.finalX, "z", (int) mc.finalZ);
            }
            case "preview" -> {
                if (!(s instanceof Player p)) return notPlayer(s);
                preview(p, mc);
            }
            default -> {
                ZoneMath.Plan plan = plugin.zone().planFor(mc);
                m(s, "zone.info-head", "phases", plan.phases().size(), "minutes", plugin.announcer().durationMinutes());
                var cs = ZoneMath.circles(plan);
                int t = 0;
                for (int i = 0; i < plan.phases().size(); i++) {
                    var ph = plan.phases().get(i);
                    var c = cs.get(i + 1);
                    t += ph.waitSeconds() + ph.shrinkSeconds();
                    m(s, "zone.info-line", "n", i + 1, "wait", ph.waitSeconds(), "shrink", ph.shrinkSeconds(), "r", (int) c.r(),
                        "x", (int) c.x(), "z", (int) c.z(), "dmg", ph.damageHp(), "at", Times.mmss(t));
                }
                if (plan.orbitRadius() <= 0) m(s, "zone.info-closed");
                else m(s, "zone.info-orbit", "x", (int) plan.orbitCenterX(), "z", (int) plan.orbitCenterZ(), "r", (int) plan.orbitRadius(), "speed", plan.orbitSpeed());
            }
        }
        return true;
    }

    /** Entry count of one loot tier. */
    private String pools(int t) {
        return String.valueOf(plugin.loot().tier(com.cartroyale.game.Mode.LT, t).size());
    }

    /** ZONE-4 preview: draws every phase circle around the admin for 15 seconds. */
    private void preview(Player p, MapConfig mc) {
        ZoneMath.Plan plan = plugin.zone().planFor(mc);
        var cs = ZoneMath.circles(plan);
        World w = plugin.arenas().world();
        if (w == null || !p.getWorld().equals(w)) {
            m(p, "arena.stand-in-arena");
            return;
        }
        m(p, "zone.previewing");
        new BukkitRunnable() {
            int n = 0;

            @Override
            public void run() {
                if (n++ > 30 || !p.isOnline()) {
                    cancel();
                    return;
                }
                for (int i = 0; i < cs.size(); i++) {
                    var c = cs.get(i);
                    int shade = 255 - i * 35;
                    Particle.DustOptions d = new Particle.DustOptions(Color.fromRGB(255, Math.max(0, shade - 100), Math.max(0, shade - 150)), 2f);
                    double step = Math.max(0.05, 3 / Math.max(3, c.r()));
                    for (double ang = 0; ang < Math.PI * 2; ang += step) {
                        double x = c.x() + Math.cos(ang) * c.r(), z = c.z() + Math.sin(ang) * c.r();
                        if (Math.hypot(x - p.getLocation().getX(), z - p.getLocation().getZ()) > 90) continue;
                        p.spawnParticle(Particle.DUST, x, p.getLocation().getY() + 1, z, 1, 0, 0, 0, 0, d);
                    }
                }
            }
        }.runTaskTimer(plugin, 0, 10);
    }

    // ---------------- chests ----------------

    private boolean chests(CommandSender s, String[] a) {
        MapConfig mc = plugin.arenas().map();
        World w = plugin.arenas().world();
        if (mc == null || w == null) {
            m(s, "admin.no-arena");
            return true;
        }
        String op = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "info";
        switch (op) {
            case "include", "exclude" -> {
                if (!(s instanceof Player p) || !plugin.arenas().isArena(p.getWorld())) {
                    m(s, "arena.stand-in-arena");
                    return true;
                }
                double r = a.length > 2 ? Double.parseDouble(a[2]) : 20;
                var c = new MapConfig.Circle(p.getLocation().getBlockX() + 0.5, p.getLocation().getBlockZ() + 0.5, r);
                (op.equals("include") ? mc.include : mc.exclude).add(c);
                mc.save();
                plugin.arenas().invalidateSpots(mc.name);
                m(s, "chests.zone-added", "kind", op, "r", (int) r);
            }
            case "clearzones" -> {
                mc.include.clear();
                mc.exclude.clear();
                mc.save();
                plugin.arenas().invalidateSpots(mc.name);
                m(s, "chests.zones-cleared");
            }
            case "scan" -> {
                plugin.arenas().invalidateSpots(mc.name);
                m(s, "chests.scanning");
                plugin.scanner().spots(w, mc, plugin.arenas().spotsFile(mc.name)).thenAccept(l ->
                    Bukkit.getScheduler().runTask(plugin, () -> m(s, "chests.scanned", "n", l.size())));
            }
            case "test" -> {
                if (plugin.games().match() != null) {
                    m(s, "arena.busy");
                    return true;
                }
                int players = a.length > 2 ? Integer.parseInt(a[2]) : 20;
                m(s, "chests.testing", "players", players);
                plugin.chests().spawn(w, mc, com.cartroyale.game.Mode.LT, players, false, true, "test").thenAccept(n ->
                    Bukkit.getScheduler().runTask(plugin, () -> m(s, "chests.tested", "report", plugin.chests().report())));
            }
            case "clear" -> {
                plugin.chests().clear(w);
                m(s, "chests.cleared");
            }
            default -> m(s, "chests.info", "report", plugin.chests().report(), "scan", plugin.scanner().progress(),
                "include", mc.include.size(), "exclude", mc.exclude.size(), "cached", plugin.arenas().spotsFile(mc.name).exists() ? "yes" : "no");
        }
        return true;
    }

    // ---------------- tickets / coins ----------------

    private boolean ticket(CommandSender s, String[] a) {
        if (a.length < 3) return usage(s, "/cart ticket <give|revoke|list> <uuid|name> [n] [transaction]");
        UUID u = Tickets.parseTarget(a[2]);
        if (u == null) {
            m(s, "cmd.unknown-player", "player", a[2]);
            return true;
        }
        int n = a.length > 3 ? Math.max(1, Integer.parseInt(a[3])) : 1;
        String tx = a.length > 4 ? a[4] : null;
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "give" -> plugin.tickets().give(s, u, n, tx);
            case "revoke" -> plugin.tickets().revoke(s, u, n, tx);
            case "list" -> plugin.tickets().list(s, u);
            default -> usage(s, "/cart ticket <give|revoke|list> <uuid|name> [n] [transaction]");
        }
        return true;
    }

    private boolean coins(CommandSender s, String[] a) {
        if (a.length < 4) return usage(s, "/cart coins <give|take|set> <player> <n>");
        UUID u = Tickets.parseTarget(a[2]);
        if (u == null) {
            m(s, "cmd.unknown-player", "player", a[2]);
            return true;
        }
        int n = Integer.parseInt(a[3]);
        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        var fut = switch (a[1].toLowerCase(Locale.ROOT)) {
            case "give" -> plugin.store().addCoins(u, op.getName(), n);
            case "take" -> plugin.store().addCoins(u, op.getName(), -n);
            case "set" -> plugin.store().setCoins(u, op.getName(), n);
            default -> null;
        };
        if (fut == null) return usage(s, "/cart coins <give|take|set> <player> <n>");
        fut.thenAccept(bal -> Bukkit.getScheduler().runTask(plugin, () -> m(s, "admin.coins", "player", op.getName() == null ? u.toString() : op.getName(), "balance", bal)));
        return true;
    }

    // ---------------- diag ----------------

    /** Every precondition on one screen, so problems are read in game instead of in the console. */
    private void diag(CommandSender s) {
        Match mt = plugin.games().match();
        MapConfig mc = plugin.arenas().map();
        GameRow next = plugin.games().next();
        List<String> lines = new ArrayList<>();
        lines.add(plugin.msg().plain("diag.state", "state", plugin.games().state().name(), "game", mt == null ? "-" : "#" + mt.id(),
            "players", mt == null ? 0 : mt.players.size(), "alive", mt == null ? 0 : mt.aliveCount()));
        lines.add(plugin.msg().plain("diag.next", "next", next == null ? "none" : "#" + next.id + " " + plugin.announcer().startLine(next), "queued", plugin.games().queue().size()));
        lines.add(plugin.msg().plain("diag.eligible", "n", plugin.games().eligible().size(), "min", plugin.getConfig().getInt("join.min-players", 2),
            "whitelist", Bukkit.hasWhitelist() ? "on" : "OFF"));
        lines.add(plugin.msg().plain("diag.arena", "world", plugin.arenas().world() == null ? "NOT LOADED" : plugin.arenas().worldName(),
            "map", String.valueOf(plugin.arenas().loadedMap()), "template", mc != null && plugin.arenas().hasTemplate(mc.name) ? "yes" : "no",
            "spots", mc != null && plugin.arenas().spotsFile(mc.name).exists() ? "cached" : "not scanned"));
        lines.add(plugin.msg().plain("diag.kits", "lt", plugin.kits().has(Mode.LT) ? "set" : "MISSING"));
        lines.add(plugin.msg().plain("diag.loot", "t1", pools(1), "t2", pools(2), "t3", pools(3),
            "chests", plugin.chests().report()));
        lines.add(plugin.msg().plain("diag.deploy", "report", plugin.deploy().report()));
        lines.add(plugin.msg().plain("diag.zone", "running", plugin.zone().running() ? "running" : "idle", "minutes", plugin.announcer().durationMinutes()));
        if (plugin.zone().running()) lines.add(plugin.msg().plain("diag.zone-wall", "walls", plugin.zone().wallViewers()));
        lines.add(plugin.msg().plain("diag.webhook", "status", plugin.hooks().status(),
            "role", plugin.getConfig().getString("webhooks.events-role-id", "").isBlank() ? "NOT SET" : "set"));
        lines.add(plugin.msg().plain("diag.stats", "status", plugin.stats().status()));
        lines.add(plugin.msg().plain("diag.webstore", "status", plugin.webStore().status()));
        String sp = plugin.getServer().getResourcePack();
        boolean spSet = sp != null && !sp.isBlank();
        lines.add(plugin.msg().plain("diag.pack", "enabled", plugin.getConfig().getBoolean("resource-pack.enabled") ? "on" : spSet ? "via server.properties" : "off",
            "url", plugin.getConfig().getBoolean("resource-pack.enabled") ? (plugin.getConfig().getString("resource-pack.url", "").isBlank() ? "no url" : "url set") : spSet ? "url set" : "no url"));
        if (s instanceof Player self) lines.add(plugin.msg().plain("diag.pack-you", "state", plugin.presence().hasPack(self.getUniqueId()) ? "yes (shader wall)" : "no (particles)"));
        lines.add(plugin.msg().plain("diag.perms", "lp", Ranks.luckPerms() ? "LuckPerms found" : "LuckPerms NOT installed", "db", plugin.db().healthy() ? "ok" : "FAILED"));
        lines.add(plugin.msg().plain("diag.shop", "items", String.join(", ", plugin.cosmetics().shop())));
        lines.add(plugin.msg().plain("diag.time","zone", plugin.zoneId().getId(), "now", Times.dayClock(System.currentTimeMillis(), plugin.zoneId())));
        m(s, "diag.head");
        for (String l : lines) s.sendMessage(plugin.msg().parse(l));
    }

    // ---------------- tab completion ----------------

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        boolean admin = s.hasPermission(Ranks.ADMIN);
        if (a.length == 1) {
            for (String sub : admin ? SUBS : List.of("help", "host")) if (sub.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(sub);
            return out;
        }
        if (!admin) return out;
        List<String> opts = switch (a[0].toLowerCase(Locale.ROOT)) {
            case "start" -> a.length == 2 ? List.of("now") : List.of();
            case "cancel" -> a.length == 2 ? plugin.games().queue().stream().map(g -> String.valueOf(g.id)).toList() : List.of();
            case "kit" -> a.length == 2 ? List.of("set", "preview") : List.of();
            case "arena" -> a.length == 2 ? List.of("info", "list", "save", "load", "tp", "setspawn", "setcenter", "setborder", "setdeploy")
                : a.length == 3 && a[1].equalsIgnoreCase("load") ? plugin.arenas().maps() : List.of();
            case "zone" -> a.length == 2 ? List.of("info", "start", "final", "preview") : List.of();
            case "book" -> a.length == 2 ? List.of("armor", "sharpness", "quickcharge") : List.of();
            case "schedule" -> a.length == 2 ? List.of("pause", "resume") : a.length == 3 && a[1].equalsIgnoreCase("pause") ? List.of("12h", "1d", "3d", "7d") : List.of();
            case "npc" -> a.length == 2 ? List.of("set", "remove", "refresh") : a.length == 3 ? List.of("1", "2", "3") : List.of();
            case "webhook" -> a.length == 2 ? List.of("test") : a.length == 3 ? List.of("ping") : List.of();
            case "chests" -> a.length == 2 ? List.of("info", "include", "exclude", "clearzones", "scan", "test", "clear") : List.of();
            case "ticket" -> a.length == 2 ? List.of("give", "revoke", "list") : a.length == 3 ? names() : List.of();
            case "coins" -> a.length == 2 ? List.of("give", "take", "set") : a.length == 3 ? names() : List.of();
            default -> List.of();
        };
        String cur = a[a.length - 1].toLowerCase(Locale.ROOT);
        for (String o : opts) if (o.toLowerCase(Locale.ROOT).startsWith(cur)) out.add(o);
        return out;
    }

    private static List<String> names() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }
}
