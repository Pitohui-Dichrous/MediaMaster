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
  private int requested, sent;
  private long nextPulseAt;
  private boolean scheduled, rejected;

  HapticPulseQueue(Scheduler scheduler, BooleanSupplier pulse) {
    this.scheduler = scheduler;
    this.pulse = pulse;
  }

  void advanceTo(int step) {
    if (rejected || step < 0) return;
    requested = Math.max(requested, Math.min(STEPS - 1, step) + 1);
    if (!scheduled && sent < requested) dispatchNext();
  }

  private void dispatchNext() {
    scheduled = false;
    if (rejected || sent >= requested) return;
    long now = scheduler.nowMillis();
    if (now < nextPulseAt) {
      schedule(nextPulseAt - now);
      return;
    }
    sent++;
    if (!pulse.getAsBoolean()) {
      // Respect disabled system/view feedback; do not retry until the next gesture.
      rejected = true;
      return;
    }
    nextPulseAt = scheduler.nowMillis() + INTERVAL_MS;
    if (sent < requested) schedule(INTERVAL_MS);
  }

  private void schedule(long delay) {
    scheduled = true;
    scheduler.postDelayed(dispatch, delay);
  }

  void cancel() {
    scheduler.remove(dispatch);
    scheduled = false;
    rejected = false;
    requested = sent = 0;
    nextPulseAt = 0;
  }
}
