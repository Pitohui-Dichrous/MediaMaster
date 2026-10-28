package io.mediamaster.app.library;

import java.util.function.BooleanSupplier;

/** Keeps distance-earned clicks distinct when one touch event crosses several thresholds. */
final class HapticPulseQueue {
  static final long INTERVAL_MS = 40;
  private static final int STEPS = 5;

  interface Scheduler {
    long nowMillis();

    void postDelayed(Runnable action, long delayMillis);

    void remove(Runnable action);
  }

  private final Scheduler scheduler;
  private final BooleanSupplier pulse;
  private final Runnable dispatch = this::dispatchNext;
  private int pending;
  private long nextPulseAt;
  private boolean scheduled, rejected;

  HapticPulseQueue(Scheduler scheduler, BooleanSupplier pulse) {
    this.scheduler = scheduler;
    this.pulse = pulse;
  }

  void enqueue(int count) {
    if (rejected || count <= 0) return;
    pending = Math.min(STEPS, pending + Math.min(STEPS, count));
    if (!scheduled) dispatchNext();
  }

  private void dispatchNext() {
    scheduled = false;
    if (rejected || pending == 0) return;
    long now = scheduler.nowMillis();
    if (now < nextPulseAt) {
      schedule(nextPulseAt - now);
      return;
    }
    pending--;
    if (!pulse.getAsBoolean()) {
      // Respect disabled system/view feedback; do not retry until the next gesture.
      rejected = true;
      pending = 0;
      return;
    }
    nextPulseAt = scheduler.nowMillis() + INTERVAL_MS;
    if (pending > 0) schedule(INTERVAL_MS);
  }

  private void schedule(long delay) {
    scheduled = true;
    scheduler.postDelayed(dispatch, delay);
  }

  /** Retraction drops old clicks without allowing a new pulse to overlap the last one. */
  void discardPending() {
    scheduler.remove(dispatch);
    scheduled = false;
    pending = 0;
  }

  void cancel() {
    discardPending();
    rejected = false;
    nextPulseAt = 0;
  }
}
