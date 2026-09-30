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
        plugin.announcer().hosted(row);
        plugin.msg().send(Bukkit.getServer(), "game.hosted", "id", id, "hoster", s.hosterName,
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
        s.hosterName = by == null ? "CartRoyale" : by.getName();
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
            if (!next.warned && now >= next.s.startAt - warnMs) {
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
                if (match.inStateMs() >= 3000) doPurge();
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
                long hard = (plugin.announcer().durationMinutes() + plugin.getConfig().getInt("match.hard-timeout-extra-minutes", 5)) * 60_000L;
                if (now - match.playingAt >= hard) end(true);
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
        plugin.announcer().purge(match.row, el.size());
        plugin.getLogger().info("Game #" + match.id() + " purge starting with " + el.size() + " eligible players (cap " + match.s().purgeCap + ")");
    }

    private void doPurge() {
        GameSettings s = match.s();
        List<Player> el = eligible();
        int min = plugin.getConfig().getInt("join.min-players", 2);
        if (el.size() < min) {
            abort(plugin.msg().plain("game.reason-not-enough", "min", min));
            return;
        }
        var cfg = plugin.getConfig();
        double vipWeight = cfg.getDouble("purge.weights.vip", 0.15), defWeight = cfg.getDouble("purge.weights.default", 1.0);
        boolean vipPlusImmune = cfg.getBoolean("purge.vipplus-immune", true), hosterImmune = cfg.getBoolean("purge.hoster-immune", true);
        List<Purge.Candidate<Player>> cands = new ArrayList<>();
        for (Player p : el) {
            Ranks.Rank r = Ranks.bought(p);
            boolean immune = (vipPlusImmune && r == Ranks.Rank.VIPPLUS) || (hosterImmune && p.getUniqueId().equals(s.hosterUuid));
            double w = r == Ranks.Rank.VIP ? vipWeight : defWeight;
            cands.add(new Purge.Candidate<>(p, w, immune));
        }
        Purge.Result<Player> res = Purge.run(cands, s.purgeCap, new Random());
        if (res.immuneOverflow()) plugin.getLogger().warning("Game #" + match.id() + ": immune players alone exceeded the purge cap; picked among them.");
        boolean spectate = "SPECTATE".equalsIgnoreCase(cfg.getString("purge.action", "KICK"));
        for (Player p : res.purged()) {
            match.removed.add(p.getUniqueId());
            if (spectate) plugin.msg().send(p, "game.purged-spectate");
            else p.kick(plugin.msg().get("game.purged-kick", "id", match.id()));
        }
        for (Player p : res.kept()) {
            match.add(p.getUniqueId(), p.getName());
            plugin.msg().send(p, "game.selected", "id", match.id());
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }
        match.startedAt = System.currentTimeMillis();
        plugin.store().markStarted(match.id(), match.startedAt, res.kept().size() + "/" + s.purgeCap);
        plugin.getLogger().info("Game #" + match.id() + " purge done: " + res.kept().size() + " selected, " + res.purged().size() + " purged");
        if (s.squad > 1) {
            match.enter(GameState.TEAMING);
            plugin.teams().begin(match);
        } else {
            for (UUID u : match.players.keySet()) match.teams.add(new ArrayList<>(List.of(u)));
            assignTeamIndexes();
            beginBoarding();
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
        plugin.chests().spawn(w, mc, match.players.size(), s.has(Modifier.LUCKIER_LOOT), s.has(Modifier.PEARLS), "game-" + match.id())
            .thenAccept(n -> plugin.getLogger().info("Game #" + match.id() + ": " + plugin.chests().report()))
            .exceptionally(t -> {
                plugin.getLogger().log(Level.WARNING, "Chest placement failed", t);
                plugin.adminAlert("chests.failed", "error", String.valueOf(t.getMessage()));
                return null;
            });
        plugin.nametags().applyMatch(match);
        match.enter(GameState.DEPLOY);
        plugin.deploy().start(match, w, mc, this::beginPlaying);
        checkWin();
    }

    private void beginPlaying() {
        if (match == null || match.state != GameState.DEPLOY) return;
        match.enter(GameState.PLAYING);
        match.playingAt = System.currentTimeMillis();
        plugin.zone().start(match, plugin.arenas().world(), plugin.arenas().map());
        for (UUID u : match.players.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) plugin.msg().send(p, "game.zone-start", "minutes", plugin.announcer().durationMinutes());
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
                plugin.econ().rewardKill(killer, k.name);
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
        plugin.store().finish(match.id(), endedAt, String.join(", ", winnerNames), mvp == null ? null : mvp.uuid,
                mvp == null ? null : mvp.name, mvp == null ? 0 : mvp.kills, results)
            .thenRun(() -> plugin.stats().pushSoon());
        plugin.announcer().results(match.row, winnerNames, winnerKills, mvp == null ? null : mvp.name, mvp == null ? 0 : mvp.kills);
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
