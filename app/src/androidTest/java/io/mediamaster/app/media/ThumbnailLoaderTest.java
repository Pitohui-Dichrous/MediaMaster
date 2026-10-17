package io.mediamaster.app.media;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import io.mediamaster.app.MainActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real attached views exercise cancellation, migration, and the memory/storage budgets. */
@RunWith(AndroidJUnit4.class)
public final class ThumbnailLoaderTest {
  @Rule
  public ActivityTestRule<MainActivity> activityRule =
      new ActivityTestRule<>(MainActivity.class, false, false);

  private final ArrayList<ThumbnailLoader> loaders = new ArrayList<>();
  private Instrumentation instrumentation;
  private Context context;
  private FrameLayout host;

  @Before
  public void prepare() {
    instrumentation = InstrumentationRegistry.getInstrumentation();
    context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .grantRuntimePermission(
            context.getPackageName(),
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE);
  }

  @After
  public void cleanup() throws Exception {
    onMain(
        () -> {
          for (ThumbnailLoader loader : loaders) loader.close();
          if (host != null && host.getParent() instanceof ViewGroup)
            ((ViewGroup) host.getParent()).removeView(host);
          return null;
        });
    activityRule.finishActivity();
  }

  @Test
  public void legacyJpegsAreRemovedAndNewFramesNeverCreateDiskFiles() throws Exception {
    File old = new File(context.getCacheDir(), "video-thumbnails-v1");
    assertTrue(old.isDirectory() || old.mkdirs());
    File jpeg = new File(old, "old-frame.jpg");
    File temporary = new File(old, "old-frame.jpg.1.tmp");
    File unrelated = new File(context.getCacheDir(), "thumbnail-test-unrelated.keep");
    for (File file : new File[] {jpeg, temporary, unrelated})
      try (FileOutputStream out = new FileOutputStream(file)) {
        out.write(1);
      }
    ThumbnailLoader loader = makeLoader(item -> smallFrame());
    try {
      await(() -> !old.exists());
      assertTrue("Only the old thumbnail subdirectory is removed", unrelated.exists());
      launchHost();
      ImageView image = addImage();
      onMain(
          () -> {
            loader.bind(video(1), image);
            return null;
          });
      await(() -> onMain(() -> image.getDrawable() instanceof BitmapDrawable));
      assertFalse("No JPEG directory is recreated by decoding", old.exists());
    } finally {
      unrelated.delete();
    }
  }

  @Test
  public void sameVideoSharesOneDecodeAndDetachedOrUnboundRowsStayEmpty() throws Exception {
    launchHost();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger decodes = new AtomicInteger();
    ThumbnailLoader loader =
        makeLoader(
            item -> {
              decodes.incrementAndGet();
              entered.countDown();
              waitFor(release);
              return smallFrame();
            });
    ImageView first = addImage();
    ImageView second = addImage();
    onMain(
        () -> {
          loader.bind(video(2), first);
          loader.bind(video(2), second);
          return null;
        });
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    onMain(
        () -> {
          loader.unbind(first);
          return null;
        });
    release.countDown();
    await(() -> onMain(() -> second.getDrawable() instanceof BitmapDrawable));
    assertEquals("Concurrent copies of one row share decoding", 1, decodes.get());
    assertNull(onMain(first::getDrawable));
    onMain(
        () -> {
          host.removeView(second);
          return null;
        });
    assertNull(
        "Leaving the window promptly releases the row drawable", onMain(second::getDrawable));
    onMain(
        () -> {
          host.addView(second, new FrameLayout.LayoutParams(100, 70));
          return null;
        });
    assertTrue(onMain(() -> second.getDrawable() instanceof BitmapDrawable));
    assertEquals("Returning rows reuse bounded memory instead of decoding again", 1, decodes.get());
  }

  @Test
  public void rapidFlingHasABoundedQueueAndDetachedJobsAreNotDecoded() throws Exception {
    launchHost();
    CountDownLatch entered = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger decodes = new AtomicInteger();
    ThumbnailLoader loader =
        makeLoader(
            item -> {
              decodes.incrementAndGet();
              entered.countDown();
              waitFor(release);
              return smallFrame();
            });
    for (int i = 0; i < 2; i++) {
      ImageView image = addImage();
      final VideoItem item = video(100 + i);
      onMain(
          () -> {
            loader.bind(item, image);
            return null;
          });
    }
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    onMain(
        () -> {
          for (int i = 2; i < 90; i++) {
            ImageView image = new ImageView(context);
            host.addView(image, new FrameLayout.LayoutParams(10, 10));
            loader.bind(video(100 + i), image);
          }
          return null;
        });
    assertTrue("Waiting jobs remain bounded during a fling", mapSize(loader, "pending") <= 32);
    onMain(
        () -> {
          host.removeAllViews();
          return null;
        });
    assertEquals("Rows leaving the screen cancel waiting jobs", 0, mapSize(loader, "pending"));
    release.countDown();
    Thread.sleep(200);
    assertEquals("Only the two already-running decodes were started", 2, decodes.get());
  }

  @Test
  public void cacheStaysWithinTwelveMiBAndCloseReleasesViewsAndMemory() throws Exception {
    launchHost();
    ThumbnailLoader loader =
        makeLoader(item -> Bitmap.createBitmap(384, 240, Bitmap.Config.ARGB_8888));
    ImageView image = addImage();
    for (int i = 0; i < 42; i++) {
      final VideoItem item = video(300 + i);
      onMain(
          () -> {
            loader.bind(item, image);
            return null;
          });
      await(() -> onMain(() -> image.getDrawable() instanceof BitmapDrawable));
    }
    LruCache<?, ?> memory = (LruCache<?, ?>) field(loader, "memory");
    assertEquals(12 * 1024 * 1024, memory.maxSize());
    assertTrue(memory.size() <= memory.maxSize());
    assertTrue("Old frames are evicted at the budget", memory.evictionCount() > 0);
    onMain(
        () -> {
          loader.close();
          return null;
        });
    assertEquals(0, memory.size());
    assertNull(onMain(image::getDrawable));
    assertFalse(new File(context.getCacheDir(), "video-thumbnails-v1").exists());
  }

  @Test
  public void backgroundPauseCancelsDeliveryAndOnlyRestartsAttachedRows() throws Exception {
    launchHost();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger decodes = new AtomicInteger();
    ThumbnailLoader loader =
        makeLoader(
            item -> {
              if (decodes.incrementAndGet() == 1) {
                entered.countDown();
                waitFor(release);
              }
              return smallFrame();
            });
    ImageView image = addImage();
    onMain(
        () -> {
          loader.bind(video(400), image);
          return null;
        });
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    onMain(
        () -> {
          loader.setPaused(true);
          return null;
        });
    release.countDown();
    Thread.sleep(150);
    assertNull(
        "A covered library never receives late thumbnail results", onMain(image::getDrawable));
    onMain(
        () -> {
          loader.setPaused(false);
          return null;
        });
    await(() -> onMain(() -> image.getDrawable() instanceof BitmapDrawable));
    assertEquals(2, decodes.get());
  }

  private ThumbnailLoader makeLoader(ThumbnailLoader.FrameDecoder decoder) {
    ThumbnailLoader loader = new ThumbnailLoader(context, decoder);
    loaders.add(loader);
    return loader;
  }

  private void launchHost() throws Exception {
    MainActivity activity = activityRule.launchActivity(new Intent(context, MainActivity.class));
    onMain(
        () -> {
          host = new FrameLayout(activity);
          activity.addContentView(host, new ViewGroup.LayoutParams(-1, -1));
          return null;
        });
  }

  private ImageView addImage() throws Exception {
    return onMain(
        () -> {
          ImageView image = new ImageView(context);
          host.addView(image, new FrameLayout.LayoutParams(100, 70));
          assertTrue(image.isAttachedToWindow());
          return image;
        });
  }

  private static Bitmap smallFrame() {
    return Bitmap.createBitmap(80, 50, Bitmap.Config.ARGB_8888);
  }

  private static VideoItem video(int id) {
    return new VideoItem(
        "thumb-test-" + id, id + ".mp4", "test://" + id, "", 1, 6000, 1, 1, 384, 240);
  }

  private static void waitFor(CountDownLatch latch) {
    try {
      latch.await(4, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static int mapSize(Object target, String name) throws Exception {
    Object lock = field(target, "lock");
    synchronized (lock) {
      return ((Map<?, ?>) field(target, name)).size();
    }
  }

  private <T> T onMain(Callable<T> action) throws Exception {
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Exception> error = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          try {
            value.set(action.call());
          } catch (Exception failure) {
            error.set(failure);
          }
        });
    if (error.get() != null) throw error.get();
    return value.get();
  }

  private static void await(Callable<Boolean> condition) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 3000;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (condition.call()) return;
      Thread.sleep(15);
    }
    fail("Timed out waiting for thumbnail state");
  }
}
