package com.hhst.youtubelite.player.gesture;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;

import java.util.function.Consumer;

public class ZoomTouchListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
    private final ScaleGestureDetector detector;
    private final PlayerView playerView;
    private final Consumer<Boolean> onShowReset;
    private float scaleFactor = 1.0f;
    private float lastX, lastY;
    private int mode = 0;

    public ZoomTouchListener(Context context, PlayerView playerView, Consumer<Boolean> onShowReset) {
        this.playerView = playerView;
        this.onShowReset = onShowReset;
        this.detector = new ScaleGestureDetector(context, this);
    }

    public void onTouch(MotionEvent event) {
        if (event.getPointerCount() < 2) return;
        detector.onTouchEvent(event);
        if (detector.isInProgress()) return;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_UP:
                mode = 0;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (scaleFactor > 1.0f) {
                    mode = 2;
                    lastX = centerX(event);
                    lastY = centerY(event);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mode == 2 && scaleFactor > 1.0f && event.getPointerCount() >= 2) {
                    float cx = centerX(event), cy = centerY(event);
                    applyTranslation(cx - lastX, cy - lastY);
                    lastX = cx;
                    lastY = cy;
                }
                break;
        }
    }

    @Override
    public boolean onScale(@NonNull ScaleGestureDetector detector) {
        scaleFactor = Math.max(1.0f, Math.min(scaleFactor * detector.getScaleFactor(), 5.0f));
        applyScale(scaleFactor);
        checkResetVisibility();
        return true;
    }

    public void reset() {
        scaleFactor = 1.0f;
        mode = 0;
        applyScale(1f);
        applyTranslation(0, 0, true);
        checkResetVisibility();
    }

    private void checkResetVisibility() {
        View target = getTargetView();
        boolean hasTranslation = target != null && (target.getTranslationX() != 0 || target.getTranslationY() != 0);
        if (onShowReset != null) {
            onShowReset.accept(scaleFactor > 1.0f || hasTranslation);
        }
    }

    public boolean shouldShowReset() {
        View target = getTargetView();
        boolean hasTranslation = target != null && (target.getTranslationX() != 0 || target.getTranslationY() != 0);
        return scaleFactor > 1.0f || hasTranslation;
    }

    private float centerX(MotionEvent e) {
        float sum = 0;
        int c = e.getPointerCount();
        for (int i = 0; i < c; i++) sum += e.getX(i);
        return sum / c;
    }

    private float centerY(MotionEvent e) {
        float sum = 0;
        int c = e.getPointerCount();
        for (int i = 0; i < c; i++) sum += e.getY(i);
        return sum / c;
    }

    private void applyScale(float scale) {
        View target = getTargetView();
        if (target != null) {
            target.setScaleX(scale);
            target.setScaleY(scale);
        }
    }

    private void applyTranslation(float dx, float dy) {
        applyTranslation(dx, dy, false);
    }

    private void applyTranslation(float dx, float dy, boolean reset) {
        View target = getTargetView();
        if (target == null) return;
        
        if (reset) {
            target.setTranslationX(0);
            target.setTranslationY(0);
        } else {
            float curX = target.getTranslationX() + dx;
            float curY = target.getTranslationY() + dy;
            float maxDx = (target.getWidth() * scaleFactor - target.getWidth()) / 2f;
            float maxDy = (target.getHeight() * scaleFactor - target.getHeight()) / 2f;
            target.setTranslationX(Math.min(maxDx, Math.max(-maxDx, curX)));
            target.setTranslationY(Math.min(maxDy, Math.max(-maxDy, curY)));
        }
    }

	@OptIn(markerClass = UnstableApi.class)
	private View getTargetView() {
        View surface = playerView.getVideoSurfaceView();
        return surface != null ? surface : playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame);
    }
}
