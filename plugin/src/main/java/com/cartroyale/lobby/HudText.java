package com.cartroyale.lobby;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

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
    private static final char HEX_FULL = '', HEX_EMPTY = '', HEX_GRACE = '', HEX_EDGE = '';

    /**
     * The zone bar for players with the pack: a row of hexagon cells where the (invisible) vanilla
     * bar sits, and the title centred above it. {@code full} cells are lit; {@code edge} marks the
     * cell draining right now (-1 for none); grace lights them green.
     */
    public static Component zoneBar(Component title, int full, int edge, boolean grace) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < HEX_CELLS; i++) {
            row.append(i == edge ? HEX_EDGE : i < full ? (grace ? HEX_GRACE : HEX_FULL) : HEX_EMPTY);
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
}
