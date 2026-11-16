package dev.friendai.foundation.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AwayBookTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void aReturnIsReportedOnceWithItsElapsedTime() {
        AwayBook book = new AwayBook();
        book.left(PLAYER, 1_000L);
        assertEquals(30_000L, book.returned(PLAYER, 31_000L));
        assertEquals(-1L, book.returned(PLAYER, 32_000L));
        assertEquals(0, book.size());
    }

    @Test
    void aLeaveInAnotherWorldIsNotAReturnInThisOne() {
        AwayBook worldA = new AwayBook();
        AwayBook worldB = new AwayBook();
        worldA.left(PLAYER, 1_000L);
        assertEquals(-1L, worldB.returned(PLAYER, 31_000L));
        assertEquals(1, worldA.size());
        assertEquals(30_000L, worldA.returned(PLAYER, 31_000L));
    }

    @Test
    void anExpiredLeaveIsDroppedWithoutAReturn() {
        AwayBook book = new AwayBook(8, 60_000L);
        book.left(PLAYER, 1_000L);
        assertEquals(-1L, book.returned(PLAYER, 61_001L));
        assertEquals(0, book.size());
    }

    @Test
    void theOldestLeaveIsEvictedPastCapacity() {
        AwayBook book = new AwayBook(2, 60_000L);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        book.left(first, 1_000L);
        book.left(second, 1_001L);
        book.left(third, 1_002L);
        assertEquals(2, book.size());
        assertEquals(-1L, book.returned(first, 2_000L));
        assertEquals(1_000L, book.returned(second, 2_001L));
    }

    @Test
    void aClockThatWentBackwardsIsNotAReturn() {
        AwayBook book = new AwayBook();
        book.left(PLAYER, 10_000L);
        assertEquals(-1L, book.returned(PLAYER, 9_000L));
        assertEquals(0, book.size());
    }

    @Test
    void rejectsAnUnusableCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new AwayBook(0, 1_000L));
    }
}
