package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Production layout classes, measured without opening media or modifying device settings. */
@RunWith(AndroidJUnit4.class)
public final class PlayerChromeLayoutTest {
  @Test
  public void fullScreenFeedbackFollowsOrderAndTransportStaysCentered() {
    onMain(
        () -> {
          Fixture fixture = new Fixture(false, 1f);
          fixture.measure(640);
          assertSame(fixture.actions, fixture.feedback.getParent());
          assertTrue(fixture.mode.getRight() <= fixture.feedback.getLeft());
          assertTrue(fixture.like.getRight() <= fixture.dislike.getLeft());
          assertEquals(
              fixture.bottom.getWidth() / 2f,
              (fixture.transport.getLeft() + fixture.transport.getRight()) / 2f,
              1f);
          fixture.assertCountWhole();
          fixture.assertControlsUsable();
        });
  }

  @Test
  public void portraitFeedbackRetainsItsTopRightPosition() {
    onMain(
        () -> {
          Fixture fixture = new Fixture(true, 1f);
          fixture.measure(375);
          assertSame(fixture.top, fixture.feedback.getParent());
          assertEquals(View.GONE, fixture.rotate.getVisibility());
          assertTrue(fixture.count.getRight() <= fixture.feedback.getLeft());
          assertTrue(fixture.feedback.getRight() <= fixture.more.getLeft());
          fixture.assertCountWhole();
          fixture.assertControlsUsable();
        });
  }

  @Test
  public void fourDigitAndLargerCountsRemainWholeAcrossWidthsAndSystemFontSizes() {
    onMain(
        () -> {
          for (boolean portrait : new boolean[] {false, true}) {
            for (float fontScale : new float[] {1f, 1.3f, 2f}) {
              Fixture fixture = new Fixture(portrait, fontScale);
              for (String count : new String[] {"9999 / 9999", "1000000 / 1000000"}) {
                fixture.count.setText(count);
                for (int width : new int[] {320, 360, 375, 640, 960}) {
                  fixture.measure(width);
                  fixture.assertCountWhole();
                  fixture.assertControlsUsable();
                  assertTrue(
                      "Header remains at the screen edge",
                      fixture.top.getHeight() <= fixture.dp(112));
                }
              }
            }
          }
        });
  }

  @Test
  public void changingPresentationReflowsExistingButtonsWithoutDuplicatingTargets() {
    onMain(
        () -> {
          Fixture fixture = new Fixture(true, 2f);
          for (int i = 0; i < 8; i++) {
            boolean portrait = (i % 2) == 0;
            fixture.presentation(portrait);
            fixture.measure(portrait ? 320 : 640);
            assertSame(portrait ? fixture.top : fixture.actions, fixture.feedback.getParent());
            assertEquals(2, fixture.feedback.getChildCount());
            fixture.assertCountWhole();
            fixture.assertControlsUsable();
          }
        });
  }

  private static final class Fixture {
    final Context context;
    final PlayerTopBar top;
    final PlayerControlBar bottom;
    final LinearLayout feedback, actions, transport;
    final TextView title, count;
    final ImageButton like, dislike, mode, rotate, more;

    Fixture(boolean portrait, float fontScale) {
      Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
      Configuration configuration = new Configuration(base.getResources().getConfiguration());
      configuration.fontScale = fontScale;
      context = base.createConfigurationContext(configuration);
      title = new TextView(context);
      title.setTextSize(15);
      title.setSingleLine(true);
      title.setEllipsize(TextUtils.TruncateAt.END);
      title.setPadding(dp(8), 0, dp(8), 0);
      title.setText("测试视频的完整长文件名 — A very long local video title 2026.mp4");
      count = new TextView(context);
      count.setTextSize(12);
      count.setFontFeatureSettings("tnum");
      count.setSingleLine(true);
      count.setPadding(dp(4), 0, dp(8), 0);
      count.setText("9999 / 9999");
      feedback = row();
      like = button(48);
      dislike = button(48);
      feedback.addView(like);
      feedback.addView(dislike);
      rotate = button(48);
      more = button(48);
      top = new PlayerTopBar(context, button(48), title, count, feedback, rotate, more);
      top.setPadding(dp(8), dp(4), dp(8), dp(4));
      actions = row();
      mode = button(48);
      actions.addView(mode);
      transport = row();
      transport.addView(button(48));
      ImageButton play = button(56);
      LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(56), dp(56));
      playParams.setMargins(dp(8), 0, dp(8), 0);
      transport.addView(play, playParams);
      transport.addView(button(48));
      bottom = new PlayerControlBar(context, actions, transport, button(48));
      bottom.setPadding(dp(16), 0, dp(16), 0);
      presentation(portrait);
    }

    void presentation(boolean portrait) {
      ViewGroup parent = portrait ? top : actions;
      if (feedback.getParent() != parent) {
        ((ViewGroup) feedback.getParent()).removeView(feedback);
        if (portrait) top.addView(feedback, 3);
        else actions.addView(feedback);
      }
      rotate.setVisibility(portrait ? View.GONE : View.VISIBLE);
    }

    void measure(int widthDp) {
      for (View bar : new View[] {top, bottom}) {
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(240), View.MeasureSpec.AT_MOST));
        bar.layout(0, 0, bar.getMeasuredWidth(), bar.getMeasuredHeight());
      }
    }

    void assertCountWhole() {
      assertNull("The count must never use an ellipsis", count.getEllipsize());
      assertNotNull(count.getLayout());
      assertEquals(1, count.getLayout().getLineCount());
      assertEquals(0, count.getLayout().getEllipsisCount(0));
      assertTrue(
          "All count glyphs fit: " + count.getText(),
          count.getPaint().measureText(count.getText().toString())
              <= count.getWidth()
                  - count.getCompoundPaddingLeft()
                  - count.getCompoundPaddingRight()
                  + 1f);
      assertTrue(
          count.getLayout().getHeight()
              <= count.getHeight()
                  - count.getCompoundPaddingTop()
                  - count.getCompoundPaddingBottom());
    }

    void assertControlsUsable() {
      for (ViewGroup bar : new ViewGroup[] {top, bottom}) {
        ArrayList<Rect> targets = new ArrayList<>();
        collectAndCheck(bar, bar, targets);
        for (int first = 0; first < targets.size(); first++)
          for (int second = first + 1; second < targets.size(); second++)
            assertFalse(
                "Touch targets must not overlap",
                Rect.intersects(targets.get(first), targets.get(second)));
      }
      Rect counter = new Rect(0, 0, count.getWidth(), count.getHeight());
      top.offsetDescendantRectToMyCoords(count, counter);
      for (View child : new View[] {feedback, rotate, more}) {
        if (child.getParent() != top || child.getVisibility() == View.GONE) continue;
        Rect bounds =
            new Rect(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
        assertFalse("Count must not overlap actions", Rect.intersects(counter, bounds));
      }
    }

    void collectAndCheck(ViewGroup root, ViewGroup parent, ArrayList<Rect> targets) {
      for (int i = 0; i < parent.getChildCount(); i++) {
        View child = parent.getChildAt(i);
        if (child.getVisibility() == View.GONE) continue;
        assertTrue(
            "Every child fits horizontally",
            child.getLeft() >= parent.getPaddingLeft()
                && child.getRight() <= parent.getWidth() - parent.getPaddingRight());
        assertTrue(
            "Every child fits vertically",
            child.getTop() >= parent.getPaddingTop()
                && child.getBottom() <= parent.getHeight() - parent.getPaddingBottom());
        if (child instanceof ImageButton) {
          assertTrue(child.getWidth() >= dp(48) && child.getHeight() >= dp(48));
          Rect bounds = new Rect(0, 0, child.getWidth(), child.getHeight());
          root.offsetDescendantRectToMyCoords(child, bounds);
          targets.add(bounds);
        }
        if (child instanceof ViewGroup) collectAndCheck(root, (ViewGroup) child, targets);
      }
    }

    LinearLayout row() {
      LinearLayout row = new LinearLayout(context);
      row.setGravity(Gravity.CENTER_VERTICAL);
      return row;
    }

    ImageButton button(int size) {
      ImageButton button = new ImageButton(context);
      button.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
      return button;
    }

    int dp(int value) {
      return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
  }

  private static void onMain(Runnable task) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
  }
}
