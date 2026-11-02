package io.mediamaster.app.player;

import io.mediamaster.app.media.VideoItem;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Hidden, reversible inference owned by one playback window or one explicit search round. */
public final class PlaybackExtraFeedbackSession {
  private static final int MAX_ENTRIES = 50_000;
  private final PlaybackStatsStore store;
  private final int inferredVote;
  private final boolean enabled;
  private final Map<String, PlaybackStatsStore.VoteContribution> contributions = new HashMap<>();
  private final Map<String, String> aliases = new LinkedHashMap<>(16, .75f, true);
  private Object generation, identityGeneration;

  public PlaybackExtraFeedbackSession(PlaybackStatsStore store, int inferredVote) {
    this(store, inferredVote, true);
  }

  PlaybackExtraFeedbackSession(PlaybackStatsStore store, int inferredVote, boolean enabled) {
    if (inferredVote != -1 && inferredVote != 1)
      throw new IllegalArgumentException("Invalid inference");
    this.store = store;
    this.inferredVote = inferredVote;
    this.enabled = enabled;
    generation = store.voteGeneration();
  }

  boolean belongsTo(PlaybackStatsStore expected) {
    return store == expected;
  }

  synchronized boolean apply(VideoItem item, int requested, Object expectedGeneration) {
    if (requested != -1 && requested != 1) throw new IllegalArgumentException("Invalid inference");
    if (!enabled || requested != inferredVote) return false;
    return store.mutateVotes(
        () -> {
          if (expectedGeneration == null || expectedGeneration != store.voteGeneration())
            return false;
          refresh();
          String original = PlaybackStatsStore.key(item), key = resolve(original);
          // Do not evict a consumed marker: doing so would allow another inference in this round.
          // An unusually large round simply stops collecting new implicit feedback at the bound.
          if (contributions.containsKey(key) || contributions.size() >= MAX_ENTRIES) return false;
          PlaybackStatsStore.VoteContribution accepted =
              store.applyVote(key, null, requested, expectedGeneration);
          if (accepted == null) return false;
          remember(original, key, accepted);
          return true;
        });
  }

  /**
   * Opposite explicit intent withdraws the extra mass and permanently consumes this round's slot.
   */
  synchronized boolean onManualVote(VideoItem item, int requested) {
    if (!enabled || requested != -inferredVote) return true;
    return store.mutateVotes(
        () -> {
          refresh();
          String original = PlaybackStatsStore.key(item), key = resolve(original);
          PlaybackStatsStore.VoteContribution previous = contributions.get(key);
          if (previous != null && (!store.isCurrent(previous) || previous.vote == 0)) return true;
          if (previous == null && contributions.size() >= MAX_ENTRIES) return true;
          PlaybackStatsStore.VoteContribution cancelled =
              store.applyVote(key, previous, 0, generation);
          if (cancelled == null) return false;
          remember(original, key, cancelled);
          return true;
        });
  }

  private void refresh() {
    Object current = store.voteGeneration();
    if (generation != current) {
      contributions.clear();
      aliases.clear();
      generation = current;
      identityGeneration = null;
    }
    identityGeneration =
        store.refreshSessionVotes(contributions, aliases, identityGeneration, true);
    trimAliases();
  }

  private void remember(
      String original, String key, PlaybackStatsStore.VoteContribution contribution) {
    contribution.retainConsumed = true;
    contributions.remove(key);
    contributions.put(contribution.key, contribution);
    if (!original.equals(contribution.key)) aliases.put(original, contribution.key);
    trimAliases();
  }

  private String resolve(String key) {
    return aliases.getOrDefault(key, key);
  }

  private void trimAliases() {
    while (aliases.size() > MAX_ENTRIES) aliases.remove(aliases.keySet().iterator().next());
  }
}
