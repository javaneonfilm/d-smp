package com.cartroyale;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Every message key the code uses exists, every message parses, and no file has an emoji. */
class ResourcesTest {

    private static final Set<String> SECTIONS = Set.of("access", "lobby", "chat", "ranks", "ip-bar", "tab", "sidebar", "states", "phases",
        "titles", "game", "teams", "deploy", "zone", "spectate", "shield", "coins", "shop", "locker", "stats", "host", "tickets", "kits",
        "loot", "pack", "items", "chests", "arena", "admin", "diag", "cmd", "help", "schedule", "gui", "discord", "team-size", "modes",
        "modifiers", "categories");

    private static YamlConfiguration messages() {
        return YamlConfiguration.loadConfiguration(new File("src/main/resources/messages.yml"));
    }

    private static List<String> literals() throws IOException {
        Pattern p = Pattern.compile("\"([a-z][a-z0-9-]*(?:\\.[a-z0-9_-]+)+)\"");
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                Matcher m = p.matcher(Files.readString(f));
                while (m.find()) out.add(m.group(1));
            }
        }
        return out;
    }

    @Test
    void everyUsedKeyExists() throws IOException {
        YamlConfiguration y = messages();
        Set<String> missing = new TreeSet<>();
        for (String k : literals()) {
            String top = k.substring(0, k.indexOf('.'));
            if (!SECTIONS.contains(top)) continue;
            // Prefixes completed at runtime, file names, database kv keys and map-yml paths are not messages.
            if (k.endsWith("-") || k.matches(".*\\.(yml|json|tmp)$") || k.equals("arena.map") || k.equals("tickets.whitelisted") || k.equals("schedule.paused-until")
                || k.matches("(deploy|zone)\\.(x|y|z|yaw|start\\..*|final\\..*)")) continue;
            if (!y.contains(k) && !isConfigKey(k)) missing.add(k);
        }
        // Keys built at runtime.
        for (String s : List.of("join_open", "countdown", "purge", "teaming", "boarding", "deploy", "playing", "ending", "resetting")) {
            if (!y.contains("states." + s)) missing.add("states." + s);
            if (!y.contains("phases." + s)) missing.add("phases." + s);
        }
        for (int t = 1; t <= 3; t++) {
            for (String k : List.of("chests.name-" + t, "chests.hologram-" + t, "gui.loot.tier-" + t, "gui.loot.tier-name-" + t)) if (!y.contains(k)) missing.add(k);
        }
        for (String k : List.of("modes.lt.name", "modes.ht.name", "modifiers.no_fall_damage.name", "modifiers.luckier_loot.name", "modifiers.pearls.name",
            "modifiers.no_fall_damage.desc", "modifiers.luckier_loot.desc", "modifiers.pearls.desc", "team-size.1", "team-size.2", "team-size.4",
            "categories.kill", "categories.explosion", "categories.suffix", "prefix")) {
            if (!y.contains(k)) missing.add(k);
        }
        assertTrue(missing.isEmpty(), "missing message keys: " + missing);
    }

    /** Literals like "host.slot-minutes" are config.yml paths, not messages. */
    private static boolean isConfigKey(String k) {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        return c.contains(k) || k.startsWith("host.limits") || k.startsWith("host.defaults") || k.startsWith("teams.phase-seconds")
            || k.startsWith("teams.sizes") || k.startsWith("lobby.spawn") || k.startsWith("chests.") && c.contains("chests") && !k.matches("chests\\.(name|hologram|info|cleared|failed|no-spots|zone|zones|scann|test).*");
    }

    @Test
    void everyMessageParses() {
        YamlConfiguration y = messages();
        MiniMessage mm = MiniMessage.miniMessage();
        for (String k : y.getKeys(true)) {
            if (k.startsWith("discord.")) continue;
            Object v = y.get(k);
            List<String> strs = v instanceof String s ? List.of(s) : v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
            for (String s : strs) assertDoesNotThrow(() -> mm.deserialize(s), k);
        }
    }

    @Test
    void noEmojiAnywhere() throws IOException {
        // The chest-tier star (U+2605) is a font glyph the plan asks for, not an emoji, so it is allowed.
        Pattern emoji = Pattern.compile("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{2604}\\x{2606}-\\x{26FF}\\x{2700}-\\x{27BF}\\x{FE0F}]");
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String t = Files.readString(f);
                assertFalse(emoji.matcher(t).find(), "emoji in " + f);
            }
        }
    }
}
