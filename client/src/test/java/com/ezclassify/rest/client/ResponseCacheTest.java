package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Concurrency and expiry checks for the transport response cache. */
class ResponseCacheTest {
  @Test
  void pendingRequestRemainsSharedPastItsTtl() throws Exception {
    ResponseCache cache = new ResponseCache();
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<String> first =
          executor.submit(
              () ->
                  cache.get(
                      "same",
                      0,
                      () -> {
                        calls.incrementAndGet();
                        started.countDown();
                        try {
                          if (!release.await(2, TimeUnit.SECONDS))
                            throw new AssertionError("wait timed out");
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new AssertionError(e);
                        }
                        return "answer";
                      }));
      assertTrue(started.await(2, TimeUnit.SECONDS));
      Future<String> second = executor.submit(() -> cache.get("same", 0, () -> "second wire trip"));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (cache.arrivals() < 2 && System.nanoTime() < deadline) Thread.onSpinWait();
      assertEquals(2, cache.arrivals(), "the second caller never joined the shared request");
      release.countDown();
      assertEquals("answer", first.get(2, TimeUnit.SECONDS));
      assertEquals("answer", second.get(2, TimeUnit.SECONDS));
      assertEquals(1, calls.get());
    } finally {
      executor.shutdownNow();
    }
    assertEquals("fresh", cache.get("same", 0, () -> "fresh"));
  }

  @Test
  void failedReadIsEvictedButPinnedSuccessIsRetained() {
    ResponseCache cache = new ResponseCache();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            cache.get(
                "same",
                Long.MAX_VALUE,
                () -> {
                  throw new IllegalArgumentException("bad response");
                }));
    assertEquals("recovered", cache.get("same", Long.MAX_VALUE, () -> "recovered"));
    assertEquals("recovered", cache.get("same", Long.MAX_VALUE, () -> "unexpected"));
  }
}
