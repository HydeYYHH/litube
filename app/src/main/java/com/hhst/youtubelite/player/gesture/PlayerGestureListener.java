package com.hhst.youtubelite.player.gesture;

import android.app.Activity;
import android.os.Handler;
import android.view.GestureDetector;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;

import com.hhst.youtubelite.R;
import com.hhst.youtubelite.player.interfaces.IControllerInternal;
import com.hhst.youtubelite.player.interfaces.IEngineInternal;
import com.hhst.youtubelite.player.interfaces.IPlayerInternal;
import com.hhst.youtubelite.player.PlayerUtils;

@UnstableApi
public class PlayerGestureListener extends GestureDetector.SimpleOnGestureListener {

	private static final float SCROLL_THRESHOLD = 18f;
	private static final float SCROLL_SENS = 0.1f;
	private static final int MODE_NONE = 0;
	private static final int MODE_VERTICAL = 1;

	private static final int SEEK_STEP_MS = 10_000;
	private static final int HINT_HIDE_DELAY_MS = 500;
	private static final int SEEK_SUBMIT_DELAY_MS = 600;

	private final Activity activity;
	private final PlayerView playerView;
	private final IPlayerInternal iPlayer;
	private final IEngineInternal engine;
	private final Handler handler;
	private final IControllerInternal gestureHandler;

	private int gestureMode = MODE_NONE;
	private float vol = -1;
	private float bri = -1;

	private boolean isSeeking = false;
	private int seekAccumMs = 0;
	private final Runnable seekRunnable = new Runnable() {
		@Override
		public void run() {
			activity.runOnUiThread(() -> {
				if (engine.getPlaybackState() == Player.STATE_READY || engine.getPlaybackState() == Player.STATE_ENDED) {
					long pos = engine.position();
					long duration = iPlayer.getDuration();
					long target = Math.max(0, Math.min(duration, pos + seekAccumMs));
					engine.seekTo(target);
					iPlayer.updateProgress(target);
				}
				isSeeking = false;
				seekAccumMs = 0;
				gestureHandler.hideHint();
			});
		}
	};

	public PlayerGestureListener(Activity activity, PlayerView playerView, IPlayerInternal iPlayer, IEngineInternal engine, Handler handler, IControllerInternal gestureHandler) {
		this.activity = activity;
		this.playerView = playerView;
		this.iPlayer = iPlayer;
		this.engine = engine;
		this.handler = handler;
		this.gestureHandler = gestureHandler;
	}

	private void processSeek(int dir) {
		handler.removeCallbacks(seekRunnable);
		if (!isSeeking) {
			isSeeking = true;
			seekAccumMs = dir * SEEK_STEP_MS;
		} else {
			boolean isForward = seekAccumMs > 0;
			boolean newIsForward = dir > 0;
			if (isForward != newIsForward) seekAccumMs = dir * SEEK_STEP_MS;
			else seekAccumMs += dir * SEEK_STEP_MS;
		}

		int secs = Math.abs(seekAccumMs / 1000);
		if (seekAccumMs > 0)
			gestureHandler.showHint(activity.getString(R.string.hint_seek_plus, secs), -1);
		else gestureHandler.showHint(activity.getString(R.string.hint_seek_minus, secs), -1);

		handler.postDelayed(seekRunnable, SEEK_SUBMIT_DELAY_MS);
	}

	@Override
	public boolean onDown(@NonNull MotionEvent e) {
		vol = -1;
		bri = -1;
		gestureMode = MODE_NONE;
		return true;
	}

	@Override
	public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
		if (!isSeeking) gestureHandler.setControlsVisible(!gestureHandler.isControlsVisible());
		return true;
	}

	@Override
	public boolean onDoubleTap(@NonNull MotionEvent e) {
		int playbackState = engine.getPlaybackState();
		if (playbackState != Player.STATE_READY && playbackState != Player.STATE_ENDED) return true;

		float x = e.getX();
		float width = playerView.getWidth();
		float third = width / 3;

		if (x >= third && x <= 2 * third) {
			if (engine.isPlaying()) engine.pause();
			else engine.play();
			gestureHandler.setControlsVisible(true);
		} else if (x < third) processSeek(-1);
		else processSeek(1);
		return true;
	}

	@Override
	public boolean onSingleTapUp(@NonNull MotionEvent e) {
		if (isSeeking) {
			float x = e.getX();
			float width = playerView.getWidth();
			float third = width / 3;

			if (seekAccumMs > 0 && x > 2 * third) processSeek(1);
			else if (seekAccumMs < 0 && x < third) processSeek(-1);
		}
		return true;
	}

	@Override
	public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float dx, float dy) {
		if (e2.getPointerCount() > 1 || isSeeking) return false;

		float absDx = Math.abs(dx);
		float absDy = Math.abs(dy);

		if (gestureMode == MODE_NONE)
			if (absDy > absDx && absDy > SCROLL_THRESHOLD) gestureMode = MODE_VERTICAL;

		if (gestureMode == MODE_NONE) return false;

		gestureHandler.setGesturing(true);

		float x = e1.getX();
		float width = playerView.getWidth();

		if (gestureMode == MODE_VERTICAL) {
			if (x < width / 2) adjustBrightness(dy);
			else adjustVolume(dy);
			return true;
		}
		return true;
	}

	private void adjustVolume(float dy) {
		vol = PlayerUtils.adjustVolume(activity, dy, playerView, vol, SCROLL_SENS);
		int percent = PlayerUtils.getVolumePercent(activity, vol);
		gestureHandler.showHint(activity.getString(R.string.hint_volume, percent), HINT_HIDE_DELAY_MS);
	}

	private void adjustBrightness(float dy) {
		bri = PlayerUtils.adjustBrightness(activity, dy, playerView, bri, SCROLL_SENS);
		int percent = PlayerUtils.getBrightnessPercent(bri);
		gestureHandler.showHint(activity.getString(R.string.hint_brightness, percent), HINT_HIDE_DELAY_MS);
	}

	@Override
	public void onLongPress(@NonNull MotionEvent e) {
		if (!isSeeking && engine.getPlaybackState() == Player.STATE_READY && engine.isPlaying()) {
			gestureHandler.setLongPress(true);
			engine.setSpeed(2.0f);
			gestureHandler.showHint("2x", -1);
		}
	}
}