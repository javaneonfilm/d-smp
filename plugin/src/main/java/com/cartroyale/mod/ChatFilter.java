package com.cartroyale.mod;

import com.cartroyale.CartRoyale;
import com.cartroyale.core.Yml;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Blocks chat that matches chatfilter.yml and (auto-punish) hands the matching /offence type
 * straight to {@link Offences}. Matching undoes leetspeak, joins spaced-out letters and lets
 * every letter be stretched, but only ever matches whole words, so "raccoon" is not "coon".
 */
public final class ChatFilter implements Listener {

    private record Rule(String type, Pattern pattern, String source) {
    }

    private final CartRoyale plugin;
    private final Yml yml;
    private final List<Rule> rules = new ArrayList<>();

    public ChatFilter(CartRoyale plugin) {
        this.plugin = plugin;
        this.yml = new Yml(plugin, "chatfilter.yml", true);
        load();
    }

    /** For tests: rules straight from type -> words/phrases, no plugin. */
    ChatFilter(java.util.Map<String, List<String>> lists) {
        this.plugin = null;
        this.yml = null;
        for (var e : lists.entrySet()) for (String w : e.getValue()) add(e.getKey(), w);
    }

    public void reload() {
        yml.reload();
        load();
    }

    private void load() {
        rules.clear();
        var sec = yml.get().getConfigurationSection("types");
        if (sec == null) return;
        for (String type : sec.getKeys(false)) {
            for (String w : sec.getStringList(type + ".words")) add(type, w);
            for (String ph : sec.getStringList(type + ".phrases")) add(type, ph);
        }
    }

    /** Each word becomes letters that may repeat (a run of k letters needs at least k), words joined by spaces. */
    private void add(String type, String text) {
        String n = normalize(text);
        if (n.isBlank()) return;
        StringBuilder re = new StringBuilder("(?:^| )");
        String[] words = n.trim().split(" ");
        for (int wi = 0; wi < words.length; wi++) {
            if (wi > 0) re.append(" ");
            String w = words[wi];
            for (int i = 0; i < w.length(); ) {
                char ch = w.charAt(i);
                int j = i;
                while (j < w.length() && w.charAt(j) == ch) j++;
                re.append(Pattern.quote(String.valueOf(ch))).append("{").append(j - i).append(",}");
                i = j;
            }
        }
        re.append("(?:$| )");
        rules.add(new Rule(type, Pattern.compile(re.toString()), text));
    }

    /** Lower case, leetspeak undone, everything but letters becomes a space, spaced-out single letters joined. */
    static String normalize(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            b.append(switch (c) {
                case '0' -> 'o';
                case '1', '!', '|' -> 'i';
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '5', '$' -> 's';
                case '7' -> 't';
                case '8' -> 'b';
                case '9' -> 'g';
                default -> Character.isLetter(c) ? c : ' ';
            });
        }
        String[] parts = b.toString().trim().split(" +");
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (String part : parts) {
            if (part.length() == 1) {
                run.append(part);
                continue;
            }
            if (!run.isEmpty()) {
                out.append(out.isEmpty() ? "" : " ").append(run);
                run.setLength(0);
            }
            if (!part.isEmpty()) out.append(out.isEmpty() ? "" : " ").append(part);
        }
        if (!run.isEmpty()) out.append(out.isEmpty() ? "" : " ").append(run);
        return out.toString();
    }

    /** The /offence type a message breaks, or null. */
    public String check(String message) {
        String n = normalize(message);
        for (Rule r : rules) if (r.pattern().matcher(n).find()) return r.type();
        return null;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!yml.get().getBoolean("enabled", true)) return;
        Player p = e.getPlayer();
        String bypass = yml.get().getString("bypass-permission", "cartzone.offence");
        if (bypass != null && !bypass.isBlank() && p.hasPermission(bypass)) return;
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        String type = check(text);
        if (type == null) return;
        e.setCancelled(true);
        boolean auto = yml.get().getBoolean("auto-punish", true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Offences.Type t = plugin.offences().type(type);
            String shown = text.length() > 80 ? text.substring(0, 80) + "..." : text;
            plugin.msg().send(p, "offence.blocked");
            if (auto && t != null) {
                plugin.offences().apply(p, p.getName(), t, "Chat filter", shown, null);
            } else {
                for (Player s : Bukkit.getOnlinePlayers()) {
                    if (s.hasPermission("cartzone.offence")) plugin.msg().send(s, "offence.filter-alert", "player", p.getName(),
                        "type", t == null ? type : t.name(), "message", shown);
                }
                plugin.getLogger().info("Chat filter blocked " + p.getName() + " (" + type + "): " + shown);
            }
        });
    }
}
