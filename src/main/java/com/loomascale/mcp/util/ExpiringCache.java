package com.loomascale.mcp.util;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// A bounded, time-expiring map, in place of a Guava cache.
//
// A library should not drag Guava onto every consumer's classpath for two in-memory maps
// holding a pending authorization request and a per-IP registration counter. Both are
// small, short-lived and non-critical: losing an entry costs the user one retry.
//
// Expiry is lazy — checked on read — plus a sweep when the map exceeds its bound, so
// there is no background thread to own or shut down.
public final class ExpiringCache<K, V> {

  private record Entry<V>(V value, long expiresAtMillis) {}

  private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();
  private final long ttlMillis;
  private final int maxSize;
  private final AtomicLong writesSinceSweep = new AtomicLong();

  public ExpiringCache(Duration ttl, int maxSize) {
    this.ttlMillis = ttl.toMillis();
    this.maxSize = maxSize;
  }

  public void put(K key, V value) {
    entries.put(key, new Entry<>(value, System.currentTimeMillis() + ttlMillis));
    // Sweep on write rather than on a timer. The check is cheap and only walks the map
    // once it has actually grown past the bound.
    if (writesSinceSweep.incrementAndGet() > 64 || entries.size() > maxSize) {
      writesSinceSweep.set(0);
      sweep();
    }
  }

  public Optional<V> get(K key) {
    Entry<V> entry = entries.get(key);
    if (entry == null) {
      return Optional.empty();
    }
    if (System.currentTimeMillis() >= entry.expiresAtMillis()) {
      entries.remove(key, entry);
      return Optional.empty();
    }
    return Optional.of(entry.value());
  }

  // Atomic get-or-create, so two concurrent callers share one value rather than each
  // installing their own. The rate limiter depends on this: a lost counter is a lost
  // limit.
  public V computeIfAbsent(K key, java.util.function.Supplier<V> factory) {
    long now = System.currentTimeMillis();
    Entry<V> entry =
        entries.compute(
            key,
            (k, existing) ->
                existing == null || now >= existing.expiresAtMillis()
                    ? new Entry<>(factory.get(), now + ttlMillis)
                    : existing);
    return entry.value();
  }

  public void invalidate(K key) {
    entries.remove(key);
  }

  public int size() {
    return entries.size();
  }

  private void sweep() {
    long now = System.currentTimeMillis();
    entries.entrySet().removeIf(e -> now >= e.getValue().expiresAtMillis());
    // Still over the bound after dropping expired entries: this is a flood rather than
    // normal use, so shed everything instead of growing without limit. Callers treat a
    // miss as "start again", which is the correct outcome for a flood.
    if (entries.size() > maxSize) {
      entries.clear();
    }
  }
}
