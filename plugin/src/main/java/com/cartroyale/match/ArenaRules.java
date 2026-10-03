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
 * WORLD-1..3 and LOOT-7. The map itself can't be broken: players (and TNT carts) only break
 * blocks a player placed during this game. Build-mode admins can still change anything.
 * Blocks drop nothing (the doTileDrops gamerule) except rails, which are dropped here by hand;
 * carts drop as entities. Loot chests are never broken. Only match players may place blocks.
 */
public final class ArenaRules implements Listener {

    private final CartRoyale plugin;
    /** Blocks players placed in the current arena world (Paper block keys). */
    private final java.util.Set<Long> placed = new java.util.HashSet<>();
    /** The arena world those keys belong to; the arena is a fresh world object after every reset. */
    private java.lang.ref.WeakReference<org.bukkit.World> placedIn = new java.lang.ref.WeakReference<>(null);

    public ArenaRules(CartRoyale plugin) {
        this.plugin = plugin;
    }

    private java.util.Set<Long> placed(org.bukkit.World w) {
        if (placedIn.get() != w) {
            placed.clear();
            placedIn = new java.lang.ref.WeakReference<>(w);
        }
        return placed;
    }

    /** Counts a block as player-placed (the self-test uses this). */
    public void markPlaced(Block b) {
        placed(b.getWorld()).add(b.getBlockKey());
    }

    /** True for blocks a player placed this game, which are the only ones that may break. */
    public boolean playerPlaced(Block b) {
        return placed(b.getWorld()).contains(b.getBlockKey());
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
        if (!placed(b.getWorld()).remove(b.getBlockKey())) {
            e.setCancelled(true);
            return;
        }
        if (Tag.RAILS.isTagged(b.getType())) popRail(b, 0);
        Block above = b.getRelative(BlockFace.UP);
        if (Tag.RAILS.isTagged(above.getType())) popRail(above, 0);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (arena(e.getBlock()) && !mayEdit(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaced(BlockPlaceEvent e) {
        Block b = e.getBlock();
        if (!arena(b) || plugin.rank().canBuild(e.getPlayer())) return;
        placed(b.getWorld()).add(b.getBlockKey());
        // Beds and doors are two blocks.
        if (b.getBlockData() instanceof org.bukkit.block.data.Bisected bi) {
            placed(b.getWorld()).add(b.getRelative(bi.getHalf() == org.bukkit.block.data.Bisected.Half.BOTTOM ? BlockFace.UP : BlockFace.DOWN).getBlockKey());
        }
        if (b.getBlockData() instanceof org.bukkit.block.data.type.Bed bed) {
            placed(b.getWorld()).add(b.getRelative(bed.getPart() == org.bukkit.block.data.type.Bed.Part.FOOT ? bed.getFacing() : bed.getFacing().getOppositeFace()).getBlockKey());
        }
    }

    /** Water and lava players pour are theirs too; the map's own water can't be scooped up. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(org.bukkit.event.player.PlayerBucketEmptyEvent e) {
        Block b = e.getBlock();
        if (!arena(b)) return;
        if (!mayEdit(e.getPlayer())) e.setCancelled(true);
        else placed(b.getWorld()).add(b.getBlockKey());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(org.bukkit.event.player.PlayerBucketFillEvent e) {
        Block b = e.getBlock();
        if (!arena(b) || plugin.rank().canBuild(e.getPlayer())) return;
        if (!placed(b.getWorld()).remove(b.getBlockKey())) e.setCancelled(true);
    }

    /** Flowing water and lava don't wash away the map (grass, flowers, torches). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(org.bukkit.event.block.BlockFromToEvent e) {
        Block to = e.getToBlock();
        if (!arena(to) || to.getType().isAir() || to.isLiquid()) return;
        if (!playerPlaced(to)) e.setCancelled(true);
    }

    /** Paintings and item frames are part of the map. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(org.bukkit.event.hanging.HangingBreakEvent e) {
        if (!plugin.arenas().isArena(e.getEntity().getWorld())) return;
        if (e instanceof org.bukkit.event.hanging.HangingBreakByEntityEvent be && be.getRemover() instanceof Player p
            && plugin.rank().canBuild(p)) return;
        e.setCancelled(true);
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
     * Only player-placed blocks break; the map and loot chests are taken out of the blast. Rails
     * (in the blast, or standing on a block that is) are removed by hand and dropped as items,
     * because with doTileDrops off a rail that loses its support would vanish.
     */
    private void filter(List<Block> blocks) {
        java.util.Set<Block> rails = new java.util.HashSet<>();
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (plugin.chests().isLootChest(b) || !placed(b.getWorld()).remove(b.getBlockKey())) {
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
        if (!arena(e.getBlock())) return;
        if (e.getBlock().getType() == Material.FARMLAND) e.setCancelled(true);
        // Sand or gravel a player placed can fall; where it lands is still theirs.
        else if (e.getEntity() instanceof org.bukkit.entity.FallingBlock && !e.getTo().isAir()) placed(e.getBlock().getWorld()).add(e.getBlock().getBlockKey());
    }

    /** Opening a loot chest outside a match (e.g. while boarding) does nothing. */
    @EventHandler(ignoreCancelled = true)
    public void onOpen(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null || !arena(e.getClickedBlock())) return;
        if (plugin.chests().isLootChest(e.getClickedBlock()) && !mayEdit(e.getPlayer())) e.setCancelled(true);
    }
}
