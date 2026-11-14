package dev.friendai.features.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.foundation.spatial.Pos;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchLedgerTest {
    @Test
    void sameGoalRetryWithinCooldownReusesSpentBudget() {
        SearchLedger ledger = new SearchLedger();
        UUID owner = UUID.randomUUID();
        var first = ledger.acquire(
                owner, "minecraft:overworld", "  Iron  Ore ", 100, SearchLedger.LOCAL_DEFAULTS, new Pos(4, 64, 8));
        first.advance(new SearchSession.Input(
                110,
                "minecraft:overworld",
                new Pos(4, 64, 8),
                true,
                false,
                SearchSession.LegOutcome.NONE,
                null,
                0,
                0,
                0,
                0));
        var retry = ledger.acquire(
                owner,
                "minecraft:overworld",
                "iron ore",
                120,
                new SearchSession.Limits(512, 2_048, 12_000, 128),
                new Pos(40, 64, 80));
        assertSame(first, retry);
        assertEquals(1, retry.snapshot().spentTicks());
        assertEquals(SearchLedger.LOCAL_DEFAULTS, retry.snapshot().limits());
    }

    @Test
    void ledgerSnapshotRetainsActiveSessionBudget() {
        SearchLedger original = new SearchLedger();
        UUID owner = UUID.randomUUID();
        var session = original.acquire(
                owner, "minecraft:overworld", "iron", 100, SearchLedger.LOCAL_DEFAULTS, new Pos(4, 64, 8));
        session.advance(new SearchSession.Input(
                110,
                "minecraft:overworld",
                new Pos(4, 64, 8),
                true,
                false,
                SearchSession.LegOutcome.NONE,
                null,
                0,
                0,
                0,
                0));
        SearchLedger restored = new SearchLedger();
        restored.restore(original.toJson());
        assertEquals(
                1,
                restored.acquire(
                                owner,
                                "minecraft:overworld",
                                "iron",
                                120,
                                SearchLedger.LOCAL_DEFAULTS,
                                new Pos(0, 64, 0))
                        .snapshot()
                        .spentTicks());
    }

    @Test
    void resumeByIdKeepsTheSpentBudgetAndTheReturnPhase() {
        SearchLedger ledger = new SearchLedger();
        UUID owner = UUID.randomUUID();
        var session = ledger.acquire(
                owner, "minecraft:overworld", "iron", 100, SearchLedger.LOCAL_DEFAULTS, new Pos(4, 64, 8));
        session.advance(new SearchSession.Input(
                110,
                "minecraft:overworld",
                new Pos(40, 64, 8),
                true,
                true,
                SearchSession.LegOutcome.NONE,
                null,
                0,
                0,
                0,
                0));
        long spentTicks = session.snapshot().spentTicks();
        long spentTravel = Math.round(session.snapshot().spentTravelBlocks());
        ledger.interruptActive();

        var resumed = ledger.resume(session.snapshot().id(), owner, "minecraft:overworld", "  Iron ", 200);

        assertSame(session, resumed);
        assertEquals(SearchSession.Phase.RETURN, resumed.snapshot().phase());
        assertTrue(resumed.snapshot().discovered());
        assertEquals(spentTicks, resumed.snapshot().spentTicks());
        assertEquals(spentTravel, Math.round(resumed.snapshot().spentTravelBlocks()));
        assertEquals(new Pos(4, 64, 8), resumed.snapshot().origin());
    }

    @Test
    void resumeRefusesAnotherGoalOwnerOrADeliberatelyStoppedSession() {
        SearchLedger ledger = new SearchLedger();
        UUID owner = UUID.randomUUID();
        var session = ledger.acquire(
                owner, "minecraft:overworld", "iron", 100, SearchLedger.LOCAL_DEFAULTS, new Pos(4, 64, 8));
        ledger.interruptActive();
        UUID id = session.snapshot().id();

        assertNull(ledger.resume(id, owner, "minecraft:overworld", "copper", 120));
        assertNull(ledger.resume(id, UUID.randomUUID(), "minecraft:overworld", "iron", 120));
        assertNull(ledger.resume(UUID.randomUUID(), owner, "minecraft:overworld", "iron", 120));

        SearchSession stopped = ledger.acquire(
                owner, "minecraft:overworld", "gold", 110, SearchLedger.LOCAL_DEFAULTS, new Pos(0, 64, 0));
        stopped.interrupt();
        assertFalse(stopped.resumable());
        assertNull(ledger.resume(stopped.snapshot().id(), owner, "minecraft:overworld", "gold", 120));
    }

    @Test
    void aLegacyRowWithoutTheReloadMarkerStillLoads() {
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        JsonObject row = new JsonObject();
        row.addProperty("owner", owner.toString());
        row.addProperty("dimension", "minecraft:overworld");
        row.addProperty("goal", "iron");
        row.addProperty("id", id.toString());
        row.addProperty("x", 4);
        row.addProperty("y", 64);
        row.addProperty("z", 8);
        row.addProperty("radius", 64);
        row.addProperty("travel", 256);
        row.addProperty("limit_ticks", 2_400);
        row.addProperty("excavation", 128);
        row.addProperty("phase", "INTERRUPTED");
        row.addProperty("started", 100);
        row.addProperty("last", 100);
        row.addProperty("spent_travel", 10.0);
        row.addProperty("spent_ticks", 7);
        row.addProperty("excavated", 2);
        row.addProperty("blocked", 0);

        SearchLedger restored = new SearchLedger();
        restored.restore(List.of(row));
        SearchSession session = restored.session(id);

        assertNotNull(session);
        assertEquals(SearchSession.Phase.INTERRUPTED, session.snapshot().phase());
        assertFalse(session.snapshot().discovered());
        assertFalse(session.resumable());
        assertNull(restored.resume(id, owner, "minecraft:overworld", "iron", 200));
        assertEquals(
                7,
                restored.acquire(
                                owner,
                                "minecraft:overworld",
                                "iron",
                                200,
                                SearchLedger.LOCAL_DEFAULTS,
                                new Pos(0, 64, 0))
                        .snapshot()
                        .spentTicks());
    }
}
