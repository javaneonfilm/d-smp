package com.cartroyale.match;

import com.cartroyale.CartRoyale;
import com.cartroyale.game.Match;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
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
        if (Tag.RAILS.isTagged(b.getType()) && !plugin.rank().canBuild(e.getPlayer())) dropRail(b);
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

    private void filter(List<Block> blocks) {
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (plugin.chests().isLootChest(b)) {
                it.remove();
            } else if (Tag.RAILS.isTagged(b.getType())) {
                it.remove();
                dropRail(b);
                b.setType(Material.AIR);
            }
        }
    }

    private void dropRail(Block b) {
        Location l = b.getLocation().add(0.5, 0.3, 0.5);
        b.getWorld().dropItemNaturally(l, new ItemStack(b.getType()));
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
