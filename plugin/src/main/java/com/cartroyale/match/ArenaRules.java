package com.cartroyale.match;

import com.cartroyale.CartRoyale;
import com.cartroyale.game.Match;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.List;

/**
 * WORLD-1..3 and LOOT-7. In the arena blocks drop nothing (the doTileDrops gamerule) except
 * rails, which are dropped here by hand; carts drop as entities. TNT carts break blocks but never
 * loot chests. Only match players and build-mode admins may change blocks.
 */
public final class ArenaRules implements Listener {

    private final CartRoyale plugin;

    public ArenaRules(CartRoyale plugin) {
        this.plugin = plugin;
    }

    private boolean arena(Block b) {
        return plugin.arenas().isArena(b.getWorld());
    }

    private boolean mayEdit(Player p) {
        if (plugin.rank().canBuild(p)) return true;
        Match m = plugin.games().match();
        return m != null && m.isAlive(p.getUniqueId()) && (m.state == com.cartroyale.game.GameState.PLAYING
            || m.state == com.cartroyale.game.GameState.DEPLOY);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!arena(b)) return;
        if (!mayEdit(e.getPlayer()) || plugin.chests().isLootChest(b)) {
            e.setCancelled(true);
            return;
        }
        if (plugin.rank().canBuild(e.getPlayer())) return;
        if (Tag.RAILS.isTagged(b.getType())) popRail(b, 0);
        Block above = b.getRelative(BlockFace.UP);
        if (Tag.RAILS.isTagged(above.getType())) popRail(above, 0);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (arena(e.getBlock()) && !mayEdit(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!plugin.arenas().isArena(e.getLocation().getWorld())) return;
        e.setYield(0);
        filter(e.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!arena(e.getBlock())) return;
        e.setYield(0);
        filter(e.blockList());
    }

    /**
     * Loot chests are taken out of the blast. Rails (in the blast, or standing on a block that is)
     * are removed by hand and dropped as items, because with doTileDrops off a rail that loses its
     * support would vanish. Vanilla TNT carts never blast rails directly, so the second case is
     * the common one.
     */
    private void filter(List<Block> blocks) {
        java.util.Set<Block> rails = new java.util.HashSet<>();
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (plugin.chests().isLootChest(b)) {
                it.remove();
                continue;
            }
            if (Tag.RAILS.isTagged(b.getType())) {
                it.remove();
                rails.add(b);
            }
            Block above = b.getRelative(BlockFace.UP);
            if (Tag.RAILS.isTagged(above.getType())) rails.add(above);
        }
        plugin.debug("explosion: " + blocks.size() + " blocks, " + rails.size() + " rails to drop");
        // A tick later: an item spawned now would be destroyed by this same blast.
        for (Block r : rails) popRail(r, 1);
    }

    private void popRail(Block b, long delay) {
        Material type = b.getType();
        Location l = b.getLocation().add(0.5, 0.3, 0.5);
        b.setType(Material.AIR, false);
        Runnable drop = () -> l.getWorld().dropItemNaturally(l, new ItemStack(type));
        if (delay <= 0) drop.run();
        else org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, drop, delay);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (arena(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) {
        if (arena(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTrample(EntityChangeBlockEvent e) {
        if (arena(e.getBlock()) && e.getBlock().getType() == Material.FARMLAND) e.setCancelled(true);
    }

    /** Opening a loot chest outside a match (e.g. while boarding) does nothing. */
    @EventHandler(ignoreCancelled = true)
    public void onOpen(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null || !arena(e.getClickedBlock())) return;
        if (plugin.chests().isLootChest(e.getClickedBlock()) && !mayEdit(e.getPlayer())) e.setCancelled(true);
    }
}
