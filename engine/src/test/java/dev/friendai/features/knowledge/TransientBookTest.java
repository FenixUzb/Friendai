package dev.friendai.features.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransientBookTest {
    @Test
    void theLeastRecentlyUsedEntryIsEvictedPastCapacity() {
        TransientBook<String> book = new TransientBook<>(2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        book.computeIfAbsent(first, () -> "a");
        book.computeIfAbsent(second, () -> "b");
        book.computeIfAbsent(third, () -> "c");
        assertEquals(2, book.size());
        assertNull(book.get(first));
        assertEquals("b", book.get(second));
        assertEquals("c", book.get(third));
    }

    @Test
    void readingAnEntryKeepsItRecent() {
        TransientBook<String> book = new TransientBook<>(2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        book.computeIfAbsent(first, () -> "a");
        book.computeIfAbsent(second, () -> "b");
        book.get(first);
        book.computeIfAbsent(third, () -> "c");
        assertEquals("a", book.get(first));
        assertNull(book.get(second));
    }

    @Test
    void forgettingAndClearingReleaseTheirEntries() {
        TransientBook<String> book = new TransientBook<>(4);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        book.computeIfAbsent(first, () -> "a");
        book.computeIfAbsent(second, () -> "b");
        assertEquals(true, book.forget(first));
        assertEquals(false, book.forget(first));
        assertEquals(1, book.size());
        book.clear();
        assertEquals(0, book.size());
    }

    @Test
    void rejectsAnUnusableCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new TransientBook<>(0));
    }
}
