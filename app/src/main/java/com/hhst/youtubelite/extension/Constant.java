package com.hhst.youtubelite.extension;

import static com.hhst.youtubelite.Constant.ENABLE_BACKGROUND_PLAY;
import static com.hhst.youtubelite.Constant.ENABLE_PIP;
import static com.hhst.youtubelite.Constant.REMEMBER_LAST_POSITION;
import static com.hhst.youtubelite.Constant.REMEMBER_RESIZE_MODE;
import static com.hhst.youtubelite.Constant.SKIP_POI_HIGHLIGHT;
import static com.hhst.youtubelite.Constant.SKIP_SELF_PROMO;
import static com.hhst.youtubelite.Constant.SKIP_SPONSORS;

import java.util.Map;

public class Constant {
	public static final String ENABLE_DISPLAY_DISLIKES = "enable_display_dislikes";
	public static final String ENABLE_HIDE_SHORTS = "enable_hide_shorts";
	public static final String REMEMBER_QUALITY = "remember_quality";
	public static final String REMEMBER_PLAYBACK_SPEED = "remember_playback_speed";

	public static final Map<String, Boolean> DEFAULT_PREFERENCES = Map.ofEntries(
					Map.entry(ENABLE_DISPLAY_DISLIKES, true),
					Map.entry(ENABLE_HIDE_SHORTS, false),
					Map.entry(SKIP_SPONSORS, true),
					Map.entry(SKIP_SELF_PROMO, true),
					Map.entry(SKIP_POI_HIGHLIGHT, true),
					Map.entry(REMEMBER_LAST_POSITION, true),
					Map.entry(REMEMBER_QUALITY, true),
					Map.entry(ENABLE_BACKGROUND_PLAY, true),
					Map.entry(ENABLE_PIP, true),
					Map.entry(REMEMBER_RESIZE_MODE, false),
					Map.entry(REMEMBER_PLAYBACK_SPEED, false)
	);
}
