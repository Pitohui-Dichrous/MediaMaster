package io.mediamaster.app.player;

import java.util.LinkedHashMap;
import java.util.UUID;

/** Small process-local registry; selected button states are never stored in a search round. */
public final class SearchFeedbackRounds {
  private static final int MAX_ROUNDS = 8;
  private static final LinkedHashMap<String, PlaybackExtraFeedbackSession> ROUNDS =
      new LinkedHashMap<>(8, .75f, true);

  private SearchFeedbackRounds() {}

  public static synchronized String create(PlaybackStatsStore store) {
    String id = UUID.randomUUID().toString();
    ROUNDS.put(id, new PlaybackExtraFeedbackSession(store, 1));
    while (ROUNDS.size() > MAX_ROUNDS) ROUNDS.remove(ROUNDS.keySet().iterator().next());
    return id;
  }

  public static synchronized PlaybackExtraFeedbackSession resolve(
      PlaybackStatsStore store, String id) {
    PlaybackExtraFeedbackSession found = ROUNDS.get(id);
    if (found != null && found.belongsTo(store)) return found;
    // A restored Activity can outlive this process-local ledger. Refuse additional implicit likes
    // for an unknown old round instead of silently granting duplicates after process recreation.
    return new PlaybackExtraFeedbackSession(store, 1, false);
  }

  public static synchronized void forget(String id) {
    ROUNDS.remove(id);
  }
}
