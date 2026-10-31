package io.mediamaster.app.player;

import android.content.Context;

/** Explicit engine preference; unsupported files can still fall back once. */
final class PlaybackEngineChoice {
  static final int AUTO = 0, SYSTEM = 1, VLC = 2;

  static int get(Context context) {
    int value = context.getSharedPreferences("player", 0).getInt("engine_choice", AUTO);
    return value >= AUTO && value <= VLC ? value : AUTO;
  }

  static void set(Context context, int value) {
    if (value < AUTO || value > VLC) throw new IllegalArgumentException("Unknown engine");
    context.getSharedPreferences("player", 0).edit().putInt("engine_choice", value).apply();
  }
}
