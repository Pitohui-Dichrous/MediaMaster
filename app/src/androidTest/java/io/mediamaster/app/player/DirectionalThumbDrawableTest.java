package io.mediamaster.app.player;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.animation.ValueAnimator;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import io.mediamaster.app.MainActivity;
import io.mediamaster.app.R;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Pixel-level checks of the supplied transparent artwork and its interruptible direction change.
 */
@RunWith(AndroidJUnit4.class)
public final class DirectionalThumbDrawableTest {
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = instrumentation.getTargetContext();

  @Test
  public void bothCharactersKeepTransparentEdgesSimilarVisualAreaAndStayInsideIntrinsicBounds()
      throws Exception {
    DirectionalThumbDrawable thumb = onMain(() -> new DirectionalThumbDrawable(context));
    assertEquals(
        34f, thumb.getIntrinsicWidth() / context.getResources().getDisplayMetrics().density, 1f);
    Bitmap forward = render(thumb, true, 1);
    Bitmap backward = render(thumb, false, 1);
    try {
      int margin = 8;
      int size = thumb.getIntrinsicWidth();
      for (Bitmap image : new Bitmap[] {forward, backward}) {
        assertEquals(
            "The top-left corner retains the original transparency",
            0,
            Color.alpha(image.getPixel(margin + 1, margin + 1)));
        assertEquals(0, Color.alpha(image.getPixel(margin + size - 2, margin + 1)));
        assertEquals(0, Color.alpha(image.getPixel(margin + 1, margin + size - 2)));
        assertEquals(0, Color.alpha(image.getPixel(margin + size - 2, margin + size - 2)));
        for (int y = 0; y < image.getHeight(); y++) {
          for (int x = 0; x < image.getWidth(); x++) {
            if (x < margin || x >= margin + size || y < margin || y >= margin + size) {
              assertEquals(
                  "Artwork must not leak outside its drawable bounds",
                  0,
                  Color.alpha(image.getPixel(x, y)));
            }
          }
        }
      }
      float areaRatio = area(forward) / (float) area(backward);
      assertTrue(
          "Changing direction preserves visual mass: " + areaRatio,
          areaRatio > .88f && areaRatio < 1.12f);
      Rect first = occupiedBounds(forward), second = occupiedBounds(backward);
      float edgeRatio =
          Math.max(first.width(), first.height())
              / (float) Math.max(second.width(), second.height());
      assertTrue("Characters have a consistent visual extent", edgeRatio > .9f && edgeRatio < 1.1f);
      assertTrue(
          "The scrubber is visibly larger than the old small circular thumb",
          Math.max(first.width(), first.height()) >= size * .85f);
    } finally {
      forward.recycle();
      backward.recycle();
    }
  }

  @Test
  public void backwardArtworkIsActuallyMirroredAsShownByItsDistinctiveBlueEye() throws Exception {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = 4;
    options.inScaled = false;
    Bitmap supplied =
        BitmapFactory.decodeResource(context.getResources(), R.drawable.scrubber_backward, options);
    DirectionalThumbDrawable thumb = onMain(() -> new DirectionalThumbDrawable(context));
    Bitmap rendered = render(thumb, false, 3);
    try {
      float sourceEye = blueEyeCenter(supplied, occupiedBounds(supplied));
      float mirroredEye = blueEyeCenter(rendered, occupiedBounds(rendered));
      assertTrue("The source's blue eye is on the left", sourceEye < .48f);
      assertTrue("The rendered blue eye moves to the right after mirroring", mirroredEye > .52f);
      assertEquals(
          "The eye keeps its optical position after a horizontal mirror",
          1f - sourceEye,
          mirroredEye,
          .075f);
    } finally {
      supplied.recycle();
      rendered.recycle();
    }
  }

  @Test
  public void reversingHalfwayStartsAtTheVisibleFrameAndFinishesInTheLatestDirection()
      throws Exception {
    assumeTrue(
        "Animation is enabled for the interruption check", ValueAnimator.areAnimatorsEnabled());
    onMain(
        () -> {
          DirectionalThumbDrawable thumb = new DirectionalThumbDrawable(context);
          thumb.setDirection(false);
          ValueAnimator first = (ValueAnimator) read(thumb, "turn");
          first.setCurrentPlayTime(70);
          float shown = (Float) read(thumb, "forwardWeight");
          assertTrue("The first turn has reached an intermediate frame", shown > 0 && shown < 1);
          thumb.setDirection(true);
          assertFalse("The obsolete turn is canceled", first.isRunning());
          assertEquals(
              "A reversal starts at the currently visible blend",
              shown,
              (Float) read(thumb, "forwardWeight"),
              .0001f);
          ValueAnimator second = (ValueAnimator) read(thumb, "turn");
          assertNotSame(first, second);
          second.setCurrentPlayTime(140);
          thumb.jumpToCurrentState();
          assertTrue(thumb.isForward());
          assertEquals(1f, (Float) read(thumb, "forwardWeight"), 0);
          assertFalse(second.isRunning());
          return null;
        });
  }

  @SdkSuppress(minSdkVersion = 33)
  @Test
  public void disablingSystemAnimationsMakesTheTurnFinishImmediately() throws Exception {
    // Establish a WindowSession even if this class is the first one in the suite. Android delivers
    // animator-scale changes through that session, rather than directly through Settings reads.
    MainActivity window =
        (MainActivity)
            instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      String[] keys = {
        Settings.Global.WINDOW_ANIMATION_SCALE,
        Settings.Global.TRANSITION_ANIMATION_SCALE,
        Settings.Global.ANIMATOR_DURATION_SCALE
      };
      String[] before = new String[keys.length];
      for (int i = 0; i < keys.length; i++) {
        before[i] = Settings.Global.getString(context.getContentResolver(), keys[i]);
      }
      try {
        instrumentation.getUiAutomation().setAnimationScale(0f);
        long deadline = SystemClock.uptimeMillis() + 3000;
        while (onMain(ValueAnimator::areAnimatorsEnabled)
            && SystemClock.uptimeMillis() < deadline) {
          SystemClock.sleep(25);
        }
        assertFalse(
            "The app observes Android's disabled-animation setting",
            onMain(ValueAnimator::areAnimatorsEnabled));
        onMain(
            () -> {
              DirectionalThumbDrawable thumb = new DirectionalThumbDrawable(context);
              thumb.setDirection(false);
              assertFalse(thumb.isForward());
              assertEquals(0f, (Float) read(thumb, "forwardWeight"), 0);
              assertNull("Reduced motion does not start an animator", read(thumb, "turn"));
              thumb.setDirection(true);
              assertEquals(1f, (Float) read(thumb, "forwardWeight"), 0);
              return null;
            });
      } finally {
        // setAnimationScale changes WindowManager's live values as well as Settings. Writing the
        // Settings rows alone does not reliably publish the reverse change to an existing session.
        // Restore through the same system API, then preserve any individually customized scales.
        instrumentation
            .getUiAutomation()
            .setAnimationScale(before[2] == null ? 1f : Float.parseFloat(before[2]));
        for (int i = 0; i < keys.length; i++) {
          String command =
              before[i] == null
                  ? "settings delete global " + keys[i]
                  : "settings put global " + keys[i] + " " + Float.parseFloat(before[i]);
          try (ParcelFileDescriptor result =
                  instrumentation.getUiAutomation().executeShellCommand(command);
              java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(result)) {
            byte[] buffer = new byte[512];
            while (input.read(buffer) != -1) {}
          }
        }
        boolean expectedEnabled = before[2] == null || Float.parseFloat(before[2]) != 0;
        long deadline = SystemClock.uptimeMillis() + 3000;
        while (onMain(ValueAnimator::areAnimatorsEnabled) != expectedEnabled
            && SystemClock.uptimeMillis() < deadline) {
          SystemClock.sleep(25);
        }
        assertEquals(
            "Animation preference is restored after the check",
            expectedEnabled,
            onMain(ValueAnimator::areAnimatorsEnabled));
      }
    } finally {
      instrumentation.runOnMainSync(window::finish);
    }
  }

  @Test
  public void launcherForegroundIsAVectorAtAllDensitiesAndStaysInsideAdaptiveSafeCircle()
      throws Exception {
    onMain(
        () -> {
          for (int dpi : new int[] {160, 320, 480, 640}) {
            Configuration configuration =
                new Configuration(context.getResources().getConfiguration());
            configuration.densityDpi = dpi;
            Context densityContext = context.createConfigurationContext(configuration);
            Drawable foreground = densityContext.getDrawable(R.drawable.ic_launcher_foreground);
            assertTrue(
                "Launcher stays a native vector at density " + dpi,
                foreground instanceof VectorDrawable);
            Bitmap rendered = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888);
            try {
              foreground.setBounds(0, 0, 432, 432);
              foreground.draw(new Canvas(rendered));
              float farthest = 0;
              int partlyCovered = 0;
              for (int y = 0; y < 432; y++) {
                for (int x = 0; x < 432; x++) {
                  int alpha = Color.alpha(rendered.getPixel(x, y));
                  if (alpha > 32)
                    farthest = Math.max(farthest, (float) Math.hypot(x + .5f - 216, y + .5f - 216));
                  if (alpha > 0 && alpha < 255) partlyCovered++;
                }
              }
              assertTrue("The play shape is legible", area(rendered) > 15000);
              assertTrue("All geometry fits the central adaptive 72dp circle", farthest <= 144.5f);
              assertTrue("Edges use vector antialiasing", partlyCovered > 100);
            } finally {
              rendered.recycle();
            }
          }
          return null;
        });
  }

  private Bitmap render(DirectionalThumbDrawable thumb, boolean forward, int multiplier)
      throws Exception {
    return onMain(
        () -> {
          int size = thumb.getIntrinsicWidth() * multiplier;
          Bitmap image = Bitmap.createBitmap(size + 16, size + 16, Bitmap.Config.ARGB_8888);
          thumb.setBounds(8, 8, 8 + size, 8 + size);
          thumb.setDirection(forward);
          thumb.jumpToCurrentState();
          thumb.draw(new Canvas(image));
          return image;
        });
  }

  private static int area(Bitmap image) {
    int pixels = 0;
    for (int y = 0; y < image.getHeight(); y++)
      for (int x = 0; x < image.getWidth(); x++)
        if (Color.alpha(image.getPixel(x, y)) >= 64) pixels++;
    return pixels;
  }

  private static Rect occupiedBounds(Bitmap image) {
    int left = image.getWidth(), top = image.getHeight(), right = 0, bottom = 0;
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if (Color.alpha(image.getPixel(x, y)) >= 64) {
          left = Math.min(left, x);
          top = Math.min(top, y);
          right = Math.max(right, x + 1);
          bottom = Math.max(bottom, y + 1);
        }
      }
    }
    return new Rect(left, top, right, bottom);
  }

  private static float blueEyeCenter(Bitmap image, Rect content) {
    double weightedX = 0;
    int count = 0;
    for (int y = content.top; y < content.bottom; y++) {
      for (int x = content.left; x < content.right; x++) {
        int color = image.getPixel(x, y);
        int red = Color.red(color), green = Color.green(color), blue = Color.blue(color);
        if (Color.alpha(color) > 180
            && blue > red * 1.45f
            && blue > green * 1.05f
            && blue > 75
            && green > 40) {
          weightedX += x + .5;
          count++;
        }
      }
    }
    assertTrue("Distinctive blue eye pixels remain visible", count > 5);
    return (float) ((weightedX / count - content.left) / content.width());
  }

  private static Object read(Object object, String field) throws Exception {
    Field f = object.getClass().getDeclaredField(field);
    f.setAccessible(true);
    return f.get(object);
  }

  private <T> T onMain(Callable<T> task) throws Exception {
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          try {
            value.set(task.call());
          } catch (Throwable error) {
            failure.set(error);
          }
        });
    if (failure.get() instanceof Error) throw (Error) failure.get();
    if (failure.get() != null) throw new RuntimeException(failure.get());
    return value.get();
  }
}
