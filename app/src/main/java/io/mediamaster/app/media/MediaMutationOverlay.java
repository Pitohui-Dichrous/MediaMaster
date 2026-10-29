package io.mediamaster.app.media;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Keeps a completed local edit visible while the platform's asynchronous index catches up. */
final class MediaMutationOverlay {
  static final long MAX_AGE_MS = 30_000;

  static final class Pending {
    final VideoItem original, replacement;
    final List<VideoItem> staleVersions;
    final long expiresAt;

    Pending(VideoItem original, VideoItem replacement, long expiresAt) {
      this(java.util.Collections.singletonList(original), replacement, expiresAt);
    }

    Pending(List<VideoItem> staleVersions, VideoItem replacement, long expiresAt) {
      this.staleVersions = new ArrayList<>(staleVersions);
      this.original = staleVersions.get(0);
      this.replacement = replacement;
      this.expiresAt = expiresAt;
    }
  }

  final List<Pending> pending = new ArrayList<>();

  void remember(VideoItem original, VideoItem replacement, long now) {
    List<VideoItem> stale = new ArrayList<>();
    for (Iterator<Pending> iterator = pending.iterator(); iterator.hasNext(); ) {
      Pending edit = iterator.next();
      if (matchesAny(edit.staleVersions, original)
          || (edit.replacement != null && sameIdentity(edit.replacement, original))) {
        stale.addAll(edit.staleVersions);
        iterator.remove();
      }
    }
    stale.add(original);
    pending.add(new Pending(stale, replacement, now + MAX_AGE_MS));
  }

  List<VideoItem> apply(
      List<VideoItem> indexed, List<VideoItem> combined, Set<String> completedVolumes, long now) {
    List<VideoItem> result = new ArrayList<>(combined);
    for (Iterator<Pending> iterator = pending.iterator(); iterator.hasNext(); ) {
      Pending edit = iterator.next();
      if (now >= edit.expiresAt || confirmed(edit, indexed, completedVolumes)) {
        iterator.remove();
        continue;
      }
      boolean keepReplacement =
          edit.replacement != null
              && (edit.replacement.id.startsWith("media:") || matchesAny(result, edit.replacement));
      result.removeIf(
          item ->
              matchesAny(edit.staleVersions, item)
                  || (edit.replacement != null && sameIdentity(item, edit.replacement)));
      if (keepReplacement) result.add(edit.replacement);
    }
    return result;
  }

  private static boolean confirmed(Pending edit, List<VideoItem> indexed, Set<String> volumes) {
    String volume = mediaVolume(edit.original);
    if (volume != null ? !volumes.contains(volume) : volumes.isEmpty()) return false;
    boolean oldPresent = false, replacementPresent = false;
    for (VideoItem item : indexed) {
      if (matchesAny(edit.staleVersions, item)) {
        if (edit.replacement == null) oldPresent = true;
        else if (isKnownOldState(edit.staleVersions, item)) oldPresent = true;
        else if (volume != null) replacementPresent = true;
      }
      if (edit.replacement != null
          && sameIdentity(item, edit.replacement)
          && item.name.equals(edit.replacement.name)
          && (item.path.isEmpty()
              || edit.replacement.path.isEmpty()
              || item.path.equals(edit.replacement.path))) replacementPresent = true;
    }
    return !oldPresent && (edit.replacement == null || replacementPresent);
  }

  private static boolean matchesAny(List<VideoItem> versions, VideoItem item) {
    for (VideoItem version : versions) if (sameIdentity(version, item)) return true;
    return false;
  }

  private static boolean isKnownOldState(List<VideoItem> versions, VideoItem item) {
    for (VideoItem version : versions)
      if (sameIdentity(version, item)
          && item.name.equals(version.name)
          && item.path.equals(version.path)) return true;
    return false;
  }

  static boolean sameIdentity(VideoItem first, VideoItem second) {
    // A newly inserted row can reuse a deleted file's path; its different media ID is not a
    // stale version of the deleted record. Raw/SAF entries still need path-based promotion.
    if (first.id.startsWith("media:") && second.id.startsWith("media:"))
      return first.id.equals(second.id);
    return (!first.id.isEmpty() && first.id.equals(second.id))
        || (!first.uri.isEmpty() && first.uri.equals(second.uri))
        || (!first.path.isEmpty() && first.path.equals(second.path));
  }

  static String mediaVolume(VideoItem item) {
    if (!item.id.startsWith("media:")) return null;
    int end = item.id.lastIndexOf(':');
    return end > 6 ? item.id.substring(6, end) : null;
  }
}
