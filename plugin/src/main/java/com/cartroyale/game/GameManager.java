package com.cartroyale.game;

import com.cartroyale.CartRoyale;
import com.cartroyale.arena.MapConfig;
import com.cartroyale.db.Store;
import com.cartroyale.rank.Ranks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * The section 4 state machine. One game at a time (D23); upcoming games live in the database and
 * survive restarts. Runs on a one-second tick; deployment and the zone have their own faster tasks.
 */
public final class GameManager {

    private final CartRoyale plugin;
    private final List<GameRow> queue = new ArrayList<>();
    private final Set<UUID> adminsPlaying = new HashSet<>();
    private Match match;
    private BukkitTask task;
    private boolean loaded;
    private long lastLobbyNotice;

    public GameManager(CartRoyale plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Loads the queue (blocking once at startup) and returns the map the arena should boot with. */
    public String loadQueue() {
        try {
            List<GameRow> rows = plugin.store().loadQueued().get(15, TimeUnit.SECONDS);
            for (GameRow r : rows) {
                if (r.status == GameRow.Status.RUNNING) {
                    // The server went down mid-game. The arena is restored on boot; the game is void.
                    plugin.getLogger().warning("Game #" + r.id + " was running when the server stopped; marking it cancelled.");
                    plugin.store().setStatus(r.id, GameRow.Status.CANCELLED);
                    if (r.bought() && r.s.hosterUuid != null) plugin.store().refundTicket(r.s.hosterUuid, r.id);
                    continue;
                }
                queue.add(r);
            }
            sortQueue();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not load the game queue", e);
        }
        loaded = true;
        GameRow next = next();
        return next == null ? null : next.s.map;
    }

    public void startTicking() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        if (match != null && match.state.inArena()) {
            plugin.deploy().stop(true);
            plugin.zone().stop();
            World w = plugin.arenas().world();
            plugin.chests().clear(w);
        }
    }

    // ------------------------------------------------------------------ queue

    public List<GameRow> queue() {
        return List.copyOf(queue);
    }

    public GameRow next() {
        return queue.isEmpty() ? null : queue.get(0);
    }

    public Match match() {
        return match;
    }

    public GameState state() {
        if (match != null) return match.state;
        return queue.isEmpty() ? GameState.IDLE : GameState.ANNOUNCED;
    }

    private void sortQueue() {
        queue.sort(Comparator.comparingLong(g -> g.s.startAt));
    }

    /** True when no queued or running game starts within the slot length of {@code at}. */
    public boolean slotFree(long at, long ignoreId) {
        long slot = plugin.getConfig().getInt("host.slot-minutes", 30) * 60_000L;
        for (GameRow g : queue) if (g.id != ignoreId && Math.abs(g.s.startAt - at) < slot) return false;
        if (match != null && Math.abs(match.row.s.startAt - at) < slot) return false;
        return true;
    }

    /** The first free start time at or after {@code from}, on a 5-minute grid. */
    public long firstFreeSlot(long from) {
        long step = 5 * 60_000L;
        long t = (from + step - 1) / step * step;
        for (int i = 0; i < 24 * 12 * 3; i++, t += step) if (slotFree(t, -1)) return t;
        return t;
    }

    /** Queues a game and posts HOOK-1. Completes on the main thread with the row (null if a duplicate). */
    public CompletableFuture<GameRow> create(GameSettings s, String source) {
        CompletableFuture<GameRow> out = new CompletableFuture<>();
        plugin.store().insert(s, source).thenAccept(id -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (id < 0) {
                out.complete(null);
                return;
            }
            out.complete(queued(id, s, source));
        })).exceptionally(t -> {
            out.completeExceptionally(t);
            return null;
        });
        return out;
    }

    /** Registers a row that is already in the database and announces it. Main thread only. */
    public GameRow queued(long id, GameSettings s, String source) {
        GameRow row = new GameRow(id, s, source);
        queue.add(row);
        sortQueue();
        if (!row.test()) plugin.announcer().hosted(row);
        if (!row.test()) plugin.msg().send(Bukkit.getServer(), "game.hosted", "id", id, "hoster", s.hosterName,
            "mode", plugin.announcer().modeName(s), "team_size", plugin.announcer().teamSize(s.squad), "start", plugin.announcer().startLine(row));
        plugin.getLogger().info("Game #" + id + " queued for " + plugin.announcer().startLine(row) + " by " + s.hosterName + " (" + source + ")");
        return row;
    }

    public boolean cancelQueued(long id, String reason) {
        for (GameRow g : queue) {
            if (g.id != id) continue;
            queue.remove(g);
            plugin.store().setStatus(id, GameRow.Status.CANCELLED);
            if (g.bought() && g.s.hosterUuid != null && !"chargeback".equals(reason)) {
                plugin.store().refundTicket(g.s.hosterUuid, id);
                Player h = Bukkit.getPlayer(g.s.hosterUuid);
                if (h != null) plugin.msg().send(h, "tickets.refunded", "id", id);
            }
            plugin.msg().send(Bukkit.getServer(), "game.cancelled", "id", id, "reason", reason);
            plugin.getLogger().info("Game #" + id + " cancelled: " + reason);
            return true;
        }
        if (match != null && match.id() == id) {
            abort(reason);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ admin controls

    public boolean adminPlays(UUID u) {
        return adminsPlaying.contains(u);
    }

    public boolean toggleAdminPlay(UUID u) {
        if (!adminsPlaying.remove(u)) {
            adminsPlaying.add(u);
            return true;
        }
        return false;
    }

    /** /cart start [now]: opens the next game (or a default one) right away. */
    private final Set<UUID> testPlayers = new HashSet<>();

    /** /cart test: a solo-capable practice game that starts in a few seconds and includes {@code p}. */
    public void startTest(org.bukkit.command.CommandSender by, Player p, int minutes) {
        if (match != null) {
            plugin.msg().send(by, "admin.already-running", "id", match.id(), "state", match.state.name());
            return;
        }
        adminsPlaying.add(p.getUniqueId());
        testPlayers.add(p.getUniqueId());
        GameSettings s = plugin.defaults().copy();
        if (minutes > 0) s.minutes = minutes;
        s.startAt = System.currentTimeMillis() + plugin.getConfig().getInt("join.countdown-seconds", 10) * 1000L + 5000;
        s.hosterUuid = p.getUniqueId();
        s.hosterName = p.getName();
        create(s, "test").whenComplete((row, t) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (row == null) {
                plugin.adminAlert("admin.queue-failed", "error", t == null ? "unknown" : String.valueOf(t.getMessage()));
                return;
            }
            if (match == null) openMatch(row);
            plugin.msg().send(Bukkit.getServer(), "game.test-started", "id", row.id, "player", p.getName());
        }));
    }

    private void endTest() {
        for (UUID u : testPlayers) adminsPlaying.remove(u);
        testPlayers.clear();
    }

    public void startSoon(boolean now, Player by) {
        long lead = now ? plugin.getConfig().getInt("join.countdown-seconds", 10) * 1000L + 3000
            : plugin.getConfig().getInt("join.start-command-seconds", 60) * 1000L;
        long at = System.currentTimeMillis() + lead;
        if (match != null) {
            plugin.msg().send(by, "admin.already-running", "id", match.id(), "state", match.state.name());
            return;
        }
        GameRow next = next();
        if (next != null) {
            next.s.startAt = at;
            plugin.store().setStart(next.id, at);
            sortQueue();
            openMatch(next);
            return;
        }
        GameSettings s = plugin.defaults().copy();
        s.startAt = at;
        s.hosterUuid = by == null ? null : by.getUniqueId();
        s.hosterName = by == null ? "CartZone" : by.getName();
        create(s, "admin").whenComplete((row, t) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (row == null) plugin.adminAlert("admin.queue-failed", "error", t == null ? "duplicate game" : String.valueOf(t.getMessage()));
            else if (match == null) openMatch(row);
        }));
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        if (!loaded) return;
        long now = System.currentTimeMillis();
        try {
            plugin.scheduler().tick(now);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Scheduler tick failed", e);
        }
        if (match == null) {
            GameRow next = next();
            if (next == null) return;
            long warnMs = plugin.getConfig().getInt("webhooks.warning-minutes", 5) * 60_000L;
            if (!next.warned && !next.test() && now >= next.s.startAt - warnMs) {
                if (now < next.s.startAt + 60_000) plugin.announcer().warning(next);
                next.warned = true;
                plugin.store().setWarned(next.id);
            }
            long grace = plugin.getConfig().getInt("join.missed-grace-minutes", 10) * 60_000L;
            if (now > next.s.startAt + grace) {
                queue.remove(next);
                plugin.store().setStatus(next.id, GameRow.Status.MISSED);
                if (next.bought() && next.s.hosterUuid != null) plugin.store().refundTicket(next.s.hosterUuid, next.id);
                plugin.getLogger().warning("Game #" + next.id + " was missed (server was down at its start time).");
                return;
            }
            long joinMs = plugin.getConfig().getInt("join.window-minutes", 5) * 60_000L;
            if (now >= next.s.startAt - joinMs) openMatch(next);
            return;
        }
        try {
            tickMatch(now);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Match tick failed in state " + match.state, e);
            plugin.adminAlert("admin.tick-error", "state", match.state.name(), "error", String.valueOf(e.getMessage()));
        }
    }

    private void tickMatch(long now) {
        GameSettings s = match.s();
        long countdownMs = plugin.getConfig().getInt("join.countdown-seconds", 10) * 1000L;
        switch (match.state) {
            case JOIN_OPEN -> {
                if (now - lastLobbyNotice >= 60_000) {
                    lastLobbyNotice = now;
                    plugin.msg().send(Bukkit.getServer(), "game.window-open", "id", match.id(), "start", plugin.announcer().startLine(match.row),
                        "players", eligible().size(), "cap", s.purgeCap);
                }
                if (now >= s.startAt - countdownMs) match.enter(GameState.COUNTDOWN);
            }
            case COUNTDOWN -> {
                long secs = Math.max(0, (s.startAt - now + 999) / 1000);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.showTitle(Title.title(plugin.msg().get("titles.countdown", "seconds", secs), plugin.msg().get("titles.countdown-sub", "id", match.id()),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)));
                    if (secs <= 5) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.2f);
                }
                if (now >= s.startAt) beginPurge();
            }
            case PURGE -> {
                if (!match.purged && match.inStateMs() >= 3000) doPurge();
                // Solo: a short gap after the purge lets the route chunks finish loading off-thread.
                else if (match.purged && match.inStateMs() >= 3000 + plugin.getConfig().getInt("deploy.solo-board-delay-seconds", 5) * 1000L) {
                    for (UUID u : match.players.keySet()) match.teams.add(new ArrayList<>(List.of(u)));
                    assignTeamIndexes();
                    beginBoarding();
                }
            }
            case TEAMING -> {
                long left = plugin.getConfig().getInt("teams.phase-seconds", 90) - match.inStateMs() / 1000;
                for (UUID u : match.players.keySet()) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null) plugin.msg().actionbar(p, "teams.phase-bar", "seconds", Math.max(0, left), "size", s.squad);
                }
                if (left <= 0) finishTeaming();
            }
            case PLAYING -> {
                long hard = (match.row.s.minutes + (plugin.getConfig().getInt("match.grace-seconds", 120) + 59) / 60 + plugin.zone().roamMinutes() + plugin.getConfig().getInt("match.hard-timeout-extra-minutes", 5)) * 60_000L;
                if (now - match.playingAt >= hard) end(true);
                else if (!match.lootWave && plugin.getConfig().getBoolean("chests.second-wave.enabled", true)
                    && now - match.playingAt >= plugin.getConfig().getDouble("chests.second-wave.minute", 15) * 60_000L) {
                    match.lootWave = true;
                    lootWave();
                }
            }
            case ENDING -> {
                if (match.inStateMs() >= plugin.getConfig().getInt("match.end-kick-seconds", 10) * 1000L) kickAndReset();
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ phases

    private void openMatch(GameRow row) {
        queue.remove(row);
        long now = System.currentTimeMillis();
        long countdownMs = plugin.getConfig().getInt("join.countdown-seconds", 10) * 1000L;
        if (row.s.startAt < now + countdownMs) {
            // Opened late (a restart or /cart start now): keep a short window so players can react.
            row.s.startAt = now + countdownMs + 3000;
            plugin.store().setStart(row.id, row.s.startAt);
        }
        match = new Match(row);
        match.enter(GameState.JOIN_OPEN);
        lastLobbyNotice = 0;
        String map = row.s.map != null ? row.s.map : plugin.arenas().randomMap();
        match.map = map;
        plugin.getLogger().info("Game #" + row.id + " join window open (map " + map + ", start " + plugin.announcer().startLine(row) + ")");
        if (map != null && !map.equals(plugin.arenas().loadedMap())) {
            plugin.arenas().swap(map).exceptionally(t -> {
                plugin.adminAlert("admin.map-swap-failed", "map", map, "error", String.valueOf(t.getMessage()));
                return null;
            });
        }
        // Warm the chest spot cache now so boarding doesn't wait on a full scan.
        World w = plugin.arenas().world();
        if (w != null && plugin.arenas().map() != null) plugin.scanner().spots(w, plugin.arenas().map(), plugin.arenas().spotsFile(plugin.arenas().map().name));
    }

    /** Players who would enter the game right now. */
    public List<Player> eligible() {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (Ranks.isAdmin(p) && !adminsPlaying.contains(p.getUniqueId())) continue;
            if (match != null && match.removed.contains(p.getUniqueId())) continue;
            out.add(p);
        }
        return out;
    }

    private void beginPurge() {
        match.enter(GameState.PURGE);
        List<Player> el = eligible();
        match.onlineAtPurge = el.size();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(Title.title(plugin.msg().get("titles.purge"), plugin.msg().get("titles.purge-sub"),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(500))));
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.4f);
        }
        plugin.msg().send(Bukkit.getServer(), "game.purge-start", "online", el.size(), "cap", match.s().purgeCap);
        if (!match.row.test()) plugin.announcer().purge(match.row, el.size());
        plugin.getLogger().info("Game #" + match.id() + " purge starting with " + el.size() + " eligible players (cap " + match.s().purgeCap + ")");
    }

    /** The match whose purge is playing out one by one, so the tick loop doesn't start it twice. */
    private Match purging;

    private void doPurge() {
        if (purging == match) return;
        purging = match;
        GameSettings s = match.s();
        List<Player> el = eligible();
        int min = match.row.test() ? 1 : plugin.getConfig().getInt("join.min-players", 2);
        if (el.size() < min) {
            abort(plugin.msg().plain("game.reason-not-enough", "min", min));
            return;
        }
        var cfg = plugin.getConfig();
        boolean hosterImmune = cfg.getBoolean("purge.hoster-immune", true);
        List<Purge.Candidate<Player>> cands = new ArrayList<>();
        for (Player p : el) {
            boolean immune = Ranks.has(p, Ranks.PURGE_IMMUNE) || (hosterImmune && p.getUniqueId().equals(s.hosterUuid));
            double w = Ranks.purgeWeight(p, cfg);
            cands.add(new Purge.Candidate<>(p, w, immune));
        }
        Purge.Result<Player> res = Purge.run(cands, s.purgeCap, new Random());
        if (res.immuneOverflow()) plugin.getLogger().warning("Game #" + match.id() + ": immune players alone exceeded the purge cap; picked among them.");
        boolean spectate = "SPECTATE".equalsIgnoreCase(cfg.getString("purge.action", "KICK"));
        for (Player p : res.purged()) match.removed.add(p.getUniqueId());
        if (res.purged().isEmpty()) {
            finishPurge(res);
            return;
        }
        // One at a time with a sound, so the purge is something to watch. The whole thing takes
        // about 15 seconds however many get cut (a few ticks each when it's a lot).
        Match mm = match;
        List<Player> queue = new ArrayList<>(res.purged());
        java.util.Collections.shuffle(queue);
        int every = (int) Math.max(3, Math.min(12, Math.round(300.0 / queue.size())));
        broadcastLobby("game.purge-cutting", "count", queue.size(), "players", el.size(), "cap", s.purgeCap);
        new org.bukkit.scheduler.BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if (match != mm) {
                    cancel();
                    return;
                }
                if (i >= queue.size()) {
                    cancel();
                    finishPurge(res);
                    return;
                }
                Player p = queue.get(i++);
                if (p.isOnline()) {
                    broadcastLobby("game.purged-one", "player", p.getName(), "n", i, "of", queue.size());
                    for (Player o : Bukkit.getOnlinePlayers()) {
                        o.playSound(net.kyori.adventure.sound.Sound.sound(net.kyori.adventure.key.Key.key("cartzone", "purge"),
                            net.kyori.adventure.sound.Sound.Source.MASTER, 0.7f, 1f));
                    }
                    if (spectate) plugin.msg().send(p, "game.purged-spectate");
                    else p.kick(plugin.msg().get("game.purged-kick", "id", mm.id()));
                }
            }
        }.runTaskTimer(plugin, 20, every);
    }

    private void broadcastLobby(String key, Object... kv) {
        for (Player p : Bukkit.getOnlinePlayers()) plugin.msg().send(p, key, kv);
    }

    private void finishPurge(Purge.Result<Player> res) {
        GameSettings s = match.s();
        for (Player p : res.kept()) {
            match.add(p.getUniqueId(), p.getName());
            plugin.msg().send(p, "game.selected", "id", match.id());
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }
        match.startedAt = System.currentTimeMillis();
        World arena = plugin.arenas().world();
        if (arena != null && plugin.arenas().map() != null) plugin.deploy().preload(arena, plugin.arenas().map(), res.kept().size());
        plugin.store().markStarted(match.id(), match.startedAt, res.kept().size() + "/" + s.purgeCap);
        plugin.getLogger().info("Game #" + match.id() + " purge done: " + res.kept().size() + " selected, " + res.purged().size() + " purged");
        match.purged = true;
        match.enter(GameState.PURGE);
        if (s.squad > 1) {
            match.enter(GameState.TEAMING);
            plugin.teams().begin(match);
        } else {
            for (UUID u : match.players.keySet()) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.showTitle(Title.title(plugin.msg().get("titles.board"), plugin.msg().get("titles.board-sub"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))));
            }
        }
    }

    private void finishTeaming() {
        match.teams.clear();
        match.teams.addAll(plugin.teams().finish(match));
        assignTeamIndexes();
        beginBoarding();
    }

    private void assignTeamIndexes() {
        for (int t = 0; t < match.teams.size(); t++) {
            for (UUID u : match.teams.get(t)) {
                Match.MP mp = match.get(u);
                if (mp != null) mp.team = t;
            }
        }
    }

    private void beginBoarding() {
        match.enter(GameState.BOARDING);
        World w = plugin.arenas().world();
        MapConfig mc = plugin.arenas().map();
        if (w == null || mc == null) {
            abort("the arena world is not loaded");
            return;
        }
        GameSettings s = match.s();
        w.setGameRule(GameRule.FALL_DAMAGE, !s.has(Modifier.NO_FALL_DAMAGE));
        long id = match.id();
        long t0 = System.nanoTime();
        plugin.chests().spawn(w, mc, s.mode, match.players.size(), s.has(Modifier.LUCKIER_LOOT), s.has(Modifier.PEARLS), "game-" + id)
            .thenAccept(n -> plugin.getLogger().info("Game #" + id + ": " + plugin.chests().report()))
            .exceptionally(t -> {
                plugin.getLogger().log(Level.WARNING, "Chest placement failed", t);
                plugin.adminAlert("chests.failed", "error", String.valueOf(t.getMessage()));
                return null;
            });
        long t1 = System.nanoTime();
        plugin.nametags().applyMatch(match);
        long t2 = System.nanoTime();
        match.enter(GameState.DEPLOY);
        plugin.deploy().start(match, w, mc, this::beginPlaying);
        long t3 = System.nanoTime();
        plugin.debug(String.format("boarding took %.1f ms (chests start %.1f, nametags %.1f, deploy %.1f)",
            (t3 - t0) / 1e6, (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6));
        checkWin();
    }

    /** The second set of chests, inside the zone as it is right now. */
    private void lootWave() {
        World w = plugin.arenas().world();
        var mc = plugin.arenas().map();
        var z = plugin.zone().now();
        if (w == null || mc == null || z == null || z.circle().r() < 4) return;
        GameSettings s = match.s();
        long id = match.id();
        plugin.chests().spawnWave(w, mc, s.mode, match.players.size(), s.has(Modifier.LUCKIER_LOOT), s.has(Modifier.PEARLS), "game-" + id,
                z.circle().x(), z.circle().z(), z.circle().r())
            .thenAccept(n -> Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getLogger().info("Game #" + id + ": second chest wave, " + n + " chests inside r" + Math.round(z.circle().r()));
                if (n <= 0) return;
                for (Player p : w.getPlayers()) {
                    plugin.msg().send(p, "game.loot-wave", "count", n);
                    p.showTitle(net.kyori.adventure.title.Title.title(plugin.msg().get("titles.loot-wave"), plugin.msg().get("titles.loot-wave-sub", "count", n)));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_CHEST_OPEN, 1f, 0.8f);
                }
            }))
            .exceptionally(t -> {
                plugin.getLogger().log(Level.WARNING, "Second chest wave failed", t);
                return null;
            });
    }

    private void beginPlaying() {
        if (match == null || match.state != GameState.DEPLOY) return;
        match.enter(GameState.PLAYING);
        match.playingAt = System.currentTimeMillis();
        plugin.zone().start(match, plugin.arenas().world(), plugin.arenas().map());
        for (UUID u : match.players.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) plugin.msg().send(p, "game.zone-start-grace", "minutes", match.row.s.minutes, "grace", com.cartroyale.util.Times.mmss(plugin.getConfig().getInt("match.grace-seconds", 120)));
        }
        checkWin();
    }

    // ------------------------------------------------------------------ eliminations

    /** A player died, quit, or was removed. {@code killer} may be null. */
    public void eliminate(UUID victim, UUID killer, boolean quit) {
        if (match == null) return;
        Match.MP mp = match.get(victim);
        if (mp == null || !mp.alive) return;
        mp.alive = false;
        mp.placement = match.aliveCount() + 1;
        if (killer != null && !killer.equals(victim)) {
            Match.MP k = match.get(killer);
            if (k != null && !match.sameTeam(killer, victim)) {
                k.kills++;
                if (!match.row.test()) plugin.econ().rewardKill(killer, k.name);
            }
        }
        String killerName = killer == null || match.get(killer) == null ? null : match.get(killer).name;
        String key = quit ? "game.elim-quit" : killerName == null ? "game.elim" : "game.elim-by";
        int left = match.aliveCount();
        for (UUID u : match.players.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) plugin.msg().send(p, key, "victim", mp.name, "killer", killerName == null ? "" : killerName, "left", left);
        }
        for (Player p : plugin.spectators().watchers()) {
            if (!match.players.containsKey(p.getUniqueId())) plugin.msg().send(p, key, "victim", mp.name, "killer", killerName == null ? "" : killerName, "left", left);
        }
        checkWin();
    }

    private void checkWin() {
        if (match == null) return;
        if (match.state != GameState.DEPLOY && match.state != GameState.PLAYING && match.state != GameState.BOARDING) return;
        List<Integer> alive = match.aliveTeams();
        if (alive.size() <= 1 && (match.teams.size() > 1 || alive.isEmpty())) end(false);
    }

    // ------------------------------------------------------------------ end

    private void end(boolean timeout) {
        if (match == null || match.state == GameState.ENDING || match.state == GameState.RESETTING) return;
        match.enter(GameState.ENDING);
        plugin.zone().stop();
        plugin.deploy().stop(false);
        List<Integer> winTeams = match.aliveTeams();
        Set<UUID> winners = new HashSet<>();
        for (int t : winTeams) winners.addAll(match.teams.get(t));
        List<String> winnerNames = new ArrayList<>();
        int winnerKills = 0;
        for (UUID u : winners) {
            Match.MP mp = match.get(u);
            if (mp == null) continue;
            winnerNames.add(mp.name);
            winnerKills += mp.kills;
            if (mp.alive) mp.placement = 1;
        }
        Match.MP mvp = null;
        for (Match.MP mp : match.players.values()) {
            if (mvp == null || mp.kills > mvp.kills || (mp.kills == mvp.kills && winners.contains(mp.uuid) && !winners.contains(mvp.uuid))) mvp = mp;
        }
        if (mvp != null && mvp.kills == 0 && !winners.contains(mvp.uuid)) mvp = null;
        List<Store.PlayerResult> results = new ArrayList<>();
        for (Match.MP mp : match.players.values()) {
            results.add(new Store.PlayerResult(mp.uuid, mp.name, mp.team, mp.kills, mp.placement == 0 ? 1 : mp.placement, winners.contains(mp.uuid)));
        }
        long endedAt = System.currentTimeMillis();
        if (match.row.test()) plugin.store().setStatus(match.id(), GameRow.Status.CANCELLED);
        else plugin.store().finish(match.id(), endedAt, String.join(", ", winnerNames), mvp == null ? null : mvp.uuid,
                mvp == null ? null : mvp.name, mvp == null ? 0 : mvp.kills, results)
            .thenRun(() -> {
                plugin.stats().pushSoon();
                Bukkit.getScheduler().runTask(plugin, () -> plugin.leaderboard().refresh());
            });
        if (!match.row.test()) plugin.announcer().results(match.row, winnerNames, winnerKills, mvp == null ? null : mvp.name, mvp == null ? 0 : mvp.kills);
        Component winLine = plugin.msg().get(winnerNames.isEmpty() ? "game.end-none" : timeout ? "game.end-timeout" : "game.end",
            "winners", String.join(", ", winnerNames), "kills", winnerKills, "mvp", mvp == null ? "-" : mvp.name, "mvp_kills", mvp == null ? 0 : mvp.kills);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(winLine);
            boolean won = winners.contains(p.getUniqueId());
            p.showTitle(Title.title(plugin.msg().get(won ? "titles.victory" : "titles.game-over"),
                plugin.msg().get("titles.end-sub", "winners", String.join(", ", winnerNames)),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(4), Duration.ofMillis(800))));
            p.playSound(p.getLocation(), won ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 1f);
        }
        plugin.getLogger().info("Game #" + match.id() + " ended" + (timeout ? " (timeout)" : "") + ". Winners: " + winnerNames + ", MVP " + (mvp == null ? "-" : mvp.name));
        if (match.row.bought() && match.s().hosterUuid != null) plugin.tickets().afterGame(match.s().hosterUuid);
    }

    private void kickAndReset() {
        match.enter(GameState.RESETTING);
        World w = plugin.arenas().world();
        if (match.row.test()) {
            // Practice game: everyone back to the lobby, arena restored in place, no kick, no restart.
            for (Player p : List.copyOf(Bukkit.getOnlinePlayers())) {
                if (plugin.arenas().isArena(p.getWorld()) || match.get(p.getUniqueId()) != null) plugin.lobby().send(p);
            }
            plugin.chests().clear(w);
            plugin.deploy().stop(true);
            plugin.nametags().clearMatch();
            String map = match.map;
            match = null;
            endTest();
            resetArena(map);
            return;
        }
        for (Player p : List.copyOf(Bukkit.getOnlinePlayers())) {
            if (Ranks.isAdmin(p)) {
                p.setGameMode(GameMode.ADVENTURE);
                plugin.lobby().send(p);
            } else {
                p.kick(plugin.msg().get("game.end-kick", "id", match.id()));
            }
        }
        plugin.chests().clear(w);
        plugin.deploy().stop(true);
        plugin.nametags().clearMatch();
        String map = match.map;
        match = null;
        if (plugin.getConfig().getBoolean("match.restart-after", true)) {
            plugin.getLogger().info("Match over. Restarting the server once so the arena is restored from its template (END-5).");
            plugin.adminAlert("admin.restarting");
            Bukkit.getScheduler().runTaskLater(plugin, Bukkit::shutdown, 60);
        } else {
            resetArena(map);
        }
    }

    /** Stops a game without a result: everyone back to the lobby, arena restored, bought ticket refunded. */
    public void abort(String reason) {
        if (match == null) return;
        Match m = match;
        match = null;
        plugin.zone().stop();
        plugin.deploy().stop(true);
        plugin.teams().reset();
        endTest();
        plugin.nametags().clearMatch();
        World w = plugin.arenas().world();
        plugin.chests().clear(w);
        boolean wasInArena = m.state.inArena();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.arenas().isArena(p.getWorld())) plugin.lobby().send(p);
        }
        plugin.store().setStatus(m.id(), GameRow.Status.CANCELLED);
        if (m.row.bought() && m.s().hosterUuid != null) {
            plugin.store().refundTicket(m.s().hosterUuid, m.id());
            Player h = Bukkit.getPlayer(m.s().hosterUuid);
            if (h != null) plugin.msg().send(h, "tickets.refunded", "id", m.id());
        }
        plugin.msg().send(Bukkit.getServer(), "game.cancelled", "id", m.id(), "reason", reason);
        plugin.getLogger().warning("Game #" + m.id() + " aborted: " + reason);
        if (wasInArena) resetArena(m.map);
    }

    private void resetArena(String map) {
        String name = map != null ? map : plugin.arenas().loadedMap();
        if (name == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.arenas().swap(name).exceptionally(t -> {
            plugin.adminAlert("admin.map-swap-failed", "map", name, "error", String.valueOf(t.getMessage()));
            return null;
        }), 40);
    }
}
