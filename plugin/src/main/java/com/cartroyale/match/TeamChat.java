package com.cartroyale.match;

import com.cartroyale.CartRoyale;
import com.cartroyale.game.Match;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /teamchat <message> sends to your team only; /teamchat on its own flips every chat message to
 * team chat until you flip it back. Works while teaming up and during the game (dead teammates
 * still get it). Mutes and the chat filter apply here too.
 */
public final class TeamChat implements TabExecutor, Listener {

    private final CartRoyale plugin;
    private final Set<UUID> toggled = ConcurrentHashMap.newKeySet();

    public TeamChat(CartRoyale plugin) {
        this.plugin = plugin;
    }

    /** Everyone on the player's team, them included; empty when they have no team. */
    private List<UUID> team(Player p) {
        Match m = plugin.games().match();
        if (m == null) return List.of();
        for (List<UUID> t : m.teams) if (t.contains(p.getUniqueId())) return t.size() > 1 ? t : List.of();
        if (plugin.teams().active()) {
            List<UUID> t = plugin.teams().teamOf(p.getUniqueId());
            return t.size() > 1 ? t : List.of();
        }
        return List.of();
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) {
            plugin.msg().send(s, "cmd.players-only");
            return true;
        }
        if (a.length == 0) {
            if (toggled.remove(p.getUniqueId())) plugin.msg().send(p, "teamchat.off");
            else if (team(p).isEmpty()) plugin.msg().send(p, "teamchat.no-team");
            else {
                toggled.add(p.getUniqueId());
                plugin.msg().send(p, "teamchat.on");
            }
            return true;
        }
        send(p, String.join(" ", a));
        return true;
    }

    /** Main thread or chat thread: delivery only reads the match and sends messages. */
    private void send(Player p, String text) {
        List<UUID> team = team(p);
        if (team.isEmpty()) {
            plugin.msg().send(p, "teamchat.no-team");
            return;
        }
        if (plugin.offences().isMuted(p.getUniqueId())) {
            plugin.msg().send(p, "offence.still-muted", "left", "a while");
            return;
        }
        if (!p.hasPermission("cartzone.offence") && plugin.chatFilter().check(text) != null) {
            plugin.msg().send(p, "offence.blocked");
            return;
        }
        Component line = plugin.msg().get("teamchat.format", "name", Component.text(p.getName(), plugin.nametags().color(p)), "message", Component.text(text));
        for (UUID u : new ArrayList<>(team)) {
            Player t = Bukkit.getPlayer(u);
            if (t != null) t.sendMessage(line);
        }
        plugin.getLogger().info("[Team] " + p.getName() + ": " + text);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!toggled.contains(p.getUniqueId())) return;
        if (team(p).isEmpty()) {
            toggled.remove(p.getUniqueId());
            return;
        }
        e.setCancelled(true);
        send(p, PlainTextComponentSerializer.plainText().serialize(e.message()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        toggled.remove(e.getPlayer().getUniqueId());
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        return List.of();
    }
}
