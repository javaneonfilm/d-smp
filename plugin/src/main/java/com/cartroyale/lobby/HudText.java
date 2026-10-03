package com.cartroyale.lobby;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Boss bar titles drawn with the pack's cartzone:hud font: the IP pill, the zone panel and the
 * squad cards. Glyphs are drawn and then the pen is moved with space glyphs, so every title has a
 * known width and its parts line up. Sizes must match resourcepack/tools/build_pack.py.
 */
public final class HudText {

    private static final Key FONT = Key.key("cartzone", "hud");
    private static final char ICON = '', IP_PILL = '', ZONE_PANEL = '';
    private static final int IP_W = 120, ICON_ADVANCE = 11, SPACE = 4, ZONE_W = 182, ZONE_BAR_W = 160;
    /** Bar pieces 1, 2, 4 ... 128 px wide: solid (tinted by the text colour) and the faint track. */
    private static final char FILL = '', TRACK = '';

    private static final Map<Character, Integer> WIDTHS = loadWidths();

    private HudText() {
    }

    // ---------------- text widths ----------------

    private static Map<Character, Integer> loadWidths() {
        Map<Character, Integer> m = new HashMap<>();
        var in = HudText.class.getResourceAsStream("/font-widths.txt");
        if (in == null) return m;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.trim().split(" ");
                m.put((char) Integer.parseInt(p[0], 16), Integer.parseInt(p[1]));
            }
        } catch (Exception ignored) {
            // Widths fall back to 6 px, which is right for most letters.
        }
        return m;
    }

    /** Pixel advance of text in the default Minecraft font. */
    static int width(String s) {
        int w = 0;
        for (char c : s.toCharArray()) w += WIDTHS.getOrDefault(c, 6);
        return w;
    }

    /** Pixel width of a component in the default font, bold counted (+1 per character). */
    public static int width(Component c) {
        return width(c, false);
    }

    private static int width(Component c, boolean boldParent) {
        var st = c.style().decoration(TextDecoration.BOLD);
        boolean bold = st == TextDecoration.State.TRUE || (st == TextDecoration.State.NOT_SET && boldParent);
        int w = 0;
        if (c instanceof TextComponent tc && c.style().font() == null) {
            w += width(tc.content()) + (bold ? tc.content().length() : 0);
        }
        for (Component child : c.children()) w += width(child, bold);
        return w;
    }

    /** Any horizontal move, composed from the pack's power-of-two space glyphs. */
    public static String shift(int px) {
        StringBuilder sb = new StringBuilder();
        int n = Math.abs(px);
        char base = px < 0 ? '' : '';
        for (int bit = 7; bit >= 0; bit--) {
            while (n >= (1 << bit)) {
                sb.append((char) (base + bit));
                n -= 1 << bit;
            }
        }
        return sb.toString();
    }

    /** {@link #shift} as a component in the HUD font, ready to append. */
    public static Component space(int px) {
        return Component.text(shift(px)).font(FONT);
    }

    /** A bar run exactly {@code px} wide from the power-of-two pieces (each piece advances 1 extra, taken back). */
    static String run(char first, int px) {
        StringBuilder sb = new StringBuilder();
        int n = Math.max(0, px);
        for (int bit = 7; bit >= 0; bit--) {
            while (n >= (1 << bit)) {
                sb.append((char) (first + bit)).append('');
                n -= 1 << bit;
            }
        }
        return sb.toString();
    }

    /** A bar {@code w} px wide, {@code on} px of it in the fill colour, the rest as faint track. */
    static Component bar(int w, int on, TextColor fill, TextColor track, ShadowColor shadow) {
        return bar(FILL, TRACK, w, on, fill, track, shadow);
    }

    private static Component bar(char fillSet, char trackSet, int w, int on, TextColor fill, TextColor track, ShadowColor shadow) {
        on = Math.max(0, Math.min(w, on));
        return Component.text()
            .append(Component.text(run(fillSet, on), fill).font(FONT).shadowColor(shadow))
            .append(Component.text(run(trackSet, w - on), track).font(FONT).shadowColor(shadow))
            .build();
    }

    // ---------------- IP pill ----------------

    /** The top-centre pill: the logo on both sides of the IP, centred on a rounded glass panel. */
    public static Component bar(Component ipText, String ipPlain) {
        int content = ICON_ADVANCE + SPACE + width(ipText) + SPACE + ICON_ADVANCE - 1;
        int pad = Math.max(0, (IP_W - content) / 2);
        return Component.text()
            .append(Component.text(IP_PILL + shift(-(IP_W + 1) + pad) + ICON).font(FONT).color(NamedTextColor.WHITE).shadowColor(ShadowColor.none()))
            .append(Component.text(" "))
            .append(ipText)
            .append(Component.text(shift(SPACE) + ICON + shift(IP_W - pad - content - 1)).font(FONT).color(NamedTextColor.WHITE).shadowColor(ShadowColor.none()))
            .build();
    }

    // ---------------- zone panel ----------------

    /**
     * The zone panel where the (invisible) vanilla bar sits: the title centred on the first line
     * and a slim bar under it, {@code fraction} of it lit in {@code color}.
     */
    public static Component zoneBar(Component title, double fraction, TextColor color) {
        int t = width(title);
        int titleX = (ZONE_W - t) / 2, barX = (ZONE_W - ZONE_BAR_W) / 2;
        int on = (int) Math.round(Math.max(0, Math.min(1, fraction)) * ZONE_BAR_W);
        ShadowColor none = ShadowColor.none();
        return Component.text()
            .append(Component.text(ZONE_PANEL + shift(-(ZONE_W + 1) + titleX)).font(FONT).color(NamedTextColor.WHITE).shadowColor(none))
            .append(title)
            .append(Component.text(shift(barX - titleX - t)).font(FONT))
            .append(bar(ZONE_BAR_W, on, color, TextColor.color(0xFFFFFF), none))
            .append(Component.text(shift(ZONE_W - barX - ZONE_BAR_W)).font(FONT))
            .build();
    }

    // ---------------- squad cards ----------------

    /** Card width in GUI pixels; must match CZ_CARD_W in the pack's cz_hud.glsl. */
    public static final int CARD_W = 136;
    private static final int CARD_ADVANCE = CARD_W + 1, HEAD_X = 3, TEXT_X = 24, KILLS_ICON = 8, RIGHT_PAD = 6;
    private static final char CARD = '', KILLS = '';
    /** Card text: the vanilla letters, drawn 3 px lower so they sit inside the card. */
    private static final Key CARD_FONT = Key.key("cartzone", "card");
    /** The card's health bar pieces: 2 px tall, 15 px below the title's top. */
    private static final char CARD_FILL = '', CARD_TRACK = '';

    /**
     * One squad card for the top-left HUD: a glass panel, the player's head (drawn 2x by the
     * shader), the name with their kills on the right, and a slim health bar under it filled to
     * {@code health} (0-1). Every colour must already carry the HUD marker (SquadHud.mark), and
     * the pen ends exactly CARD_W to the right so the shader knows where the card starts.
     */
    public static Component squadCard(Component head, String name, TextColor nameColor, boolean struck, ShadowColor textShadow,
                                      TextColor killsColor, int kills, double health, TextColor hpColor, TextColor white) {
        String k = String.valueOf(kills);
        int killsW = KILLS_ICON + width(k);
        int maxName = CARD_W - TEXT_X - RIGHT_PAD - killsW - 4;
        String shown = name;
        if (width(shown) > maxName) {
            while (!shown.isEmpty() && width(shown + "..") > maxName) shown = shown.substring(0, shown.length() - 1);
            shown += "..";
        }
        int nameEnd = TEXT_X + width(shown);
        int barW = CARD_W - TEXT_X - RIGHT_PAD;
        int on = health <= 0 ? 0 : Math.max(1, (int) Math.round(Math.min(1, health) * barW));
        ShadowColor none = ShadowColor.none();
        return Component.text()
            .append(Component.text(CARD + shift(-CARD_ADVANCE + HEAD_X)).font(FONT).color(white).shadowColor(none))
            .append(head)
            .append(Component.text(shift(TEXT_X - HEAD_X - 8)).font(FONT))
            .append(Component.text(shown, nameColor).font(CARD_FONT).shadowColor(textShadow)
                .decoration(TextDecoration.STRIKETHROUGH, struck))
            .append(Component.text(shift(CARD_W - RIGHT_PAD - killsW - nameEnd) + KILLS).font(FONT).color(white).shadowColor(none))
            .append(Component.text(k, killsColor).font(CARD_FONT).shadowColor(textShadow))
            .append(Component.text(shift(-(CARD_W - RIGHT_PAD - TEXT_X)), white).font(FONT).shadowColor(none))
            .append(bar(CARD_FILL, CARD_TRACK, barW, on, hpColor, white, none))
            .append(Component.text(shift(RIGHT_PAD)).font(FONT))
            .build();
    }
}
