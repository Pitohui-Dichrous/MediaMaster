package io.mediamaster.app.media;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Functional index reconciliation using app-owned copies and an isolated repository cache. */
@RunWith(AndroidJUnit4.class)
public final class MediaIndexSyncTest {
  private Context application;
  private Context isolatedContext;
  private ContentResolver resolver;
  private VideoRepository repository;
  private File sandbox;
  private String prefix;
  private final List<Uri> inserted = new ArrayList<>();
  private final Set<String> preferenceFiles = new HashSet<>();

  @Before
  public void prepare() {
    application = InstrumentationRegistry.getInstrumentation().getTargetContext();
    InstrumentationRegistry.getInstrumentation()
        .getUiAutomation()
        .grantRuntimePermission(
            application.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    if (Build.VERSION.SDK_INT <= 28)
      InstrumentationRegistry.getInstrumentation()
          .getUiAutomation()
          .grantRuntimePermission(
              application.getPackageName(), Manifest.permission.WRITE_EXTERNAL_STORAGE);
    resolver = application.getContentResolver();
    prefix = "MediaMaster_QA_Index_" + UUID.randomUUID();
    sandbox = new File(application.getCacheDir(), prefix);
    assertTrue(sandbox.mkdirs());
    isolatedContext =
        new ContextWrapper(application) {
          @Override
          public Context getApplicationContext() {
            return this;
          }

          @Override
          public File getFilesDir() {
            return sandbox;
          }

          @Override
          public SharedPreferences getSharedPreferences(String name, int mode) {
            String scoped = prefix + "_" + name;
            preferenceFiles.add(scoped);
            return application.getSharedPreferences(scoped, mode);
          }
        };
    repository = new VideoRepository(isolatedContext);
  }

  @After
  public void cleanup() throws Exception {
    if (resolver != null)
      for (Uri uri : inserted) {
        // Every URI here was returned by this test's own insert, never a source fixture URI.
        resolver.delete(uri, null, null);
      }
    if (application != null)
      for (String name : preferenceFiles) {
        application.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
        application.deleteSharedPreferences(name);
      }
    if (sandbox != null && sandbox.isDirectory()) {
      assertTrue(
          sandbox
              .getCanonicalPath()
              .startsWith(application.getCacheDir().getCanonicalPath() + File.separator));
      removeSandbox(sandbox);
    }
  }

  @Test
  public void pendingAndTrashedRowsAreExcludedUntilACompletedVideoIsPublished() throws Exception {
    assumeTrue(Build.VERSION.SDK_INT >= 29);
    String name = prefix + ".mp4";
    Uri owned = insertCopy(name, true);
    assertNull(
        "Pending browser-style downloads stay hidden", find(repository.refreshMediaIndex(), name));

    publish(owned);
    VideoItem published = find(repository.refreshMediaIndex(), name);
    assertNotNull("A completed publication is discovered by an index-only refresh", published);
    assertTrue(published.size > 0);
    assertTrue(published.id.startsWith("media:"));

    if (Build.VERSION.SDK_INT >= 30) {
      ContentValues trashed = new ContentValues();
      trashed.put(MediaStore.MediaColumns.IS_TRASHED, 1);
      assertEquals(1, resolver.update(owned, trashed, null, null));
      assertNull(
          "Trashed videos do not remain in the current index",
          find(repository.refreshMediaIndex(), name));
    }
  }

  @Test
  public void anIndexRefreshKeepsDeepOnlyFilesButDoesNotTraverseTheirFolders() throws Exception {
    File folder = new File(sandbox, "unindexed-videos");
    assertTrue(folder.mkdir());
    File first = new File(folder, prefix + "_one.mp4");
    copyFixture(first);
    repository.addPath(folder.getAbsolutePath());

    assertNull(
        "Adding a source alone does not make the quick route traverse it",
        find(repository.refreshMediaIndex(), first.getName()));
    VideoItem discovered = find(repository.scanFolders(), first.getName());
    assertNotNull("The explicit configured-folder scan discovers the file", discovered);
    assertTrue(discovered.id.startsWith("file:"));
    repository.saveCache(repository.snapshot());

    File second = new File(folder, prefix + "_two.mp4");
    copyFixture(second);
    repository = new VideoRepository(isolatedContext);
    List<VideoItem> quick = repository.refreshMediaIndex();
    assertNotNull(
        "Quick reconciliation preserves the earlier deep-only file", find(quick, first.getName()));
    assertNull(
        "Quick reconciliation does not discover a new unindexed sibling",
        find(quick, second.getName()));

    List<VideoItem> explicit = repository.scanFolders();
    assertNotNull(find(explicit, first.getName()));
    assertNotNull(
        "The user-requested folder scan still discovers new siblings",
        find(explicit, second.getName()));
    repository.removeSource(folder.getAbsolutePath());
    assertNull(
        "Removing a source prunes its private first entry",
        find(repository.snapshot(), first.getName()));
    assertNull(
        "Removing a source prunes its private second entry",
        find(repository.snapshot(), second.getName()));
  }

  @Test
  public void anUnconfirmedRenameSurvivesOldRowsThenAllowsAConfirmedExternalRename()
      throws Exception {
    String originalName = prefix + "_old.mp4";
    Uri owned = insertCopy(originalName, false);
    VideoItem original = find(repository.refreshMediaIndex(), originalName);
    assertNotNull(original);
    String firstRename = prefix + "_local.mp4";
    VideoItem expected = renamed(original, firstRename);

    // Model the async scanner window: the local operation is known, but the index still has old
    // data.
    repository.rememberMutation(original, expected);
    assertNotNull(find(repository.snapshot(), firstRename));
    assertNotNull(
        "The immediate name is also persisted", find(repository.loadCache(), firstRename));
    List<VideoItem> stale = repository.refreshMediaIndex();
    assertNotNull(
        "A stale system row cannot roll back a completed local rename", find(stale, firstRename));
    assertNull(find(stale, originalName));

    updateName(owned, firstRename);
    assertNotNull(find(repository.refreshMediaIndex(), firstRename));
    String external = prefix + "_external.mp4";
    updateName(owned, external);
    List<VideoItem> changedAgain = repository.refreshMediaIndex();
    assertNotNull(
        "Once acknowledged, a later external rename is not masked", find(changedAgain, external));
    assertNull(find(changedAgain, firstRename));
  }

  @Test
  public void aLocalDeletionIsNotResurrectedByAnOldIndexRow() throws Exception {
    String name = prefix + "_delete.mp4";
    Uri owned = insertCopy(name, false);
    VideoItem original = find(repository.refreshMediaIndex(), name);
    assertNotNull(original);

    repository.rememberMutation(original, null);
    assertNull(find(repository.snapshot(), name));
    assertNull("The deletion is immediately persisted", find(repository.loadCache(), name));
    assertNull(
        "An unacknowledged old row cannot bring back a locally removed video",
        find(repository.refreshMediaIndex(), name));

    assertEquals(1, resolver.delete(owned, null, null));
    inserted.remove(owned);
    assertNull(find(repository.refreshMediaIndex(), name));
    Uri replacement = insertCopy(name, false);
    assertNotNull(replacement);
    assertNotNull(
        "A later newly created video at this name is not permanently suppressed",
        find(repository.refreshMediaIndex(), name));
  }

  @Test
  public void anUnchangedIndexSnapshotDoesNotRewriteTheCache() throws Exception {
    Uri owned = insertCopy(prefix + "_stable.mp4", false);
    assertNotNull(owned);
    List<VideoItem> snapshot = repository.refreshMediaIndex();
    repository.saveCache(snapshot);
    File cache = new File(sandbox, "video-library-v4.json");
    long marker = 1_600_000_000_000L;
    assertTrue(cache.isFile());
    assertTrue(cache.setLastModified(marker));

    repository.saveCache(new ArrayList<>(snapshot));
    assertEquals("No changes require no second JSON write", marker, cache.lastModified());
  }

  private Uri insertCopy(String name, boolean pending) throws Exception {
    ContentValues values = new ContentValues();
    values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
    values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
    if (Build.VERSION.SDK_INT >= 29) {
      values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MediaMasterQA/IndexSync/");
      values.put(MediaStore.Video.Media.IS_PENDING, 1);
    }
    Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
    assertNotNull("Creates a uniquely named app-owned video", uri);
    inserted.add(uri);
    try (InputStream input = resolver.openInputStream(fixtureUri());
        OutputStream output = resolver.openOutputStream(uri)) {
      assertNotNull(input);
      assertNotNull(output);
      copy(input, output);
    }
    if (!pending) publish(uri);
    return uri;
  }

  private void publish(Uri uri) {
    if (Build.VERSION.SDK_INT >= 29) {
      ContentValues values = new ContentValues();
      values.put(MediaStore.Video.Media.IS_PENDING, 0);
      assertEquals(1, resolver.update(uri, values, null, null));
    }
  }

  private void updateName(Uri uri, String name) {
    ContentValues changes = new ContentValues();
    changes.put(MediaStore.Video.Media.DISPLAY_NAME, name);
    assertEquals(1, resolver.update(uri, changes, null, null));
  }

  private Uri fixtureUri() {
    try (Cursor cursor =
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            new String[] {MediaStore.Video.Media._ID},
            MediaStore.Video.Media.DISPLAY_NAME + "=?",
            new String[] {"01_Aurora.mp4"},
            null)) {
      assertNotNull("The read-only source fixture is installed", cursor);
      assertTrue(cursor.moveToFirst());
      return Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getString(0));
    }
  }

  private void copyFixture(File file) throws Exception {
    try (InputStream input = resolver.openInputStream(fixtureUri());
        OutputStream output = new FileOutputStream(file)) {
      assertNotNull(input);
      copy(input, output);
    }
  }

  private static void copy(InputStream input, OutputStream output) throws Exception {
    byte[] bytes = new byte[16384];
    int read;
    while ((read = input.read(bytes)) != -1) output.write(bytes, 0, read);
  }

  private static VideoItem renamed(VideoItem original, String name) {
    String path =
        original.path.isEmpty()
            ? ""
            : new File(new File(original.path).getParentFile(), name).getAbsolutePath();
    return new VideoItem(
        original.id,
        name,
        original.uri,
        path,
        original.size,
        original.durationMs,
        original.modifiedMs,
        original.addedMs,
        original.width,
        original.height);
  }

  private static VideoItem find(List<VideoItem> items, String name) {
    for (VideoItem item : items) if (name.equals(item.name)) return item;
    return null;
  }

  private static void removeSandbox(File directory) {
    File[] children = directory.listFiles();
    if (children != null)
      for (File child : children) {
        if (child.isDirectory()) removeSandbox(child);
        else assertTrue("Clean up a test-created file", child.delete());
      }
    assertTrue("Clean up the isolated test directory", directory.delete());
  }
}
