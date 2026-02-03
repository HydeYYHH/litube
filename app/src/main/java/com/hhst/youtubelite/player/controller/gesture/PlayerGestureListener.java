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
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;

import com.hhst.youtubelite.R;
import com.hhst.youtubelite.player.LitePlayerView;
import com.hhst.youtubelite.player.controller.Controller;
import com.hhst.youtubelite.player.engine.Engine;
import com.hhst.youtubelite.util.DeviceUtils;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

@UnstableApi
public class PlayerGestureListener extends GestureDetector.SimpleOnGestureListener {

    private static final int MODE_NONE = 0;
    private static final int MODE_VERTICAL = 1;
    private static final int SEEK_STEP_MS = 10_000;
    private static final int SEEK_SUBMIT_DELAY_MS = 400;
    private static final float SCROLL_SENS = 0.1f;
    private static final float SCROLL_THRESHOLD = 18f;

    // TUNED SENSITIVITY CONSTANTS
    private static final float SPEED_SENSITIVITY = 0.015f;
    private static final float VOLUME_SENSITIVITY_BOOST = 8.0f;
    // Matching Brightness boost to Volume boost for identical feel
    private static final float BRIGHTNESS_SENSITIVITY_BOOST = 8.0f;

    private static final int AUTO_HIDE_DELAY_MS = 200;
    private static final String ICON_BRI = "☀️ ";

    private final Activity activity;
    private final LitePlayerView playerView;
    private final Engine engine;
    private final Handler handler;
    private final Controller controller;

    private int gestureMode = MODE_NONE;
    private float vol = -1;
    private float bri = -1;
    private float currentSpeed = -1f;
    private float preLongPressSpeed = 1.0f;
    private boolean isLongPressing = false;
    private boolean isGesturing = false;

    private boolean isSeeking = false;
    private int seekAccumMs = 0;

    private final Runnable hideHintRunnable;

    public PlayerGestureListener(Activity activity, LitePlayerView playerView, Engine engine, Controller controller) {
        this.activity = activity;
        this.playerView = playerView;
        this.engine = engine;
        this.controller = controller;
        this.handler = new Handler(activity.getMainLooper());

        this.hideHintRunnable = () -> {
            if (!isLongPressing) {
                this.controller.hideHint();
            }
        };

        this.playerView.setGestureListener(this);
    }

    public void onTouchRelease() {
        if (isLongPressing) {
            engine.setPlaybackRate(preLongPressSpeed);
            updateSpeedButtonUI(preLongPressSpeed);
            controller.hideHint();
            isLongPressing = false;
        }
        handler.removeCallbacks(hideHintRunnable);
        handler.post(hideHintRunnable);
    }

    @Override
    public boolean onDown(@NonNull MotionEvent e) {
        handler.removeCallbacks(hideHintRunnable);
        gestureMode = MODE_NONE;
        vol = -1;
        bri = -1;
        currentSpeed = -1f;
        isGesturing = false;
        return true;
    }

    @Override
    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
        controller.setControlsVisible(!controller.isControlsVisible());
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

    private void updateSpeedButtonUI(float speed) {
        final TextView speedView = playerView.findViewById(R.id.btn_speed);
        if (speedView != null) {
            speedView.setText(String.format(Locale.getDefault(), "%sx", speed));
        }
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
        if (e1 == null || e2.getPointerCount() > 1 || isSeeking || isLongPressing) return false;

        float absDx = Math.abs(dx);
        float absDy = Math.abs(dy);

        if (gestureMode == MODE_NONE) {
            if (absDy > absDx && absDy > SCROLL_THRESHOLD) {
                gestureMode = MODE_VERTICAL;
                vibrate(10, TimeUnit.MILLISECONDS);
            }
        }

        if (gestureMode == MODE_VERTICAL) {
            handler.removeCallbacks(hideHintRunnable);

            float x = e1.getX();
            float width = playerView.getWidth();

            if (x < width * 0.30f) adjustBrightness(dy);
            else if (x > width * 0.70f) adjustVolume(dy);
            else adjustPlaybackSpeed(dy);

            handler.postDelayed(hideHintRunnable, AUTO_HIDE_DELAY_MS);
        }
        return true;
    }

    private void adjustBrightness(float dy) {
        if (bri == -1) {
            bri = activity.getWindow().getAttributes().screenBrightness;
            if (bri < 0) bri = 0.5f; // Default if not set
        }

        // Apply the BOOST here to make it match the Volume sensitivity
        bri = DeviceUtils.adjustBrightness(activity, dy, playerView, bri, SCROLL_SENS * BRIGHTNESS_SENSITIVITY_BOOST);

        // Map 0.0-1.0 to 1-15 scale
        int level = (int) (bri * 14) + 1;
        if (level < 1) level = 1;
        if (level > 15) level = 15;

        controller.showHint(ICON_BRI + level, -1);
    }

    private void adjustVolume(float dy) {
        final AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        final int maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        if (vol == -1) vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);

        vol = DeviceUtils.adjustVolume(activity, dy, playerView, vol, SCROLL_SENS * VOLUME_SENSITIVITY_BOOST);

        if (vol < 0) vol = 0;
        if (vol > maxVolume) vol = (float) maxVolume;

        int currentLevel = (int) vol;
        am.setStreamVolume(AudioManager.STREAM_MUSIC, currentLevel, 0);
        controller.showHint(String.valueOf(currentLevel), -1);
    }

    private void adjustPlaybackSpeed(float dy) {
        if (currentSpeed == -1f) {
            currentSpeed = engine.getPlaybackRate();
        }

        isGesturing = true;
        currentSpeed += (dy * SPEED_SENSITIVITY);

        if (currentSpeed < 0.25f) currentSpeed = 0.25f;
        if (currentSpeed > 4.0f) currentSpeed = 4.0f;

        float notchedSpeed = Math.round(currentSpeed * 20) / 20.0f;
        engine.setPlaybackRate(notchedSpeed);
        updateSpeedButtonUI(notchedSpeed);

        controller.showHint(notchedSpeed + "x", -1);
    }

    @Override
    public void onLongPress(@NonNull MotionEvent e) {
        if (!isSeeking && engine.getPlaybackState() == Player.STATE_READY && engine.isPlaying()) {
            vibrate(15, TimeUnit.MILLISECONDS);
            preLongPressSpeed = engine.getPlaybackRate();
            isLongPressing = true;
            engine.setPlaybackRate(2.0f);
            updateSpeedButtonUI(2.0f);
            controller.showHint("2x", -1);
        }
    }

    @Override
    public boolean onSingleTapUp(@NonNull MotionEvent e) {
        onTouchRelease();
        return super.onSingleTapUp(e);
    }

    private void vibrate(long duration, TimeUnit unit) {
        Vibrator v = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator())
            v.vibrate(VibrationEffect.createOneShot(unit.toMillis(duration), VibrationEffect.DEFAULT_AMPLITUDE));
    }
}