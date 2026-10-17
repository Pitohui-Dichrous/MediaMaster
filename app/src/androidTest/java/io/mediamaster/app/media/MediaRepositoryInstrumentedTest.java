package io.mediamaster.app.media;

import static org.junit.Assert.*;
import static org.junit.Assume.*;

import android.Manifest;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Uses the generated QA fixtures on the emulator; does not edit or delete videos. */
@RunWith(AndroidJUnit4.class)
public final class MediaRepositoryInstrumentedTest {
  @Test
  public void genericMediaUrisUseVideoCollectionForMutations() {
    assertEquals(
        "content://media/external_primary/video/media/31",
        VideoRepository.mutationUri(Uri.parse("content://media/external_primary/file/31"))
            .toString());
    assertEquals(
        "content://media/1234-5678/video/media/88",
        VideoRepository.mutationUri(Uri.parse("content://media/1234-5678/downloads/88"))
            .toString());
    Uri document =
        Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2Fexample.mp4");
    assertEquals(document, VideoRepository.mutationUri(document));
  }

  /**
   * Only the explicitly generated fixture is touched, and only a consent request may be created.
   */
  @Test
  public void foreignFixtureRequiresConsentBeforeRename() throws Exception {
    assumeTrue(Build.VERSION.SDK_INT >= 30);
    assumeFalse(Environment.isExternalStorageManager());
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    VideoRepository repository = new VideoRepository(context);
    VideoItem fixture = null;
    for (VideoItem item : repository.scan()) {
      if (item.name.equals("QA_Foreign_Consent.mp4")) fixture = item;
    }
    assumeNotNull(fixture);
    Uri video = VideoRepository.mutationUri(Uri.parse(fixture.uri));
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                video,
                new String[] {MediaStore.MediaColumns.OWNER_PACKAGE_NAME},
                null,
                null,
                null)) {
      assumeTrue(cursor != null && cursor.moveToFirst());
      assumeFalse(context.getPackageName().equals(cursor.getString(0)));
    }
    try {
      repository.rename(fixture, "QA_Foreign_Consent_Check.mp4");
      fail("A foreign video must request system write consent before rename");
    } catch (MutationConsentException consent) {
      assertNotNull(consent.intentSender);
      assertFalse(consent.operationCompletedByConsent);
    }
    assertTrue("The original file remains until consent", new File(fixture.path).isFile());
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(video, new String[] {MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
      assertTrue(cursor != null && cursor.moveToFirst());
      assertEquals(fixture.name, cursor.getString(0));
    }
  }

  @Test
  public void localFixturesHaveMetadataAndFiveSecondFrames() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    InstrumentationRegistry.getInstrumentation()
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    VideoRepository repository = new VideoRepository(context);
    List<VideoItem> items = repository.scan();
    VideoItem mp4 = null;
    for (VideoItem item : items) if (item.name.equals("01_Aurora.mp4")) mp4 = item;
    assumeNotNull(mp4);
    ThumbnailLoader loader = new ThumbnailLoader(context);
    try {
      Method extract = ThumbnailLoader.class.getDeclaredMethod("extract", VideoItem.class);
      extract.setAccessible(true);
      for (VideoItem item : items) {
        if (!item.name.equals("01_Aurora.mp4")
            && !item.name.equals("02_Horizon.mkv")
            && !item.name.equals("03_Evening.avi")
            && !item.name.equals("04_Portrait_Loop.mp4")) continue;
        Log.i(
            "MediaRepositoryTest",
            item.name
                + ": "
                + item.durationMs
                + " ms, "
                + item.width
                + "x"
                + item.height
                + ", "
                + item.uri);
        assertTrue(item.name + " duration", item.durationMs > 0);
        assertTrue(item.name + " dimensions", item.width > 0 && item.height > 0);
        Bitmap frame = (Bitmap) extract.invoke(loader, item);
        assertNotNull(item.name + " five-second thumbnail", frame);
        assertTrue(frame.getWidth() > 0 && frame.getHeight() > 0);
        Log.i(
            "MediaRepositoryTest",
            item.name + " frame " + frame.getWidth() + "x" + frame.getHeight());
        frame.recycle();
      }
    } finally {
      loader.close();
    }
  }
}
