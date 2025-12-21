package com.hhst.youtubelite.player.interfaces;

import androidx.media3.common.VideoSize;

/**
 * Public interface for external use containing engine functionality.
 */
public interface IEngine {
	boolean isPlaying();

	void play();

	void pause();

	void skipToNext();

	void skipToPrevious();

	void seekTo(long position);

	VideoSize getVideoSize();
}