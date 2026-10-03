package com.cartroyale.cmd;

import com.cartroyale.CartRoyale;
import com.cartroyale.arena.MapConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.minecart.ExplosiveMinecart;

import java.util.EnumMap;
import java.util.Map;

/**
 * /cart selftest: checks the arena rules on the live server in a few seconds, in game.
 * A TNT cart explodes next to player-placed stone, a rail and a protected loot chest, on top of
 * the map's own ground; then it reports what broke and dropped (WORLD-1/2, LOOT-7). Only runs when no game is using the arena.
 */
public final class SelfTest {

    private final CartRoyale plugin;

    public SelfTest(CartRoyale plugin) {
        this.plugin = plugin;
    }

    public void run(CommandSender s) {
        if (plugin.games().match() != null) {
            plugin.msg().send(s, "arena.busy");
            return;
        }
        World w = plugin.arenas().world();
        MapConfig mc = plugin.arenas().map();
        if (w == null || mc == null) {
            plugin.msg().send(s, "admin.no-arena");
            return;
        }
        int x = (int) mc.centerX + 7, z = (int) mc.centerZ + 7;
        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(c -> {
            // Keep the area loaded for the test: with nobody nearby the chunks would unload at once
            // and take the dropped items with them.
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) w.addPluginChunkTicket((x >> 4) + dx, (z >> 4) + dz, plugin);
            int y = w.getHighestBlockYAt(x, z) + 1;
            Block stone = w.getBlockAt(x + 1, y, z), rail = w.getBlockAt(x - 1, y, z), chest = w.getBlockAt(x, y, z + 2);
            Block ground = w.getBlockAt(x, y - 1, z);
            Material groundType = ground.getType();
            stone.setType(Material.STONE);
            rail.setType(Material.RAIL);
            plugin.arenaRules().markPlaced(stone);
            plugin.arenaRules().markPlaced(rail);
            chest.setType(Material.CHEST);
            plugin.chests().protectForTest(chest);
            Location at = new Location(w, x + 0.5, y, z + 0.5);
            ExplosiveMinecart cart = w.spawn(at, ExplosiveMinecart.class);
            plugin.msg().send(s, "selftest.start", "where", x + ", " + y + ", " + z);
            Bukkit.getScheduler().runTaskLater(plugin, () -> cart.explode(), 5);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Map<Material, Integer> drops = new EnumMap<>(Material.class);
                for (Entity e : w.getNearbyEntities(at, 10, 10, 10)) {
                    if (e instanceof Item it) {
                        drops.merge(it.getItemStack().getType(), it.getItemStack().getAmount(), Integer::sum);
                        e.remove();
                    }
                }
                boolean stoneGone = stone.getType() != Material.STONE;
                boolean railGone = rail.getType() != Material.RAIL;
                int stoneDrops = drops.getOrDefault(Material.STONE, 0) + drops.getOrDefault(Material.COBBLESTONE, 0)
                    + drops.getOrDefault(Material.DIRT, 0) + drops.getOrDefault(Material.GRASS_BLOCK, 0);
                int railDrops = drops.getOrDefault(Material.RAIL, 0);
                boolean chestKept = chest.getType() == Material.CHEST;
                boolean groundKept = ground.getType() == groundType;
                line(s, "breaks placed blocks", stoneGone, "stone " + (stoneGone ? "destroyed" : "still there"));
                line(s, "map survives", groundKept, groundKept ? "ground under the cart intact" : "ground was blown up");
                line(s, "no block drops", stoneDrops == 0, stoneDrops + " block items dropped");
                line(s, "rails drop", !railGone || railDrops > 0, railGone ? railDrops + " rail item(s)" : "rail survived (too far), not tested");
                line(s, "loot chest survives", chestKept, chestKept ? "chest intact" : "chest destroyed");
                plugin.msg().send(s, "selftest.drops", "drops", drops.isEmpty() ? "none" : drops.toString().toLowerCase());
                chest.setType(Material.AIR);
                if (rail.getType() == Material.RAIL) rail.setType(Material.AIR);
                if (stone.getType() == Material.STONE) stone.setType(Material.AIR);
                plugin.chests().clearTestProtection();
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) w.removePluginChunkTicket((x >> 4) + dx, (z >> 4) + dz, plugin);
            }, 45);
        });
    }

    private void line(CommandSender s, String what, boolean ok, String detail) {
        plugin.msg().send(s, ok ? "selftest.pass" : "selftest.fail", "check", what, "detail", detail);
        plugin.getLogger().info("selftest " + (ok ? "PASS" : "FAIL") + ": " + what + " (" + detail + ")");
    }
}
