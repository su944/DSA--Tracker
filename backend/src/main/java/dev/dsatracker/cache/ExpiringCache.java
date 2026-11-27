package dev.dsatracker.cache;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Optional;

public final class ExpiringCache<K, V> {
    private final int capacity;
    private final Clock clock;
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>();

    public ExpiringCache(int capacity, Clock clock) {
        this.capacity = capacity;
        this.clock = clock;
    }

    public synchronized void put(K key, V value, Instant expires) {
        entries.remove(key);
        entries.put(key, new Entry<>(value, expires));
        if (entries.size() > capacity) {
            entries.pollFirstEntry();
        }
    }

    public synchronized Optional<V> get(K key) {
        var entry = entries.get(key);
        if (entry == null) return Optional.empty();
        if (!entry.expires().isAfter(clock.instant())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    public synchronized boolean consume(K key) {
        return get(key).isPresent() && entries.remove(key) != null;
    }

    private record Entry<V>(V value, Instant expires) {}
}
