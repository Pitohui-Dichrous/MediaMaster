package io.mediamaster.app.player;

import java.io.Closeable;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Admission and asynchronous retirement share one bound: at most six native players, including
 * players whose shutdown is still blocked. A caller without a lease must use the compatible engine.
 */
final class NativePlayerRetirement {
  private static final int MAX_OWNED = 6;
  private static final NativePlayerRetirement SHARED =
      new NativePlayerRetirement(MAX_OWNED, retirementWorkers());

  private final int capacity;
  private final Executor executor;
  // Strong ownership is intentional. A failed native release cannot prove that its input stopped;
  // quarantine keeps that player and its descriptor alive and consumes its original bounded slot.
  private final Set<Lease> owned = new HashSet<>();

  static Lease tryAcquire() {
    return SHARED.acquire();
  }

  NativePlayerRetirement(int capacity, Executor executor) {
    if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
    this.capacity = capacity;
    this.executor = Objects.requireNonNull(executor);
  }

  synchronized Lease acquire() {
    if (owned.size() >= capacity) return null;
    Lease lease = new Lease(this);
    owned.add(lease);
    return lease;
  }

  synchronized int ownedCount() {
    return owned.size();
  }

  synchronized int quarantinedCount() {
    int count = 0;
    for (Lease lease : owned) if (lease.state == State.QUARANTINED) count++;
    return count;
  }

  private static Executor retirementWorkers() {
    AtomicInteger serial = new AtomicInteger();
    ThreadPoolExecutor workers =
        new ThreadPoolExecutor(
            3,
            3,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_OWNED),
            task -> {
              Thread thread = new Thread(task, "native-retirement-" + serial.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    workers.allowCoreThreadTimeOut(true);
    return workers;
  }

  private enum State {
    RESERVED,
    RETIRING,
    RUNNING,
    RELEASED,
    QUARANTINED
  }

  static final class Lease {
    private final NativePlayerRetirement owner;
    private State state = State.RESERVED;
    private Runnable nativeRelease;
    private Closeable descriptor;
    private String failureStage;

    private Lease(NativePlayerRetirement owner) {
      this.owner = owner;
    }

    /** Only for a reservation that never acquired a native player, such as constructor failure. */
    boolean abandon() {
      synchronized (owner) {
        if (state != State.RESERVED) return false;
        state = State.RELEASED;
        owner.owned.remove(this);
        return true;
      }
    }

    /**
     * Transfers sole ownership once. Before calling, remove listeners and detach both public views
     * and native Vout callbacks on main, then remove the player/descriptor from the page. Neither
     * resource may be used again. The descriptor closes only after native shutdown returns
     * normally. A duplicate call returns false without accepting its arguments.
     */
    boolean retire(Runnable release, Closeable inputDescriptor) {
      Objects.requireNonNull(release);
      synchronized (owner) {
        if (state != State.RESERVED) return false;
        nativeRelease = release;
        descriptor = inputDescriptor;
        state = State.RETIRING;
      }
      try {
        owner.executor.execute(this::finish);
      } catch (Throwable unavailable) {
        // Never run native shutdown on the caller as an executor-rejection fallback. Production
        // admission bounds all queued/running work to six, so ordinary queue overflow cannot occur.
        quarantine("scheduling");
      }
      return true;
    }

    /**
     * Takes ownership after main-thread view detachment failed. Native release would repeat that
     * detachment and could touch a still-attached View from a worker, so do not schedule or retry
     * it.
     */
    boolean quarantineUnreleased(Runnable release, Closeable inputDescriptor) {
      Objects.requireNonNull(release);
      synchronized (owner) {
        if (state != State.RESERVED) return false;
        nativeRelease = release;
        descriptor = inputDescriptor;
        failureStage = "view-detach";
        state = State.QUARANTINED;
        return true;
      }
    }

    private void finish() {
      synchronized (owner) {
        if (state != State.RETIRING) return;
        state = State.RUNNING;
      }
      try {
        nativeRelease.run();
      } catch (Throwable incomplete) {
        // No retry: release may have partially destroyed the native object. Keep its descriptor
        // alive because this failure does not prove that native input has finished using it.
        quarantine("native-release");
        return;
      }
      try {
        if (descriptor != null) descriptor.close();
      } catch (Throwable incomplete) {
        // Bound descriptor-close failures too; do not silently admit unlimited replacement inputs.
        quarantine("descriptor-close");
        return;
      }
      synchronized (owner) {
        nativeRelease = null;
        descriptor = null;
        state = State.RELEASED;
        owner.owned.remove(this);
      }
    }

    private void quarantine(String stage) {
      synchronized (owner) {
        if (state == State.RELEASED) return;
        // An executor that started the task before reporting an error already transferred
        // shutdown to that worker. Let its normal completion/failure keep ownership coherent.
        if (stage.equals("scheduling") && state != State.RETIRING) return;
        failureStage = stage;
        state = State.QUARANTINED;
      }
    }

    String failureStage() {
      synchronized (owner) {
        return failureStage;
      }
    }
  }
}
