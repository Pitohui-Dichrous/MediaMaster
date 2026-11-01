package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;
import androidx.media3.common.util.UnstableApi;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.mediamaster.app.R;
import io.mediamaster.app.media.VideoItem;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Gestures during the real no-engine interval while asynchronous header selection is pending. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class VideoPagePreparationTest {
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private VideoPage page;
  private SharedPreferences preferences;
  private boolean preferenceSaved, hadNativeDecoder;
  private int savedNativeDecoder;

  @Before
  public void pendingPage() {
    preferences = instrumentation.getTargetContext().getSharedPreferences("player", 0);
    hadNativeDecoder = preferences.contains("native_decoder_mode");
    savedNativeDecoder = preferences.getInt("native_decoder_mode", NativeDecoderPolicy.AUTO);
    preferenceSaved = true;
    assertTrue(preferences.edit().putInt("native_decoder_mode", NativeDecoderPolicy.AUTO).commit());
    instrumentation.runOnMainSync(
        () -> {
          page =
              new VideoPage(
                  new ContextThemeWrapper(instrumentation.getTargetContext(), R.style.PlayerTheme),
                  () -> {
                    throw new AssertionError("Pending selection must not open a decoder");
                  },
                  new VideoPage.Listener() {
                    @Override
                    public void onChanged(VideoPage page) {}

                    @Override
                    public void onFirstFrame(VideoPage page) {}

                    @Override
                    public void onEnded(VideoPage page) {}

                    @Override
                    public void onFailed(VideoPage page) {}

                    @Override
                    public void onVideoSize(VideoPage page, int width, int height) {}
                  });
          // This is precisely the page state between prepare() and the selector callback. No
          // file/decoder is needed to exercise the gestures that previously lost their target.
          page.item = new VideoItem("pending", "pending.mp4", "", "", 0, 30000, 0, 0, 1920, 1080);
          page.index = 0;
          page.buffering = true;
        });
  }

  @After
  public void release() {
    try {
      if (page != null) instrumentation.runOnMainSync(page::release);
    } finally {
      if (preferenceSaved) {
        SharedPreferences.Editor restore = preferences.edit();
        if (hadNativeDecoder) restore.putInt("native_decoder_mode", savedNativeDecoder);
        else restore.remove("native_decoder_mode");
        assertTrue(restore.commit());
      }
    }
  }

  @Test
  public void latestSeekIncludingZeroIsRetainedBeforeAnEngineExists() {
    onMain(
        () -> {
          page.seekTo(21000);
          assertEquals(21000, page.position());
          page.seekTo(0);
          assertEquals(0, page.position());
          page.seekTo(40000);
          assertEquals(29999, page.position());
          assertNull(page.exo);
          assertNull(page.vlc);
        });
  }

  @Test
  public void cancelledPreparingGestureRestoresOriginAndPauseIntent() {
    onMain(
        () -> {
          page.setPlayback(true, false, true);
          page.seekTo(1400);
          page.beginScrub();
          page.scrubTo(23000);
          assertTrue(page.isScrubbing());
          assertEquals(23000, page.position());
          page.endScrub(23000, false);
          assertFalse(page.isScrubbing());
          assertEquals(1400, page.position());
          assertFalse(page.playRequested);
        });
  }

  @Test
  public void preparingGestureCommitKeepsItsFinalTargetAndPlayIntent() {
    onMain(
        () -> {
          page.setPlayback(true, true, true);
          page.beginScrub();
          page.scrubTo(6000);
          page.scrubTo(18000);
          page.endScrub(20400, true);
          assertEquals(20400, page.position());
          assertFalse(page.isScrubbing());
          assertTrue(page.playRequested);
        });
  }

  @Test
  public void releaseCancelsPendingGestureAndRejectsFurtherMoves() {
    onMain(
        () -> {
          long generation = page.generation;
          page.beginScrub();
          page.scrubTo(18000);
          page.release();
          page.scrubTo(24000);
          page.endScrub(24000, true);
          assertTrue(page.generation > generation);
          assertNull(page.item);
          assertFalse(page.isScrubbing());
          assertEquals(0, page.position());
          assertNull(page.exo);
          assertNull(page.vlc);
        });
  }

  private void onMain(Runnable test) {
    AtomicReference<Throwable> error = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          try {
            test.run();
          } catch (Throwable failure) {
            error.set(failure);
          }
        });
    if (error.get() != null) throw new AssertionError(error.get());
  }
}
