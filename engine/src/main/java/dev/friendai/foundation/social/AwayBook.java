package dev.friendai.foundation.social;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

/**
 * One server's record of when its players left, kept only so a genuine return can be mentioned once.
 *
 * <p>It is deliberately small and runtime-scoped: a leave in another world must never read as a return in this one, and
 * a player who never comes back must not accumulate. Reading a return removes the entry, so the same absence cannot be
 * reported twice. Entries older than {@link #DEFAULT_MAX_AGE_MILLIS} are treated as unknown, and the oldest entry is
 * evicted once the book holds more than its capacity.
 */
public final class AwayBook {
    /** More players than a single server is expected to see leave and return; oldest entries go first. */
    public static final int DEFAULT_CAPACITY = 256;

    /** A leave older than this is not a return worth mentioning, and the entry is dropped. */
    public static final long DEFAULT_MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L;

    private final int capacity;
    private final long maxAgeMillis;
    private final LinkedHashMap<UUID, Long> leftAt = new LinkedHashMap<>();

    public AwayBook() {
        this(DEFAULT_CAPACITY, DEFAULT_MAX_AGE_MILLIS);
    }

    public AwayBook(int capacity, long maxAgeMillis) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive: " + capacity);
        if (maxAgeMillis < 0) throw new IllegalArgumentException("maxAgeMillis must not be negative");
        this.capacity = capacity;
        this.maxAgeMillis = maxAgeMillis;
    }

    /** Records that a player left at {@code now}, replacing any earlier leave and evicting the oldest excess entry. */
    public void left(UUID player, long now) {
        Objects.requireNonNull(player, "player is required");
        leftAt.remove(player);
        leftAt.put(player, now);
        while (leftAt.size() > capacity) {
            leftAt.remove(leftAt.keySet().iterator().next());
        }
    }

    /**
     * How long the player was away, in milliseconds, or {@code -1} when no usable leave is recorded. A usable leave is
     * consumed, so one absence is reported once; an entry older than the age limit or newer than the clock is dropped
     * without being reported.
     */
    public long returned(UUID player, long now) {
        if (player == null) return -1L;
        Long left = leftAt.remove(player);
        if (left == null || left > now || now - left > maxAgeMillis) return -1L;
        return now - left;
    }

    public int size() {
        return leftAt.size();
    }

    public void clear() {
        leftAt.clear();
    }
}
