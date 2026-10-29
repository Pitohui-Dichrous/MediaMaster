package io.mediamaster.app.media;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class MediaMutationOverlayTest {
  private static final Set<String> VOLUMES =
      new HashSet<>(Collections.singletonList("external_primary"));

  @Test
  public void renamedItemDoesNotRevertWhileTheIndexStillHasTheOldName() {
    MediaMutationOverlay overlay = new MediaMutationOverlay();
    VideoItem old = media("1", "old.mp4"), renamed = media("1", "new.mp4");
    overlay.remember(old, renamed, 100);
    List<VideoItem> result = overlay.apply(Arrays.asList(old), Arrays.asList(old), VOLUMES, 101);
    assertEquals(1, result.size());
    assertEquals("new.mp4", result.get(0).name);
    assertEquals(1, overlay.pending.size());
  }

  @Test
  public void confirmingTheRenameAllowsLaterExternalNamesToAppear() {
    MediaMutationOverlay overlay = new MediaMutationOverlay();
    VideoItem old = media("1", "old.mp4"), renamed = media("1", "new.mp4");
    overlay.remember(old, renamed, 100);
    assertEquals("new.mp4", apply(overlay, renamed, 101).get(0).name);
    assertTrue(overlay.pending.isEmpty());
    assertEquals("external.mp4", apply(overlay, media("1", "external.mp4"), 102).get(0).name);
  }

  @Test
  public void aLaterExternalRenameCanSupersedeTheLocalRenameWithoutWaitingForExpiry() {
    MediaMutationOverlay overlay = new MediaMutationOverlay();
    overlay.remember(media("1", "old.mp4"), media("1", "new.mp4"), 100);
    assertEquals("external.mp4", apply(overlay, media("1", "external.mp4"), 101).get(0).name);
    assertTrue(overlay.pending.isEmpty());
  }

  @Test
  public void aChainedRenameRecognizesEveryUnconfirmedOldState() {
    MediaMutationOverlay overlay = new MediaMutationOverlay();
    VideoItem a = media("1", "a.mp4"), b = media("1", "b.mp4"), c = media("1", "c.mp4");
    overlay.remember(a, b, 100);
    overlay.remember(b, c, 101);
    assertEquals("c.mp4", apply(overlay, a, 102).get(0).name);
    assertEquals("c.mp4", apply(overlay, b, 103).get(0).name);
    assertEquals(1, overlay.pending.size());
    assertEquals("c.mp4", apply(overlay, c, 104).get(0).name);
    assertTrue(overlay.pending.isEmpty());
    assertEquals("d.mp4", apply(overlay, media("1", "d.mp4"), 105).get(0).name);
  }

  @Test
  public void deletingAfterARenameSuppressesBothOldIndexNames() {
    MediaMutationOverlay overlay = new MediaMutationOverlay();
    VideoItem a = media("1", "a.mp4"), b = media("1", "b.mp4");
    overlay.remember(a, b, 100);
    overlay.remember(b, null, 101);
    assertTrue(apply(overlay, a, 102).isEmpty());
    assertTrue(apply(overlay, b, 103).isEmpty());
    assertTrue(
        overlay.apply(Collections.emptyList(), Collections.emptyList(), VOLUMES, 104).isEmpty());
    assertTrue(overlay.pending.isEmpty());
    assertEquals("b.mp4", apply(overlay, media("2", "b.mp4"), 105).get(0).name);
  }

  @Test
  public void aFailedVolumeReadDoesNotConfirmADeletion() {
    MediaMutationOverlay overlay = new MediaMutationOverlay();
    VideoItem old = media("1", "old.mp4");
    overlay.remember(old, null, 100);
    overlay.apply(Collections.emptyList(), Collections.emptyList(), Collections.emptySet(), 101);
    assertEquals(1, overlay.pending.size());
    assertTrue(apply(overlay, old, 102).isEmpty());
  }

  @Test
  public void aNewMediaIdAtADeletedPathIsNotMistakenForTheDeletedRow() {
    MediaMutationOverlay overlay = new MediaMutationOverlay();
    overlay.remember(media("1", "same.mp4"), null, 100);
    assertEquals("media:external_primary:2", apply(overlay, media("2", "same.mp4"), 101).get(0).id);
    assertTrue(overlay.pending.isEmpty());
  }

  @Test
  public void rawFileRenameSuppressesTheOldIndexedPathUntilTheNewPathIsIndexed() {
    MediaMutationOverlay overlay = new MediaMutationOverlay();
    VideoItem old = raw("old.mp4"), renamed = raw("new.mp4");
    overlay.remember(old, renamed, 100);
    VideoItem oldIndex = media("1", "old.mp4"), newIndex = media("2", "new.mp4");
    assertEquals(
        "file:/videos/new.mp4",
        overlay
            .apply(Arrays.asList(oldIndex), Arrays.asList(oldIndex, renamed), VOLUMES, 101)
            .get(0)
            .id);
    List<VideoItem> confirmed =
        overlay.apply(Arrays.asList(newIndex), Arrays.asList(newIndex), VOLUMES, 102);
    assertEquals("media:external_primary:2", confirmed.get(0).id);
    assertTrue(overlay.pending.isEmpty());
  }

  @Test
  public void unrelatedRowsAreNeverRemovedByAMutation() {
    MediaMutationOverlay overlay = new MediaMutationOverlay();
    VideoItem old = media("1", "old.mp4"), unrelated = media("2", "other.mp4");
    overlay.remember(old, null, 100);
    List<VideoItem> result =
        overlay.apply(Arrays.asList(old, unrelated), Arrays.asList(old, unrelated), VOLUMES, 101);
    assertEquals(1, result.size());
    assertEquals("other.mp4", result.get(0).name);
  }

  @Test
  public void removingAnExtraSourceDoesNotResurrectItsUnconfirmedRenamedItem() {
    MediaMutationOverlay overlay = new MediaMutationOverlay();
    overlay.remember(raw("old.mp4"), raw("new.mp4"), 100);
    VideoItem stale = media("1", "old.mp4");
    assertTrue(overlay.apply(Arrays.asList(stale), Arrays.asList(stale), VOLUMES, 101).isEmpty());
  }

  @Test
  public void abandonedIndexProtectionExpiresRatherThanMaskingChangesForever() {
    MediaMutationOverlay overlay = new MediaMutationOverlay();
    VideoItem old = media("1", "old.mp4");
    overlay.remember(old, media("1", "new.mp4"), 100);
    assertEquals("old.mp4", apply(overlay, old, 100 + MediaMutationOverlay.MAX_AGE_MS).get(0).name);
    assertTrue(overlay.pending.isEmpty());
  }

  private static List<VideoItem> apply(MediaMutationOverlay overlay, VideoItem item, long now) {
    return overlay.apply(Arrays.asList(item), Arrays.asList(item), VOLUMES, now);
  }

  private static VideoItem media(String id, String name) {
    return new VideoItem(
        "media:external_primary:" + id,
        name,
        "content://media/external_primary/file/" + id,
        "/videos/" + name,
        100,
        1000,
        1,
        1,
        100,
        100);
  }

  private static VideoItem raw(String name) {
    return new VideoItem(
        "file:/videos/" + name,
        name,
        "file:///videos/" + name,
        "/videos/" + name,
        100,
        1000,
        1,
        1,
        100,
        100);
  }
}
