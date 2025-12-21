package com.hhst.youtubelite.player.interfaces;

import com.hhst.youtubelite.PlaybackService;

/**
 * Public interface for external use containing core player functionality.
 */
public interface IPlayer {
	/**
	 * Starts playback of a video from the given URL.
	 *
	 * @param url The video URL to play
	 */
	void play(String url);

	/**
	 * Hides the player and stops playback.
	 */
	void hide();

	/**
	 * Sets the height of the player view.
	 *
	 * @param height The desired height in pixels
	 */
	void setPlayerHeight(int height);

	void attachPlaybackService(PlaybackService playbackService);

	void release();

}