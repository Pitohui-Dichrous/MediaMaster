package io.mediamaster.app.player;

import static org.junit.Assert.*;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.media3.common.util.UnstableApi;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Captures VLC playback controls for one generated fixture; never visits its media library. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class VlcPlaybackEnvironmentProbe {
  private static final String VLC = "org.videolan.vlc";
  private static final String FIXTURE = "mm_seek_b2_h264_1080_gop10.mp4";
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final UiAutomation automation = instrumentation.getUiAutomation();

  @Test
  public void capturePlaybackControls() throws Exception {
    Context context = instrumentation.getTargetContext();
    String permission =
        Build.VERSION.SDK_INT >= 33
            ? Manifest.permission.READ_MEDIA_VIDEO
            : Manifest.permission.READ_EXTERNAL_STORAGE;
    assertEquals(
        "Reuse the existing benchmark media permission; do not change permissions",
        PackageManager.PERMISSION_GRANTED,
        context.checkSelfPermission(permission));
    String run =
        InstrumentationRegistry.getArguments()
            .getString("probeRun", "controls-" + SystemClock.uptimeMillis());
    assertTrue(run.matches("[A-Za-z0-9_-]{1,100}"));
    String menuArgument = InstrumentationRegistry.getArguments().getString("probeMenu", "false");
    assertTrue(
        "probeMenu must be true or false",
        menuArgument.equals("true") || menuArgument.equals("false"));
    boolean captureMenu = Boolean.parseBoolean(menuArgument);
    int menuScrolls =
        Integer.parseInt(InstrumentationRegistry.getArguments().getString("probeMenuScroll", "0"));
    assertTrue(
        "probeMenuScroll must be 0..3 and requires probeMenu=true",
        menuScrolls >= 0 && menuScrolls <= 3 && (menuScrolls == 0 || captureMenu));
    String select = InstrumentationRegistry.getArguments().getString("probeSelect", "");
    assertTrue(
        "Only an explicitly requested read-only information/settings entry is allowed",
        select.isEmpty()
            || (captureMenu
                && (select.equals("信息")
                    || select.equals("视频信息")
                    || select.equals("媒体信息")
                    || select.equals("播放器设置")
                    || select.equals("Information")
                    || select.equals("Media information")
                    || select.equals("Player settings"))));
    File output = new File(context.getExternalFilesDir(null), "test-only/vlc-environment/" + run);
    assertTrue(output.isDirectory() || output.mkdirs());
    Uri fixture = generatedFixture(context);
    AccessibilityServiceInfo service = automation.getServiceInfo();
    int originalFlags = service.flags;
    service.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
    automation.setServiceInfo(service);
    try {
      try (InputStream stream =
          new ParcelFileDescriptor.AutoCloseInputStream(
              automation.executeShellCommand("am force-stop org.videolan.vlc"))) {
        byte[] buffer = new byte[1024];
        while (stream.read(buffer) >= 0) {
          /* Finish the requested fresh fixture launch. */
        }
      }
      context.startActivity(
          new Intent(Intent.ACTION_VIEW)
              .setPackage(VLC)
              .setDataAndType(fixture, "video/mp4")
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));

      // Only inspect transient screenshots in memory until the generated barcode is present.
      long deadline = SystemClock.uptimeMillis() + 20000;
      boolean barcodeSeen = false;
      while (SystemClock.uptimeMillis() < deadline) {
        AccessibilityNodeInfo root = freshRoot();
        if (isVlc(root)) {
          Bitmap screen = screenshot();
          try {
            if (OpticalSeekBenchmark.decode(screen) != null) {
              barcodeSeen = true;
              AccessibilityNodeInfo title = findId(root, "/player_overlay_title");
              if (title == null || !title.isVisibleToUser()) {
                tap(screen.getWidth() * .5f, screen.getHeight() * .5f);
              }
              break;
            }
          } finally {
            screen.recycle();
          }
        }
        SystemClock.sleep(80);
      }
      assertTrue("Generated fixture barcode not visible; nothing captured", barcodeSeen);

      AccessibilityNodeInfo root = null;
      String title = null;
      deadline = SystemClock.uptimeMillis() + 2000;
      while (SystemClock.uptimeMillis() < deadline) {
        root = freshRoot();
        assertTrue("VLC must remain foreground; do not inspect another app", isVlc(root));
        AccessibilityNodeInfo node = findId(root, "/player_overlay_title");
        if (node != null && node.isVisibleToUser() && node.getText() != null) {
          title = node.getText().toString().trim();
          break;
        }
        SystemClock.sleep(50);
      }
      assertNotNull("Playback controls did not expose the fixture title", title);
      assertEquals("Never capture a private media item", basename(FIXTURE), basename(title));
      Bitmap screen = screenshot();
      try {
        OpticalSeekBenchmark.Code code = OpticalSeekBenchmark.decode(screen);
        assertNotNull("Generated fixture disappeared before capture", code);
        // Snapshot immediately; no waitForIdle/uiautomator dump that outlasts VLC's overlay timer.
        JSONObject evidence =
            new JSONObject()
                .put("package", VLC)
                .put("fixture", FIXTURE)
                .put("visibleTitle", title)
                .put("barcodeFrame", code.frame)
                .put("uptimeMs", SystemClock.uptimeMillis())
                .put("sdk", Build.VERSION.SDK_INT)
                .put("model", "reference-device")
                .put("note", "Playback controls only; no decoder conclusion or preference changes")
                .put("hierarchy", hierarchy(root));
        try (FileOutputStream png = new FileOutputStream(new File(output, "controls.png"))) {
          assertTrue(screen.compress(Bitmap.CompressFormat.PNG, 100, png));
        }
        try (OutputStreamWriter writer =
            new OutputStreamWriter(
                new FileOutputStream(new File(output, "controls.json")), StandardCharsets.UTF_8)) {
          writer.write(evidence.toString(2));
        }
      } finally {
        screen.recycle();
      }
      if (captureMenu) {
        SystemClock.sleep(650);
        root = freshRoot();
        assertTrue("VLC must remain foreground", isVlc(root));
        AccessibilityNodeInfo advanced = findId(root, "/player_overlay_adv_function");
        clickVisible(advanced);
        SystemClock.sleep(450);
        captureReadOnlyStage(output, "menu", "player_overlay_adv_function");
        for (int scroll = 1; scroll <= menuScrolls; scroll++) {
          root = freshRoot();
          assertTrue("VLC must remain foreground", isVlc(root));
          AccessibilityNodeInfo drawer = findId(root, "/options_background");
          assertNotNull("Only the observed advanced-options drawer may be scrolled", drawer);
          AccessibilityNodeInfo list = findId(drawer, "/options_list");
          assertNotNull("Observed options list is absent", list);
          assertTrue(
              "Only the visible observed RecyclerView may be scrolled",
              list.isVisibleToUser()
                  && list.isEnabled()
                  && "androidx.recyclerview.widget.RecyclerView"
                      .contentEquals(list.getClassName()));
          if (!list.isScrollable() || !hasAction(list, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            break;
          boolean moved = list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
          SystemClock.sleep(450);
          captureReadOnlyStage(
              output,
              "menu-scroll-" + scroll,
              "options_list ACTION_SCROLL_FORWARD accepted=" + moved);
          if (!moved) break;
        }
        if (!select.isEmpty()) {
          root = freshRoot();
          assertTrue("VLC must remain foreground", isVlc(root));
          AccessibilityNodeInfo entry = findExactText(root, select);
          assertNotNull("The requested read-only entry is not visible: " + select, entry);
          // Menu text can be a child of its clickable item; never use arbitrary coordinates.
          for (int depth = 0; !entry.isClickable() && depth < 3; depth++) {
            AccessibilityNodeInfo parent = entry.getParent();
            if (!isVlc(parent) || !parent.isVisibleToUser()) break;
            entry = parent;
          }
          clickVisible(entry);
          SystemClock.sleep(450);
          captureReadOnlyStage(output, "selection", select);
        }
      }
    } finally {
      service.flags = originalFlags;
      automation.setServiceInfo(service);
    }
  }

  private Uri generatedFixture(Context context) {
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] {"_id", "duration"},
                "_display_name=?",
                new String[] {FIXTURE},
                null)) {
      assertNotNull(cursor);
      assertEquals("Generated fixture must be unique", 1, cursor.getCount());
      assertTrue(cursor.moveToFirst());
      assertTrue(
          "Expected generated 30-second video",
          cursor.getLong(1) >= 29500 && cursor.getLong(1) <= 30500);
      return Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getString(0));
    }
  }

  private AccessibilityNodeInfo freshRoot() {
    if (Build.VERSION.SDK_INT >= 33) automation.clearCache();
    AccessibilityNodeInfo root = automation.getRootInActiveWindow();
    if (root != null) root.refresh();
    return root;
  }

  private static boolean isVlc(AccessibilityNodeInfo node) {
    return node != null
        && node.getPackageName() != null
        && VLC.contentEquals(node.getPackageName());
  }

  private static AccessibilityNodeInfo findId(AccessibilityNodeInfo node, String suffix) {
    if (!isVlc(node)) return null;
    String id = node.getViewIdResourceName();
    if (id != null && id.endsWith(suffix)) return node;
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfo found = findId(node.getChild(i), suffix);
      if (found != null) return found;
    }
    return null;
  }

  private static AccessibilityNodeInfo findExactText(AccessibilityNodeInfo node, String text) {
    if (!isVlc(node) || !node.isVisibleToUser()) return null;
    if ((node.getText() != null && text.contentEquals(node.getText()))
        || (node.getContentDescription() != null
            && text.contentEquals(node.getContentDescription()))) return node;
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfo found = findExactText(node.getChild(i), text);
      if (found != null) return found;
    }
    return null;
  }

  private static void clickVisible(AccessibilityNodeInfo node) {
    assertNotNull("Requested playback control is absent", node);
    assertTrue(
        "Requested playback control must be visible, enabled and clickable",
        isVlc(node) && node.isVisibleToUser() && node.isEnabled() && node.isClickable());
    assertTrue(
        "Accessibility click was rejected", node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
  }

  private static boolean hasAction(AccessibilityNodeInfo node, int actionId) {
    for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList())
      if (action.getId() == actionId) return true;
    return false;
  }

  private void captureReadOnlyStage(File output, String name, String clickedEntry)
      throws Exception {
    AccessibilityNodeInfo root = freshRoot();
    assertTrue("Do not capture another foreground app", isVlc(root));
    JSONObject evidence =
        new JSONObject()
            .put("package", VLC)
            .put("fixtureVerifiedBeforeNavigation", FIXTURE)
            .put("clickedEntry", clickedEntry)
            .put("uptimeMs", SystemClock.uptimeMillis())
            .put("note", "Read-only playback menu inspection; no values selected or changed")
            .put("hierarchy", hierarchy(root));
    Bitmap screen = screenshot();
    try {
      try (FileOutputStream png = new FileOutputStream(new File(output, name + ".png"))) {
        assertTrue(screen.compress(Bitmap.CompressFormat.PNG, 100, png));
      }
      try (OutputStreamWriter writer =
          new OutputStreamWriter(
              new FileOutputStream(new File(output, name + ".json")), StandardCharsets.UTF_8)) {
        writer.write(evidence.toString(2));
      }
    } finally {
      screen.recycle();
    }
  }

  private static JSONObject hierarchy(AccessibilityNodeInfo node) throws Exception {
    if (!isVlc(node) || !node.isVisibleToUser()) return null;
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    JSONArray children = new JSONArray();
    JSONArray actions = new JSONArray();
    for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
      actions.put(
          new JSONObject()
              .put("id", action.getId())
              .put("label", action.getLabel() == null ? "" : action.getLabel().toString()));
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      JSONObject child = hierarchy(node.getChild(i));
      if (child != null) children.put(child);
    }
    return new JSONObject()
        .put("id", node.getViewIdResourceName())
        .put("class", String.valueOf(node.getClassName()))
        .put("text", node.getText() == null ? "" : node.getText().toString())
        .put(
            "description",
            node.getContentDescription() == null ? "" : node.getContentDescription().toString())
        .put("bounds", bounds.flattenToString())
        .put("clickable", node.isClickable())
        .put("scrollable", node.isScrollable())
        .put("actions", actions)
        .put("enabled", node.isEnabled())
        .put("checked", node.isChecked())
        .put("selected", node.isSelected())
        .put("children", children);
  }

  private Bitmap screenshot() {
    Bitmap screen = automation.takeScreenshot();
    assertNotNull("Foreground screenshot unavailable", screen);
    if (screen.getConfig() == Bitmap.Config.HARDWARE) {
      Bitmap readable = screen.copy(Bitmap.Config.ARGB_8888, false);
      screen.recycle();
      screen = readable;
    }
    assertNotNull(screen);
    return screen;
  }

  private void tap(float x, float y) {
    long down = SystemClock.uptimeMillis();
    inject(MotionEvent.ACTION_DOWN, x, y, down);
    SystemClock.sleep(80);
    inject(MotionEvent.ACTION_UP, x, y, down);
  }

  private void inject(int action, float x, float y, long down) {
    MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    try {
      assertTrue("UiAutomation touch injection failed", automation.injectInputEvent(event, true));
    } finally {
      event.recycle();
    }
  }

  private static String basename(String value) {
    return value.endsWith(".mp4") ? value.substring(0, value.length() - 4) : value;
  }
}
