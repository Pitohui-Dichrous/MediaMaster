package io.mediamaster.app.player;

import io.mediamaster.app.media.VideoItem;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** One reversible contribution per video in one playback window. Never serialized to disk. */
public final class PlaybackVoteSession {
  private final PlaybackStatsStore store;
  private final Map<String, PlaybackStatsStore.VoteContribution> votes = new HashMap<>();
  private final Map<String, String> aliases = new LinkedHashMap<>(16, .75f, true);
  private Object generation;
  private Object identityGeneration;

  public PlaybackVoteSession(PlaybackStatsStore store) {
    this.store = store;
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
    if (!store.canAcceptVote()) return false;
    int previous = vote(item);
    // A zero contribution is also a deliberate cancellation. Retain its token so a later
    // automatic event cannot put back feedback that the user explicitly cleared.
    if (previous == requested && votes.containsKey(resolve(PlaybackStatsStore.key(item))))
      return true;
    return apply(item, requested);
  }

  /**
   * Apply at most one automatic decision in this window. Any existing choice, including a cancelled
   * one, takes precedence. Automatic feedback has the same reversible, decaying mass as a button
   * vote; it never creates a persistent selected-state flag.
   *
   * @return true only when a new contribution was accepted; false is a silent no-op/retry later.
   */
  public synchronized boolean applyAutomaticVote(VideoItem item, int requested) {
    return applyAutomaticVote(item, requested, store.voteGeneration());
  }

  /** Reject an inferred event superseded by a reset, including a reset racing this call. */
  public synchronized boolean applyAutomaticVote(
      VideoItem item, int requested, Object expectedGeneration) {
    if (requested != -1 && requested != 1)
      throw new IllegalArgumentException("Automatic vote must be like or dislike");
    if (expectedGeneration == null || expectedGeneration != store.voteGeneration()) return false;
    if (!store.canAcceptVote()) return false;
    vote(item); // Reconcile reset generations, removed records and owned rename aliases first.
    if (votes.containsKey(resolve(PlaybackStatsStore.key(item)))) return false;
    return apply(item, requested, expectedGeneration);
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
