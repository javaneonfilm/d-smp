package com.cartroyale.team;

import com.cartroyale.CartRoyale;
import com.cartroyale.game.GameState;
import com.cartroyale.game.Match;
import com.cartroyale.game.TeamMath;
import com.cartroyale.gui.Gui;
import com.cartroyale.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * TEAM-1: the team-up phase after the purge. Players invite and accept with /team or the hotbar
 * item; when the phase ends leftovers are grouped by {@link TeamMath#autofill}.
 */
public final class TeamManager {

    public static final String ITEM = "team_menu";

    private final CartRoyale plugin;
    /** Leader -> members (leader first). Only during TEAMING. */
    private final Map<UUID, List<UUID>> teams = new LinkedHashMap<>();
    private final Map<UUID, UUID> memberOf = new HashMap<>();
    /** Invitee -> (inviter -> expiry). */
    private final Map<UUID, Map<UUID, Long>> invites = new HashMap<>();
    private Match match;

    public TeamManager(CartRoyale plugin) {
        this.plugin = plugin;
    }

    public void begin(Match m) {
        reset();
        match = m;
        for (UUID u : m.players.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.getInventory().setItem(plugin.getConfig().getInt("lobby-items.team.slot", 2), item());
            plugin.msg().send(p, "teams.phase-start", "seconds", plugin.getConfig().getInt("teams.phase-seconds", 90), "size", m.s().squad);
        }
    }

    public void reset() {
        teams.clear();
        memberOf.clear();
        invites.clear();
        match = null;
    }

    public boolean active() {
        return match != null && match.state == GameState.TEAMING;
    }

    private int size() {
        return match == null ? 1 : match.s().squad;
    }

    public List<UUID> teamOf(UUID u) {
        UUID leader = memberOf.get(u);
        return leader == null ? List.of(u) : teams.get(leader);
    }

    public ItemStack item() {
        return Items.tagged(Items.make(Material.LEAD, plugin.msg().item("items.team.name"), plugin.msg().lore("items.team.lore")), ITEM);
    }

    public void invite(Player from, Player to) {
        if (!active()) {
            plugin.msg().send(from, "teams.not-now");
            return;
        }
        if (from.equals(to)) {
            plugin.msg().send(from, "teams.self");
            return;
        }
        if (match.get(to.getUniqueId()) == null || match.get(from.getUniqueId()) == null) {
            plugin.msg().send(from, "teams.not-in-game", "player", to.getName());
            return;
        }
        if (teamOf(from.getUniqueId()).size() >= size()) {
            plugin.msg().send(from, "teams.full");
            return;
        }
        if (memberOf.containsKey(to.getUniqueId()) && teamOf(to.getUniqueId()).size() > 1) {
            plugin.msg().send(from, "teams.already-teamed", "player", to.getName());
            return;
        }
        // Your first invite makes a team with you as its leader.
        if (!memberOf.containsKey(from.getUniqueId())) {
            teams.put(from.getUniqueId(), new ArrayList<>(List.of(from.getUniqueId())));
            memberOf.put(from.getUniqueId(), from.getUniqueId());
            plugin.msg().send(from, "teams.created");
        }
        invites.computeIfAbsent(to.getUniqueId(), k -> new HashMap<>()).put(from.getUniqueId(), System.currentTimeMillis() + 60_000);
        plugin.msg().send(from, "teams.invited", "player", to.getName());
        plugin.msg().send(to, "teams.invite", "player", from.getName(), "accept",
            com.cartroyale.core.Msg.command(plugin.msg().get("teams.accept-button"), "/team accept " + from.getName()));
    }

    public void accept(Player p, String fromName) {
        if (!active()) {
            plugin.msg().send(p, "teams.not-now");
            return;
        }
        Map<UUID, Long> mine = invites.getOrDefault(p.getUniqueId(), Map.of());
        long now = System.currentTimeMillis();
        UUID from = null;
        for (var e : mine.entrySet()) {
            if (e.getValue() < now) continue;
            Player fp = Bukkit.getPlayer(e.getKey());
            if (fp == null) continue;
            if (fromName == null || fp.getName().equalsIgnoreCase(fromName)) {
                from = e.getKey();
                break;
            }
        }
        if (from == null) {
            plugin.msg().send(p, "teams.no-invite");
            return;
        }
        UUID leader = memberOf.get(from);
        List<UUID> team = leader == null ? null : teams.get(leader);
        if (team == null) {
            // The inviter's team was disbanded since.
            plugin.msg().send(p, "teams.no-invite");
            return;
        }
        if (team.size() >= size()) {
            plugin.msg().send(p, "teams.full");
            return;
        }
        leave(p, false);
        team.add(p.getUniqueId());
        memberOf.put(p.getUniqueId(), leader);
        invites.remove(p.getUniqueId());
        for (UUID u : team) {
            Player m = Bukkit.getPlayer(u);
            if (m != null) plugin.msg().send(m, "teams.joined", "player", p.getName(), "count", team.size(), "size", size());
        }
    }

    public void leave(Player p, boolean announce) {
        UUID leader = memberOf.remove(p.getUniqueId());
        if (leader == null) {
            if (announce) plugin.msg().send(p, "teams.no-team");
            return;
        }
        List<UUID> team = teams.get(leader);
        if (team == null) return;
        team.remove(p.getUniqueId());
        if (leader.equals(p.getUniqueId()) || team.size() <= 1) {
            // Disband: the leader left, or one player is left alone.
            teams.remove(leader);
            for (UUID u : team) {
                memberOf.remove(u);
                Player m = Bukkit.getPlayer(u);
                if (m != null) plugin.msg().send(m, "teams.disbanded");
            }
        } else {
            for (UUID u : team) {
                Player m = Bukkit.getPlayer(u);
                if (m != null) plugin.msg().send(m, "teams.left", "player", p.getName());
            }
        }
        if (announce) plugin.msg().send(p, "teams.you-left");
    }

    /** The leader breaks the team up; everyone in it is teamless again. */
    public void disband(Player p) {
        UUID leader = memberOf.get(p.getUniqueId());
        if (leader == null) {
            plugin.msg().send(p, "teams.no-team");
            return;
        }
        if (!leader.equals(p.getUniqueId())) {
            plugin.msg().send(p, "teams.not-leader");
            return;
        }
        List<UUID> team = teams.remove(leader);
        if (team != null) {
            for (UUID u : team) {
                memberOf.remove(u);
                Player m = Bukkit.getPlayer(u);
                if (m != null) plugin.msg().send(m, "teams.disbanded");
            }
        }
        // Invites the leader sent are void now.
        for (Map<UUID, Long> m : invites.values()) m.remove(leader);
    }

    public boolean isLeader(UUID u) {
        return u.equals(memberOf.get(u));
    }

    public void list(Player p) {
        List<UUID> team = teamOf(p.getUniqueId());
        List<String> names = new ArrayList<>();
        for (UUID u : team) {
            Match.MP mp = match == null ? null : match.get(u);
            names.add(mp == null ? u.toString().substring(0, 8) : mp.name);
        }
        plugin.msg().send(p, "teams.list", "members", String.join(", ", names), "count", team.size(), "size", size());
    }

    /** Ends the phase: forms final teams, removes the hotbar item. */
    public List<List<UUID>> finish(Match m) {
        List<List<UUID>> formed = new ArrayList<>();
        List<UUID> solo = new ArrayList<>();
        for (List<UUID> t : teams.values()) {
            List<UUID> online = new ArrayList<>();
            for (UUID u : t) if (m.players.containsKey(u)) online.add(u);
            if (!online.isEmpty()) formed.add(online);
        }
        for (UUID u : m.players.keySet()) if (!memberOf.containsKey(u)) solo.add(u);
        List<List<UUID>> out = TeamMath.autofill(formed, solo, m.s().squad, new Random());
        for (UUID u : m.players.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.getInventory().remove(Material.LEAD);
        }
        reset();
        return out;
    }

    /** The team GUI: heads of every selected player without a full team, plus pending invites. */
    public void openMenu(Player p) {
        if (!active()) {
            plugin.msg().send(p, "teams.not-now");
            return;
        }
        Gui gui = new Gui(6, plugin.msg().get("gui.team.title", "size", size()));
        int slot = 0;
        Map<UUID, Long> mine = invites.getOrDefault(p.getUniqueId(), Map.of());
        for (UUID u : match.players.keySet()) {
            if (u.equals(p.getUniqueId()) || slot >= 45) continue;
            Player other = Bukkit.getPlayer(u);
            if (other == null) continue;
            boolean invitedMe = mine.containsKey(u) && mine.get(u) > System.currentTimeMillis();
            boolean mate = teamOf(p.getUniqueId()).contains(u);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            head.editMeta(SkullMeta.class, sm -> {
                sm.setOwningPlayer(other);
                sm.displayName(plugin.msg().item("gui.team.head", "player", other.getName()));
                sm.lore(plugin.msg().lore(mate ? "gui.team.lore-mate" : invitedMe ? "gui.team.lore-accept" : "gui.team.lore-invite",
                    "count", teamOf(u).size(), "size", size()));
            });
            gui.set(slot++, head, e -> {
                p.closeInventory();
                if (mate) return;
                if (invitedMe) accept(p, other.getName());
                else invite(p, other);
            });
        }
        if (isLeader(p.getUniqueId())) {
            gui.set(49, Items.make(Material.TNT, plugin.msg().item("gui.team.disband"), plugin.msg().lore("gui.team.disband-lore")), e -> {
                p.closeInventory();
                disband(p);
            });
        } else {
            gui.set(49, Items.make(Material.BARRIER, plugin.msg().item("gui.team.leave"), List.of()), e -> {
                p.closeInventory();
                leave(p, true);
            });
        }
        gui.open(p);
    }
}
