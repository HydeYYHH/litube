package com.hhst.youtubelite.player.controller.gesture;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;

import com.hhst.youtubelite.R;
import com.hhst.youtubelite.player.LitePlayerView;
import com.hhst.youtubelite.player.controller.Controller;
import com.hhst.youtubelite.player.engine.Engine;
import com.hhst.youtubelite.util.DeviceUtils;

import java.util.Locale;

/**
 * Gesture listener that maps swipes and taps to playback controls.
 */
@UnstableApi
public class PlayerGestureListener extends GestureDetector.SimpleOnGestureListener {
	private static final int AUTO_HIDE_DELAY_MS = 200;
	private static final int SEEK_WINDOW_MS = 600;

	private final Activity activity;
	private final LitePlayerView playerView;
	private final Engine engine;
	private final Controller controller;
	private final Handler handler;
	private final Runnable hideHint;

	private int gestureMode;
	private float brightness = -1, longPressSpeed = 1.0f;
	private boolean longPressing, gesturing, swipeTriggered;
	private long seekStartPos;

	private int seekAccum;
	private final Runnable resetSeek = () -> seekAccum = 0;
	private long lastTapTime;
	private float volume = -1;

	public PlayerGestureListener(Activity activity, LitePlayerView playerView, Engine engine, Controller controller) {
		this.activity = activity;
		this.playerView = playerView;
		this.engine = engine;
		this.controller = controller;
		this.handler = new Handler(activity.getMainLooper());
		this.hideHint = controller::hideHint;
	}

	private static DoubleTapAction getDoubleTapAction(float x, float width) {
		if (width <= 0f) return DoubleTapAction.TOGGLE_PLAYBACK;
		if (x < width / 3f) return DoubleTapAction.SEEK_BACKWARD;
		if (x > width * 2f / 3f) return DoubleTapAction.SEEK_FORWARD;
		return DoubleTapAction.TOGGLE_PLAYBACK;
	}

	private boolean isEnabled() {
		return controller.getExtensionManager().isEnabled(com.hhst.youtubelite.extension.Constant.ENABLE_PLAYER_GESTURES);
	}

	public void onTouchRelease() {
		if (!isEnabled()) return;
		if (longPressing) {
			engine.setPlaybackRate(longPressSpeed);
			updateSpeedButtonUI(longPressSpeed);
			controller.hideHint();
			longPressing = false;
		}
		if (gesturing) {
			handler.postDelayed(hideHint, AUTO_HIDE_DELAY_MS);
			gesturing = false;
		}
	}

	@Override
	public boolean onDown(@NonNull MotionEvent e) {
		if (!isEnabled()) return false;
		handler.removeCallbacks(hideHint);
		gestureMode = 0;
		brightness = -1;
		volume = -1;
		gesturing = false;
		swipeTriggered = false;
		seekStartPos = engine.position();
		return true;
	}

	@Override
	public boolean onSingleTapUp(@NonNull MotionEvent e) {
		if (!isEnabled()) return false;
		long now = System.currentTimeMillis();
		float x = e.getX();
		float width = playerView.getWidth();
		DoubleTapAction action = getDoubleTapAction(x, width);

		if (seekAccum != 0 && (now - lastTapTime < SEEK_WINDOW_MS)) {
			if ((seekAccum < 0 && action == DoubleTapAction.SEEK_BACKWARD)
							|| (seekAccum > 0 && action == DoubleTapAction.SEEK_FORWARD)) {
				processSeek(action == DoubleTapAction.SEEK_BACKWARD);
				lastTapTime = now;
				return true;
			}
		}
		return super.onSingleTapUp(e);
	}

	@Override
	public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
		controller.setControlsVisible(!controller.isControlsVisible());
		return true;
	}

	@Override
	public boolean onDoubleTap(@NonNull MotionEvent e) {
		if (!isEnabled()) return false;
		switch (getDoubleTapAction(e.getX(), playerView.getWidth())) {
			case SEEK_BACKWARD:
				processSeek(true);
				lastTapTime = System.currentTimeMillis();
				return true;
			case SEEK_FORWARD:
				processSeek(false);
				lastTapTime = System.currentTimeMillis();
				return true;
			case TOGGLE_PLAYBACK:
				if (engine.isPlaying()) {
					engine.pause();
				} else {
					engine.play();
				}
				controller.setControlsVisible(true);
				return true;
			default:
				return false;
		}
	}

	private void processSeek(boolean isLeft) {
		handler.removeCallbacks(resetSeek);
		if (isLeft) {
			seekAccum -= 10;
			engine.seekBy(-10000);
			controller.showHint(seekAccum + "s", 500);
		} else {
			seekAccum += 10;
			engine.seekBy(10000);
			controller.showHint("+" + seekAccum + "s", 500);
		}
		handler.postDelayed(resetSeek, SEEK_WINDOW_MS);
	}

	@Override
	public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float dx, float dy) {
		if (!isEnabled() || e1 == null || e2.getPointerCount() > 1 || longPressing) return false;
		if (gestureMode == 0) {
			if (Math.abs(dy) > Math.abs(dx)) gestureMode = 1;
			else if (Math.abs(dx) > Math.abs(dy)) gestureMode = 2;
		}
		if (gestureMode == 1) {
			gesturing = true;
			handler.removeCallbacks(hideHint);
			float x = e1.getX(), width = playerView.getWidth();
			if (x < width * 0.35f) adjustBrightness(dy);
			else if (x > width * 0.65f) adjustVolume(dy);
			else handleCenterVerticalGesture(e1, e2);
			handler.postDelayed(hideHint, AUTO_HIDE_DELAY_MS);
		} else if (gestureMode == 2) {
			gesturing = true;
			handler.removeCallbacks(hideHint);
			adjustSeek(e1, e2);
			handler.postDelayed(hideHint, AUTO_HIDE_DELAY_MS);
		}
		return true;
	}

	private void adjustSeek(MotionEvent e1, MotionEvent e2) {
		float width = playerView.getWidth();
		long offset = (long) (((e2.getX() - e1.getX()) / width) * 120000);
		long pos = seekStartPos + offset;
		engine.seekTo(pos);
		controller.showHint(formatTime(pos), -1);
	}

	private String formatTime(long ms) {
		if (ms < 0) ms = 0;
		int seconds = (int) (ms / 1000) % 60;
		int minutes = (int) ((ms / (1000 * 60)) % 60);
		int hours = (int) ((ms / (1000 * 60 * 60)) % 24);
		if (hours > 0)
			return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
		return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
	}

	private void adjustBrightness(float dy) {
		brightness = DeviceUtils.adjustBrightness(activity, dy, playerView, brightness, 0.5f);
		controller.showHint(Math.round(brightness * 100) + "%", -1);
	}

	private void adjustVolume(float dy) {
		AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
		if (am == null) return;
		if (volume == -1) volume = (float) am.getStreamVolume(AudioManager.STREAM_MUSIC);
		volume = DeviceUtils.adjustVolume(activity, dy, playerView, volume, 0.4f);
		int pct = Math.round((volume / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) * 100);
		controller.showHint(pct + "%", -1);
	}

	private void handleCenterVerticalGesture(@NonNull MotionEvent e1, @NonNull MotionEvent e2) {
		if (swipeTriggered) return;
		float dy = e2.getY() - e1.getY();
		float threshold = playerView.getHeight() * 0.08f;
		if (Math.abs(dy) < threshold) return;

		swipeTriggered = true;
		if (dy < 0 && !controller.isFullscreen()) {
			controller.enterFullscreen();
		} else if (dy > 0 && controller.isFullscreen()) {
			controller.exitFullscreen();
		}
	}

	@Override
	public void onLongPress(@NonNull MotionEvent e) {
		if (!isEnabled() || !engine.isPlaying()) return;
		vibrate();
		longPressSpeed = engine.getPlaybackRate();
		longPressing = true;
		engine.setPlaybackRate(2.0f);
		updateSpeedButtonUI(2.0f);
		controller.showHint("2x", -1);
	}

	private void updateSpeedButtonUI(float speed) {
		TextView v = playerView.findViewById(R.id.btn_speed);
		if (v != null) v.setText(String.format(Locale.getDefault(), "%.2fx", speed));
	}

	private void vibrate() {
		Vibrator vib = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
		if (vib != null && vib.hasVibrator())
			vib.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE));
	}

/**
 * Enumeration of app logic.
 */
	private enum DoubleTapAction {
		SEEK_BACKWARD,
		TOGGLE_PLAYBACK,
		SEEK_FORWARD
	}
}




