package com.cartroyale.arena;

import com.cartroyale.CartRoyale;
import com.cartroyale.core.Yml;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * Owns the arena world. Map templates live in plugins/CartZone/templates/&lt;name&gt;/ (a full
 * world folder). On every boot the arena folder is wiped and copied from the template before the
 * world loads, which is the most reliable way to undo a match's damage (END-4).
 */
public final class ArenaManager {

    public static final String STANDIN = "standin";

    private final CartRoyale plugin;
    private final File templates;
    private final File maps;
    private String loaded;
    private MapConfig config;

    public ArenaManager(CartRoyale plugin) {
        this.plugin = plugin;
        this.templates = new File(plugin.getDataFolder(), "templates");
        this.maps = new File(plugin.getDataFolder(), "maps");
        templates.mkdirs();
        maps.mkdirs();
    }

    public String worldName() {
        return plugin.getConfig().getString("worlds.arena", "arena");
    }

    public World world() {
        return Bukkit.getWorld(worldName());
    }

    public boolean isArena(World w) {
        return w != null && w.getName().equals(worldName());
    }

    public String loadedMap() {
        return loaded;
    }

    public MapConfig map() {
        return config;
    }

    public MapConfig map(String name) {
        if (name.equals(loaded) && config != null) return config;
        return new MapConfig(new Yml(plugin, "maps/" + name + ".yml", false), name);
    }

    public boolean hasTemplate(String name) {
        return new File(templates, name + "/level.dat").exists();
    }

    /** Every map with a template, plus the one loaded now. */
    public List<String> maps() {
        Set<String> out = new TreeSet<>();
        File[] dirs = templates.listFiles(File::isDirectory);
        if (dirs != null) for (File d : dirs) if (new File(d, "level.dat").exists()) out.add(d.getName());
        if (loaded != null) out.add(loaded);
        return new ArrayList<>(out);
    }

    public String randomMap() {
        List<String> all = maps();
        if (all.isEmpty()) return loaded;
        Collections.shuffle(all);
        return all.get(0);
    }

    /** Called from onEnable before any player can join. Restores and loads the arena. */
    public void boot(String preferred) {
        String name = preferred;
        if (name == null || !hasTemplate(name)) name = plugin.getConfig().getString("worlds.default-map", "");
        if (name == null || name.isBlank() || !hasTemplate(name)) {
            List<String> all = maps();
            name = all.isEmpty() ? null : all.get(0);
        }
        File folder = new File(Bukkit.getWorldContainer(), worldName());
        if (name != null && hasTemplate(name)) {
            try {
                restoreFolder(name, folder);
                plugin.getLogger().info("Arena restored from template '" + name + "'");
            } catch (IOException e) {
                plugin.getLogger().severe("Could not restore arena from template '" + name + "': " + e.getMessage());
            }
        } else {
            // No template yet: a stand-in arena is generated so everything can be tested. It is
            // regenerated from a fixed seed on every boot, which resets it just like a template.
            name = STANDIN;
            try {
                deleteTree(folder.toPath());
            } catch (IOException e) {
                plugin.getLogger().warning("Could not wipe the stand-in arena: " + e.getMessage());
            }
            plugin.getLogger().warning("No arena template found. Using the generated stand-in arena. "
                + "Install your map: put the world folder in plugins/CartZone/templates/<name>/, then /cart arena load <name>.");
        }
        load(name);
    }

    private void load(String name) {
        MapConfig mc = map(name);
        boolean standin = STANDIN.equals(name) || "standin".equals(mc.generator);
        WorldCreator wc = new WorldCreator(worldName());
        if (standin) {
            wc.generator(new StandInGenerator()).type(WorldType.NORMAL).generateStructures(false).seed(20260930L);
            if (!"standin".equals(mc.generator)) {
                mc.generator = "standin";
                mc.save();
            }
        } else {
            // Real maps: anything outside the saved template is open sea, never vanilla terrain.
            wc.generator(new OceanGenerator(mc.raw().getInt("ocean.water-top", 71), mc.raw().getInt("ocean.floor", 62)))
                .generateStructures(false);
        }
        World w = wc.createWorld();
        if (w == null) {
            plugin.getLogger().severe("The arena world could not be loaded.");
            return;
        }
        loaded = name;
        config = mc;
        if (!mc.raw().contains("border-radius")) mc.save();
        // 2026-10-02: The Lost Island's border moved from 500 to 700 so it can't be seen from the
        // island (the sea past the saved map comes from OceanGenerator). Once, and only if untouched.
        if ("lostisland".equals(name) && mc.borderRadius == 500 && !mc.raw().getBoolean("migrations.border-700", false)) {
            mc.borderRadius = 700;
            mc.raw().set("migrations.border-700", true);
            mc.save();
            plugin.getLogger().info("Map lostisland: border radius 500 -> 700.");
        }
        applyRules(w, mc);
        plugin.db().kvSet("arena.map", name);
    }

    public void applyRules(World w, MapConfig mc) {
        w.setAutoSave(false);
        w.setDifficulty(Difficulty.NORMAL);
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        w.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        w.setGameRule(GameRule.DO_WARDEN_SPAWNING, false);
        w.setGameRule(GameRule.DISABLE_RAIDS, true);
        w.setGameRule(GameRule.DO_INSOMNIA, false);
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setGameRule(GameRule.DO_FIRE_TICK, false);
        w.setGameRule(GameRule.MOB_GRIEFING, false);
        w.setGameRule(GameRule.KEEP_INVENTORY, false);
        w.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        w.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        w.setGameRule(GameRule.SHOW_DEATH_MESSAGES, false);
        w.setGameRule(GameRule.SPECTATORS_GENERATE_CHUNKS, false);
        // WORLD-2: blocks never drop; rails are dropped by hand in ArenaRules, carts are entities.
        w.setGameRule(GameRule.DO_TILE_DROPS, false);
        w.setGameRule(GameRule.DO_ENTITY_DROPS, true);
        w.setGameRule(GameRule.FALL_DAMAGE, true);
        w.setTime(6000);
        w.setStorm(false);
        w.getWorldBorder().setCenter(mc.centerX, mc.centerZ);
        w.getWorldBorder().setSize(mc.borderRadius * 2);
        w.getWorldBorder().setWarningDistance(0);
        w.getWorldBorder().setDamageBuffer(0);
        w.getWorldBorder().setDamageAmount(1.0);
    }

    /** Snapshots the loaded arena as template {@code name}. */
    public CompletableFuture<String> saveTemplate(String name) {
        World w = world();
        if (w == null) return CompletableFuture.failedFuture(new IllegalStateException("arena world not loaded"));
        w.save();
        File src = w.getWorldFolder();
        File dst = new File(templates, name);
        MapConfig current = config;
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(1500); // let the region writer flush after save()
                if (dst.exists()) deleteTree(dst.toPath());
                copyTree(src.toPath(), dst.toPath());
                File mapYml = new File(maps, name + ".yml");
                if (!mapYml.exists() && current != null) Files.copy(new File(maps, current.name + ".yml").toPath(), mapYml.toPath());
                invalidateSpots(name);
                return name;
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    /**
     * Swaps the arena to another map (or restores the same one). Only when nobody is in the arena.
     * Copying runs off the main thread; the world is loaded back on the main thread.
     */
    public CompletableFuture<Void> swap(String name) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        World w = world();
        if (w != null) {
            for (Player p : w.getPlayers()) p.teleport(plugin.lobby().spawn());
            if (!Bukkit.unloadWorld(w, false)) {
                done.completeExceptionally(new IllegalStateException("the arena world could not be unloaded"));
                return done;
            }
        }
        File folder = new File(Bukkit.getWorldContainer(), worldName());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (hasTemplate(name)) restoreFolder(name, folder);
                else if (STANDIN.equals(name)) deleteTree(folder.toPath());
                else throw new IOException("no template named " + name);
            } catch (IOException e) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    load(loaded == null ? STANDIN : loaded);
                    done.completeExceptionally(e);
                });
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                load(name);
                done.complete(null);
            });
        });
        return done;
    }

    private void restoreFolder(String name, File folder) throws IOException {
        if (folder.exists()) deleteTree(folder.toPath());
        copyTree(new File(templates, name).toPath(), folder.toPath());
    }

    // ---- chest spot cache ----

    public File spotsFile(String map) {
        return new File(maps, map + ".spots");
    }

    public void invalidateSpots(String map) {
        File f = spotsFile(map);
        if (f.exists() && !f.delete()) plugin.getLogger().warning("Could not delete " + f.getName());
    }

    // ---- file helpers ----

    static void copyTree(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String n = file.getFileName().toString();
                if (n.equals("session.lock") || n.equals("uid.dat")) return FileVisitResult.CONTINUE;
                Files.copy(file, dst.resolve(src.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
