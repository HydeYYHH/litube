package com.hhst.youtubelite.extension;

import java.util.Map;

public class Constant {

  // Extension key
  public static final String enableDisplayDislikes = "enable_display_dislikes";
  public static final String enableHideShorts = "enable_hide_shorts";
  public static final String enableH264ify = "enable_h264ify";
  public static final String enableLiveChat = "enable_live_chat";
  public static final String skipSponsor = "skip_sponsors";
  public static final String skipSelfPromo = "skip_self_promo";
  public static final String skipHighlight = "skip_poi_highlight";
  public static final String enableCpuTamer = "enable_cpu_tamer";
  public static final String rememberLastPosition = "remember_last_position";
  public static final String rememberQuality = "remember_quality";
  public static final String enableBackgroundPlay = "enable_background_play";
  public static final String rememberPlaybackSpeed = "remember_playback_speed";
  public static final Map<String, Boolean> defaultPreferences =
      Map.ofEntries(
          Map.entry(enableDisplayDislikes, true),
          Map.entry(enableHideShorts, false),
          Map.entry(enableH264ify, false),
          Map.entry(enableLiveChat, true),
          Map.entry(skipSponsor, true),
          Map.entry(skipSelfPromo, true),
          Map.entry(skipHighlight, true),
          Map.entry(enableCpuTamer, false),
          Map.entry(rememberLastPosition, true),
          Map.entry(rememberQuality, true),
          Map.entry(enableBackgroundPlay, true),
          Map.entry(rememberPlaybackSpeed, false));
}
