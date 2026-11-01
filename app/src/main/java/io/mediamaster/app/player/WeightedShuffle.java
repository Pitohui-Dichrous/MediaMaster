package io.mediamaster.app.player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Random;
import java.util.function.IntToDoubleFunction;

/** Shared, in-memory sampling policy for the library button and shuffled playback rounds. */
public final class WeightedShuffle {
  private static final double MIN_WEIGHT = .01;

  private WeightedShuffle() {}

  /** Frequent plays reduce priority gently, with a nonzero floor and no count overflow. */
  public static double weight(long plays) {
    return Math.max(MIN_WEIGHT, 1 / Math.sqrt(1.0 + Math.max(0, plays)));
  }

  /** Returns -1 for an empty library; otherwise every item retains a positive chance. */
  public static int choose(int count, IntToDoubleFunction weights, Random random) {
    return choose(snapshot(count, weights), -1, random);
  }

  /** The current item anchors only the first round; all others are weighted without replacement. */
  static ArrayList<Integer> initialRound(
      int count, int current, IntToDoubleFunction weights, Random random) {
    double[] snapshot = snapshot(count, weights);
    ArrayList<Integer> round = remaining(snapshot, current, random);
    round.add(0, current);
    return round;
  }

  /** A new full round, conditioned on its first item differing from the adjacent prior item. */
  static ArrayList<Integer> nextRound(
      int count, int adjacent, IntToDoubleFunction weights, Random random) {
    double[] snapshot = snapshot(count, weights);
    int first = choose(snapshot, count > 1 ? adjacent : -1, random);
    ArrayList<Integer> round = remaining(snapshot, first, random);
    if (first >= 0) round.add(0, first);
    return round;
  }

  private static double[] snapshot(int count, IntToDoubleFunction provider) {
    if (count < 0) throw new IllegalArgumentException("Negative library size");
    double[] values = new double[count];
    for (int i = 0; i < count; i++) {
      double value = provider.applyAsDouble(i);
      // Normal callers use weight(). Keep malformed persisted input from excluding an item.
      values[i] = Double.isNaN(value) ? MIN_WEIGHT : Math.max(MIN_WEIGHT, Math.min(1.0, value));
    }
    return values;
  }

  private static int choose(double[] weights, int excluded, Random random) {
    double total = 0;
    int last = -1;
    for (int i = 0; i < weights.length; i++) {
      if (i == excluded) continue;
      total += weights[i];
      last = i;
    }
    if (last < 0) return -1;
    double target = random.nextDouble() * total;
    for (int i = 0; i < weights.length; i++) {
      if (i == excluded) continue;
      target -= weights[i];
      if (target < 0) return i;
    }
    return last; // Floating-point rounding cannot turn a valid draw into an absent candidate.
  }

  /** Exponential clocks produce weighted sampling without replacement in O(n log n). */
  private static ArrayList<Integer> remaining(double[] weights, int excluded, Random random) {
    ArrayList<Candidate> candidates = new ArrayList<>(weights.length);
    for (int i = 0; i < weights.length; i++) {
      if (i == excluded) continue;
      double clock = -Math.log1p(-random.nextDouble()) / weights[i];
      candidates.add(new Candidate(i, clock));
    }
    candidates.sort(Comparator.comparingDouble(candidate -> candidate.clock));
    ArrayList<Integer> result = new ArrayList<>(weights.length);
    for (Candidate candidate : candidates) result.add(candidate.index);
    return result;
  }

  private static final class Candidate {
    final int index;
    final double clock;

    Candidate(int index, double clock) {
      this.index = index;
      this.clock = clock;
    }
  }
}
