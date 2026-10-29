package io.mediamaster.app.media;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Supplies controlled incomplete index rows; never mutates the device's media provider. */
@RunWith(AndroidJUnit4.class)
public final class MediaIndexMetadataReuseTest {
  private static final String PATH = "/storage/emulated/0/Movies/metadata-reuse.mp4";
  private Context application, isolated;
  private File sandbox;
  private IncompleteVideoProvider provider;
  private final Set<String> preferenceNames = new HashSet<>();

  @Before
  public void prepare() {
    assumeTrue(Build.VERSION.SDK_INT >= 29);
    application = InstrumentationRegistry.getInstrumentation().getTargetContext();
    InstrumentationRegistry.getInstrumentation()
        .getUiAutomation()
        .grantRuntimePermission(
            application.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    String prefix = "metadata_reuse_" + UUID.randomUUID();
    sandbox = new File(application.getCacheDir(), prefix);
    assertTrue(sandbox.mkdirs());
    provider = new IncompleteVideoProvider();
    ProviderInfo info = new ProviderInfo();
    info.authority = "media";
    info.exported = true;
    provider.attachInfo(application, info);
    ContentResolver controlled = ContentResolver.wrap(provider);
    isolated =
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
          public ContentResolver getContentResolver() {
            return controlled;
          }

          @Override
          public SharedPreferences getSharedPreferences(String name, int mode) {
            String scoped = prefix + "_" + name;
            preferenceNames.add(scoped);
            return application.getSharedPreferences(scoped, mode);
          }
        };
  }

  @After
  public void cleanup() throws Exception {
    if (application != null) {
      for (String name : preferenceNames) application.deleteSharedPreferences(name);
      if (sandbox != null && sandbox.exists()) {
        assertTrue(
            sandbox
                .getCanonicalPath()
                .startsWith(application.getCacheDir().getCanonicalPath() + File.separator));
        File[] files = sandbox.listFiles();
        if (files != null) for (File file : files) assertTrue(file.delete());
        assertTrue(sandbox.delete());
      }
    }
  }

  @Test
  public void newlyIndexedRawFileKeepsKnownMetadataByPath() {
    assertReuse("file:" + PATH, "file://" + PATH, 4000, true);
  }

  @Test
  public void externalVolumeAliasChangeKeepsKnownMetadataByPath() {
    assertReuse("media:external:7", "content://media/external/file/7", 4000, true);
  }

  @Test
  public void changedFileDoesNotReuseStalePathMetadata() {
    assertReuse("file:" + PATH, "file://" + PATH, 3000, false);
  }

  @Test
  public void aFailedCursorCannotPublishBothOldAndPartiallyReadRenamedRows() {
    String volume = MediaStore.getExternalVolumeNames(application).iterator().next();
    VideoRepository repository = new VideoRepository(isolated);
    repository.saveCache(
        Collections.singletonList(
            new VideoItem(
                "media:" + volume + ":7",
                "old.mp4",
                "content://media/" + volume + "/file/7",
                "/videos/old.mp4",
                1234,
                54321,
                4000,
                1000,
                360,
                640)));
    provider.failAfterFirstRow = true;
    repository = new VideoRepository(isolated);
    List<VideoItem> result = repository.refreshMediaIndex();
    assertEquals(1, result.size());
    assertEquals("old.mp4", result.get(0).name);
    assertFalse(repository.getWarnings().isEmpty());
  }

  private void assertReuse(String id, String uri, long modified, boolean expectedReuse) {
    VideoRepository repository = new VideoRepository(isolated);
    repository.saveCache(
        Collections.singletonList(
            new VideoItem(
                id, "metadata-reuse.mp4", uri, PATH, 1234, 54321, modified, 1000, 360, 640)));
    // Recreate the repository so this also checks cache restoration of the path lookup.
    List<VideoItem> refreshed = new VideoRepository(isolated).refreshMediaIndex();
    assertEquals(1, refreshed.size());
    VideoItem item = refreshed.get(0);
    assertTrue(item.id.startsWith("media:"));
    assertEquals(PATH, item.path);
    assertEquals(expectedReuse ? 54321 : 0, item.durationMs);
    assertEquals(expectedReuse ? 360 : 0, item.width);
    assertEquals(expectedReuse ? 640 : 0, item.height);
  }

  private static final class IncompleteVideoProvider extends ContentProvider {
    boolean failAfterFirstRow;

    @Override
    public boolean onCreate() {
      return true;
    }

    @Override
    public String getType(Uri uri) {
      return "video/mp4";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Cursor query(
        Uri uri, String[] projection, String selection, String[] args, String order) {
      MatrixCursor cursor =
          new MatrixCursor(projection) {
            @Override
            public boolean onMove(int oldPosition, int newPosition) {
              if (failAfterFirstRow && newPosition > 0)
                throw new IllegalStateException("Controlled cursor interruption");
              return super.onMove(oldPosition, newPosition);
            }
          };
      Object[] row = new Object[projection.length];
      for (int i = 0; i < projection.length; i++) {
        switch (projection[i]) {
          case "_id":
            row[i] = 7L;
            break;
          case "_display_name":
            row[i] = "metadata-reuse.mp4";
            break;
          case "_data":
            row[i] = PATH;
            break;
          case "_size":
            row[i] = 1234L;
            break;
          case "date_modified":
            row[i] = 4L;
            break;
          case "date_added":
            row[i] = 1L;
            break;
          default:
            row[i] = 0L;
        }
      }
      cursor.addRow(row);
      if (failAfterFirstRow) cursor.addRow(row.clone());
      return cursor;
    }
  }
}
