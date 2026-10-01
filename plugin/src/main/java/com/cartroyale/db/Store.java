package com.cartroyale.db;

import com.cartroyale.game.GameRow;
import com.cartroyale.game.GameSettings;
import com.cartroyale.game.Mode;
import com.cartroyale.game.Modifier;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** All SQL in one place. Every method returns a future that completes on the DB thread. */
public final class Store {

    public record Stat(String name, int val) {
    }

    public record Round(long id, String winner, String players, String time) {
    }

    public record TicketResult(boolean applied, int count, String reason) {
    }

    private final Database db;

    public Store(Database db) {
        this.db = db;
    }

    // ---------------- games ----------------

    public CompletableFuture<List<GameRow>> loadQueued() {
        return db.submit(c -> {
            List<GameRow> out = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT * FROM games WHERE status IN ('QUEUED','RUNNING') ORDER BY scheduled_at")) {
                while (rs.next()) out.add(row(rs));
            }
            return out;
        });
    }

    private static GameRow row(ResultSet rs) throws SQLException {
        GameSettings s = new GameSettings();
        s.mode = Mode.parse(rs.getString("mode"));
        if (s.mode == null) s.mode = Mode.LT;
        s.modifiers.addAll(Modifier.parseSet(rs.getString("modifiers")));
        s.squad = rs.getInt("squad");
        s.purgeCap = rs.getInt("purge_cap");
        int mins = rs.getInt("minutes");
        if (mins > 0) s.minutes = mins;
        s.map = rs.getString("map");
        s.startAt = rs.getLong("scheduled_at");
        String hu = rs.getString("hoster_uuid");
        s.hosterUuid = hu == null ? null : UUID.fromString(hu);
        s.hosterName = rs.getString("hoster_name");
        GameRow g = new GameRow(rs.getLong("id"), s, rs.getString("source"));
        g.status = GameRow.Status.valueOf(rs.getString("status"));
        g.warned = rs.getInt("warned") != 0;
        return g;
    }

    /** Inserts a queued game; returns its id, or -1 if a game with the same source already exists. */
    public CompletableFuture<Long> insert(GameSettings s, String source) {
        return db.submit(c -> insertGame(c, s, source));
    }

    private static long insertGame(Connection c, GameSettings s, String source) throws SQLException {
        // Check first: an ignored INSERT still uses up an AUTOINCREMENT id, and ids are game numbers.
        if (source != null && source.startsWith("schedule:")) {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM games WHERE source=?")) {
                ps.setString(1, source);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return -1L;
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT OR IGNORE INTO games(hoster_uuid,hoster_name,mode,squad,purge_cap,modifiers,map,scheduled_at,status,source,minutes) VALUES(?,?,?,?,?,?,?,?,'QUEUED',?,?)",
            Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, s.hosterUuid == null ? null : s.hosterUuid.toString());
            ps.setString(2, s.hosterName);
            ps.setString(3, s.mode.name());
            ps.setInt(4, s.squad);
            ps.setInt(5, s.purgeCap);
            ps.setString(6, Modifier.toCsv(s.modifiers));
            ps.setString(7, s.map);
            ps.setLong(8, s.startAt);
            if (source == null) ps.setNull(9, Types.VARCHAR);
            else ps.setString(9, source);
            ps.setInt(10, s.minutes);
            if (ps.executeUpdate() == 0) return -1L;
            try (ResultSet k = ps.getGeneratedKeys()) {
                return k.next() ? k.getLong(1) : -1L;
            }
        }
    }

    public CompletableFuture<Void> setStatus(long id, GameRow.Status st) {
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE games SET status=? WHERE id=?")) {
                ps.setString(1, st.name());
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> setWarned(long id) {
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE games SET warned=1 WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> setStart(long id, long at) {
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE games SET scheduled_at=? WHERE id=?")) {
                ps.setLong(1, at);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> markStarted(long id, long at, String players) {
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE games SET status='RUNNING', started_at=?, players=? WHERE id=?")) {
                ps.setLong(1, at);
                ps.setString(2, players);
                ps.setLong(3, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public record PlayerResult(UUID uuid, String name, int team, int kills, int placement, boolean won) {
    }

    /** END-1: stores the result, the per-player lines and the lifetime stats in one transaction. */
    public CompletableFuture<Void> finish(long id, long endedAt, String winners, UUID mvp, String mvpName, int mvpKills, List<PlayerResult> players) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE games SET status='DONE', ended_at=?, winners=?, mvp_uuid=?, mvp_name=?, mvp_kills=? WHERE id=?")) {
                ps.setLong(1, endedAt);
                ps.setString(2, winners);
                ps.setString(3, mvp == null ? null : mvp.toString());
                ps.setString(4, mvpName);
                ps.setInt(5, mvpKills);
                ps.setLong(6, id);
                ps.executeUpdate();
            }
            try (PreparedStatement gp = c.prepareStatement(
                "INSERT OR REPLACE INTO game_players(game_id,uuid,name,team,kills,placement) VALUES(?,?,?,?,?,?)");
                 PreparedStatement pl = c.prepareStatement(
                     "INSERT INTO players(uuid,name,kills,wins,played,first_seen) VALUES(?,?,?,?,1,?) "
                         + "ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, kills=kills+excluded.kills, wins=wins+excluded.wins, played=played+1")) {
                for (PlayerResult r : players) {
                    gp.setLong(1, id);
                    gp.setString(2, r.uuid().toString());
                    gp.setString(3, r.name());
                    gp.setInt(4, r.team());
                    gp.setInt(5, r.kills());
                    gp.setInt(6, r.placement());
                    gp.addBatch();
                    pl.setString(1, r.uuid().toString());
                    pl.setString(2, r.name());
                    pl.setInt(3, r.kills());
                    pl.setInt(4, r.won() ? 1 : 0);
                    pl.setLong(5, endedAt);
                    pl.addBatch();
                }
                gp.executeBatch();
                pl.executeBatch();
            }
            return null;
        });
    }

    public CompletableFuture<Long> nextGameNumber() {
        return db.submit(c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(id),0)+1 FROM games")) {
                return rs.next() ? rs.getLong(1) : 1L;
            }
        });
    }

    // ---------------- players / coins ----------------

    public CompletableFuture<Void> touch(UUID uuid, String name) {
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO players(uuid,name,first_seen) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Integer> coins(UUID uuid) {
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT coins FROM players WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** Adds (or with a negative delta, takes) coins. Never goes below zero. Returns the new balance. */
    public CompletableFuture<Integer> addCoins(UUID uuid, String name, int delta) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO players(uuid,name,coins,first_seen) VALUES(?,?,MAX(0,?),?) ON CONFLICT(uuid) DO UPDATE SET coins=MAX(0,coins+?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name == null ? "?" : name);
                ps.setInt(3, delta);
                ps.setLong(4, System.currentTimeMillis());
                ps.setInt(5, delta);
                ps.executeUpdate();
            }
            return balance(c, uuid);
        });
    }

    public CompletableFuture<Integer> setCoins(UUID uuid, String name, int value) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO players(uuid,name,coins,first_seen) VALUES(?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET coins=excluded.coins")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name == null ? "?" : name);
                ps.setInt(3, Math.max(0, value));
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return balance(c, uuid);
        });
    }

    private static int balance(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT coins FROM players WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public CompletableFuture<List<Stat>> top(String column, int limit) {
        if (!List.of("kills", "wins", "played", "coins").contains(column)) throw new IllegalArgumentException(column);
        return db.submit(c -> {
            List<Stat> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT name," + column + " FROM players WHERE " + column + ">0 ORDER BY " + column + " DESC, name LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Stat(rs.getString(1), rs.getInt(2)));
                }
            }
            return out;
        });
    }

    public CompletableFuture<List<Round>> recentRounds(int limit) {
        return db.submit(c -> {
            List<Round> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT id,winners,players,started_at,ended_at FROM games WHERE status='DONE' ORDER BY ended_at DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long secs = Math.max(0, (rs.getLong(5) - rs.getLong(4)) / 1000);
                        String w = rs.getString(2);
                        out.add(new Round(rs.getLong(1), w == null || w.isBlank() ? "-" : w, rs.getString(3) == null ? "" : rs.getString(3),
                            String.format(java.util.Locale.ROOT, "%02d:%02d", secs / 60, secs % 60)));
                    }
                }
            }
            return out;
        });
    }

    public record Profile(int coins, int kills, int wins, int played) {
    }

    public CompletableFuture<Profile> profile(UUID uuid) {
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT coins,kills,wins,played FROM players WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Profile(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)) : new Profile(0, 0, 0, 0);
                }
            }
        });
    }

    // ---------------- tickets ----------------

    public CompletableFuture<Integer> tickets(UUID uuid) {
        return db.submit(c -> ticketCount(c, uuid));
    }

    private static int ticketCount(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count FROM tickets WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static void setTickets(Connection c, UUID uuid, int n) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO tickets(uuid,count) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET count=excluded.count")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, Math.max(0, n));
            ps.executeUpdate();
        }
    }

    private static boolean logTicket(Connection c, UUID uuid, int delta, String source, String tx, Long gameId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT OR IGNORE INTO ticket_log(uuid,delta,source,transaction_id,at,game_id) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, delta);
            ps.setString(3, source);
            if (tx == null) ps.setNull(4, Types.VARCHAR);
            else ps.setString(4, tx);
            ps.setLong(5, System.currentTimeMillis());
            if (gameId == null) ps.setNull(6, Types.INTEGER);
            else ps.setLong(6, gameId);
            return ps.executeUpdate() > 0;
        }
    }

    /** Idempotent per transaction id: the same id gives once. */
    public CompletableFuture<TicketResult> giveTickets(UUID uuid, int n, String source, String tx) {
        return db.tx(c -> {
            if (!logTicket(c, uuid, n, source, tx, null)) return new TicketResult(false, ticketCount(c, uuid), "duplicate transaction " + tx);
            int now = ticketCount(c, uuid) + n;
            setTickets(c, uuid, now);
            return new TicketResult(true, now, "ok");
        });
    }

    /** Removes up to n tickets. The count of tickets that were already spent is returned in reason. */
    public CompletableFuture<TicketResult> revokeTickets(UUID uuid, int n, String source, String tx) {
        return db.tx(c -> {
            if (!logTicket(c, uuid, -n, source, tx, null)) return new TicketResult(false, ticketCount(c, uuid), "duplicate transaction " + tx);
            int have = ticketCount(c, uuid);
            int take = Math.min(have, n);
            setTickets(c, uuid, have - take);
            return new TicketResult(true, have - take, String.valueOf(n - take));
        });
    }

    /** Spends one ticket for a game, atomically. False if the player has none left. */
    public CompletableFuture<Long> spendTicketAndQueue(UUID uuid, GameSettings s) {
        return db.tx(c -> {
            int have = ticketCount(c, uuid);
            if (have <= 0) return -1L;
            long id = insertGame(c, s, "ticket");
            if (id < 0) return -1L;
            setTickets(c, uuid, have - 1);
            logTicket(c, uuid, -1, "host", null, id);
            return id;
        });
    }

    /** The newest queued bought game of this player, for chargebacks. */
    public CompletableFuture<Long> latestQueuedBought(UUID uuid) {
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM games WHERE hoster_uuid=? AND source='ticket' AND status='QUEUED' ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            }
        });
    }

    public CompletableFuture<Void> refundTicket(UUID uuid, long gameId) {
        return db.tx(c -> {
            if (logTicket(c, uuid, 1, "refund", "refund-game-" + gameId, gameId)) setTickets(c, uuid, ticketCount(c, uuid) + 1);
            return null;
        });
    }

    public record TicketLine(String source, int delta, String tx, long at) {
    }

    public CompletableFuture<List<TicketLine>> ticketLog(UUID uuid, int limit) {
        return db.submit(c -> {
            List<TicketLine> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT source,delta,transaction_id,at FROM ticket_log WHERE uuid=? ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new TicketLine(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getLong(4)));
                }
            }
            return out;
        });
    }

    // ---------------- cosmetics ----------------

    public record Equipped(String kill, String explosion, String suffix) {
    }

    public CompletableFuture<List<String>> owned(UUID uuid) {
        return db.submit(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT cosmetic_id FROM cosmetics_owned WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
            }
            return out;
        });
    }

    public CompletableFuture<Equipped> equipped(UUID uuid) {
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT kill_effect,explosion_effect,suffix FROM cosmetics_equipped WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Equipped(rs.getString(1), rs.getString(2), rs.getString(3)) : new Equipped(null, null, null);
                }
            }
        });
    }

    public CompletableFuture<Void> equip(UUID uuid, String column, String id) {
        if (!List.of("kill_effect", "explosion_effect", "suffix").contains(column)) throw new IllegalArgumentException(column);
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO cosmetics_equipped(uuid," + column + ") VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET " + column + "=excluded." + column)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public enum Buy { OK, OWNED, POOR }

    public record BuyResult(Buy result, int balance) {
    }

    /** ECON-4: owns-check, balance-check, charge and grant in one transaction. */
    public CompletableFuture<BuyResult> buy(UUID uuid, String id, int price) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM cosmetics_owned WHERE uuid=? AND cosmetic_id=?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return new BuyResult(Buy.OWNED, balance(c, uuid));
                }
            }
            int bal = balance(c, uuid);
            if (bal < price) return new BuyResult(Buy.POOR, bal);
            try (PreparedStatement ps = c.prepareStatement("UPDATE players SET coins=coins-? WHERE uuid=? AND coins>=?")) {
                ps.setInt(1, price);
                ps.setString(2, uuid.toString());
                ps.setInt(3, price);
                if (ps.executeUpdate() == 0) return new BuyResult(Buy.POOR, bal);
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO cosmetics_owned(uuid,cosmetic_id,acquired_at) VALUES(?,?,?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, id);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return new BuyResult(Buy.OK, bal - price);
        });
    }

    public CompletableFuture<String> dailyShop(String date) {
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT items FROM daily_shop WHERE date=?")) {
                ps.setString(1, date);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    public CompletableFuture<Void> saveDailyShop(String date, String items) {
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO daily_shop(date,items) VALUES(?,?)")) {
                ps.setString(1, date);
                ps.setString(2, items);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
