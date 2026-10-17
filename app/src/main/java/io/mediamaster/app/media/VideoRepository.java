package io.mediamaster.app.media;

import android.Manifest;
import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.AtomicFile;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.interfaces.IMedia;

/** Local storage catalogue. All scanning and mutation methods run on a worker thread. */
public final class VideoRepository {
  private static final String[] EXTENSIONS = {
    "mp4", "mkv", "avi", "webm", "mov", "m4v", "3gp", "flv", "wmv", "ts", "mpeg", "mpg"
  };
  private static final Set<String> SUPPORTED = new HashSet<>(Arrays.asList(EXTENSIONS));
  private static final String SOURCE_KEY = "sources";
  private final Context context;
  private final ContentResolver resolver;
  private final SharedPreferences preferences;
  private final AtomicFile cache;
  private final Object cacheLock = new Object();
  private final Object sourcesLock = new Object();
  private volatile List<String> warnings = Collections.emptyList();
  private volatile List<String> watchPaths = Collections.emptyList();
  private Map<String, VideoItem> previous = new HashMap<>();
  private LibVLC metadataEngine;

  public VideoRepository(Context context) {
    this.context = context.getApplicationContext();
    resolver = this.context.getContentResolver();
    preferences = this.context.getSharedPreferences("media_library", Context.MODE_PRIVATE);
    cache = new AtomicFile(new File(this.context.getFilesDir(), "video-library-v4.json"));
  }

  public static boolean isSupportedVideo(String name) {
    if (name == null) return false;
    int dot = name.lastIndexOf('.');
    return dot >= 0 && SUPPORTED.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  public List<String> getWarnings() {
    return new ArrayList<>(warnings);
  }

  public List<String> getSources() {
    synchronized (sourcesLock) {
      List<String> result = new ArrayList<>();
      try {
        JSONArray values = new JSONArray(preferences.getString(SOURCE_KEY, "[]"));
        for (int i = 0; i < values.length(); i++) result.add(values.getString(i));
      } catch (Exception ignored) {
      }
      return result;
    }
  }

  public void addTree(Uri treeUri) {
    if (treeUri == null || !DocumentsContract.isTreeUri(treeUri)) {
      throw new IllegalArgumentException("请选择一个文件夹");
    }
    try {
      resolver.takePersistableUriPermission(
          treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    } catch (SecurityException writeUnavailable) {
      resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    addSource(treeUri.toString());
  }

  public void addPath(String path) throws IOException {
    if (path == null || path.trim().isEmpty()) throw new IOException("请输入文件夹的完整路径");
    File folder = new File(path.trim());
    if (!folder.isAbsolute()) throw new IOException("请填写绝对路径，例如 /storage/emulated/0/Movies");
    folder = folder.getCanonicalFile();
    if (!folder.isDirectory() || !folder.canRead()) {
      throw new IOException("无法读取文件夹：" + folder + "。请授予全部文件访问权限，或使用「选择文件夹」。");
    }
    addSource(folder.getPath());
  }

  private void addSource(String source) {
    synchronized (sourcesLock) {
      List<String> sources = getSources();
      if (!sources.contains(source)) {
        sources.add(source);
        preferences.edit().putString(SOURCE_KEY, new JSONArray(sources).toString()).apply();
      }
    }
  }

  public void removeSource(String source) {
    synchronized (sourcesLock) {
      List<String> sources = getSources();
      sources.remove(source);
      preferences.edit().putString(SOURCE_KEY, new JSONArray(sources).toString()).apply();
      if (source != null && source.startsWith("content://")) {
        try {
          resolver.releasePersistableUriPermission(
              Uri.parse(source),
              Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }
      }
    }
  }

  /** Reconciles all visible shared videos and configured folders. Never removes files. */
  public synchronized List<VideoItem> scan() {
    try {
      return scanInternal();
    } finally {
      if (metadataEngine != null) {
        metadataEngine.release();
        metadataEngine = null;
      }
    }
  }

  private List<VideoItem> scanInternal() {
    checkInterrupted();
    if (previous.isEmpty()) indexPrevious(loadCache());
    LinkedHashMap<String, VideoItem> found = new LinkedHashMap<>();
    List<String> issues = new ArrayList<>();
    LinkedHashSet<String> directories = new LinkedHashSet<>();
    File primary = Environment.getExternalStorageDirectory();
    addWatchDirectory(directories, primary);
    for (String name : new String[] {"Download", "Movies", "DCIM", "Pictures", "Videos"}) {
      addWatchDirectory(directories, new File(primary, name));
    }
    scanMediaStore(found, issues, directories);
    Set<String> traversed = new HashSet<>();
    if (hasBroadFileAccess()) {
      for (File root : storageRoots()) scanDirectory(root, found, issues, directories, traversed);
    }
    for (String source : getSources()) {
      checkInterrupted();
      if (source.startsWith("content://")) {
        scanTree(Uri.parse(source), found, issues, directories);
      } else {
        scanDirectory(new File(source), found, issues, directories, traversed);
      }
    }
    List<VideoItem> result = new ArrayList<>(found.values());
    indexPrevious(result);
    warnings = Collections.unmodifiableList(issues);
    watchPaths = Collections.unmodifiableList(new ArrayList<>(directories));
    return result;
  }

  public List<String> getFileWatchPaths() {
    return new ArrayList<>(watchPaths);
  }

  private void scanMediaStore(
      Map<String, VideoItem> found, List<String> issues, Set<String> directories) {
    String permission =
        Build.VERSION.SDK_INT >= 33
            ? Manifest.permission.READ_MEDIA_VIDEO
            : Manifest.permission.READ_EXTERNAL_STORAGE;
    // Android 14 limited selection is still queryable, so do not discard that catalogue.
    boolean limited =
        Build.VERSION.SDK_INT >= 34
            && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                == PackageManager.PERMISSION_GRANTED;
    if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        && !limited
        && !hasBroadFileAccess()) {
      issues.add("尚未授予视频读取权限；可以在设置中授权，或添加允许访问的文件夹。");
      return;
    }
    Set<String> volumes = new LinkedHashSet<>();
    if (Build.VERSION.SDK_INT >= 29) {
      try {
        volumes.addAll(MediaStore.getExternalVolumeNames(context));
      } catch (Exception ignored) {
      }
    }
    if (volumes.isEmpty()) volumes.add("external");
    StringBuilder selection =
        new StringBuilder("(" + MediaStore.Files.FileColumns.MEDIA_TYPE + "=?");
    List<String> args = new ArrayList<>();
    args.add(String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO));
    for (String extension : EXTENSIONS) {
      selection.append(" OR ").append(MediaStore.MediaColumns.DISPLAY_NAME).append(" LIKE ?");
      args.add("%." + extension);
    }
    selection.append(")");
    if (Build.VERSION.SDK_INT >= 29) selection.append(" AND is_pending=0");
    if (Build.VERSION.SDK_INT >= 30) selection.append(" AND is_trashed=0");
    List<String> projection =
        new ArrayList<>(
            Arrays.asList(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.Video.Media.DURATION,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.DATA));
    if (Build.VERSION.SDK_INT >= 29) projection.add(MediaStore.MediaColumns.ORIENTATION);
    for (String volume : volumes) {
      checkInterrupted();
      Uri collection = MediaStore.Files.getContentUri(volume);
      try (Cursor cursor =
          resolver.query(
              collection,
              projection.toArray(new String[0]),
              selection.toString(),
              args.toArray(new String[0]),
              null)) {
        if (cursor == null) throw new IOException("媒体索引暂时无响应");
        while (cursor.moveToNext()) {
          checkInterrupted();
          String name = string(cursor, MediaStore.MediaColumns.DISPLAY_NAME);
          if (!isSupportedVideo(name)) continue;
          long size = number(cursor, MediaStore.MediaColumns.SIZE);
          if (size <= 0) continue; // Do not present incomplete/empty browser downloads.
          long mediaId = number(cursor, MediaStore.MediaColumns._ID);
          String path = string(cursor, MediaStore.MediaColumns.DATA);
          String itemUri = ContentUris.withAppendedId(collection, mediaId).toString();
          int width = (int) number(cursor, MediaStore.MediaColumns.WIDTH);
          int height = (int) number(cursor, MediaStore.MediaColumns.HEIGHT);
          long rotation =
              Build.VERSION.SDK_INT >= 29 ? number(cursor, MediaStore.MediaColumns.ORIENTATION) : 0;
          if (rotation == 90 || rotation == 270) {
            int swap = width;
            width = height;
            height = swap;
          }
          long modified = number(cursor, MediaStore.MediaColumns.DATE_MODIFIED) * 1000L;
          long added = number(cursor, MediaStore.MediaColumns.DATE_ADDED) * 1000L;
          VideoItem item =
              new VideoItem(
                  "media:" + volume + ":" + mediaId,
                  name,
                  itemUri,
                  path,
                  size,
                  number(cursor, MediaStore.Video.Media.DURATION),
                  modified,
                  added > 0 ? added : modified,
                  width,
                  height);
          VideoItem old = previous.get(itemUri);
          if (old != null && old.modifiedMs == item.modifiedMs && old.size == item.size) {
            item = fillMissingMetadata(item, old);
          } else if (item.durationMs == 0 || item.width == 0 || item.height == 0) {
            item = fillMissingMetadata(item, readMetadata(item));
          }
          found.put(key(item.uri, item.path), item);
          if (!path.isEmpty()) addWatchDirectory(directories, new File(path).getParentFile());
        }
      } catch (Exception error) {
        if (error instanceof CancellationException) throw (CancellationException) error;
        retainPrevious(found, "media:" + volume + ":", null);
        issues.add("无法读取媒体索引（" + volume + "），暂时保留已有目录：" + safeMessage(error));
      }
    }
  }

  private void scanDirectory(
      File root,
      Map<String, VideoItem> found,
      List<String> issues,
      Set<String> directories,
      Set<String> traversed) {
    if (!root.isDirectory() || !root.canRead()) {
      issues.add("文件夹不可访问：" + root.getPath());
      return;
    }
    ArrayDeque<File> pending = new ArrayDeque<>();
    pending.add(root);
    while (!pending.isEmpty()) {
      checkInterrupted();
      File directory = pending.removeFirst();
      String canonical;
      try {
        canonical = directory.getCanonicalPath();
      } catch (IOException ignored) {
        continue;
      }
      if (!traversed.add(canonical) || restrictedDirectory(canonical)) continue;
      addWatchDirectory(directories, directory);
      File[] children = directory.listFiles();
      if (children == null) continue;
      for (File child : children) {
        checkInterrupted();
        if (child.isDirectory()) {
          // A link out of the storage tree is not a new scan root.
          try {
            if (child.getCanonicalFile().equals(child.getAbsoluteFile())) pending.addLast(child);
          } catch (IOException ignored) {
          }
        } else if (isSupportedVideo(child.getName()) && child.length() > 0 && child.canRead()) {
          String path;
          try {
            path = child.getCanonicalPath();
          } catch (IOException ignored) {
            path = child.getAbsolutePath();
          }
          String itemKey = key("", path);
          if (found.containsKey(itemKey)) continue;
          String uri = Uri.fromFile(child).toString();
          VideoItem old = previous.get(path);
          VideoItem item =
              new VideoItem(
                  "file:" + path,
                  child.getName(),
                  uri,
                  path,
                  child.length(),
                  0,
                  child.lastModified(),
                  old == null || old.addedMs <= 0 ? child.lastModified() : old.addedMs,
                  0,
                  0);
          if (sameFile(item, old)) item = fillMissingMetadata(item, old);
          else item = readMetadata(item);
          found.put(itemKey, item);
        }
      }
    }
  }

  private void scanTree(
      Uri tree, Map<String, VideoItem> found, List<String> issues, Set<String> directories) {
    ArrayDeque<String> pending = new ArrayDeque<>();
    Set<String> visited = new HashSet<>();
    try {
      pending.add(DocumentsContract.getTreeDocumentId(tree));
      while (!pending.isEmpty()) {
        checkInterrupted();
        String parentId = pending.removeFirst();
        if (!visited.add(parentId)) continue;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] projection = {
          DocumentsContract.Document.COLUMN_DOCUMENT_ID,
          DocumentsContract.Document.COLUMN_DISPLAY_NAME,
          DocumentsContract.Document.COLUMN_MIME_TYPE,
          DocumentsContract.Document.COLUMN_SIZE,
          DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
          if (cursor == null) throw new IOException("文件夹提供方暂时无响应");
          while (cursor.moveToNext()) {
            checkInterrupted();
            String documentId = string(cursor, DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(
                string(cursor, DocumentsContract.Document.COLUMN_MIME_TYPE))) {
              pending.addLast(documentId);
              continue;
            }
            String name = string(cursor, DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            if (!isSupportedVideo(name)) continue;
            long size = number(cursor, DocumentsContract.Document.COLUMN_SIZE);
            if (size <= 0) continue;
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
            String path = documentPath(tree.getAuthority(), documentId);
            String itemKey = key(document.toString(), path);
            if (found.containsKey(itemKey)) continue;
            VideoItem old = previous.get(path.isEmpty() ? document.toString() : path);
            long modified = number(cursor, DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            VideoItem item =
                new VideoItem(
                    "document:" + tree.getAuthority() + ":" + documentId,
                    name,
                    document.toString(),
                    path,
                    size,
                    0,
                    modified,
                    old == null || old.addedMs <= 0 ? modified : old.addedMs,
                    0,
                    0);
            item = sameFile(item, old) ? fillMissingMetadata(item, old) : readMetadata(item);
            found.put(itemKey, item);
            if (!path.isEmpty()) addWatchDirectory(directories, new File(path).getParentFile());
          }
        }
      }
    } catch (Exception error) {
      if (error instanceof CancellationException) throw (CancellationException) error;
      if (!(error instanceof java.io.FileNotFoundException))
        retainPrevious(found, null, tree.toString() + "/document/");
      issues.add("文件夹授权已失效或暂不可用：" + tree + "（" + safeMessage(error) + "）");
    }
  }

  private VideoItem readMetadata(VideoItem item) {
    checkInterrupted();
    MediaMetadataRetriever metadata = new MediaMetadataRetriever();
    VideoItem result = item;
    try {
      metadata.setDataSource(context, Uri.parse(item.uri));
      long duration =
          parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
      int width =
          (int)
              parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
      int height =
          (int)
              parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
      int rotation =
          (int)
              parseLong(
                  metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
      if (rotation == 90 || rotation == 270) {
        int swap = width;
        width = height;
        height = swap;
      }
      result =
          new VideoItem(
              item.id,
              item.name,
              item.uri,
              item.path,
              item.size,
              duration,
              item.modifiedMs,
              item.addedMs,
              width,
              height);
    } catch (Exception ignored) {
      // LibVLC below can read containers that the platform extractor does not understand.
    } finally {
      try {
        metadata.release();
      } catch (Exception ignored) {
      }
    }
    return result.durationMs == 0 || result.width == 0 || result.height == 0
        ? readVlcMetadata(result)
        : result;
  }

  private VideoItem readVlcMetadata(VideoItem item) {
    checkInterrupted();
    Media media = null;
    ParcelFileDescriptor descriptor = null;
    try {
      if (metadataEngine == null)
        metadataEngine =
            new LibVLC(
                context,
                new ArrayList<>(Arrays.asList("--quiet", "--no-audio", "--no-video-title-show")));
      boolean filePath = !item.path.isEmpty() && new File(item.path).canRead();
      if (filePath) {
        media = new Media(metadataEngine, item.path);
      } else {
        descriptor = resolver.openFileDescriptor(Uri.parse(item.uri), "r");
        if (descriptor == null) return item;
        media = new Media(metadataEngine, descriptor.getFileDescriptor());
      }
      CountDownLatch parsed = new CountDownLatch(1);
      media.setEventListener(
          event -> {
            if (event.type == IMedia.Event.ParsedChanged) parsed.countDown();
          });
      // Native timeout plus a Java upper bound prevents a damaged file holding up scans.
      // ParseLocal skips non-file schemes. fd:// still references a local, already-open file.
      int flags = filePath ? IMedia.Parse.ParseLocal : IMedia.Parse.ParseNetwork;
      if (!media.parseAsync(flags, 1200)) {
        Log.w("VideoMetadata", "VLC parse did not start for " + item.extension());
        return item;
      }
      parsed.await(1600, TimeUnit.MILLISECONDS);
      checkInterrupted();
      long duration = Math.max(item.durationMs, media.getDuration());
      int width = item.width;
      int height = item.height;
      for (int i = 0; i < media.getTrackCount(); i++) {
        IMedia.Track track = media.getTrack(i);
        if (track instanceof IMedia.VideoTrack) {
          IMedia.VideoTrack video = (IMedia.VideoTrack) track;
          if (video.width > 0) width = video.width;
          if (video.height > 0) height = video.height;
          if (video.orientation >= IMedia.VideoTrack.Orientation.LeftTop) {
            int swap = width;
            width = height;
            height = swap;
          }
          break;
        }
      }
      return new VideoItem(
          item.id,
          item.name,
          item.uri,
          item.path,
          item.size,
          duration,
          item.modifiedMs,
          item.addedMs,
          width,
          height);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new CancellationException("视频扫描已取消");
    } catch (Exception | LinkageError unsupported) {
      if (unsupported instanceof CancellationException) throw (CancellationException) unsupported;
      Log.w("VideoMetadata", "VLC metadata failed for " + item.extension(), unsupported);
      return item;
    } finally {
      if (media != null) {
        media.setEventListener(null);
        media.release();
      }
      if (descriptor != null)
        try {
          descriptor.close();
        } catch (IOException ignored) {
        }
    }
  }

  public List<VideoItem> loadCache() {
    synchronized (cacheLock) {
      List<VideoItem> result = new ArrayList<>();
      try (FileInputStream input = cache.openRead();
          ByteArrayOutputStream data = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[16384];
        int read;
        while ((read = input.read(buffer)) != -1) data.write(buffer, 0, read);
        JSONArray items = new JSONArray(data.toString(StandardCharsets.UTF_8.name()));
        for (int i = 0; i < items.length(); i++) {
          JSONObject value = items.getJSONObject(i);
          long modified = value.optLong("modified");
          long added = value.optLong("added");
          result.add(
              new VideoItem(
                  value.optString("id"),
                  value.optString("name"),
                  value.optString("uri"),
                  value.optString("path"),
                  value.optLong("size"),
                  value.optLong("duration"),
                  modified,
                  added > 0 ? added : modified,
                  value.optInt("width"),
                  value.optInt("height")));
        }
      } catch (Exception ignored) {
      }
      return result;
    }
  }

  public void saveCache(List<VideoItem> items) {
    synchronized (cacheLock) {
      FileOutputStream stream = null;
      try {
        JSONArray values = new JSONArray();
        for (VideoItem item : items) {
          JSONObject value = new JSONObject();
          value
              .put("id", item.id)
              .put("name", item.name)
              .put("uri", item.uri)
              .put("path", item.path)
              .put("size", item.size)
              .put("duration", item.durationMs)
              .put("modified", item.modifiedMs)
              .put("added", item.addedMs)
              .put("width", item.width)
              .put("height", item.height);
          values.put(value);
        }
        stream = cache.startWrite();
        stream.write(values.toString().getBytes(StandardCharsets.UTF_8));
        cache.finishWrite(stream);
      } catch (Exception error) {
        if (stream != null) cache.failWrite(stream);
      }
    }
  }

  /** Preserves the extension if the user only enters a stem. Never overwrites a file. */
  public VideoItem rename(VideoItem item, String requestedName) throws Exception {
    String name = requestedName == null ? "" : requestedName.trim();
    if (name.isEmpty()
        || name.equals(".")
        || name.equals("..")
        || name.contains("/")
        || name.contains("\\")
        || name.indexOf('\0') >= 0) throw new IOException("请输入有效的文件名");
    if (name.lastIndexOf('.') < 0 && !item.extension().isEmpty()) name += "." + item.extension();
    if (!isSupportedVideo(name)) throw new IOException("请保留支持的视频扩展名，例如 ." + item.extension());
    if (name.equals(item.name)) return item;
    Uri uri = Uri.parse(item.uri);
    if ("file".equals(uri.getScheme())) {
      File old = new File(uri.getPath());
      File target = new File(old.getParentFile(), name);
      if (target.exists()) throw new IOException("这个文件夹中已经有同名文件");
      if (!old.renameTo(target)) throw new IOException("重命名失败，请检查文件访问权限");
      MediaScannerConnection.scanFile(
          context, new String[] {old.getPath(), target.getPath()}, null, null);
      return new VideoItem(
          "file:" + target.getCanonicalPath(),
          name,
          Uri.fromFile(target).toString(),
          target.getCanonicalPath(),
          target.length(),
          item.durationMs,
          target.lastModified(),
          item.addedMs,
          item.width,
          item.height);
    }
    if (DocumentsContract.isDocumentUri(context, uri)) {
      Uri renamed = DocumentsContract.renameDocument(resolver, uri, name);
      if (renamed == null) throw new IOException("文件提供方未能完成重命名");
      String path = documentPath(renamed.getAuthority(), DocumentsContract.getDocumentId(renamed));
      return new VideoItem(
          "document:" + renamed.getAuthority() + ":" + DocumentsContract.getDocumentId(renamed),
          name,
          renamed.toString(),
          path,
          item.size,
          item.durationMs,
          item.modifiedMs,
          item.addedMs,
          item.width,
          item.height);
    }
    // Some providers silently resolve collisions; reject an existing physical target first.
    if (!item.path.isEmpty()) {
      File target = new File(new File(item.path).getParentFile(), name);
      if (target.exists()) throw new IOException("这个文件夹中已经有同名文件");
    }
    ContentValues changes = new ContentValues();
    changes.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
    uri = mutationUri(uri);
    try {
      if (resolver.update(uri, changes, null, null) <= 0
          && !hasRequestedName(uri, name)
          && !physicalRenameCompleted(item, name)) throw noChange(uri, false);
    } catch (SecurityException security) {
      throw consentFor(uri, security, false);
    }
    String path =
        item.path.isEmpty() ? "" : new File(new File(item.path).getParentFile(), name).getPath();
    return new VideoItem(
        item.id,
        name,
        item.uri,
        path,
        item.size,
        item.durationMs,
        item.modifiedMs,
        item.addedMs,
        item.width,
        item.height);
  }

  public void delete(VideoItem item) throws Exception {
    Uri uri = Uri.parse(item.uri);
    if ("file".equals(uri.getScheme())) {
      File file = new File(uri.getPath());
      if (!file.delete()) throw new IOException("删除失败，请检查文件是否存在及访问权限");
      MediaScannerConnection.scanFile(context, new String[] {file.getPath()}, null, null);
    } else if (DocumentsContract.isDocumentUri(context, uri)) {
      if (!DocumentsContract.deleteDocument(resolver, uri)) throw new IOException("文件提供方未能删除视频");
    } else {
      uri = mutationUri(uri);
      try {
        if (resolver.delete(uri, null, null) <= 0) throw noChange(uri, true);
      } catch (SecurityException security) {
        throw consentFor(uri, security, true);
      }
    }
  }

  private Exception consentFor(Uri uri, SecurityException error, boolean deleting) {
    if (!deleting && hasWriteGrant(uri)) return error;
    if (Build.VERSION.SDK_INT >= 30 && "media".equals(uri.getAuthority())) {
      try {
        return new MutationConsentException(
            (deleting
                    ? MediaStore.createDeleteRequest(resolver, Collections.singletonList(uri))
                    : MediaStore.createWriteRequest(resolver, Collections.singletonList(uri)))
                .getIntentSender(),
            deleting);
      } catch (Exception ignored) {
      }
    }
    if (Build.VERSION.SDK_INT == 29 && error instanceof RecoverableSecurityException) {
      return new MutationConsentException(
          ((RecoverableSecurityException) error)
              .getUserAction()
              .getActionIntent()
              .getIntentSender(),
          false);
    }
    return error;
  }

  /** MediaStore consent accepts typed media URIs; generic /file IDs are rejected for videos. */
  static Uri mutationUri(Uri uri) {
    if (!"media".equals(uri.getAuthority())) return uri;
    List<String> parts = uri.getPathSegments();
    if (parts.size() == 3 && ("file".equals(parts.get(1)) || "downloads".equals(parts.get(1)))) {
      try {
        return ContentUris.withAppendedId(
            MediaStore.Video.Media.getContentUri(parts.get(0)), ContentUris.parseId(uri));
      } catch (NumberFormatException ignored) {
        return uri;
      }
    }
    return uri;
  }

  private boolean hasWriteGrant(Uri uri) {
    return context.checkUriPermission(
            uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        == PackageManager.PERMISSION_GRANTED;
  }

  private boolean hasRequestedName(Uri uri, String name) {
    try (Cursor cursor =
        resolver.query(
            uri, new String[] {MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
      return cursor != null && cursor.moveToFirst() && name.equals(cursor.getString(0));
    } catch (Exception ignored) {
      return false;
    }
  }

  private boolean physicalRenameCompleted(VideoItem item, String name) {
    if (item.path.isEmpty()) return false;
    File old = new File(item.path);
    File moved = new File(old.getParentFile(), name);
    if (old.exists() || !moved.isFile() || moved.length() != item.size) return false;
    // Some OEM providers can move the file but return zero before their row catches up.
    MediaScannerConnection.scanFile(
        context, new String[] {old.getPath(), moved.getPath()}, null, null);
    return true;
  }

  private Exception noChange(Uri uri, boolean deleting) {
    boolean exists = false;
    try (Cursor cursor =
        resolver.query(uri, new String[] {MediaStore.MediaColumns._ID}, null, null, null)) {
      exists = cursor != null && cursor.moveToFirst();
    } catch (Exception ignored) {
      // A read failure is different from an extant row that a provider silently filters for writes.
    }
    if (exists
        && Build.VERSION.SDK_INT >= 30
        && "media".equals(uri.getAuthority())
        && (deleting || (!hasWriteGrant(uri) && !hasBroadFileAccess()))) {
      return consentFor(uri, new SecurityException("系统需要授权后才能修改这个视频"), deleting);
    }
    return new IOException(
        exists
            ? (deleting ? "系统未能删除这个视频，请检查文件访问权限" : "系统未能重命名这个视频，请检查文件访问权限")
            : (deleting ? "没有找到可删除的视频" : "没有找到可重命名的视频"));
  }

  private void indexPrevious(List<VideoItem> items) {
    Map<String, VideoItem> next = new HashMap<>();
    for (VideoItem item : items) {
      next.put(item.uri, item);
      if (!item.path.isEmpty()) next.put(canonical(item.path), item);
    }
    previous = next;
  }

  private void retainPrevious(Map<String, VideoItem> found, String idPrefix, String uriPrefix) {
    // A transient provider/permission failure must not turn a saved catalogue into an empty one.
    for (VideoItem item : new HashSet<>(previous.values())) {
      if ((idPrefix != null && item.id.startsWith(idPrefix))
          || (uriPrefix != null && item.uri.startsWith(uriPrefix))) {
        found.putIfAbsent(key(item.uri, item.path), item);
      }
    }
  }

  private static VideoItem fillMissingMetadata(VideoItem item, VideoItem old) {
    return new VideoItem(
        item.id,
        item.name,
        item.uri,
        item.path,
        item.size,
        item.durationMs > 0 ? item.durationMs : old.durationMs,
        item.modifiedMs,
        item.addedMs > 0 ? item.addedMs : old.addedMs,
        item.width > 0 ? item.width : old.width,
        item.height > 0 ? item.height : old.height);
  }

  private static boolean sameFile(VideoItem item, VideoItem old) {
    return old != null && old.size == item.size && old.modifiedMs == item.modifiedMs;
  }

  private boolean hasBroadFileAccess() {
    return Build.VERSION.SDK_INT >= 30
        ? Environment.isExternalStorageManager()
        : Build.VERSION.SDK_INT <= 28
            && context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
  }

  private List<File> storageRoots() {
    LinkedHashSet<String> paths = new LinkedHashSet<>();
    paths.add(canonical(Environment.getExternalStorageDirectory().getPath()));
    if (Build.VERSION.SDK_INT >= 30) {
      StorageManager storage = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
      if (storage != null)
        for (StorageVolume volume : storage.getStorageVolumes()) {
          File directory = volume.getDirectory();
          if (directory != null && directory.canRead()) paths.add(canonical(directory.getPath()));
        }
    }
    for (File file : context.getExternalFilesDirs(null)) {
      if (file == null) continue;
      String path = file.getAbsolutePath();
      int marker = path.indexOf("/Android/");
      if (marker > 0) paths.add(canonical(path.substring(0, marker)));
    }
    List<File> result = new ArrayList<>();
    for (String path : paths) result.add(new File(path));
    return result;
  }

  private static String documentPath(String authority, String id) {
    if (!"com.android.externalstorage.documents".equals(authority) || id == null) return "";
    int colon = id.indexOf(':');
    if (colon < 0) return "";
    String volume = id.substring(0, colon);
    File root =
        "primary".equalsIgnoreCase(volume)
            ? Environment.getExternalStorageDirectory()
            : new File("/storage", volume);
    return canonical(new File(root, id.substring(colon + 1)).getPath());
  }

  private static boolean restrictedDirectory(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    return lower.endsWith("/android/data")
        || lower.endsWith("/android/obb")
        || lower.contains("/android/data/")
        || lower.contains("/android/obb/")
        || lower.endsWith("/.thumbnails")
        || lower.endsWith("/.trash");
  }

  private static void addWatchDirectory(Set<String> directories, File file) {
    if (file != null && directories.size() < 512 && file.isDirectory() && file.canRead())
      directories.add(canonical(file.getPath()));
  }

  private static String key(String uri, String path) {
    return path == null || path.isEmpty() ? uri : canonical(path);
  }

  private static String canonical(String path) {
    try {
      return new File(path).getCanonicalPath();
    } catch (IOException ignored) {
      return path;
    }
  }

  private static String string(Cursor cursor, String column) {
    int index = cursor.getColumnIndex(column);
    return index < 0 || cursor.isNull(index) ? "" : cursor.getString(index);
  }

  private static long number(Cursor cursor, String column) {
    int index = cursor.getColumnIndex(column);
    return index < 0 || cursor.isNull(index) ? 0 : cursor.getLong(index);
  }

  private static long parseLong(String value) {
    try {
      return Long.parseLong(value);
    } catch (Exception ignored) {
      return 0;
    }
  }

  private static void checkInterrupted() {
    if (Thread.currentThread().isInterrupted()) throw new CancellationException("视频扫描已取消");
  }

  private static String safeMessage(Exception error) {
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }
}
