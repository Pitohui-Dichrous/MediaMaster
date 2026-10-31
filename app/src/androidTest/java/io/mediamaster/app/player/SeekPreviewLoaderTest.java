package io.mediamaster.app.player;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.mediamaster.app.media.VideoItem;
import io.mediamaster.app.media.VideoRepository;
import io.mediamaster.app.media.VlcFrameExtractor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real decoded frames exercise seek timing, request conflation and lifecycle cancellation. */
@RunWith(AndroidJUnit4.class)
public final class SeekPreviewLoaderTest {
  @Test
  public void differentMp4PositionsHaveDifferentSmallFramesOnMainThread() throws Exception {
    Context context = contextWithPermission();
    VideoItem video = fixture(context, "Aurora", "mp4");
    assumeNotNull(video);
    try (SeekPreviewLoader loader = new SeekPreviewLoader(context)) {
      Bitmap first = frame(loader, video, 1000);
      Bitmap later = frame(loader, video, 5000);
      assertNotNull("A frame at one second", first);
      assertNotNull("A frame at five seconds", later);
      assertTrue(first.getWidth() <= 256 && first.getHeight() <= 144);
      assertTrue(later.getWidth() <= 256 && later.getHeight() <= 144);
      assertTrue("The preview follows the requested position", differ(first, later));
    }
  }

  @Test
  public void onlyLatestRequestSurvivesAndResetCancelsDelivery() throws Exception {
    Context context = contextWithPermission();
    VideoItem video = fixture(context, "Aurora", "mp4");
    assumeNotNull(video);
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    try (SeekPreviewLoader loader = new SeekPreviewLoader(context)) {
      AtomicInteger obsolete = new AtomicInteger();
      CountDownLatch latest = new CountDownLatch(1);
      AtomicReference<Bitmap> bitmap = new AtomicReference<>();
      instrumentation.runOnMainSync(
          () -> {
            for (int i = 0; i < 30; i++)
              loader.request(video, i * 100L, (image, position) -> obsolete.incrementAndGet());
            loader.request(
                video,
                5000,
                (image, position) -> {
                  assertSame(Looper.getMainLooper(), Looper.myLooper());
                  assertEquals(5000L, position);
                  bitmap.set(image);
                  latest.countDown();
                });
          });
      assertTrue(
          "Latest position delivered without a queue of old decodes",
          latest.await(8, TimeUnit.SECONDS));
      assertNotNull(bitmap.get());
      assertEquals("Obsolete finger movements never overwrite the preview", 0, obsolete.get());

      instrumentation.runOnMainSync(
          () -> {
            loader.request(video, 2000, (image, position) -> obsolete.incrementAndGet());
            loader.reset();
          });
      Thread.sleep(350);
      assertEquals("Reset suppresses queued and in-flight results", 0, obsolete.get());
      assertNotNull("A reset loader remains reusable", frame(loader, video, 3000));
    }
  }

  @Test
  public void sustainedSixtyHertzMovesProduceFramesBeforeTheFingerStops() throws Exception {
    Context context = contextWithPermission();
    VideoItem video = fixture(context, "Aurora", "mp4");
    assumeNotNull(video);
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    AtomicInteger whileMoving = new AtomicInteger();
    java.util.concurrent.atomic.AtomicBoolean moving =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    java.util.concurrent.atomic.AtomicLong firstFrameAt =
        new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);
    long start = SystemClock.elapsedRealtime();
    try (SeekPreviewLoader loader = new SeekPreviewLoader(context)) {
      for (int i = 0; i < 65; i++) {
        final long position = 500 + (i * 83L) % 5000;
        instrumentation.runOnMainSync(
            () ->
                loader.request(
                    video,
                    position,
                    (image, actual) -> {
                      assertSame(Looper.getMainLooper(), Looper.myLooper());
                      if (image != null && moving.get()) {
                        whileMoving.incrementAndGet();
                        firstFrameAt.compareAndSet(Long.MAX_VALUE, SystemClock.elapsedRealtime());
                      }
                    }));
        Thread.sleep(16);
      }
      moving.set(false);
      assertTrue(
          "Previews update during motion rather than waiting for release", whileMoving.get() >= 2);
      assertTrue("The first live preview arrives promptly", firstFrameAt.get() - start < 650);
      loader.reset();
    }
  }

  @Test
  public void consecutiveDragsReuseTheSameSmallFrameAndInteractivePathWorks() throws Exception {
    Context context = contextWithPermission();
    VideoItem video = fixture(context, "Aurora", "mp4");
    assumeNotNull(video);
    try (SeekPreviewLoader loader = new SeekPreviewLoader(context)) {
      Bitmap first = frame(loader, video, 1000);
      assertNotNull(first);
      loader.reset();
      assertSame(
          "A second drag can reuse its retained memory frame", first, frame(loader, video, 1000));

      CountDownLatch ready = new CountDownLatch(1);
      AtomicReference<Bitmap> interactive = new AtomicReference<>();
      loader.requestInteractive(
          video,
          5000,
          (image, position) -> {
            assertSame(Looper.getMainLooper(), Looper.myLooper());
            assertEquals(5000, position);
            interactive.set(image);
            ready.countDown();
          });
      assertTrue("Interactive platform fallback remains bounded", ready.await(8, TimeUnit.SECONDS));
      assertNotNull(interactive.get());
      assertTrue(differ(first, interactive.get()));
    }
  }

  @Test
  public void aviCompatibilityFramesFollowPositionAndCanBeCanceled() throws Exception {
    Context context = contextWithPermission();
    VideoItem video = fixture(context, "Evening", "avi");
    assumeNotNull(video);
    long start = SystemClock.elapsedRealtime();
    assertNull(VlcFrameExtractor.extractAt(context, video, 1000, () -> true));
    assertTrue(
        "Canceled VLC extraction avoids native setup", SystemClock.elapsedRealtime() - start < 200);
    Bitmap first = VlcFrameExtractor.extractAt(context, video, 1000, () -> false);
    Bitmap later = VlcFrameExtractor.extractAt(context, video, 5000, () -> false);
    try {
      assertNotNull("AVI preview at one second", first);
      assertNotNull("AVI preview at five seconds", later);
      assertTrue(first.getWidth() <= 256 && first.getHeight() <= 144);
      assertTrue("Compatibility extraction honors arbitrary timestamps", differ(first, later));
      try (SeekPreviewLoader loader = new SeekPreviewLoader(context)) {
        assertNotNull("AVI works through the regular preview API", frame(loader, video, 3000));
      }
    } finally {
      if (first != null) first.recycle();
      if (later != null) later.recycle();
    }
  }

  private static Bitmap frame(SeekPreviewLoader loader, VideoItem video, long position)
      throws Exception {
    CountDownLatch ready = new CountDownLatch(1);
    AtomicReference<Bitmap> bitmap = new AtomicReference<>();
    loader.request(
        video,
        position,
        (image, reported) -> {
          assertSame(Looper.getMainLooper(), Looper.myLooper());
          assertEquals(position, reported);
          bitmap.set(image);
          ready.countDown();
        });
    assertTrue("Preview callback is bounded", ready.await(8, TimeUnit.SECONDS));
    return bitmap.get();
  }

  private static boolean differ(Bitmap first, Bitmap second) {
    if (first.getWidth() != second.getWidth() || first.getHeight() != second.getHeight())
      return true;
    int differences = 0;
    for (int y = 0; y < first.getHeight(); y += 4)
      for (int x = 0; x < first.getWidth(); x += 4)
        if (first.getPixel(x, y) != second.getPixel(x, y)) differences++;
    return differences > 20;
  }

  private static Context contextWithPermission() {
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    Context context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
    return context;
  }

  private static VideoItem fixture(Context context, String name, String extension) {
    for (VideoItem item : new VideoRepository(context).scan())
      if (item.name.contains(name) && item.extension().equals(extension)) return item;
    return null;
  }
}
