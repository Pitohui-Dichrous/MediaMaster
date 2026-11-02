package io.mediamaster.app.player;

import io.mediamaster.app.media.VideoItem;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** One reversible contribution per video in one playback window. Never serialized to disk. */
public final class PlaybackVoteSession {
  private final PlaybackStatsStore store;
  private final PlaybackExtraFeedbackSession extras;
  private final Map<String, PlaybackStatsStore.VoteContribution> votes = new HashMap<>();
  private final Map<String, String> aliases = new LinkedHashMap<>(16, .75f, true);
  private Object generation;
  private Object identityGeneration;

  public PlaybackVoteSession(PlaybackStatsStore store) {
    this(store, new PlaybackExtraFeedbackSession(store, -1));
  }

  public PlaybackVoteSession(PlaybackStatsStore store, PlaybackExtraFeedbackSession extras) {
    if (extras == null || !extras.belongsTo(store))
      throw new IllegalArgumentException("Invalid scope");
    this.store = store;
    this.extras = extras;
    generation = store.voteGeneration();
  }

  public synchronized int vote(VideoItem item) {
    refreshGeneration();
    String key = resolve(PlaybackStatsStore.key(item));
    PlaybackStatsStore.VoteContribution contribution = votes.get(key);
    if (contribution == null) return 0;
    if (!store.isCurrent(contribution)) {
      votes.remove(key);
      return 0;
    }
    return contribution.vote;
  }

  /** Repeating a state is idempotent; changing it replaces only this window's contribution. */
  public synchronized boolean setVote(VideoItem item, int requested) {
    if (requested < -1 || requested > 1) throw new IllegalArgumentException("Invalid vote");
    // Lock shared inference before the store. No store callback ever locks a session, so separate
    // playback windows can safely cancel the same search-round contribution atomically.
    synchronized (extras) {
      return store.mutateVotes(
          () -> {
            if (!store.canAcceptVote()) return false;
            int previous = vote(item);
            if (!extras.onManualVote(item, requested)) return false;
            if (previous == requested && votes.containsKey(resolve(PlaybackStatsStore.key(item))))
              return true;
            return apply(item, requested);
          });
    }
  }

  /**
   * Add hidden feedback in its own scope. It never changes the visible button state or spends this
   * window's manual vote. Opposite manual intent can withdraw it even after leaving and revisiting.
   *
   * @return true only when a new contribution was accepted; false is a silent no-op/retry later.
   */
  public synchronized boolean applyAutomaticVote(VideoItem item, int requested) {
    return applyAutomaticVote(item, requested, store.voteGeneration());
  }

  /** Reject an inferred event superseded by a reset, including a reset racing this call. */
  public synchronized boolean applyAutomaticVote(
      VideoItem item, int requested, Object expectedGeneration) {
    return extras.apply(item, requested, expectedGeneration);
  }

  private boolean apply(VideoItem item, int requested) {
    return apply(item, requested, null);
  }

  private boolean apply(VideoItem item, int requested, Object expectedGeneration) {
    String original = PlaybackStatsStore.key(item);
    String key = resolve(original);
    PlaybackStatsStore.VoteContribution applied =
        store.applyVote(key, votes.get(key), requested, expectedGeneration);
    if (applied == null) return false;
    if (applied.generation != generation) {
      votes.clear();
      aliases.clear();
      generation = applied.generation;
    }
    votes.remove(key);
    votes.put(applied.key, applied);
    if (!original.equals(applied.key)) aliases.put(original, applied.key);
    trimAliases();
    return true;
  }

  private void refreshGeneration() {
    Object current = store.voteGeneration();
    if (current != generation) {
      votes.clear();
      generation = current;
    }
    identityGeneration = store.refreshSessionVotes(votes, aliases, identityGeneration);
    trimAliases();
  }

  private String resolve(String key) {
    return aliases.getOrDefault(key, key);
  }

  private void trimAliases() {
    while (aliases.size() > 50_000) aliases.remove(aliases.keySet().iterator().next());
  }
}
