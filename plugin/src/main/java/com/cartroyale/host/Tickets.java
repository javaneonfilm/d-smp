package com.cartroyale.host;

import com.cartroyale.CartRoyale;
import com.cartroyale.rank.Ranks;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * HOST-4/5 and 6.2. {@code cart ticket give <uuid> [n] [transaction]} is run by CraftingStore from
 * the console. It is idempotent per transaction id, works while the player is offline, whitelists
 * the UUID and grants the temporary host group. Revoke is the chargeback path.
 */
public final class Tickets {

    private static final String WL_KEY = "tickets.whitelisted";

    private final CartRoyale plugin;
    /** UUIDs this plugin put on the whitelist, so it only ever removes its own entries. */
    private final Set<UUID> ourWhitelist = new HashSet<>();

    public Tickets(CartRoyale plugin) {
        this.plugin = plugin;
        plugin.db().kvGet(WL_KEY).thenAccept(v -> {
            if (v == null || v.isBlank()) return;
            for (String s : v.split(",")) {
                try {
                    ourWhitelist.add(UUID.fromString(s));
                } catch (IllegalArgumentException ignored) {
                }
            }
        });
    }

    private void persistWhitelist() {
        StringBuilder sb = new StringBuilder();
        for (UUID u : ourWhitelist) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append(u);
        }
        plugin.db().kvSet(WL_KEY, sb.toString());
    }

    public static UUID parseTarget(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            if (s.length() == 32) {
                try {
                    return UUID.fromString(s.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
                } catch (IllegalArgumentException ignored) {
                }
            }
            Player p = Bukkit.getPlayerExact(s);
            if (p != null) return p.getUniqueId();
            OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(s);
            return op == null ? null : op.getUniqueId();
        }
    }

    public void give(CommandSender by, UUID uuid, int n, String tx) {
        plugin.store().giveTickets(uuid, n, by instanceof Player ? "admin:" + by.getName() : "store", tx).thenAccept(r -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!r.applied()) {
                plugin.msg().send(by, "tickets.duplicate", "tx", String.valueOf(tx), "count", r.count());
                plugin.getLogger().info("Ticket give ignored (duplicate transaction " + tx + ") for " + uuid);
                return;
            }
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            if (!op.isWhitelisted()) {
                op.setWhitelisted(true);
                ourWhitelist.add(uuid);
                persistWhitelist();
            }
            int hours = plugin.getConfig().getInt("host.ticket-hours", 6);
            boolean lp = Ranks.addTempGroup(uuid, plugin.getConfig().getString("host.host-group", "host"), hours);
            plugin.msg().send(by, "tickets.given", "n", n, "player", name(uuid), "count", r.count(), "lp", lp ? "yes" : "no (LuckPerms missing)");
            plugin.getLogger().info("Gave " + n + " ticket(s) to " + uuid + " (tx " + tx + "), now " + r.count());
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                plugin.msg().send(p, "tickets.received", "count", r.count());
                Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.hostGui().open(p), 20);
            }
        }));
    }

    public void revoke(CommandSender by, UUID uuid, int n, String tx) {
        plugin.store().revokeTickets(uuid, n, by instanceof Player ? "admin:" + by.getName() : "chargeback", tx).thenAccept(r -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!r.applied()) {
                plugin.msg().send(by, "tickets.duplicate", "tx", String.valueOf(tx), "count", r.count());
                return;
            }
            int spent = Integer.parseInt(r.reason());
            plugin.msg().send(by, "tickets.revoked", "n", n, "player", name(uuid), "count", r.count());
            if (spent > 0 && plugin.getConfig().getBoolean("host.revoke-cancels-game", true)) {
                // Cancel the game the ticket paid for first, then clean up (cleanup skips anyone with a queued game).
                plugin.store().latestQueuedBought(uuid).thenAccept(id -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (id > 0 && plugin.games().cancelQueued(id, "chargeback")) plugin.msg().send(by, "tickets.revoke-cancelled-game", "id", id);
                    if (r.count() == 0) cleanup(uuid);
                }));
            } else if (r.count() == 0) {
                cleanup(uuid);
            }
        }));
    }

    public void list(CommandSender by, UUID uuid) {
        plugin.store().tickets(uuid).thenCombine(plugin.store().ticketLog(uuid, 8), (count, log) -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.msg().send(by, "tickets.list-head", "player", name(uuid), "count", count);
                for (var l : log) {
                    plugin.msg().send(by, "tickets.list-line", "delta", (l.delta() > 0 ? "+" : "") + l.delta(), "source", l.source(),
                        "tx", l.tx() == null ? "-" : l.tx(), "when", com.cartroyale.util.Times.dayClock(l.at(), plugin.zoneId()));
                }
            });
            return null;
        });
    }

    /** After a bought game ends: drop the whitelist entry and host group if nothing else is pending. */
    public void afterGame(UUID uuid) {
        plugin.store().tickets(uuid).thenAccept(count -> Bukkit.getScheduler().runTask(plugin, () -> {
            boolean queued = plugin.games().queue().stream().anyMatch(g -> uuid.equals(g.s.hosterUuid));
            if (count == 0 && !queued) cleanup(uuid);
        }));
    }

    private void cleanup(UUID uuid) {
        boolean queued = plugin.games().queue().stream().anyMatch(g -> uuid.equals(g.s.hosterUuid));
        if (queued) return;
        if (ourWhitelist.remove(uuid)) {
            Bukkit.getOfflinePlayer(uuid).setWhitelisted(false);
            persistWhitelist();
        }
        Ranks.removeGroup(uuid, plugin.getConfig().getString("host.host-group", "host"));
    }

    private static String name(UUID u) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        return op.getName() == null ? u.toString() : op.getName();
    }

    public static List<String> usage() {
        return List.of("give", "revoke", "list");
    }
}
