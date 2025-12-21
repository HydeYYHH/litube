package com.hhst.youtubelite.extension;

import com.hhst.youtubelite.R;
import java.util.List;
public class Extension {
  private String key; // If key is null, this is a group node.
  private int description;
  private List<Extension> children; // If children is null, this is a leaf node.

  public Extension(String key, int description, List<Extension> children) {
    this.key = key;
    this.description = description;
    this.children = children;
  }

  public String getKey() {
    return key;
  }

  public int getDescription() {
    return description;
  }

  public List<Extension> getChildren() {
    return children;
  }

  public static List<Extension> defaultExtensionTree() {
    return List.of(
        new Extension(
            null,
            R.string.general,
            List.of(
                new Extension(Constant.enableDisplayDislikes, R.string.display_dislikes, null),
                new Extension(Constant.enableHideShorts, R.string.hide_shorts, null),
                new Extension(Constant.enableLiveChat, R.string.enable_live_chat, null),
		            new Extension(Constant.enablePip, R.string.enable_pip, null))),
        new Extension(
            null,
            R.string.player,
            List.of(
                new Extension(Constant.rememberLastPosition, R.string.remember_last_position, null),
                new Extension(Constant.rememberQuality, R.string.remember_quality, null),
                new Extension(Constant.rememberPlaybackSpeed, R.string.remember_playback_speed, null),
                new Extension(Constant.rememberResizeMode, R.string.remember_resize_mode, null),
                new Extension(Constant.rememberAudioTrack, R.string.remember_audio_track, null),
                new Extension(Constant.enableBackgroundPlay, R.string.background_play, null))),
        new Extension(
            null,
            R.string.skip_sponsors,
            List.of(
                new Extension(Constant.skipSponsor, R.string.skip_sponsors_sponsor, null),
                new Extension(Constant.skipSelfPromo, R.string.skip_sponsors_selfpromo, null),
                new Extension(Constant.skipHighlight, R.string.skip_sponsors_highlight, null)))
        );
  }
}
