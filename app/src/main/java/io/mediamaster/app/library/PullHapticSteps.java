package io.mediamaster.app.library;

/** Five distance marks that re-arm after the finger retracts below each mark. */
final class PullHapticSteps {
  private static final int STEPS = 5;
  private final HapticPulseQueue pulses;
  private final float firstDistance, lastDistance, stepDistance, hysteresis;
  private float previousDistance, forwardPeak;
  private int nextStep;
  private boolean retracting;

  PullHapticSteps(
      HapticPulseQueue pulses, float firstDistance, float lastDistance, float hysteresis) {
    this.pulses = pulses;
    this.firstDistance = firstDistance;
    this.lastDistance = lastDistance;
    this.stepDistance = (lastDistance - firstDistance) / (STEPS - 1);
    this.hysteresis = hysteresis;
  }

  void moveTo(float distance) {
    if (Float.isNaN(distance) || Float.isInfinite(distance)) return;
    distance = Math.max(0, distance);
    if (distance < previousDistance) {
      // Require cumulative movement away from the local peak, not one large touch sample.
      // A small holding/release jitter must not erase clicks already earned by a fast pull.
      if (distance <= forwardPeak - hysteresis) {
        pulses.discardPending();
        retracting = true;
      }
      while (nextStep > 0 && distance <= threshold(nextStep - 1) - hysteresis) nextStep--;
    } else if (distance > previousDistance) {
      forwardPeak = retracting ? distance : Math.max(forwardPeak, distance);
      retracting = false;
      int crossed = 0;
      while (nextStep < STEPS && distance >= threshold(nextStep)) {
        nextStep++;
        crossed++;
      }
      pulses.enqueue(crossed);
    }
    previousDistance = distance;
  }

  private float threshold(int step) {
    // Keep the final mark exactly at the refresh threshold, including fractional densities.
    return step == STEPS - 1 ? lastDistance : firstDistance + step * stepDistance;
  }

  int lastReachedStep() {
    return nextStep - 1;
  }

  void reset() {
    pulses.cancel();
    previousDistance = forwardPeak = 0;
    nextStep = 0;
    retracting = false;
  }
}
