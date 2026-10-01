package com.cartroyale.hooks.papi;

import com.cartroyale.CartRoyale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * %cartzone_...% placeholders, made for the TAB plugin (see plugins/TAB in the upload folder):
 * tag (rank badge + space), namecolor (colour code to put before %player%), name (coloured name),
 * suffix (space + equipped suffix), rank (admin, mod, host, vipplus, vip, default; for sorting),
 * state (what the lobby/game is doing), ip. Colours come out as section-sign codes with hex.
 */
final class CartZoneExpansion extends PlaceholderExpansion {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
        .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private final CartRoyale plugin;

    CartZoneExpansion(CartRoyale plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "cartzone";
    }

    @Override
    public String getAuthor() {
        return "Cuda";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer off, String params) {
        switch (params.toLowerCase()) {
            case "state" -> {
                return plugin.presence().stateLine();
            }
            case "ip" -> {
                return plugin.getConfig().getString("server-ip", "cartzone.vip");
            }
            default -> {
            }
        }
        Player p = off == null ? null : off.getPlayer();
        if (p == null) return "";
        return switch (params.toLowerCase()) {
            case "tag" -> {
                Component t = plugin.nametags().tag(p);
                yield t.equals(Component.empty()) ? "" : LEGACY.serialize(t) + "§r ";
            }
            case "namecolor" -> color(plugin.nametags().color(p));
            case "name" -> color(plugin.nametags().color(p)) + p.getName();
            case "suffix" -> {
                Component s = plugin.cosmetics().suffixComponent(p.getUniqueId());
                yield s == null ? "" : " " + LEGACY.serialize(s);
            }
            case "rank" -> plugin.nametags().rank(p).id();
            default -> null;
        };
    }

    private static String color(TextColor c) {
        return LEGACY.serialize(Component.text("x", c)).replaceAll("x$", "");
    }
}
