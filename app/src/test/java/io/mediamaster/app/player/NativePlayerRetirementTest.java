package io.mediamaster.app.player;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class NativePlayerRetirementTest {
  private static final class DeferredExecutor implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    void finishOne() {
      tasks.remove().run();
    }
  }

  @Test
  public void admissionBoundsLiveAndRetiringPlayersTogether() {
    DeferredExecutor executor = new DeferredExecutor();
    NativePlayerRetirement owner = new NativePlayerRetirement(6, executor);
    NativePlayerRetirement.Lease[] leases = new NativePlayerRetirement.Lease[6];
    for (int i = 0; i < leases.length; i++) leases[i] = owner.acquire();
    assertNull(owner.acquire());
    for (int i = 0; i < 3; i++) assertTrue(leases[i].retire(() -> {}, null));
    assertEquals(6, owner.ownedCount());
    assertNull(owner.acquire());
    executor.finishOne();
    assertEquals(5, owner.ownedCount());
    assertNotNull(owner.acquire());
    assertNull(owner.acquire());
  }

  @Test
  public void descriptorClosesOnlyAfterNativeShutdownReturns() {
    DeferredExecutor executor = new DeferredExecutor();
    NativePlayerRetirement owner = new NativePlayerRetirement(1, executor);
    NativePlayerRetirement.Lease lease = owner.acquire();
    List<String> order = new ArrayList<>();
    assertTrue(lease.retire(() -> order.add("native"), () -> order.add("descriptor")));
    assertTrue(order.isEmpty());
    assertEquals(1, owner.ownedCount());
    executor.finishOne();
    assertEquals(Arrays.asList("native", "descriptor"), order);
    assertEquals(0, owner.ownedCount());
    assertNotNull(owner.acquire());
  }

  @Test
  public void blockedNativeShutdownDoesNotBlockTheCallerOrCloseItsInput() throws Exception {
    CountDownLatch started = new CountDownLatch(1), unblock = new CountDownLatch(1);
    CountDownLatch closed = new CountDownLatch(1);
    Executor executor =
        task -> {
          Thread worker = new Thread(task, "retirement-test");
          worker.setDaemon(true);
          worker.start();
        };
    NativePlayerRetirement owner = new NativePlayerRetirement(1, executor);
    NativePlayerRetirement.Lease lease = owner.acquire();
    AtomicBoolean callerReturned = new AtomicBoolean();
    try {
      assertTrue(
          lease.retire(
              () -> {
                started.countDown();
                try {
                  assertTrue(unblock.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                  throw new AssertionError(error);
                }
              },
              closed::countDown));
      callerReturned.set(true);
      assertTrue(started.await(1, TimeUnit.SECONDS));
      assertTrue(callerReturned.get());
      assertEquals(1, closed.getCount());
      assertNull(owner.acquire());
    } finally {
      unblock.countDown();
    }
    assertTrue(closed.await(1, TimeUnit.SECONDS));
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (owner.ownedCount() != 0 && System.nanoTime() < until) Thread.yield();
    assertEquals(0, owner.ownedCount());
  }

  @Test
  public void duplicateRetirementAndAbandonCannotDoubleRelease() {
    DeferredExecutor executor = new DeferredExecutor();
    NativePlayerRetirement owner = new NativePlayerRetirement(1, executor);
    NativePlayerRetirement.Lease lease = owner.acquire();
    AtomicInteger releases = new AtomicInteger(), closes = new AtomicInteger();
    assertTrue(lease.retire(releases::incrementAndGet, closes::incrementAndGet));
    assertFalse(lease.retire(releases::incrementAndGet, closes::incrementAndGet));
    assertFalse(lease.abandon());
    assertEquals(1, executor.tasks.size());
    executor.finishOne();
    assertFalse(lease.retire(releases::incrementAndGet, closes::incrementAndGet));
    assertFalse(lease.abandon());
    assertEquals(1, releases.get());
    assertEquals(1, closes.get());
  }

  @Test
  public void failedConstructorReservationCanBeAbandonedExactlyOnce() {
    NativePlayerRetirement owner = new NativePlayerRetirement(1, new DeferredExecutor());
    NativePlayerRetirement.Lease lease = owner.acquire();
    assertTrue(lease.abandon());
    assertFalse(lease.abandon());
    assertEquals(0, owner.ownedCount());
    assertNotNull(owner.acquire());
  }

  @Test
  public void releaseFailureQuarantinesOwnershipWithoutClosingPotentiallyLiveInput() {
    DeferredExecutor executor = new DeferredExecutor();
    NativePlayerRetirement owner = new NativePlayerRetirement(1, executor);
    NativePlayerRetirement.Lease lease = owner.acquire();
    AtomicBoolean closed = new AtomicBoolean();
    assertTrue(
        lease.retire(
            () -> {
              throw new IllegalStateException("simulated native shutdown failure");
            },
            () -> closed.set(true)));
    executor.finishOne();
    assertFalse(closed.get());
    assertEquals("native-release", lease.failureStage());
    assertEquals(1, owner.quarantinedCount());
    assertEquals(1, owner.ownedCount());
    assertNull(owner.acquire());
    assertFalse(lease.abandon());
  }

  @Test
  public void descriptorFailureRemainsBoundedAfterNativeShutdown() {
    DeferredExecutor executor = new DeferredExecutor();
    NativePlayerRetirement owner = new NativePlayerRetirement(1, executor);
    NativePlayerRetirement.Lease lease = owner.acquire();
    AtomicBoolean released = new AtomicBoolean();
    assertTrue(
        lease.retire(
            () -> released.set(true),
            () -> {
              throw new IOException("simulated descriptor close failure");
            }));
    executor.finishOne();
    assertTrue(released.get());
    assertEquals("descriptor-close", lease.failureStage());
    assertEquals(1, owner.quarantinedCount());
    assertNull(owner.acquire());
  }

  @Test
  public void executorRejectionNeverRunsReleaseOnTheCaller() {
    NativePlayerRetirement owner =
        new NativePlayerRetirement(
            1,
            task -> {
              throw new RejectedExecutionException("simulated unavailable worker");
            });
    NativePlayerRetirement.Lease lease = owner.acquire();
    AtomicBoolean released = new AtomicBoolean(), closed = new AtomicBoolean();
    assertTrue(lease.retire(() -> released.set(true), () -> closed.set(true)));
    assertFalse(released.get());
    assertFalse(closed.get());
    assertEquals("scheduling", lease.failureStage());
    assertEquals(1, owner.quarantinedCount());
    assertNull(owner.acquire());
    assertFalse(lease.abandon());
  }

  @Test
  public void failedViewDetachmentQuarantinesWithoutRunningViewWorkOnAWorker() {
    DeferredExecutor executor = new DeferredExecutor();
    NativePlayerRetirement owner = new NativePlayerRetirement(1, executor);
    NativePlayerRetirement.Lease lease = owner.acquire();
    AtomicBoolean released = new AtomicBoolean(), closed = new AtomicBoolean();
    assertTrue(lease.quarantineUnreleased(() -> released.set(true), () -> closed.set(true)));
    assertTrue(executor.tasks.isEmpty());
    assertFalse(released.get());
    assertFalse(closed.get());
    assertEquals("view-detach", lease.failureStage());
    assertEquals(1, owner.ownedCount());
    assertEquals(1, owner.quarantinedCount());
    assertNull(owner.acquire());
    assertFalse(lease.abandon());
    assertFalse(lease.retire(() -> released.set(true), () -> closed.set(true)));
    assertFalse(lease.quarantineUnreleased(() -> released.set(true), () -> closed.set(true)));
    assertTrue(executor.tasks.isEmpty());
  }
}
