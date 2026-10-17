package io.mediamaster.app.media;

import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;

/** A local video. Times are milliseconds since epoch; duration is milliseconds. */
public final class VideoItem implements Serializable {
  private static final long serialVersionUID = 1L;

  public final String id;
  public final String name;
  public final String uri;
  public final String path;
  public final long size;
  public final long durationMs;
  public final long modifiedMs;
  public final long addedMs;
  public final int width;
  public final int height;

  public VideoItem(
      String id,
      String name,
      String uri,
      String path,
      long size,
      long durationMs,
      long modifiedMs,
      long addedMs,
      int width,
      int height) {
    this.id = id == null ? "" : id;
    this.name = name == null ? "" : name;
    this.uri = uri == null ? "" : uri;
    this.path = path == null ? "" : path;
    this.size = Math.max(0, size);
    this.durationMs = Math.max(0, durationMs);
    this.modifiedMs = Math.max(0, modifiedMs);
    this.addedMs = Math.max(0, addedMs);
    this.width = Math.max(0, width);
    this.height = Math.max(0, height);
  }

  public String extension() {
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  public VideoItem withName(String newName) {
    return new VideoItem(
        id, newName, uri, path, size, durationMs, modifiedMs, addedMs, width, height);
  }

  public boolean isPortrait() {
    return width > 0 && height > width;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof VideoItem && id.equals(((VideoItem) other).id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }
}
