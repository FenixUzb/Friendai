package dev.friendai.features.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AcquisitionAttemptsTest {
    @Test
    void aFirstFailureIsRememberedAndPenalised() {
        AcquisitionAttempts attempts = new AcquisitionAttempts();

        attempts.recordFailure("tool_materials", "minecraft:deepslate", "no safe return");

        assertEquals(1, attempts.failures("minecraft:deepslate"));
        assertEquals(AcquisitionAttempts.PENALTY_PER_FAILURE, attempts.penalty("minecraft:deepslate"));
    }

    @Test
    void aRepeatedFailureRaisesThePenaltyWithoutRemovingTheRow() {
        AcquisitionAttempts attempts = new AcquisitionAttempts();

        attempts.recordFailure("tool_materials", "minecraft:deepslate", "no safe return");
        attempts.recordFailure("tool_materials", "minecraft:deepslate", "still unreachable");

        assertEquals(2, attempts.failures("minecraft:deepslate"));
        assertEquals(2 * AcquisitionAttempts.PENALTY_PER_FAILURE, attempts.penalty("minecraft:deepslate"));
        assertEquals(1, attempts.entries().size());
        assertEquals("still unreachable", attempts.entries().getFirst().lastCause());
    }

    @Test
    void theLedgerStaysBoundedAndDropsTheOldestSource() {
        AcquisitionAttempts attempts = new AcquisitionAttempts();
        for (int i = 0; i < AcquisitionAttempts.MAX_ENTRIES + 4; i++) {
            attempts.recordFailure("family", "minecraft:source_" + i, "failed");
        }

        assertEquals(AcquisitionAttempts.MAX_ENTRIES, attempts.entries().size());
        assertEquals(0, attempts.failures("minecraft:source_0"));
        assertEquals(1, attempts.failures("minecraft:source_" + (AcquisitionAttempts.MAX_ENTRIES + 3)));
    }

    @Test
    void anUntriedSourceHasNoPenalty() {
        AcquisitionAttempts attempts = new AcquisitionAttempts();

        assertEquals(0, attempts.failures("minecraft:stone"));
        assertEquals(0, attempts.penalty("minecraft:stone"));
        assertTrue(attempts.sourceFailures().isEmpty());
    }

    @Test
    void aLongCauseIsShortenedAndSingleLined() {
        AcquisitionAttempts attempts = new AcquisitionAttempts();

        attempts.recordFailure("family", "minecraft:stone", "first line\nsecond line " + "x".repeat(400));

        String cause = attempts.entries().getFirst().lastCause();
        assertTrue(cause.length() <= 120, "cause was not shortened: " + cause.length());
        assertFalse(cause.contains("\n"));
    }

    @Test
    void aBlankSourceIsIgnoredAndClearForgetsEverything() {
        AcquisitionAttempts attempts = new AcquisitionAttempts();
        attempts.recordFailure("family", "  ", "failed");
        attempts.recordFailure("family", null, "failed");
        assertTrue(attempts.entries().isEmpty());

        attempts.recordFailure("family", "minecraft:stone", "failed");
        attempts.clear();

        assertTrue(attempts.entries().isEmpty());
        assertTrue(attempts.sourceFailures().isEmpty());
    }
}
