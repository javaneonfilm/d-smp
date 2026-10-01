package com.cartroyale.lobby;

import com.cartroyale.CartRoyale;
import com.cartroyale.game.GameRow;
import com.cartroyale.game.GameState;
import com.cartroyale.game.Match;
import com.cartroyale.rank.Ranks;
import com.cartroyale.util.Times;
import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.ban.BanListType;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.net.InetAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ACC-1..3 (the login gate), the join flow, the resource pack (UI-3), the top-centre IP bar
 * (UI-2), the lobby sidebar and tab header (LOBBY-4), and the chat format with rank tags (RANK-4).
 */
public final class Presence implements Listener {

    private final CartRoyale plugin;
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Set<UUID> packLoaded = new HashSet<>();
    private final UUID packId = UUID.nameUUIDFromBytes("cartzone-pack".getBytes());

    public Presence(CartRoyale plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshBoards, 40, 20);
    }

    // ---------------- access gate ----------------

    /**
     * The vanilla whitelist stays on. When it would turn a player away, this lets them in while a
     * game's join window is open (unless they are banned or were purged from it).
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onValidate(PlayerConnectionValidateLoginEvent e) {
        if (e.isAllowed()) return;
        PlayerProfile profile = null;
        if (e.getConnection() instanceof PlayerLoginConnection lc) profile = lc.getAuthenticatedProfile();
        else if (e.getConnection() instanceof PlayerConfigurationConnection cc) profile = cc.getProfile();
        if (profile == null || profile.getId() == null) return;
        UUID id = profile.getId();
        if (Bukkit.getBanList(BanListType.PROFILE).isBanned(profile)) return;
        InetAddress addr = e.getConnection().getClientAddress() == null ? null : e.getConnection().getClientAddress().getAddress();
        if (addr != null && ((BanList<InetAddress>) Bukkit.getBanList(BanListType.IP)).isBanned(addr)) return;
        Match m = plugin.games().match();
        boolean whitelistKick = !Bukkit.getOfflinePlayer(id).isWhitelisted() && Bukkit.hasWhitelist();
        if (!whitelistKick) return;
        if (m != null && m.state.joinOpen() && !m.removed.contains(id)) {
            e.allow();
            return;
        }
        GameRow next = plugin.games().next();
        if (m != null && !m.state.joinOpen()) {
            e.kickMessage(plugin.msg().get("access.in-progress", "id", m.id()));
        } else if (next != null) {
            e.kickMessage(plugin.msg().get("access.next-game", "id", next.id, "start", plugin.announcer().startLine(next),
                "minutes", plugin.getConfig().getInt("join.window-minutes", 5)));
        } else {
            e.kickMessage(plugin.msg().get("access.no-game"));
        }
    }

    // ---------------- join / quit ----------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        e.joinMessage(null);
        plugin.store().touch(p.getUniqueId(), p.getName());
        plugin.nametags().refresh(p);
        showBar(p);
        sendPack(p);
        tabHeader(p);
        p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        Match m = plugin.games().match();
        if (m != null && m.state.inArena() && m.get(p.getUniqueId()) != null) {
            // Back after leaving mid-game: they were eliminated, so they watch.
            Bukkit.getScheduler().runTask(plugin, () -> plugin.spectators().make(p, null));
            return;
        }
        plugin.lobby().send(p);
        plugin.msg().send(p, "lobby.welcome", "player", p.getName());
        if (m != null && m.state.joinOpen()) plugin.msg().send(p, "lobby.window-open", "id", m.id(), "start", plugin.announcer().startLine(m.row));
        // HOST-4: a ticket holder without a queued game gets the Host GUI.
        plugin.store().tickets(p.getUniqueId()).thenAccept(n -> Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (n <= 0 || !p.isOnline()) return;
            boolean hasGame = plugin.games().queue().stream().anyMatch(g -> p.getUniqueId().equals(g.s.hosterUuid));
            plugin.msg().send(p, "tickets.reminder", "count", n);
            if (!hasGame) plugin.hostGui().open(p);
        }, 40));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        e.quitMessage(null);
        Player p = e.getPlayer();
        BossBar bar = bars.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
        packLoaded.remove(p.getUniqueId());
        plugin.nametags().remove(p);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (!plugin.lobby().isLobby(p.getWorld())) Lobby.stripLobbyItems(p);
    }

    // ---------------- chat (RANK-4) ----------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Component tag0 = plugin.nametags().tag(p);
        Component tag = tag0.equals(Component.empty()) ? tag0 : tag0.append(Component.space());
        Component suffix = plugin.cosmetics().suffixComponent(p.getUniqueId());
        boolean spec = plugin.spectators().isWatcher(p);
        e.renderer((source, displayName, message, viewer) -> plugin.msg().get(spec ? "chat.spectator-format" : "chat.format",
            "tag", tag, "name", Component.text(source.getName(), plugin.nametags().color(source.getUniqueId())),
            "suffix", suffix == null ? Component.empty() : Component.space().append(suffix), "message", message));
    }

    // ---------------- IP bar (UI-2) ----------------

    private BossBar.Color barColor() {
        try {
            return BossBar.Color.valueOf(plugin.getConfig().getString("ip-bar.color", "PINK").toUpperCase());
        } catch (IllegalArgumentException e) {
            return BossBar.Color.PINK;
        }
    }

    public void showBar(Player p) {
        if (!plugin.getConfig().getBoolean("ip-bar.enabled", true)) return;
        BossBar bar = bars.computeIfAbsent(p.getUniqueId(), u -> BossBar.bossBar(Component.empty(), 1f, barColor(), BossBar.Overlay.PROGRESS));
        updateBar(p, bar);
        p.showBossBar(bar);
    }

    private void updateBar(Player p, BossBar bar) {
        boolean styled = packLoaded.contains(p.getUniqueId()) && plugin.getConfig().getBoolean("resource-pack.enabled", false);
        String ip = plugin.getConfig().getString("server-ip", "play.cartzone.vip");
        bar.name(styled ? HudText.bar(plugin.msg().get("ip-bar.styled", "ip", ip), ip) : plugin.msg().get("ip-bar.plain", "ip", ip));
        bar.color(barColor());
    }

    // ---------------- resource pack (UI-3) ----------------

    private void sendPack(Player p) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("resource-pack.enabled", false)) return;
        String url = cfg.getString("resource-pack.url", ""), sha = cfg.getString("resource-pack.sha1", "");
        if (url == null || !url.startsWith("http") || sha == null || sha.length() != 40) {
            plugin.adminAlert("pack.misconfigured");
            return;
        }
        try {
            ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(packId, URI.create(url), sha.toLowerCase());
            p.sendResourcePacks(ResourcePackRequest.resourcePackRequest().packs(info)
                .required(cfg.getBoolean("resource-pack.required", true)).replace(true)
                .prompt(plugin.msg().get("pack.prompt")).build());
        } catch (IllegalArgumentException ex) {
            plugin.adminAlert("pack.misconfigured");
        }
    }

    @EventHandler
    public void onPack(PlayerResourcePackStatusEvent e) {
        Player p = e.getPlayer();
        plugin.debug("Resource pack " + e.getStatus() + " for " + p.getName());
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                packLoaded.add(p.getUniqueId());
                BossBar bar = bars.get(p.getUniqueId());
                if (bar != null) updateBar(p, bar);
            }
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> {
                packLoaded.remove(p.getUniqueId());
                if (plugin.getConfig().getBoolean("resource-pack.required", true) && e.getStatus() == PlayerResourcePackStatusEvent.Status.DECLINED) {
                    p.kick(plugin.msg().get("pack.declined-kick"));
                } else {
                    plugin.msg().send(p, "pack.failed");
                }
            }
            default -> {
            }
        }
    }

    // ---------------- sidebar + tab (LOBBY-4) ----------------

    private void tabHeader(Player p) {
        p.sendPlayerListHeaderAndFooter(plugin.msg().get("tab.header", "ip", plugin.getConfig().getString("server-ip")),
            plugin.msg().get("tab.footer", "online", Bukkit.getOnlinePlayers().size(), "state", stateLine()));
    }

    private String stateLine() {
        Match m = plugin.games().match();
        if (m != null) return plugin.msg().plain("states." + m.state.name().toLowerCase(), "id", m.id(), "alive", m.aliveCount());
        GameRow next = plugin.games().next();
        return next == null ? plugin.msg().plain("states.none") : plugin.msg().plain("states.next", "id", next.id,
            "time", Times.clock(next.s.startAt, plugin.zoneId()) + " " + plugin.zoneId().getId(), "rel", Times.relative(next.s.startAt));
    }

    private void refreshBoards() {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective o = board.getObjective("cr_side");
        if (!plugin.getConfig().getBoolean("sidebar.enabled", true)) {
            if (o != null) o.unregister();
            return;
        }
        if (o == null) {
            o = board.registerNewObjective("cr_side", Criteria.DUMMY, plugin.msg().get("sidebar.title"));
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            o.numberFormat(NumberFormat.blank());
        }
        o.displayName(plugin.msg().get("sidebar.title"));
        Match m = plugin.games().match();
        GameRow next = plugin.games().next();
        List<String> lines = plugin.msg().rawList(m != null && m.state.inArena() ? "sidebar.match" : "sidebar.lobby");
        // Entries are only keys; the client shows each score's custom name instead.
        for (String entry : board.getEntries()) if (entry.startsWith("crline")) board.resetScores(entry);
        int score = lines.size();
        int i = 0;
        for (String line : lines) {
            String entry = "crline" + i++;
            var sc = o.getScore(entry);
            sc.setScore(score--);
            sc.customName(plugin.msg().parse(line,
                "online", Bukkit.getOnlinePlayers().size(),
                "ip", plugin.getConfig().getString("server-ip"),
                "state", stateLine(),
                "next", next == null ? plugin.msg().plain("states.none-short") : "#" + next.id + " " + Times.clock(next.s.startAt, plugin.zoneId()),
                "next_rel", next == null ? "" : Times.relative(next.s.startAt),
                "alive", m == null ? 0 : m.aliveCount(),
                "game", m == null ? "-" : "#" + m.id(),
                "phase", m == null ? "-" : plugin.msg().plain("phases." + m.state.name().toLowerCase())));
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            tabHeader(p);
            BossBar bar = bars.get(p.getUniqueId());
            if (bar == null && plugin.getConfig().getBoolean("ip-bar.enabled", true)) showBar(p);
        }
    }

    public boolean packLoaded(UUID u) {
        return packLoaded.contains(u);
    }

    public static boolean inGame(GameState s) {
        return s != null && s.inArena();
    }

    public static boolean isStaff(Player p) {
        return Ranks.isAdmin(p);
    }
}
