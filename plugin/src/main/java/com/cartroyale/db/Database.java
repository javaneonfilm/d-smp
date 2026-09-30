package com.cartroyale.db;

import org.bukkit.plugin.java.JavaPlugin;
import java.util.logging.Logger;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * SQLite behind one dedicated thread. Every query runs on that thread, so writes never race and
 * the main thread never blocks on disk. Callers get a CompletableFuture and hop back to the main
 * thread themselves when they touch Bukkit.
 */
public final class Database {

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }


    private final File folder;
    private final Logger log;
    private final ExecutorService exec;
    private Connection conn;
    private volatile boolean healthy;

    public Database(JavaPlugin plugin) {
        this(plugin.getDataFolder(), plugin.getLogger());
    }

    /** Also used by tests with a temp folder. */
    public Database(File folder, Logger log) {
        this.folder = folder;
        this.log = log;
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CartRoyale-DB");
            t.setDaemon(true);
            return t;
        });
    }

    /** Opens the file and runs migrations. Blocks the caller once, at startup. */
    public boolean open() {
        try {
            return exec.submit(() -> {
                File f = new File(folder, "cartroyale.db");
                f.getParentFile().mkdirs();
                Class.forName("org.sqlite.JDBC");
                conn = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
                try (Statement s = conn.createStatement()) {
                    s.execute("PRAGMA journal_mode=WAL");
                    s.execute("PRAGMA synchronous=NORMAL");
                    s.execute("PRAGMA foreign_keys=ON");
                }
                migrate();
                healthy = true;
                return true;
            }).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Database failed to open", e);
            healthy = false;
            return false;
        }
    }

    public boolean healthy() {
        return healthy;
    }

    public <T> CompletableFuture<T> submit(Work<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        exec.execute(() -> {
            try {
                f.complete(work.run(conn));
            } catch (Throwable t) {
                log.log(Level.WARNING, "Database query failed", t);
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    /** Runs a unit of work inside a transaction; rolls back on any error. */
    public <T> CompletableFuture<T> tx(Work<T> work) {
        return submit(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T out = work.run(c);
                c.commit();
                return out;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        });
    }

    /** Waits for queued work to finish, then closes. Called from onDisable. */
    public void close() {
        exec.shutdown();
        try {
            if (!exec.awaitTermination(10, TimeUnit.SECONDS)) log.warning("Database queue did not drain in 10s");
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
        }
    }

    private void migrate() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        }
        int current = 0;
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT MAX(version) FROM schema_version")) {
            if (rs.next()) current = rs.getInt(1);
        }
        if (current < 1) {
            try (Statement s = conn.createStatement()) {
                s.execute("""
                    CREATE TABLE IF NOT EXISTS players (
                      uuid TEXT PRIMARY KEY, name TEXT NOT NULL, coins INTEGER NOT NULL DEFAULT 0,
                      kills INTEGER NOT NULL DEFAULT 0, wins INTEGER NOT NULL DEFAULT 0,
                      played INTEGER NOT NULL DEFAULT 0, first_seen INTEGER NOT NULL)""");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS cosmetics_owned (
                      uuid TEXT NOT NULL, cosmetic_id TEXT NOT NULL, acquired_at INTEGER NOT NULL,
                      PRIMARY KEY (uuid, cosmetic_id))""");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS cosmetics_equipped (
                      uuid TEXT PRIMARY KEY, kill_effect TEXT, explosion_effect TEXT, suffix TEXT)""");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS games (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, hoster_uuid TEXT, hoster_name TEXT NOT NULL,
                      mode TEXT NOT NULL, squad INTEGER NOT NULL, purge_cap INTEGER NOT NULL,
                      modifiers TEXT NOT NULL, map TEXT, scheduled_at INTEGER NOT NULL,
                      started_at INTEGER, ended_at INTEGER, status TEXT NOT NULL,
                      winners TEXT, mvp_uuid TEXT, mvp_name TEXT, mvp_kills INTEGER,
                      warned INTEGER NOT NULL DEFAULT 0, source TEXT, players TEXT)""");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS game_players (
                      game_id INTEGER NOT NULL, uuid TEXT NOT NULL, name TEXT NOT NULL, team INTEGER,
                      kills INTEGER NOT NULL DEFAULT 0, placement INTEGER, PRIMARY KEY (game_id, uuid))""");
                s.execute("CREATE TABLE IF NOT EXISTS tickets (uuid TEXT PRIMARY KEY, count INTEGER NOT NULL)");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS ticket_log (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, delta INTEGER NOT NULL,
                      source TEXT NOT NULL, transaction_id TEXT, at INTEGER NOT NULL, game_id INTEGER)""");
                s.execute("CREATE UNIQUE INDEX IF NOT EXISTS ticket_tx ON ticket_log(transaction_id, delta) WHERE transaction_id IS NOT NULL");
                s.execute("CREATE TABLE IF NOT EXISTS daily_shop (date TEXT PRIMARY KEY, items TEXT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT)");
            }
            setVersion(1);
            current = 1;
        }
        if (current < 2) {
            // v2: only scheduled occurrences are unique. v1 made every source unique, which blocked
            // the second "admin" or "ticket" game.
            try (Statement s = conn.createStatement()) {
                s.execute("DROP INDEX IF EXISTS games_source");
                s.execute("CREATE UNIQUE INDEX IF NOT EXISTS games_schedule_source ON games(source) WHERE source LIKE 'schedule:%'");
            }
            setVersion(2);
        }
    }

    private void setVersion(int v) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO schema_version(version) VALUES (?)")) {
            ps.setInt(1, v);
            ps.executeUpdate();
        }
    }

    // ---- small key/value store for plugin state that must survive restarts ----

    public CompletableFuture<String> kvGet(String k) {
        return submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT v FROM kv WHERE k=?")) {
                ps.setString(1, k);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    public CompletableFuture<Void> kvSet(String k, String v) {
        return submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kv(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
                ps.setString(1, k);
                ps.setString(2, v);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
