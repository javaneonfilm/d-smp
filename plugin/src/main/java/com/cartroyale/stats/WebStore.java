package com.cartroyale.stats;

import com.cartroyale.CartRoyale;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The web store's delivery side. Purchases on the website are paid through PayPal and land in the
 * Worker (worker/worker.js); every webstore.poll-seconds this asks the Worker for orders it hasn't
 * delivered, runs the product's commands from config.yml (webstore.products), and confirms them.
 * Works while the buyer is offline (LuckPerms and tickets go by UUID). Delivered order ids are kept
 * in the database too, so a lost confirmation never delivers twice.
 */
public final class WebStore {

    private static final String DONE_KEY = "webstore.delivered";
    private static final int KEEP = 500;

    private final CartRoyale plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Set<String> done = new LinkedHashSet<>();
    private final Set<String> warned = new LinkedHashSet<>();
    private volatile String last = "never";

    public WebStore(CartRoyale plugin) {
        this.plugin = plugin;
        plugin.db().kvGet(DONE_KEY).thenAccept(v -> {
            if (v == null || v.isBlank()) return;
            synchronized (done) {
                for (String s : v.split(",")) if (!s.isBlank()) done.add(s);
            }
        });
        int every = Math.max(15, plugin.getConfig().getInt("webstore.poll-seconds", 30));
        Bukkit.getScheduler().runTaskTimer(plugin, this::poll, 20L * 30, 20L * every);
    }

    public String status() {
        return (base() == null ? "off (needs stats.export.url and stats-token)" : "on") + ", last: " + last;
    }

    /** The Worker's address: webstore.url, or stats.export.url without its /stats ending. */
    private String base() {
        if (!plugin.getConfig().getBoolean("webstore.enabled", true)) return null;
        String url = plugin.getConfig().getString("webstore.url", "");
        if (url == null || url.isBlank()) url = plugin.getConfig().getString("stats.export.url", "");
        if (url == null) return null;
        url = url.replaceAll("/+$", "").replaceAll("/stats$", "");
        boolean ok = url.startsWith("https://") || url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:");
        String token = plugin.secret("stats-token");
        return ok && token != null && !token.isBlank() && !token.equals("REPLACE_ME") ? url : null;
    }

    public void poll() {
        String base = base();
        if (base == null || !busy.compareAndSet(false, true)) return;
        String token = plugin.secret("stats-token");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/orders")).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token).GET().build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) {
                    last = "HTTP " + res.statusCode() + " at " + Instant.now();
                    busy.set(false);
                    return;
                }
                JsonArray orders = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("orders");
                last = "OK at " + Instant.now() + (orders.isEmpty() ? "" : ", " + orders.size() + " new");
                if (orders.isEmpty()) {
                    busy.set(false);
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> deliver(base, token, orders));
            } catch (Exception e) {
                last = "failed: " + e.getClass().getSimpleName() + " at " + Instant.now();
                busy.set(false);
            }
        });
    }

    /** Main thread: run each order's commands, then confirm them to the Worker. */
    private void deliver(String base, String token, JsonArray orders) {
        List<String> ack = new ArrayList<>();
        for (JsonElement el : orders) {
            JsonObject o = el.getAsJsonObject();
            String id = str(o, "id"), kind = str(o, "kind"), ref = str(o, "ref"), product = str(o, "product"), uuid = str(o, "uuid"), name = str(o, "name");
            if (id.isEmpty() || uuid.isEmpty()) continue;
            synchronized (done) {
                if (done.contains(id)) {
                    ack.add(id);
                    continue;
                }
            }
            try {
                UUID.fromString(uuid);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ConfigurationSection p = plugin.getConfig().getConfigurationSection("webstore.products." + product);
            List<String> cmds = p == null ? List.of() : p.getStringList(kind.equals("revoke") ? "revoke" : "buy");
            if (p == null) {
                // Leave it waiting until the product is added to config.yml.
                if (warned.add(id)) plugin.getLogger().warning("Web store order " + id + " is for '" + product + "', which isn't in webstore.products. It waits until it is.");
                continue;
            }
            for (String c : cmds) {
                String cmd = c.replace("{uuid}", uuid).replace("{name}", name).replace("{order}", ref.isEmpty() ? id : ref).replace("{product}", product);
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                } catch (Exception e) {
                    plugin.getLogger().warning("Web store command failed (" + cmd + "): " + e.getMessage());
                }
            }
            // getString(path, fallback) would skip config.yml's built-in defaults on the first start.
            String label = p.getString("name");
            if (label == null || label.isBlank()) label = product;
            plugin.getLogger().info("Web store: " + (kind.equals("revoke") ? "took back " : "delivered ") + label + " for " + name + " (" + uuid + "), order " + id);
            if (!kind.equals("revoke")) {
                Player pl = Bukkit.getPlayer(UUID.fromString(uuid));
                if (pl != null) plugin.msg().send(pl, "webstore.delivered", "item", label);
                plugin.adminAlert("webstore.admin-sold", "item", label, "player", name);
            } else {
                plugin.adminAlert("webstore.admin-revoked", "item", label, "player", name);
            }
            synchronized (done) {
                done.add(id);
                while (done.size() > KEEP) done.remove(done.iterator().next());
            }
            ack.add(id);
        }
        String saved;
        synchronized (done) {
            saved = String.join(",", done);
        }
        plugin.db().kvSet(DONE_KEY, saved);
        if (ack.isEmpty()) {
            busy.set(false);
            return;
        }
        StringBuilder body = new StringBuilder("{\"ids\":[");
        for (int i = 0; i < ack.size(); i++) body.append(i > 0 ? "," : "").append('"').append(ack.get(i).replace("\\", "").replace("\"", "")).append('"');
        body.append("]}");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/orders/ack")).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
                http.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                // Not confirmed: the Worker sends them again, and the done list skips them.
            } finally {
                busy.set(false);
            }
        });
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
