package dev.friendai.features.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Bounded, non-persisted state keyed by an entity id, evicted least-recently-used first.
 *
 * <p>Resource evidence belongs to the entity that saw it. A per-entity cap alone does not bound the map across repeated
 * summon, dismissal and replacement cycles, so this book keeps the number of retained entities bounded as well. It is
 * transient by definition: nothing here is saved, and dropping an entry only forgets what was seen this session.
 */
public final class TransientBook<V> {
    private final Store<V> entries;

    public TransientBook(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive: " + capacity);
        this.entries = new Store<>(capacity);
    }

    public V computeIfAbsent(UUID id, Supplier<V> factory) {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(factory, "factory is required");
        return entries.computeIfAbsent(id, ignored -> factory.get());
    }

    public V get(UUID id) {
        return id == null ? null : entries.get(id);
    }

    public boolean forget(UUID id) {
        return id != null && entries.remove(id) != null;
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    private static final class Store<V> extends LinkedHashMap<UUID, V> {
        private final int capacity;

        Store(int capacity) {
            super(16, 0.75f, true);
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, V> eldest) {
            return size() > capacity;
        }
    }
}
