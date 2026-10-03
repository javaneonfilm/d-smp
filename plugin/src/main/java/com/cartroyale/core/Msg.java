package com.cartroyale.core;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Modifying;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/**
 * Every player-facing string lives in messages.yml as MiniMessage. Placeholders are passed as
 * key/value pairs and written in the file as {@code <key>}. Values are inserted unparsed, so a
 * player name can never inject formatting.
 *
 * The look is set here too: {@code <sc>} turns text into small caps, and the palette tags
 * ({@code <accent> <hl> <fg> <sub> <dim> <ok> <warn> <bad> <coin> <ink>}) keep every message on the same
 * colours, so the whole server can be recoloured from {@link #PALETTE}.
 */
public final class Msg {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** The glass palette, in Diamond SMP's colours (taken from the logo). */
    public static final Map<String, Integer> PALETTE = Map.of(
        "accent", 0x8CC8DC,   // diamond light blue
        "hl", 0xC4DFE4,       // highlighted values: times, names in a sentence
        "fg", 0xEFF6F8,       // main text
        "sub", 0x8FB0BE,      // secondary text
        "dim", 0x4F7F92,      // hints, separators
        "ok", 0x4ADE80,
        "warn", 0xFACC15,
        "bad", 0xFF5D5D,
        "coin", 0xFFC94A,
        "ink", 0x0E2545);     // menu titles, which sit on Minecraft's light grey

    private static final String SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

    /** Letters to small caps (digits and symbols stay). */
    public static String smallCaps(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= 'a' && c <= 'z') sb.append(SMALL.charAt(c - 'a'));
            else if (c >= 'A' && c <= 'Z') sb.append(SMALL.charAt(c - 'A'));
            else sb.append(c);
        }
        return sb.toString();
    }

    private static final Pattern SC = Pattern.compile("<sc>(.*?)(?:</sc>|$)", Pattern.DOTALL);
    private static final Pattern PALETTE_TAG = Pattern.compile("</?(accent|hl|fg|sub|dim|ok|warn|bad|coin|ink)>");

    /** {@code <sc>}: rewrites every bit of text inside it, placeholders included. */
    private static final Modifying SMALL_CAPS = (current, depth) -> current instanceof TextComponent tc
        ? Component.text(smallCaps(tc.content()), tc.style())
        : current.children(List.of());

    private static final TagResolver STYLE;

    static {
        TagResolver.Builder b = TagResolver.builder();
        b.tag("sc", SMALL_CAPS);
        for (var e : PALETTE.entrySet()) b.tag(e.getKey(), Tag.styling(TextColor.color(e.getValue())));
        STYLE = b.build();
    }

    private final JavaPlugin plugin;
    private final Logger log;
    private YamlConfiguration yml;
    private YamlConfiguration defaults;
    private final Set<String> warned = new HashSet<>();

    public Msg(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        reload();
    }

    public void reload() {
        File f = new File(plugin.getDataFolder(), "messages.yml");
        if (!f.exists()) plugin.saveResource("messages.yml", false);
        yml = YamlConfiguration.loadConfiguration(f);
        var in = plugin.getResource("messages.yml");
        if (in != null) {
            defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            // An existing file keeps its own text for every key it has, so a rewrite of the
            // messages would never arrive. When the jar's messages-version is newer, the old file
            // is kept as messages-old-<n>.yml and the new one goes in its place.
            int have = yml.getInt("messages-version", 1), want = defaults.getInt("messages-version", 1);
            if (want > have) {
                File old = new File(plugin.getDataFolder(), "messages-old-" + have + ".yml");
                if (f.renameTo(old)) {
                    plugin.saveResource("messages.yml", true);
                    yml = YamlConfiguration.loadConfiguration(f);
                    log.info("messages.yml updated to version " + want + "; your old one is " + old.getName() + ".");
                }
            }
            yml.setDefaults(defaults);
        }
        warned.clear();
    }

    /** The raw MiniMessage string for a key, or a visible marker when it is missing. */
    public String raw(String key) {
        String s = yml.getString(key);
        if (s == null) {
            if (warned.add(key)) log.warning("messages.yml is missing key '" + key + "'");
            return "<red>[missing message: " + key + "]";
        }
        return s;
    }

    public List<String> rawList(String key) {
        List<String> l = yml.getStringList(key);
        if (l.isEmpty() && !yml.contains(key) && warned.add(key)) log.warning("messages.yml is missing list '" + key + "'");
        return l;
    }

    public boolean has(String key) {
        return yml.contains(key);
    }

    /** Renders a key with the prefix tag available as {@code <prefix>}. */
    public Component get(String key, Object... kv) {
        return parse(raw(key), kv);
    }

    /** Same as {@link #get} but with italics forced off, for item names and lore. */
    public Component item(String key, Object... kv) {
        return get(key, kv).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public List<Component> lore(String key, Object... kv) {
        List<Component> out = new ArrayList<>();
        for (String line : rawList(key)) {
            out.add(parse(line, kv).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        return out;
    }

    public Component parse(String mini, Object... kv) {
        return MM.deserialize(mini, resolver(kv));
    }

    public void send(Audience to, String key, Object... kv) {
        to.sendMessage(get(key, kv));
    }

    public void actionbar(Audience to, String key, Object... kv) {
        to.sendActionBar(get(key, kv));
    }

    /**
     * Plain text of a key with placeholders, for places that take a String (Discord, values put
     * into other lines). {@code <sc>} still becomes small caps; palette tags are dropped.
     */
    public String plain(String key, Object... kv) {
        String s = raw(key);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("<" + kv[i] + ">", String.valueOf(kv[i + 1]));
        }
        Matcher m = SC.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(smallCaps(m.group(1))));
        m.appendTail(out);
        return PALETTE_TAG.matcher(out).replaceAll("");
    }

    private TagResolver resolver(Object... kv) {
        TagResolver.Builder b = TagResolver.builder();
        b.resolver(STYLE);
        b.resolver(Placeholder.parsed("prefix", yml.getString("prefix", "")));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String k = String.valueOf(kv[i]);
            Object v = kv[i + 1];
            if (v instanceof Component c) b.resolver(Placeholder.component(k, c));
            else b.resolver(Placeholder.unparsed(k, String.valueOf(v)));
        }
        return b.build();
    }

    /** Placeholders can't go inside a click tag's argument, so clickable parts are built here. */
    public static Component link(String url) {
        return Component.text(url).clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(url));
    }

    public static Component command(Component label, String cmd) {
        return label.clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(cmd));
    }

    public static String strip(Component c) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
    }
}
