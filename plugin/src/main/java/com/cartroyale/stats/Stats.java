package com.cartroyale.stats;

import com.cartroyale.CartRoyale;
import com.cartroyale.db.Store;
import com.cartroyale.game.Match;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * STATS-2/3. Builds the website JSON (top 10 wins/kills/played, last 8 rounds, live status) and
 * POSTs it to the Cloudflare Worker with a bearer token from secrets.yml, or writes stats.json in
 * FILE mode. Never on the main thread; a failure is retried next interval and never touches play.
 */
public final class Stats {

    private final CartRoyale plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile String last = "never";

    public Stats(CartRoyale plugin) {
        this.plugin = plugin;
        int every = Math.max(15, plugin.getConfig().getInt("stats.export.interval-seconds", 60));
        Bukkit.getScheduler().runTaskTimer(plugin, this::push, 20L * 20, 20L * every);
    }

    public String status() {
        return plugin.getConfig().getString("stats.export.mode", "OFF") + ", last: " + last;
    }

    public void pushSoon() {
        Bukkit.getScheduler().runTaskLater(plugin, this::push, 40);
    }

    /** Main thread: snapshot the live status, then build and send off-thread. */
    public void push() {
        String mode = plugin.getConfig().getString("stats.export.mode", "OFF").toUpperCase();
        // YAML reads an unquoted OFF as the boolean false.
        if (mode.equals("OFF") || mode.equals("FALSE") || !busy.compareAndSet(false, true)) return;
        Match m = plugin.games().match();
        int online = 0;
        for (Player p : Bukkit.getOnlinePlayers()) if (!p.hasPermission("cartzone.admin")) online++;
        String state = m == null ? (plugin.games().next() == null ? "idle" : "lobby") : m.state.inArena() ? "playing" : "lobby";
        int max = plugin.games().next() != null ? plugin.games().next().s.purgeCap : m != null ? m.s().purgeCap : plugin.getConfig().getInt("host.default-cap", 100);
        String status = "{\"online\":" + online + ",\"max\":" + max + ",\"state\":\"" + state + "\"}";
        Store st = plugin.store();
        CompletableFuture.allOf().thenCompose(v -> st.top("wins", 10)).thenCompose(wins -> st.top("kills", 10).thenCompose(kills ->
            st.top("played", 10).thenCompose(played -> st.recentRounds(8).thenApply(rounds -> json(wins, kills, played, rounds, status)))))
            .thenAccept(json -> send(mode, json))
            .whenComplete((v, t) -> {
                if (t != null) last = "failed: " + t.getClass().getSimpleName();
                busy.set(false);
            });
    }

    private void send(String mode, String json) {
        if (mode.equals("FILE")) {
            try {
                File f = new File(plugin.getDataFolder(), "stats.json");
                File tmp = new File(plugin.getDataFolder(), "stats.json.tmp");
                Files.writeString(tmp.toPath(), json, StandardCharsets.UTF_8);
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                last = "wrote stats.json at " + Instant.now();
            } catch (Exception e) {
                last = "file write failed: " + e.getMessage();
            }
            return;
        }
        String url = plugin.getConfig().getString("stats.export.url", "");
        String token = plugin.secret("stats-token");
        if (url == null || !url.startsWith("https://") || token == null || token.isBlank()) {
            last = "not configured (stats.export.url in config.yml, stats-token in secrets.yml)";
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            last = "HTTP " + res.statusCode() + " at " + Instant.now();
            if (res.statusCode() >= 300) plugin.getLogger().warning("Stats upload rejected: HTTP " + res.statusCode());
        } catch (Exception e) {
            last = "upload failed: " + e.getClass().getSimpleName();
        }
    }

    static String json(List<Store.Stat> wins, List<Store.Stat> kills, List<Store.Stat> played, List<Store.Round> rounds, String status) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"updated\":\"").append(Instant.now().toString().replaceAll("\\.\\d+Z$", "Z")).append("\",");
        sb.append("\"wins\":").append(board(wins)).append(',');
        sb.append("\"kills\":").append(board(kills)).append(',');
        sb.append("\"played\":").append(board(played)).append(',');
        sb.append("\"rounds\":[");
        for (int i = 0; i < rounds.size(); i++) {
            Store.Round r = rounds.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(r.id()).append(",\"winner\":").append(q(r.winner())).append(",\"players\":").append(q(r.players()))
                .append(",\"time\":").append(q(r.time())).append('}');
        }
        sb.append("],\"status\":").append(status).append('}');
        return sb.toString();
    }

    private static String board(List<Store.Stat> l) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"name\":").append(q(l.get(i).name())).append(",\"val\":").append(l.get(i).val()).append('}');
        }
        return sb.append(']').toString();
    }

    private static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    /** The slot-9 stats book: the player's own numbers plus the website link. */
    public void show(Player p) {
        plugin.store().profile(p.getUniqueId()).thenAccept(pr -> Bukkit.getScheduler().runTask(plugin, () ->
            plugin.msg().send(p, "stats.self", "coins", pr.coins(), "kills", pr.kills(), "wins", pr.wins(), "played", pr.played(),
                "site", com.cartroyale.core.Msg.link(plugin.getConfig().getString("stats.site-url", "https://cartzone.vip/#boards")))));
    }
}
