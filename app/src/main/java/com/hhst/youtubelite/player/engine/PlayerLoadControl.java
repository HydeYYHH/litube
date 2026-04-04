package com.hhst.youtubelite.player.engine;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;

/**
 * Component that handles app logic.
 */
@OptIn(markerClass = UnstableApi.class)
class PlayerLoadControl {
	private PlayerLoadControl() {
	}

	static DefaultLoadControl create() {
		return new DefaultLoadControl.Builder()
						.setBufferDurationsMs(
										90_000,
										300_000,
										1_500,
										15_000)
						.setPrioritizeTimeOverSizeThresholds(true)
						.build();
	}
}
