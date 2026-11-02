package io.mediamaster.app.player;

/** Converts one entry's qualified viewing into one statistical action at each boundary. */
public final class PlaybackEntryFeedbackPolicy {
  public static final long GLANCE_MIN_MS = 500;
  public static final long QUICK_SKIP_MS = 1500;

  public enum Action {
    NONE,
    RECORD_PLAY,
    LIKE,
    DISLIKE
  }

  private final PlaybackWatchTracker tracker;
  private boolean fromSearch, departed;

  public PlaybackEntryFeedbackPolicy(PlaybackWatchTracker tracker) {
    if (tracker == null) throw new IllegalArgumentException("A watch tracker is required");
    this.tracker = tracker;
  }

  /** Every entry inherits its playback window's source; search remains special when navigating. */
  public void start(boolean fromSearch) {
    restore(fromSearch, 0, false, false);
  }

  /** A restored entry is stopped until its replacement starts actual playback. */
  public void restore(
      boolean fromSearch, long watchedMs, boolean qualifiedRecorded, boolean departed) {
    this.fromSearch = fromSearch;
    this.departed = departed;
    tracker.restore(watchedMs, qualifiedRecorded);
  }

  /** Pauses, loading, backgrounding and rotation settle viewing without ending the entry. */
  public Action update(boolean eligible, long elapsedRealtimeMs) {
    if (departed || !tracker.update(eligible, elapsedRealtimeMs)) return Action.NONE;
    return fromSearch ? Action.LIKE : Action.RECORD_PLAY;
  }

  /**
   * Call only when the current entry really leaves, never just because playback is suspended.
   * Natural completion and error recovery can qualify viewing but cannot create a quick-skip vote.
   * The separate extra-feedback scope enforces manual overrides and per-video limits.
   */
  public Action depart(boolean userInitiated, long elapsedRealtimeMs) {
    if (departed) return Action.NONE;
    Action settled = update(false, elapsedRealtimeMs);
    departed = true;
    if (settled != Action.NONE) return settled;
    if (userInitiated
        && !fromSearch
        && !tracker.recorded()
        && tracker.watchedMs() > GLANCE_MIN_MS
        && tracker.watchedMs() < QUICK_SKIP_MS) return Action.DISLIKE;
    return Action.NONE;
  }

  public boolean fromSearch() {
    return fromSearch;
  }

  public boolean departed() {
    return departed;
  }
}
