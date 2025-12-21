package com.hhst.youtubelite.player;

import android.annotation.SuppressLint;
import android.app.Activity;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;

import com.hhst.youtubelite.MainActivity;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.player.interfaces.IController;
import com.hhst.youtubelite.player.interfaces.IControllerInternal;
import com.hhst.youtubelite.player.interfaces.IEngine;
import com.hhst.youtubelite.player.interfaces.IEngineInternal;
import com.hhst.youtubelite.player.interfaces.IPlayer;
import com.hhst.youtubelite.player.interfaces.IPlayerInternal;
import com.hhst.youtubelite.webview.YoutubeWebview;

import lombok.Getter;

/**
 * This class manages the creation and dependency injection of player components.
 */
@Getter
@UnstableApi
@SuppressLint("StaticFieldLeak")
public class PlayerContext {
	@Getter
	private static PlayerContext instance;
	private Activity activity;
	private PlayerView playerView;
	private PlayerPreferences preferences;
	private IPlayer player;
	private IPlayerInternal playerInternal;
	private IController controller;
	private IControllerInternal controllerInternal;
	private IEngine engine;
	private IEngineInternal engineInternal;

	/**
	 * Gets the singleton instance of PlayerContext.
	 *
	 * @param activity The activity context
	 * @return The singleton instance
	 */
	public static PlayerContext getInstance(Activity activity, YoutubeWebview webview) {
		if (instance == null) {
			instance = new PlayerContext();
			instance.populate(activity, webview);
		}
		return instance;
	}

	private void populate(Activity activity, YoutubeWebview webview) {
		this.activity = activity;
		this.playerView = activity.findViewById(R.id.playerView);
		this.preferences = new PlayerPreferences(((MainActivity) activity).getExtensionManager());

		// Create engine first
		PlayerEngine playerEngine = new PlayerEngine();
		this.engine = playerEngine;
		this.engineInternal = playerEngine;

		YoutubePlayer youtubePlayer = new YoutubePlayer(activity, webview, playerEngine);

		// Public interfaces for external use
		this.player = youtubePlayer;

		// Internal interfaces for module use
		this.playerInternal = youtubePlayer;

		// Create controller
		PlayerController playerController = new PlayerController(activity, playerView);
		this.controller = playerController;
		this.controllerInternal = playerController;
	}

}