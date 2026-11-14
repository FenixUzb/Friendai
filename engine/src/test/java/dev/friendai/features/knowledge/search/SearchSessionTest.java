package dev.friendai.features.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.spatial.Pos;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchSessionTest {
    @Test
    void localDefaultsMatchPolicy() {
        assertEquals(new SearchSession.Limits(64, 256, 2400, 128), SearchLedger.LOCAL_DEFAULTS);
    }

    @Test
    void reservesReturnBudgetBeforeStartingOutboundLeg() {
        SearchSession session = new SearchSession(
                UUID.randomUUID(),
                "minecraft:overworld",
                "iron",
                new Pos(0, 64, 0),
                new SearchSession.Limits(64, 256, 2400, 128),
                0);
        var ready = new SearchSession.Input(
                1,
                "minecraft:overworld",
                new Pos(0, 64, 0),
                true,
                false,
                SearchSession.LegOutcome.NONE,
                null,
                0,
                0,
                0,
                0);
        session.advance(ready);
        var frontier = new SearchPlanner.Frontier(
                new Pos(20, 64, 0), new Pos(20, 64, 0), ResourceSearchProfile.Strategy.SURFACE, 4, 1000);
        var decision = session.advance(new SearchSession.Input(
                2,
                "minecraft:overworld",
                new Pos(0, 64, 0),
                true,
                false,
                SearchSession.LegOutcome.NONE,
                frontier,
                0,
                0,
                0,
                1500));
        assertEquals(SearchSession.Phase.RETURN, decision.phase());
        assertEquals(SearchSession.Reason.BUDGET_RESERVE, decision.reason());
    }

    @Test
    void workTicksCountTaskTicksNotElapsedTime() {
        SearchSession session = new SearchSession(
                UUID.randomUUID(),
                "minecraft:overworld",
                "iron",
                new Pos(0, 64, 0),
                new SearchSession.Limits(64, 256, 2400, 128),
                100);
        assertFalse(session.accountWork(10_000, new Pos(0, 64, 0), 0, 0));
        assertEquals(1, session.snapshot().spentTicks());
        for (int i = 1; i < 2399; i++) {
            assertFalse(session.accountWork(10_000L + i, new Pos(0, 64, 0), 0, 0));
        }
        assertTrue(session.accountWork(12_399, new Pos(0, 64, 0), 0, 0));
        assertEquals(2400, session.snapshot().spentTicks());
        assertEquals(SearchSession.Phase.RETURN, session.snapshot().phase());
    }

    @Test
    void theSameTickIsCountedOnceAcrossAdvanceAndAccountWork() {
        SearchSession session = new SearchSession(
                UUID.randomUUID(),
                "minecraft:overworld",
                "iron",
                new Pos(0, 64, 0),
                new SearchSession.Limits(64, 256, 2400, 128),
                100);
        session.advance(new SearchSession.Input(
                100,
                "minecraft:overworld",
                new Pos(0, 64, 0),
                true,
                false,
                SearchSession.LegOutcome.NONE,
                null,
                0,
                0,
                0,
                0));
        assertFalse(session.accountWork(100, new Pos(0, 64, 0), 0, 0));
        assertEquals(1, session.snapshot().spentTicks());
    }

    @Test
    void discoveryReturnsHomeBeforeCompletingSession() {
        SearchSession session = new SearchSession(
                UUID.randomUUID(),
                "minecraft:overworld",
                "iron",
                new Pos(0, 64, 0),
                new SearchSession.Limits(64, 256, 2400, 128),
                0);
        session.advance(new SearchSession.Input(
                1,
                "minecraft:overworld",
                new Pos(0, 64, 0),
                true,
                false,
                SearchSession.LegOutcome.NONE,
                null,
                0,
                0,
                0,
                0));
        var frontier = new SearchPlanner.Frontier(
                new Pos(16, 64, 0), new Pos(16, 64, 0), ResourceSearchProfile.Strategy.SURFACE, 1, 32);
        session.advance(new SearchSession.Input(
                2,
                "minecraft:overworld",
                new Pos(0, 64, 0),
                true,
                false,
                SearchSession.LegOutcome.NONE,
                frontier,
                0,
                0,
                0,
                32));

        var discovered = session.advance(new SearchSession.Input(
                10,
                "minecraft:overworld",
                new Pos(16, 64, 0),
                true,
                true,
                SearchSession.LegOutcome.NONE,
                null,
                0,
                0,
                0,
                0));
        assertEquals(SearchSession.Phase.RETURN, discovered.phase());
        assertFalse(session.terminal());

        var returned = session.advance(new SearchSession.Input(
                20,
                "minecraft:overworld",
                new Pos(0, 64, 0),
                true,
                false,
                SearchSession.LegOutcome.NONE,
                null,
                16,
                0,
                0,
                0));
        assertEquals(SearchSession.Phase.COMPLETE, returned.phase());
        assertEquals(4, session.snapshot().spentTicks());
        assertEquals(16, session.snapshot().spentTravelBlocks());
    }

    @Test
    void livePolicyOnlyClampsLimitsAndKeepsSpentWork() {
        SearchSession session = new SearchSession(
                UUID.randomUUID(),
                "minecraft:overworld",
                "iron",
                new Pos(0, 64, 0),
                new SearchSession.Limits(64, 256, 2400, 128),
                100);
        session.accountWork(900, new Pos(10, 64, 0), 10, 0);
        session.clampLimits(new SearchSession.Limits(32, 128, 600, 32));
        assertEquals(
                new SearchSession.Limits(32, 128, 600, 32), session.snapshot().limits());
        assertEquals(1, session.snapshot().spentTicks());
        assertEquals(10, session.snapshot().spentTravelBlocks());
    }

    @Test
    void reloadResumesPlanningWithoutResettingSpentWork() {
        SearchSession session = new SearchSession(
                UUID.randomUUID(),
                "minecraft:overworld",
                "iron",
                new Pos(0, 64, 0),
                new SearchSession.Limits(64, 256, 2400, 128),
                100);
        session.accountWork(900, new Pos(10, 64, 0), 10, 2);
        session.resumeAfterReload(1_000);

        assertEquals(SearchSession.Phase.CHOOSE_FRONTIER, session.snapshot().phase());
        assertEquals(1, session.snapshot().spentTicks());
        assertEquals(10, session.snapshot().spentTravelBlocks());
        assertEquals(2, session.snapshot().excavationBlocks());
        assertEquals(1_000, session.snapshot().lastTick());
    }

    @Test
    void reloadKeepsAWalkHomeAndNeverReopensAFinishedSession() {
        SearchSession returning = new SearchSession(
                UUID.randomUUID(),
                "minecraft:overworld",
                "iron",
                new Pos(0, 64, 0),
                new SearchSession.Limits(64, 256, 2400, 128),
                0);
        returning.advance(new SearchSession.Input(
                1,
                "minecraft:overworld",
                new Pos(0, 64, 0),
                true,
                false,
                SearchSession.LegOutcome.NONE,
                null,
                0,
                0,
                0,
                0));
        var frontier = new SearchPlanner.Frontier(
                new Pos(16, 64, 0), new Pos(16, 64, 0), ResourceSearchProfile.Strategy.SURFACE, 1, 32);
        returning.advance(new SearchSession.Input(
                2,
                "minecraft:overworld",
                new Pos(0, 64, 0),
                true,
                false,
                SearchSession.LegOutcome.NONE,
                frontier,
                0,
                0,
                0,
                32));
        returning.advance(new SearchSession.Input(
                10,
                "minecraft:overworld",
                new Pos(16, 64, 0),
                true,
                true,
                SearchSession.LegOutcome.NONE,
                null,
                0,
                0,
                0,
                0));
        returning.resumeAfterReload(20);
        assertEquals(SearchSession.Phase.RETURN, returning.snapshot().phase());

        returning.advance(new SearchSession.Input(
                30,
                "minecraft:overworld",
                new Pos(0, 64, 0),
                true,
                false,
                SearchSession.LegOutcome.NONE,
                null,
                16,
                0,
                0,
                0));
        assertEquals(SearchSession.Phase.COMPLETE, returning.snapshot().phase());

        returning.resumeAfterReload(40);
        assertEquals(SearchSession.Phase.COMPLETE, returning.snapshot().phase());
    }

    @Test
    void reloadInterruptOfAnOutboundLegResumesPlanningWithItsSpend() {
        SearchSession session = new SearchSession(
                UUID.randomUUID(),
                "minecraft:overworld",
                "iron",
                new Pos(0, 64, 0),
                new SearchSession.Limits(64, 256, 2400, 128),
                0);
        session.advance(readyInput(1));
        var frontier = new SearchPlanner.Frontier(
                new Pos(16, 64, 0), new Pos(16, 64, 0), ResourceSearchProfile.Strategy.SURFACE, 1, 32);
        session.advance(new SearchSession.Input(
                2,
                "minecraft:overworld",
                new Pos(0, 64, 0),
                true,
                false,
                SearchSession.LegOutcome.NONE,
                frontier,
                0,
                0,
                0,
                32));
        assertEquals(SearchSession.Phase.TRAVEL, session.snapshot().phase());
        long spentTicks = session.snapshot().spentTicks();
        long spentTravel = Math.round(session.snapshot().spentTravelBlocks());

        session.interruptForReload();
        assertTrue(session.resumable());
        assertEquals(SearchSession.Phase.INTERRUPTED, session.snapshot().phase());
        assertEquals(SearchSession.Phase.TRAVEL, session.snapshot().interruptedFrom());

        session.resumeAfterReload(50);
        assertEquals(SearchSession.Phase.CHOOSE_FRONTIER, session.snapshot().phase());
        assertEquals(spentTicks, session.snapshot().spentTicks());
        assertEquals(spentTravel, Math.round(session.snapshot().spentTravelBlocks()));
        assertEquals(50, session.snapshot().lastTick());
    }

    @Test
    void reloadInterruptOfAReturnKeepsWalkingHomeAndItsDiscovery() {
        SearchSession session = new SearchSession(
                UUID.randomUUID(),
                "minecraft:overworld",
                "iron",
                new Pos(0, 64, 0),
                new SearchSession.Limits(64, 256, 2400, 128),
                0);
        session.advance(new SearchSession.Input(
                5,
                "minecraft:overworld",
                new Pos(16, 64, 0),
                true,
                true,
                SearchSession.LegOutcome.NONE,
                null,
                0,
                0,
                0,
                0));
        assertEquals(SearchSession.Phase.RETURN, session.snapshot().phase());
        assertTrue(session.snapshot().discovered());
        long spentTicks = session.snapshot().spentTicks();

        session.interruptForReload();
        assertEquals(SearchSession.Phase.RETURN, session.snapshot().interruptedFrom());
        session.resumeAfterReload(60);

        assertEquals(SearchSession.Phase.RETURN, session.snapshot().phase());
        assertTrue(session.snapshot().discovered());
        assertEquals(spentTicks, session.snapshot().spentTicks());
    }

    @Test
    void aDeliberateStopIsNeverResumedByACheckpoint() {
        SearchSession session = new SearchSession(
                UUID.randomUUID(),
                "minecraft:overworld",
                "iron",
                new Pos(0, 64, 0),
                new SearchSession.Limits(64, 256, 2400, 128),
                0);
        session.advance(readyInput(1));
        session.interrupt();

        assertFalse(session.resumable());
        session.resumeAfterReload(20);
        assertEquals(SearchSession.Phase.INTERRUPTED, session.snapshot().phase());
        assertEquals(null, session.snapshot().interruptedFrom());
    }

    private static SearchSession.Input readyInput(long tick) {
        return new SearchSession.Input(
                tick,
                "minecraft:overworld",
                new Pos(0, 64, 0),
                true,
                false,
                SearchSession.LegOutcome.NONE,
                null,
                0,
                0,
                0,
                0);
    }
}
