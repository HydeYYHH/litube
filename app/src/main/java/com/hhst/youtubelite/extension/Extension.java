package com.hhst.youtubelite.extension;

import static com.hhst.youtubelite.Constant.ENABLE_BACKGROUND_PLAY;
import static com.hhst.youtubelite.Constant.ENABLE_IN_APP_MINI_PLAYER;
import static com.hhst.youtubelite.Constant.ENABLE_PIP;
import static com.hhst.youtubelite.Constant.REMEMBER_LAST_POSITION;
import static com.hhst.youtubelite.Constant.REMEMBER_RESIZE_MODE;
import static com.hhst.youtubelite.Constant.SKIP_POI_HIGHLIGHT;
import static com.hhst.youtubelite.Constant.SKIP_SELF_PROMO;
import static com.hhst.youtubelite.Constant.SKIP_SPONSORS;

import com.hhst.youtubelite.R;

import java.util.List;

/**
 * Value object for app logic.
 */
public record Extension(String key, int title, int summary, int icon, List<Extension> children) {

	public Extension {
		children = children == null ? List.of() : children;
	}

	public static Extension root() {
		return page(R.string.extension, 0, 0, List.of(
						page(R.string.interface_category, R.string.interface_summary, R.drawable.ic_settings, List.of(
										toggle(Constant.ENABLE_DISPLAY_DISLIKES, R.string.display_dislikes),
										toggle(Constant.ENABLE_HIDE_SHORTS, R.string.hide_shorts)
						)),
						page(R.string.player, R.string.playback_summary, R.drawable.ic_play, List.of(
										toggle(REMEMBER_LAST_POSITION, R.string.remember_last_position),
										toggle(Constant.REMEMBER_QUALITY, R.string.remember_quality),
										toggle(Constant.REMEMBER_PLAYBACK_SPEED, R.string.remember_playback_speed),
										toggle(REMEMBER_RESIZE_MODE, R.string.remember_resize_mode)
						)),
						page(R.string.gesture, R.string.gesture_summary, R.drawable.ic_gesture, List.of(
										page(R.string.gesture_single_tap, 0, 0, List.of(
														toggle(Constant.GESTURE_TAP_WINDOWED, R.string.enable_in_windowed),
														toggle(Constant.GESTURE_TAP_FULLSCREEN, R.string.enable_in_fullscreen)
										)),
										page(R.string.gesture_double_tap, 0, 0, List.of(
														toggle(Constant.GESTURE_DOUBLE_TAP_WINDOWED, R.string.enable_in_windowed),
														toggle(Constant.GESTURE_DOUBLE_TAP_FULLSCREEN, R.string.enable_in_fullscreen)
										)),
										page(R.string.gesture_long_press_speed, 0, 0, List.of(
														toggle(Constant.GESTURE_LONG_PRESS_WINDOWED, R.string.enable_in_windowed),
														toggle(Constant.GESTURE_LONG_PRESS_FULLSCREEN, R.string.enable_in_fullscreen)
										)),
										page(R.string.brightness, 0, 0, List.of(
														toggle(Constant.GESTURE_BRIGHTNESS_WINDOWED, R.string.enable_in_windowed),
														toggle(Constant.GESTURE_BRIGHTNESS_FULLSCREEN, R.string.enable_in_fullscreen)
										)),
										page(R.string.volume, 0, 0, List.of(
														toggle(Constant.GESTURE_VOLUME_WINDOWED, R.string.enable_in_windowed),
														toggle(Constant.GESTURE_VOLUME_FULLSCREEN, R.string.enable_in_fullscreen)
										)),
										page(R.string.gesture_seek, 0, 0, List.of(
														toggle(Constant.GESTURE_SEEK_WINDOWED, R.string.enable_in_windowed),
														toggle(Constant.GESTURE_SEEK_FULLSCREEN, R.string.enable_in_fullscreen)
										)),
										page(R.string.gesture_fullscreen_swipe, 0, 0, List.of(
														toggle(Constant.GESTURE_FULLSCREEN_WINDOWED, R.string.enable_in_windowed),
														toggle(Constant.GESTURE_FULLSCREEN_FULLSCREEN, R.string.enable_in_fullscreen)
										))
						)),
						page(R.string.background_mini_player, R.string.background_mini_player_summary, R.drawable.ic_pip, List.of(
										toggle(ENABLE_PIP, R.string.pip),
										toggle(ENABLE_IN_APP_MINI_PLAYER, R.string.in_app_mini_player),
										toggle(ENABLE_BACKGROUND_PLAY, R.string.background_play)
						)),
						page(R.string.sponsorblock, R.string.sponsorblock_summary, R.drawable.ic_block, List.of(
										toggle(SKIP_SPONSORS, R.string.skip_sponsors),
										toggle(SKIP_SELF_PROMO, R.string.skip_sponsors_selfpromo),
										toggle(SKIP_POI_HIGHLIGHT, R.string.skip_sponsors_highlight)
						))
		));
	}

	private static Extension page(int title, int summary, int icon, List<Extension> children) {
		return new Extension(null, title, summary, icon, children);
	}

	private static Extension toggle(String key, int title) {
		return new Extension(key, title, 0, 0, List.of());
	}

	public boolean hasChildren() {
		return !children.isEmpty();
	}
}
