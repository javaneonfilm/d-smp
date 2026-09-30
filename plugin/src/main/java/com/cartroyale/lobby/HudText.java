package com.cartroyale.lobby;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * UI-2: builds the top-centre IP bar title from the pack's cartroyale:hud font. The bar glyph is
 * drawn first, then the pen is moved back with negative-space glyphs so the minecart icon and the
 * IP sit centred on it. Sizes must match resourcepack/tools/build_pack.py.
 */
public final class HudText {

    private static final Key FONT = Key.key("cartroyale", "hud");
    private static final int BAR_W = 150, ICON_ADVANCE = 10, SPACE = 4;
    private static final char ICON = '', BAR = '';

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
        char base = px < 0 ? '' : '';
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
}
