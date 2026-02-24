package com.hhst.youtubelite.player.controller.gesture;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.player.LitePlayerView;
import com.hhst.youtubelite.player.controller.Controller;
import com.hhst.youtubelite.player.engine.Engine;
import java.util.Locale;

@UnstableApi
public class PlayerGestureListener extends GestureDetector.SimpleOnGestureListener {
    private static final int AUTO_HIDE_DELAY_MS = 200;
    private static final int SEEK_CONTINUATION_WINDOW_MS = 600;
    private static final int HINT_HIDE_FAST_MS = 500;
    private static final float SPEED_SENSITIVITY = 0.015f;

    private final Activity activity;
    private final LitePlayerView playerView;
    private final Engine engine;
    private final Controller controller;
    private final Handler handler;
    private final Runnable hideHintRunnable;

    private int gestureMode = 0;
    private float bri = -1, currentSpeed = -1f, preLongPressSpeed = 1.0f;
    private boolean isLongPressing = false, isGesturing = false;
    private long scrollStartPosition = 0;

    private int cumulativeSeekAmount = 0;
    private final Runnable resetSeekRunnable = () -> cumulativeSeekAmount = 0;
    private long lastTapTime = 0;

    public PlayerGestureListener(Activity activity, LitePlayerView playerView, Engine engine, Controller controller) {
        this.activity = activity;
        this.playerView = playerView;
        this.engine = engine;
        this.controller = controller;
        this.handler = new Handler(activity.getMainLooper());
        this.hideHintRunnable = controller::hideHint;
    }

    private boolean isEnabled() {
        return controller.getExtensionManager().isEnabled(com.hhst.youtubelite.extension.Constant.ENABLE_PLAYER_GESTURES);
    }

    public void onTouchRelease() {
        if (!isEnabled()) return;
        if (isLongPressing) {
            engine.setPlaybackRate(preLongPressSpeed);
            updateSpeedButtonUI(preLongPressSpeed);
            controller.hideHint();
            isLongPressing = false;
        }
        if (isGesturing) {
            handler.postDelayed(hideHintRunnable, AUTO_HIDE_DELAY_MS);
            isGesturing = false;
        }
    }

    @Override
    public boolean onDown(@NonNull MotionEvent e) {
        if (!isEnabled()) return false;
        handler.removeCallbacks(hideHintRunnable);
        gestureMode = 0;
        bri = -1;
        vol = -1;
        currentSpeed = -1f;
        isGesturing = false;
        scrollStartPosition = engine.position();
        return true;
    }

    @Override
    public boolean onSingleTapUp(@NonNull MotionEvent e) {
        if (!isEnabled()) return false;
        long currentTime = System.currentTimeMillis();
        float x = e.getX();
        float width = playerView.getWidth();

        if (cumulativeSeekAmount != 0 && (currentTime - lastTapTime < SEEK_CONTINUATION_WINDOW_MS)) {
            if ((cumulativeSeekAmount < 0 && x < width * 0.35f) || (cumulativeSeekAmount > 0 && x > width * 0.65f)) {
                processSeek(x < width * 0.5f);
                lastTapTime = currentTime;
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
        float x = e.getX();
        float width = playerView.getWidth();
        if (x < width * 0.35f || x > width * 0.65f) {
            processSeek(x < width * 0.5f);
            lastTapTime = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    private void processSeek(boolean isLeft) {
        handler.removeCallbacks(resetSeekRunnable);
        long seekStep = 10000;
        if (isLeft) {
            cumulativeSeekAmount -= 10;
            engine.seekBy(-seekStep);
            controller.showHint(cumulativeSeekAmount + "s", HINT_HIDE_FAST_MS);
        } else {
            cumulativeSeekAmount += 10;
            engine.seekBy(seekStep);
            controller.showHint("+" + cumulativeSeekAmount + "s", HINT_HIDE_FAST_MS);
        }
        handler.postDelayed(resetSeekRunnable, SEEK_CONTINUATION_WINDOW_MS);
    }

    @Override
    public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float dx, float dy) {
        if (!isEnabled() || e1 == null || e2.getPointerCount() > 1 || isLongPressing) return false;
        if (gestureMode == 0) {
            if (Math.abs(dy) > Math.abs(dx)) gestureMode = 1;
            else if (Math.abs(dx) > Math.abs(dy)) gestureMode = 2;
        }
        if (gestureMode == 1) {
            isGesturing = true;
            handler.removeCallbacks(hideHintRunnable);
            float x = e1.getX(), width = playerView.getWidth();
            if (x < width * 0.35f) adjustBrightness(dy);
            else if (x > width * 0.65f) adjustVolume(dy);
            else adjustPlaybackSpeed(dy);
            handler.postDelayed(hideHintRunnable, AUTO_HIDE_DELAY_MS);
        } else if (gestureMode == 2) {
            isGesturing = true;
            handler.removeCallbacks(hideHintRunnable);
            adjustSeek(e1, e2);
            handler.postDelayed(hideHintRunnable, AUTO_HIDE_DELAY_MS);
        }
        return true;
    }

    private void adjustSeek(MotionEvent e1, MotionEvent e2) {
        float distanceX = e2.getX() - e1.getX();
        float width = playerView.getWidth();
        long maxSeekRange = 120000;
        long seekOffset = (long) ((distanceX / width) * maxSeekRange);
        long targetPosition = scrollStartPosition + seekOffset;
        engine.seekTo(targetPosition);
        controller.showHint(formatTime(targetPosition), -1);
    }

    private String formatTime(long ms) {
        if (ms < 0) ms = 0;
        int seconds = (int) (ms / 1000) % 60;
        int minutes = (int) ((ms / (1000 * 60)) % 60);
        int hours = (int) ((ms / (1000 * 60 * 60)) % 24);
        if (hours > 0) return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    private void adjustBrightness(float dy) {
        WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
        if (bri == -1) {
            bri = lp.screenBrightness;
            if (bri < 0) {
                try {
                    float systemBri = Settings.System.getInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
                    bri = systemBri / 255.0f;
                } catch (Settings.SettingNotFoundException e) {
                    bri = 0.5f;
                }
            }
        }
        float delta = (dy / playerView.getHeight()) * 1.5f;
        bri = Math.min(Math.max(bri + delta, 0.01f), 1.0f);
        lp.screenBrightness = bri;
        activity.getWindow().setAttributes(lp);
        controller.showHint(Math.round(bri * 100) + "%", -1);
    }

    private float vol = -1;

    private void adjustVolume(float dy) {
        final AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        final int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (vol == -1) vol = (float) am.getStreamVolume(AudioManager.STREAM_MUSIC);
        float delta = (dy / playerView.getHeight()) * (float) max * 1.2f;
        vol = Math.min(Math.max(vol + delta, 0), (float) max);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(vol), 0);
        int percentage = Math.round((vol / (float) max) * 100);
        controller.showHint(percentage + "%", -1);
    }

    private void adjustPlaybackSpeed(float dy) {
        if (currentSpeed == -1f) currentSpeed = engine.getPlaybackRate();
        currentSpeed += (dy * SPEED_SENSITIVITY);
        currentSpeed = Math.min(Math.max(currentSpeed, 0.25f), 4.0f);
        float notched = Math.round(currentSpeed * 20) / 20.0f;
        engine.setPlaybackRate(notched);
        updateSpeedButtonUI(notched);
        controller.showHint(notched + "x", -1);
    }

    @Override
    public void onLongPress(@NonNull MotionEvent e) {
        if (!isEnabled() || !engine.isPlaying()) return;
        vibrate();
        preLongPressSpeed = engine.getPlaybackRate();
        isLongPressing = true;
        engine.setPlaybackRate(2.0f);
        updateSpeedButtonUI(2.0f);
        controller.showHint("2x", -1);
    }

    private void updateSpeedButtonUI(float speed) {
        final TextView speedView = playerView.findViewById(R.id.btn_speed);
        if (speedView != null) speedView.setText(String.format(Locale.getDefault(), "%.2fx", speed));
    }

    private void vibrate() {
        Vibrator v = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }
}