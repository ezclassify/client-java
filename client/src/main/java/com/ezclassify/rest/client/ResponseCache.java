package com.ezclassify.rest.client;

import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Bounded, thread-safe response reuse with one in-flight request per operation key. */
final class ResponseCache {
  private static final int MAX_ENTRIES = 500;

  private static final class Entry {
    final CompletableFuture<Object> result = new CompletableFuture<>();
    long expiresAtNanos = Long.MAX_VALUE;
  }

  private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
  private final LongSupplier nanoClock;
  private long arrivals;

  ResponseCache() {
    this(System::nanoTime);
  }

  /**
   * A cache whose entry lifetimes are measured on the given monotonic clock. Only lifetimes read
   * it; request timeouts belong to the transport. Tests move it to age entries without waiting.
   */
  ResponseCache(LongSupplier nanoClock) {
    this.nanoClock = nanoClock;
  }

  /**
   * How many calls have reached {@link #get} and been filed against an entry, whether they started
   * the request or joined one already in flight. A test that starts several callers at once reads
   * this to know all of them have joined before it lets the request finish.
   */
  long arrivals() {
    synchronized (entries) {
      return arrivals;
    }
  }

  <T> T get(String key, long ttlNanos, Supplier<T> load) {
    Entry entry;
    boolean owner;
    synchronized (entries) {
      arrivals++;
      Entry existing = entries.get(key);
      if (existing != null
          && (!existing.result.isDone() || existing.expiresAtNanos > nanoClock.getAsLong())) {
        entry = existing;
        owner = false;
      } else {
        entries.remove(key);
        entry = new Entry();
        entries.put(key, entry);
        while (entries.size() > MAX_ENTRIES) {
          String eldest = entries.keySet().iterator().next();
          entries.remove(eldest);
        }
        owner = true;
      }
    }

    if (owner) {
      try {
        T value = load.get();
        entry.expiresAtNanos =
            ttlNanos == Long.MAX_VALUE ? Long.MAX_VALUE : nanoClock.getAsLong() + ttlNanos;
        entry.result.complete(value);
        return value;
      } catch (RuntimeException | Error failure) {
        entry.result.completeExceptionally(failure);
        synchronized (entries) {
          if (entries.get(key) == entry) entries.remove(key);
        }
        throw failure;
      }
    }

    try {
      // Each key includes the operation and body, so all waiters expect one result type. The
      // request this waits on carries its own deadline, so the wait is bounded by it.
      @SuppressWarnings("unchecked")
      T value = (T) entry.result.get();
      return value;
    } catch (InterruptedException interrupted) {
      // Stop waiting, but leave the shared request running for the callers still joined to it.
      Thread.currentThread().interrupt();
      throw new EzClassifyClientException(
          "waiting for a shared request was interrupted", interrupted);
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof RuntimeException runtime) throw runtime;
      if (cause instanceof Error error) throw error;
      throw new IllegalStateException(cause);
    }
  }

  void clear() {
    synchronized (entries) {
      entries.clear();
    }
  }

  void clearExcept(String keepKey) {
    synchronized (entries) {
      Entry keep = keepKey == null ? null : entries.get(keepKey);
      entries.clear();
      if (keep != null) entries.put(keepKey, keep);
    }
  }
}
