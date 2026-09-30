package com.cartroyale;

import com.cartroyale.db.Database;
import com.cartroyale.db.Store;
import com.cartroyale.game.GameSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Real SQLite: tickets, game inserts and purchases (HOST-4/5, 6.2, ECON-4). */
class StoreTest {

    @TempDir
    Path dir;
    Database db;
    Store store;

    @BeforeEach
    void open() {
        db = new Database(dir.toFile(), Logger.getLogger("test"));
        assertTrue(db.open());
        store = new Store(db);
    }

    @AfterEach
    void close() {
        db.close();
    }

    private static GameSettings game(long at) {
        GameSettings s = new GameSettings();
        s.startAt = at;
        s.hosterName = "Tester";
        return s;
    }

    @Test
    void manyAdminAndBoughtGamesCanBeQueued() throws Exception {
        long a = store.insert(game(1000), "admin").get();
        long b = store.insert(game(2000), "admin").get();
        assertTrue(a > 0 && b == a + 1, a + " " + b);
        UUID u = UUID.randomUUID();
        store.giveTickets(u, 2, "store", "tx-1").get();
        long t1 = store.spendTicketAndQueue(u, game(3000)).get();
        long t2 = store.spendTicketAndQueue(u, game(4000)).get();
        assertEquals(b + 1, t1);
        assertEquals(b + 2, t2);
        assertEquals(-1L, store.spendTicketAndQueue(u, game(5000)).get(), "no tickets left");
        assertEquals(0, store.tickets(u).get());
    }

    @Test
    void scheduledOccurrenceIsCreatedOnceWithoutBurningGameNumbers() throws Exception {
        long a = store.insert(game(1000), "schedule:game1:1000").get();
        for (int i = 0; i < 5; i++) assertEquals(-1L, store.insert(game(1000), "schedule:game1:1000").get());
        long b = store.insert(game(9000), "admin").get();
        assertEquals(a + 1, b);
    }

    @Test
    void ticketGiveIsIdempotentPerTransaction() throws Exception {
        UUID u = UUID.randomUUID();
        assertTrue(store.giveTickets(u, 1, "store", "abc").get().applied());
        assertFalse(store.giveTickets(u, 1, "store", "abc").get().applied());
        assertEquals(1, store.tickets(u).get());
        assertTrue(store.revokeTickets(u, 1, "chargeback", "abc").get().applied());
        assertFalse(store.revokeTickets(u, 1, "chargeback", "abc").get().applied());
        assertEquals(0, store.tickets(u).get());
    }

    @Test
    void rapidBuysChargeOnce() throws Exception {
        UUID u = UUID.randomUUID();
        store.setCoins(u, "Buyer", 500).get();
        List<CompletableFuture<Store.BuyResult>> fs = new ArrayList<>();
        for (int i = 0; i < 10; i++) fs.add(store.buy(u, "kill_ember_ring", 350));
        int ok = 0;
        for (var f : fs) if (f.get().result() == Store.Buy.OK) ok++;
        assertEquals(1, ok);
        assertEquals(150, store.coins(u).get());
        assertEquals(Store.Buy.POOR, store.buy(u, "boom_derailer", 2800).get().result());
    }

    @Test
    void coinsNeverGoNegative() throws Exception {
        UUID u = UUID.randomUUID();
        assertEquals(5, store.addCoins(u, "K", 5).get());
        assertEquals(0, store.addCoins(u, "K", -50).get());
    }
}
