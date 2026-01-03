package com.hhst.youtubelite.extension;

import com.hhst.youtubelite.R;

import java.util.List;

import static com.hhst.youtubelite.Constant.*;

public record Extension(String key, int description, List<Extension> children) {

	public static List<Extension> defaultExtensionTree() {
		return List.of(
						new Extension(null, R.string.general, List.of(
										new Extension(Constant.ENABLE_DISPLAY_DISLIKES, R.string.display_dislikes, null),
										new Extension(Constant.ENABLE_HIDE_SHORTS, R.string.hide_shorts, null),
										new Extension(ENABLE_PIP, R.string.pip, null)
						)),
						new Extension(null, R.string.player, List.of(
										new Extension(REMEMBER_LAST_POSITION, R.string.remember_last_position, null),
										new Extension(Constant.REMEMBER_QUALITY, R.string.remember_quality, null),
										new Extension(Constant.REMEMBER_PLAYBACK_SPEED, R.string.remember_playback_speed, null),
										new Extension(REMEMBER_RESIZE_MODE, R.string.remember_resize_mode, null),
										new Extension(ENABLE_BACKGROUND_PLAY, R.string.background_play, null)
						)),
						new Extension(null, R.string.skip_sponsors, List.of(
										new Extension(SKIP_SPONSORS, R.string.skip_sponsors, null),
										new Extension(SKIP_SELF_PROMO, R.string.skip_sponsors_selfpromo, null),
										new Extension(SKIP_POI_HIGHLIGHT, R.string.skip_sponsors_highlight, null)
						))
		);
	}
}
