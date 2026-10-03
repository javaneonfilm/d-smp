package com.cartroyale.lobby;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * UI-2: builds the top-centre IP bar title from the pack's cartzone:hud font. The bar glyph is
 * drawn first, then the pen is moved back with negative-space glyphs so the minecart icon and the
 * IP sit centred on it. Sizes must match resourcepack/tools/build_pack.py.
 */
public final class HudText {

    private static final Key FONT = Key.key("cartzone", "hud");
    private static final int BAR_W = 150, ICON_ADVANCE = 11, SPACE = 4;
    private static final char ICON = '\uE000', BAR = '\uE001';

    private HudText() {
    }

    public static Component bar(Component ipText, String ipPlain) {
        int content = ICON_ADVANCE + SPACE + width(ipPlain);
        int pad = Math.max(0, (BAR_W - content) / 2);
        String glyphs = shift(-pad) + BAR + shift(-(BAR_W + 1) + pad) + ICON;
        return Component.text()
            .append(Component.text(glyphs).font(FONT).color(NamedTextColor.WHITE))
            .append(Component.text(" "))
            .append(ipText)
            .build();
    }

    /** Any horizontal move, composed from the pack's power-of-two space glyphs. */
    static String shift(int px) {
        StringBuilder sb = new StringBuilder();
        int n = Math.abs(px);
        char base = px < 0 ? '\uF801' : '\uF821';
        for (int bit = 7; bit >= 0; bit--) {
            while (n >= (1 << bit)) {
                sb.append((char) (base + bit));
                n -= 1 << bit;
            }
        }
        return sb.toString();
    }

    /** Pixel advance of plain ASCII in the default Minecraft font. */
    static int width(String s) {
        int w = 0;
        for (char c : s.toCharArray()) {
            w += switch (c) {
                case 'i', '.', ',', ':', ';', '!', '\'', '|' -> 2;
                case 'l', '`' -> 3;
                case 'I', 't', ' ', '"', '(', ')', '*', '[', ']', '{', '}' -> 4;
                case 'f', 'k', '<', '>' -> 5;
                case '@', '~' -> 7;
                default -> 6;
            };
        }
        return w;
    }

    // ---------------- zone boss bar ----------------

    public static final int HEX_CELLS = 16, HEX_ADVANCE = 10;
    public static final char HEX_FULL = '', HEX_EMPTY = '', HEX_GRACE = '', HEX_EDGE = '', HEX_WARN = '';

    /**
     * The zone bar for players with the pack: a row of hexagon cells where the (invisible) vanilla
     * bar sits, and the title centred above it. {@code full} cells are lit; {@code edge} marks the
     * cell draining right now (-1 for none); {@code lit} is the glyph for lit cells (colour).
     */
    public static Component zoneBar(Component title, int full, int edge, char lit) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < HEX_CELLS; i++) {
            row.append(i == edge ? HEX_EDGE : i < full ? lit : HEX_EMPTY);
        }
        int w = HEX_CELLS * HEX_ADVANCE, t = width(title);
        // Row first, then step back so the title is centred over it, then forward to the row's end.
        return Component.text()
            .append(Component.text(row + shift(-(w + t) / 2)).font(FONT).color(NamedTextColor.WHITE))
            .append(title)
            .append(Component.text(shift((w - t) / 2)).font(FONT))
            .build();
    }

    /** Pixel width of a component in the default font, bold counted (+1 per character). */
    static int width(Component c) {
        return width(c, false);
    }

    private static int width(Component c, boolean boldParent) {
        var st = c.style().decoration(net.kyori.adventure.text.format.TextDecoration.BOLD);
        boolean bold = st == net.kyori.adventure.text.format.TextDecoration.State.TRUE
            || (st == net.kyori.adventure.text.format.TextDecoration.State.NOT_SET && boldParent);
        int w = 0;
        if (c instanceof net.kyori.adventure.text.TextComponent tc) {
            w += width(tc.content()) + (bold ? tc.content().length() : 0);
        }
        for (Component child : c.children()) w += width(child, bold);
        return w;
    }

    // ---------------- squad cards ----------------

    /** Card width in GUI pixels; must match CZ_CARD_W in the pack's cz_hud.glsl. */
    public static final int CARD_W = 126;
    public static final int HP_SEGMENTS = 20;
    private static final int CARD_ADVANCE = CARD_W + 1, HEAD_X = 2, TEXT_X = 21, HP_ADVANCE = 5, KILLS_ICON = 8, RIGHT_PAD = 4;
    private static final char CARD = '', HP_ON = '', HP_OFF = '', KILLS = '';

    /**
     * One squad card for the top-left HUD: a dark panel, the player's head (drawn 2x by the
     * shader), the name with their kills on the right, and a 20-segment health bar under it.
     * Every colour must already carry the HUD marker (SquadHud.mark), and the pen ends exactly
     * CARD_W to the right so the shader knows where the card starts.
     */
    public static Component squadCard(Component head, String name, TextColor nameColor, boolean struck, ShadowColor textShadow,
                                      TextColor killsColor, int kills, int hpOn, TextColor hpColor, TextColor white) {
        String k = String.valueOf(kills);
        int killsW = KILLS_ICON + width(k);
        int maxName = CARD_W - TEXT_X - RIGHT_PAD - killsW - 4;
        String shown = name;
        if (width(shown) > maxName) {
            while (!shown.isEmpty() && width(shown + "..") > maxName) shown = shown.substring(0, shown.length() - 1);
            shown += "..";
        }
        int nameEnd = TEXT_X + width(shown);
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < HP_SEGMENTS; i++) bar.append(i < hpOn ? HP_ON : HP_OFF);
        int barEnd = TEXT_X + HP_SEGMENTS * HP_ADVANCE;
        ShadowColor none = ShadowColor.none();
        return Component.text()
            .append(Component.text(CARD + shift(-CARD_ADVANCE + HEAD_X)).font(FONT).color(white).shadowColor(none))
            .append(head)
            .append(Component.text(shift(TEXT_X - HEAD_X - 8)).font(FONT))
            .append(Component.text(shown, nameColor).shadowColor(textShadow)
                .decoration(TextDecoration.STRIKETHROUGH, struck))
            .append(Component.text(shift(CARD_W - RIGHT_PAD - killsW - nameEnd) + KILLS).font(FONT).color(white).shadowColor(none))
            .append(Component.text(k, killsColor).shadowColor(textShadow))
            .append(Component.text(shift(-(CARD_W - RIGHT_PAD - TEXT_X)), white).font(FONT).shadowColor(none))
            .append(Component.text(bar.substring(0, hpOn), hpColor).font(FONT).shadowColor(none))
            .append(Component.text(bar.substring(hpOn) + shift(CARD_W - barEnd), white).font(FONT).shadowColor(none))
            .build();
    }
}
