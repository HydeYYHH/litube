package com.hhst.youtubelite.player.controller.gesture;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.R;

import com.hhst.youtubelite.player.LitePlayerView;

import java.util.function.Consumer;

import javax.inject.Inject;

import dagger.hilt.android.scopes.ActivityScoped;
import lombok.Setter;

@ActivityScoped
@UnstableApi
public class ZoomTouchListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
	private final ScaleGestureDetector detector;
	private final LitePlayerView playerView;
	@Setter
	private Consumer<Boolean> onShowReset;
	private float scaleFactor = 1.0f;
	private float lastX, lastY;
	private int mode = 0;

	@Inject
	public ZoomTouchListener(Activity activity, LitePlayerView playerView) {
		this.playerView = playerView;
		this.detector = new ScaleGestureDetector(activity, this);
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
		boolean hasTranslation = target.getTranslationX() != 0 || target.getTranslationY() != 0;
		if (onShowReset != null) onShowReset.accept(scaleFactor > 1.0f || hasTranslation);
	}

	public boolean shouldShowReset() {
		View target = getTargetView();
		boolean hasTranslation = target.getTranslationX() != 0 || target.getTranslationY() != 0;
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
		target.setScaleX(scale);
		target.setScaleY(scale);
	}

	private void applyTranslation(float dx, float dy) {
		applyTranslation(dx, dy, false);
	}

	private void applyTranslation(float dx, float dy, boolean reset) {
		View target = getTargetView();

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


	private View getTargetView() {
		View surface = playerView.getVideoSurfaceView();
		return surface != null ? surface : playerView.findViewById(R.id.exo_content_frame);
	}
}
