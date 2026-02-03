package com.hhst.youtubelite.player.controller.gesture;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.GestureDetector;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;

import com.hhst.youtubelite.player.common.Constant;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.player.LitePlayerView;
import com.hhst.youtubelite.util.DeviceUtils;
import com.hhst.youtubelite.player.controller.Controller;
import com.hhst.youtubelite.player.engine.Engine;

import java.util.concurrent.TimeUnit;


@UnstableApi
public class PlayerGestureListener extends GestureDetector.SimpleOnGestureListener {

	private static final int MODE_NONE = 0;
	private static final int MODE_VERTICAL = 1;
	private static final int SEEK_STEP_MS = 10_000;
	private static final int SEEK_SUBMIT_DELAY_MS = 400;
	private static final float SCROLL_SENS = 0.1f;
	private static final float SCROLL_THRESHOLD = 18f;

	private final Activity activity;
	private final LitePlayerView playerView;
	private final Engine engine;
	private final Handler handler;
	private final Controller controller;

	private int gestureMode = MODE_NONE;
	private float vol = -1;
	private float bri = -1;

	private boolean isSeeking = false;
	private int seekAccumMs = 0;

	public PlayerGestureListener(Activity activity, LitePlayerView playerView, Engine engine, Controller controller) {
		this.activity = activity;
		this.playerView = playerView;
		this.engine = engine;
		this.controller = controller;
		this.handler = new Handler(activity.getMainLooper());
	}

	@Override
	public boolean onDown(@NonNull MotionEvent e) {
		gestureMode = MODE_NONE;
		vol = -1;
		bri = -1;
		return true;
	}

	@Override
	public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
		if (!isSeeking) controller.setControlsVisible(!controller.isControlsVisible());
		return true;
	}

	@Override
	public boolean onDoubleTap(@NonNull MotionEvent e) {
		vibrate(12, TimeUnit.MILLISECONDS);
		int playbackState = engine.getPlaybackState();
		if (playbackState != Player.STATE_READY && playbackState != Player.STATE_ENDED) return true;

		float x = e.getX();
		float width = playerView.getWidth();
		float third = width / 3;

		if (x >= third && x <= 2 * third) {
			if (engine.isPlaying()) engine.pause();
			else engine.play();
			controller.setControlsVisible(true);
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

	private final Runnable seek = this::seek;
	private void processSeek(int direction) {
		isSeeking = true;
		seekAccumMs += direction * SEEK_STEP_MS;
		handler.removeCallbacks(seek);
		handler.postDelayed(seek, SEEK_SUBMIT_DELAY_MS);
		String prefix = seekAccumMs > 0 ? "+" : "";
		controller.showHint(prefix + (seekAccumMs / 1000) + "s", -1);
	}

	private void seek() {
		activity.runOnUiThread(() -> {
			if (seekAccumMs != 0) {
				engine.seekBy(seekAccumMs);
				seekAccumMs = 0;
				isSeeking = false;
				controller.hideHint();
			}
		});
	}

	@Override
	public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float dx, float dy) {
		if (e1 == null || e2.getPointerCount() > 1 || isSeeking) return false;

		float absDx = Math.abs(dx);
		float absDy = Math.abs(dy);

		if (gestureMode == MODE_NONE) {
			if (absDy > absDx && absDy > SCROLL_THRESHOLD) {
				gestureMode = MODE_VERTICAL;
				vibrate(10, TimeUnit.MILLISECONDS);
			}
		}

		if (gestureMode == MODE_VERTICAL) {
			if (e1.getX() < playerView.getWidth() / 2f) adjustBrightness(dy);
			else adjustVolume(dy);
		}
		return true;
	}

	private void adjustBrightness(float dy) {
		if (bri == -1) bri = activity.getWindow().getAttributes().screenBrightness;
		if (bri < 0) bri = 0.5f;

		bri = DeviceUtils.adjustBrightness(activity, dy, playerView, bri, SCROLL_SENS);
		int percent = DeviceUtils.getBrightnessPercent(bri);
		controller.showHint(activity.getString(R.string.hint_brightness, percent), Constant.HINT_HIDE_DELAY_MS);
	}

	private void adjustVolume(float dy) {
		final AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
		final int maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
		if (vol == -1) vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);

		vol = DeviceUtils.adjustVolume(activity, dy, playerView, vol, SCROLL_SENS);
		int percent = DeviceUtils.getVolumePercent(vol, maxVolume);
		controller.showHint(activity.getString(R.string.hint_volume, percent), Constant.HINT_HIDE_DELAY_MS);
	}

	@Override
	public void onLongPress(@NonNull MotionEvent e) {
		if (!isSeeking && engine.getPlaybackState() == Player.STATE_READY && engine.isPlaying()) {
			vibrate(15, TimeUnit.MILLISECONDS);
			controller.setLongPress(true);
			engine.setPlaybackRate(2.0f);
			controller.showHint("2x", -1);
		}
	}

	private void vibrate(long duration, TimeUnit unit) {
		Vibrator v = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
		if (v != null && v.hasVibrator())
			v.vibrate(VibrationEffect.createOneShot(unit.toMillis(duration), VibrationEffect.DEFAULT_AMPLITUDE));
	}
}
