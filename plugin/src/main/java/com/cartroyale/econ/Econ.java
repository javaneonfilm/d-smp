package com.cartroyale.econ;

import com.cartroyale.CartRoyale;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.UUID;

/** ECON-1: coins. 5 per kill by default; nothing else earns them and they can't be bought. */
public final class Econ {

    private final CartRoyale plugin;

    public Econ(CartRoyale plugin) {
        this.plugin = plugin;
    }

    public int perKill() {
        return plugin.getConfig().getInt("economy.coins-per-kill", 5);
    }

    public void rewardKill(UUID killer, String name) {
        int n = perKill();
        if (n <= 0) return;
        plugin.store().addCoins(killer, name, n).thenAccept(bal -> Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(killer);
            if (p == null) return;
            plugin.msg().actionbar(p, "coins.kill", "coins", n, "balance", bal);
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
        }));
    }
}
