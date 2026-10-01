package com.cartroyale;

import com.cartroyale.arena.ArenaManager;
import com.cartroyale.cmd.CartCommand;
import com.cartroyale.cmd.SmallCommands;
import com.cartroyale.core.Msg;
import com.cartroyale.core.Yml;
import com.cartroyale.db.Database;
import com.cartroyale.db.Store;
import com.cartroyale.deploy.Deployment;
import com.cartroyale.econ.Cosmetics;
import com.cartroyale.econ.CosmeticsGui;
import com.cartroyale.econ.Econ;
import com.cartroyale.game.GameManager;
import com.cartroyale.game.GameSettings;
import com.cartroyale.game.Mode;
import com.cartroyale.game.Modifier;
import com.cartroyale.gui.GuiListener;
import com.cartroyale.hooks.Announcer;
import com.cartroyale.hooks.Webhooks;
import com.cartroyale.host.HostGui;
import com.cartroyale.host.Tickets;
import com.cartroyale.kit.KitManager;
import com.cartroyale.lobby.Lobby;
import com.cartroyale.lobby.Presence;
import com.cartroyale.loot.ChestManager;
import com.cartroyale.loot.ChestScanner;
import com.cartroyale.loot.LootEditorGui;
import com.cartroyale.loot.LootTables;
import com.cartroyale.match.ArenaRules;
import com.cartroyale.match.MatchListener;
import com.cartroyale.match.Nametags;
import com.cartroyale.match.Spectators;
import com.cartroyale.rank.RankService;
import com.cartroyale.schedule.ScheduleGui;
import com.cartroyale.schedule.Scheduler;
import com.cartroyale.shield.OneTimeShield;
import com.cartroyale.stats.Stats;
import com.cartroyale.team.TeamManager;
import com.cartroyale.util.Keys;
import com.cartroyale.zone.Zone;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.ZoneId;
import java.util.logging.Level;

public final class CartRoyale extends JavaPlugin {

    private Msg msg;
    private Yml secrets;
    private Database db;
    private Store store;
    private Webhooks hooks;
    private Announcer announcer;
    private ArenaManager arenas;
    private ChestScanner scanner;
    private ChestManager chests;
    private LootTables loot;
    private KitManager kits;
    private Deployment deploy;
    private Zone zone;
    private Presence presence;
    private com.cartroyale.loot.UpgradeBooks books;
    private TeamManager teams;
    private Spectators spectators;
    private Nametags nametags;
    private Econ econ;
    private Cosmetics cosmetics;
    private CosmeticsGui cosmeticsGui;
    private Tickets tickets;
    private HostGui hostGui;
    private Scheduler scheduler;
    private ScheduleGui scheduleGui;
    private LootEditorGui lootEditor;
    private Lobby lobby;
    private Stats stats;
    private RankService rank;
    private GameManager games;
    private boolean started;

    @Override
    public void onEnable() {
        if (Bukkit.getCurrentTick() > 100) {
            // TECH-3: a /reload leaves players mid-state; refuse instead of half-working.
            getLogger().severe("CartZone was loaded by /reload. Restart the server instead. The plugin stays disabled.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        // A typo in config.yml must never cost the owner their settings: refuse to start instead of
        // letting Bukkit fall back to an empty config that would then be saved over the file.
        for (String name : java.util.List.of("config.yml", "messages.yml", "loot.yml", "zone.yml", "schedule.yml", "cosmetics.yml", "kits.yml")) {
            File f = new File(getDataFolder(), name);
            if (!f.exists()) continue;
            try {
                new org.bukkit.configuration.file.YamlConfiguration().load(f);
            } catch (Exception e) {
                getLogger().severe(name + " has a syntax error and was NOT changed. Fix it and restart. " + e.getMessage());
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
        }
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        writeSecretsExample();
        Keys.init(this);
        msg = new Msg(this);
        secrets = new Yml(this, "secrets.yml", false);
        if (!secrets.file().exists()) {
            secrets.get().set("discord-webhook-url", "");
            secrets.get().set("stats-token", "");
            secrets.get().options().setHeader(java.util.List.of("Secrets. Never share this file, never commit it. See secrets.example.yml."));
            secrets.save();
        }
        db = new Database(this);
        if (!db.open()) {
            // TECH-3: fail safe. Without a database nothing can be tracked or charged correctly.
            getLogger().severe("The database could not be opened. CartZone is disabling itself.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        store = new Store(db);
        rank = new RankService();
        hooks = new Webhooks(this);
        announcer = new Announcer(this);
        loot = new LootTables(this);
        kits = new KitManager(this);
        zone = new Zone(this);
        scanner = new ChestScanner(this);
        chests = new ChestManager(this);
        arenas = new ArenaManager(this);
        deploy = new Deployment(this);
        teams = new TeamManager(this);
        spectators = new Spectators(this);
        nametags = new Nametags(this);
        econ = new Econ(this);
        cosmetics = new Cosmetics(this);
        cosmeticsGui = new CosmeticsGui(this);
        tickets = new Tickets(this);
        hostGui = new HostGui(this);
        lootEditor = new LootEditorGui(this);
        lobby = new Lobby(this);
        lobby.applyRules();
        games = new GameManager(this);
        scheduler = new Scheduler(this);
        scheduleGui = new ScheduleGui(this);

        String preferred = games.loadQueue();
        arenas.boot(preferred);
        World arena = arenas.world();
        if (arena != null) {
            int swept = chests.sweep(arena);
            if (swept > 0) getLogger().info("Removed " + swept + " leftover plugin entities from the arena.");
        }

        register(new GuiListener(), new OneTimeShield(this), deploy, spectators, cosmetics, lobby,
            presence = new Presence(this), new MatchListener(this), new ArenaRules(this),
            books = new com.cartroyale.loot.UpgradeBooks(this), chests);
        CartCommand cart = new CartCommand(this);
        bind("cart", cart);
        SmallCommands small = new SmallCommands(this);
        bind("team", small);
        bind("kitready", small);
        bind("spectate", small);

        stats = new Stats(this);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().info(com.cartroyale.hooks.papi.PapiHook.register(this) ? "PlaceholderAPI: %cartzone_*% placeholders registered." : "PlaceholderAPI: could not register %cartzone_*%.");
        }
        games.startTicking();
        started = true;
        for (Player p : Bukkit.getOnlinePlayers()) lobby.send(p);
        getLogger().info("CartZone ready. Arena: " + arenas.loadedMap() + ", queued games: " + games.queue().size()
            + ", time zone " + zoneId().getId() + ", webhooks " + (hooks.configured() ? "on" : "off (secrets.yml)"));
    }

    @Override
    public void onDisable() {
        if (!started) return;
        try {
            games.shutdown();
            World w = arenas.world();
            if (w != null) chests.clear(w);
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "Cleanup on disable failed", e);
        }
        if (hooks != null) hooks.shutdown();
        if (db != null) db.close();
    }

    private void register(Listener... ls) {
        for (Listener l : ls) Bukkit.getPluginManager().registerEvents(l, this);
    }

    private void bind(String name, org.bukkit.command.TabExecutor ex) {
        PluginCommand c = getCommand(name);
        if (c == null) {
            getLogger().severe("Command /" + name + " is missing from plugin.yml");
            return;
        }
        c.setExecutor(ex);
        c.setTabCompleter(ex);
    }

    private void writeSecretsExample() {
        File ex = new File(getDataFolder(), "secrets.example.yml");
        if (!ex.exists()) saveResource("secrets.example.yml", false);
    }

    public void reloadAll() {
        reloadConfig();
        msg.reload();
        secrets.reload();
        loot.reload();
        kits.reload();
        zone.reload();
        cosmetics.reload();
        scheduler.reload();
        for (Player p : Bukkit.getOnlinePlayers()) nametags.refresh(p);
    }

    // ---------------- shared helpers ----------------

    public String secret(String key) {
        return secrets == null ? null : secrets.get().getString(key);
    }

    public ZoneId zoneId() {
        try {
            return ZoneId.of(getConfig().getString("timezone", "UTC"));
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }

    /**
     * Whether players get the CartZone pack at all: through resource-pack.* here, or through
     * server.properties (resource-pack=...), which the host panel may set instead.
     */
    public boolean packOn() {
        if (getConfig().getBoolean("resource-pack.enabled", false)) return true;
        String sp = getServer().getResourcePack();
        return sp != null && !sp.isBlank();
    }

    /** Match length limits for hosted games (host.limits.min-minutes / max-minutes). */
    public int clampMinutes(int m) {
        int lo = getConfig().getInt("host.limits.min-minutes", 20), hi = getConfig().getInt("host.limits.max-minutes", 40);
        return Math.max(lo, Math.min(hi, m));
    }

    /** Default game settings from config.yml (used by /cart start and the Host GUI). */
    public GameSettings defaults() {
        GameSettings s = new GameSettings();
        Mode m = Mode.parse(getConfig().getString("host.defaults.mode", "LT"));
        s.mode = m == null ? Mode.LT : m;
        s.modifiers.addAll(Modifier.parseSet(String.join(",", getConfig().getStringList("host.defaults.modifiers"))));
        s.squad = getConfig().getInt("host.defaults.squad", 1);
        s.purgeCap = getConfig().getInt("host.default-cap", 100);
        s.minutes = clampMinutes(getConfig().getInt("match.zone-close-minutes", 20));
        return s;
    }

    /** Console-only detail for testing; off unless config.yml has debug: true. */
    public void debug(String line) {
        if (getConfig().getBoolean("debug", false)) getLogger().info("[debug] " + line);
    }

    /** Tells online admins (and the console) about a problem, in game. */
    public void adminAlert(String key, Object... kv) {
        var c = msg.get(key, kv);
        getLogger().warning(Msg.strip(c));
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("cartzone.admin")) p.sendMessage(msg.get("admin.alert-prefix").append(c));
    }

    public Msg msg() { return msg; }
    public Database db() { return db; }
    public Store store() { return store; }
    public Webhooks hooks() { return hooks; }
    public Announcer announcer() { return announcer; }
    public ArenaManager arenas() { return arenas; }
    public ChestScanner scanner() { return scanner; }
    public ChestManager chests() { return chests; }
    public LootTables loot() { return loot; }
    public KitManager kits() { return kits; }
    public Deployment deploy() { return deploy; }
    public Zone zone() { return zone; }
    public Presence presence() { return presence; }
    public com.cartroyale.loot.UpgradeBooks books() { return books; }
    public TeamManager teams() { return teams; }
    public Spectators spectators() { return spectators; }
    public Nametags nametags() { return nametags; }
    public Econ econ() { return econ; }
    public Cosmetics cosmetics() { return cosmetics; }
    public CosmeticsGui cosmeticsGui() { return cosmeticsGui; }
    public Tickets tickets() { return tickets; }
    public HostGui hostGui() { return hostGui; }
    public Scheduler scheduler() { return scheduler; }
    public ScheduleGui scheduleGui() { return scheduleGui; }
    public LootEditorGui lootEditor() { return lootEditor; }
    public Lobby lobby() { return lobby; }
    public Stats stats() { return stats; }
    public RankService rank() { return rank; }
    public GameManager games() { return games; }
}
