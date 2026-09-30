package com.cartroyale.hooks;

import com.cartroyale.CartRoyale;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discord webhook sender. One background thread sends messages in order; HTTP 429 is retried
 * after Discord's retry_after. Failures are logged without the URL and never reach the game.
 */
public final class Webhooks {

    private static final Pattern RETRY = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)");

    private final CartRoyale plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CartRoyale-Webhooks");
        t.setDaemon(true);
        return t;
    });
    private volatile String lastError = "none";
    private volatile int sent;

    public Webhooks(CartRoyale plugin) {
        this.plugin = plugin;
    }

    public boolean configured() {
        String url = plugin.secret("discord-webhook-url");
        // http is only accepted for a local test receiver.
        return plugin.getConfig().getBoolean("webhooks.enabled", true) && url != null
            && (url.startsWith("https://") || url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:"));
    }

    public String status() {
        return (configured() ? "configured" : "NOT configured (secrets.yml discord-webhook-url)") + ", sent " + sent + ", last error: " + lastError;
    }

    /** One embed. {@code ping} adds the events role mention with allowed_mentions set. */
    public void send(Embed embed, boolean ping) {
        if (!configured()) return;
        String url = plugin.secret("discord-webhook-url");
        String role = plugin.getConfig().getString("webhooks.events-role-id", "").trim();
        StringBuilder json = new StringBuilder("{");
        json.append("\"username\":").append(q(plugin.getConfig().getString("webhooks.username", "CartRoyale"))).append(',');
        if (ping && role.matches("\\d{5,25}")) {
            json.append("\"content\":").append(q("<@&" + role + ">")).append(',');
            json.append("\"allowed_mentions\":{\"parse\":[],\"roles\":[").append(q(role)).append("]},");
        } else {
            json.append("\"allowed_mentions\":{\"parse\":[]},");
        }
        json.append("\"embeds\":[").append(embed.json()).append("]}");
        String body = json.toString();
        queue.execute(() -> post(url, body));
    }

    private void post(String url, String body) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url + (url.contains("?") ? "&" : "?") + "wait=true"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = res.statusCode();
                if (code >= 200 && code < 300) {
                    sent++;
                    return;
                }
                if (code == 429) {
                    Matcher m = RETRY.matcher(res.body());
                    double wait = m.find() ? Double.parseDouble(m.group(1)) : 2;
                    Thread.sleep((long) (Math.min(wait, 60) * 1000) + 250);
                    continue;
                }
                lastError = "HTTP " + code;
                plugin.getLogger().warning("Discord webhook rejected a message: HTTP " + code);
                if (code >= 500) {
                    Thread.sleep(2000L * (attempt + 1));
                    continue;
                }
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName();
                plugin.getLogger().warning("Discord webhook failed (" + e.getClass().getSimpleName() + "), retrying");
                try {
                    Thread.sleep(2000L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public void shutdown() {
        queue.shutdown();
        try {
            queue.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> {
                }
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    public record Embed(String title, String description, int color, String footer) {
        String json() {
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"title\":").append(q(title)).append(',');
            sb.append("\"description\":").append(q(description)).append(',');
            sb.append("\"color\":").append(color);
            if (footer != null && !footer.isBlank()) sb.append(",\"footer\":{\"text\":").append(q(footer)).append('}');
            return sb.append('}').toString();
        }
    }

    public static String joinLines(List<String> lines) {
        return String.join("\n", lines);
    }
}
