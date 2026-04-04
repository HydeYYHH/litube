package com.hhst.youtubelite.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

/**
 * Progress bar that tracks page loading state.
 */
public final class LoadingProgressBar extends View {

	private static final float INITIAL_PROGRESS = 6F;
	private static final float MAX_VISIBLE_PROGRESS = 99.4F;
	private static final long FADE_OUT_DURATION_MS = 220L;

	private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF trackRect = new RectF();
	private final Matrix fillMatrix = new Matrix();
	private final Matrix shimmerMatrix = new Matrix();
	@Nullable
	private LinearGradient fillGradient;
	@Nullable
	private LinearGradient shimmerGradient;
	private float displayedProgress;	private final Runnable frameRunner = this::runFrame;
	private float reportedProgress;
	private float shimmerOffsetPx;
	private float shimmerCycleWidthPx;
	private long lastFrameTimeMs;
	private boolean frameScheduled;
	private boolean finishing;
	public LoadingProgressBar(@NonNull Context context) {
		this(context, null);
	}

	public LoadingProgressBar(@NonNull Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public LoadingProgressBar(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		init();
	}

	private void init() {
		int trackColor = ColorUtils.setAlphaComponent(Color.parseColor("#CCCCCC"), 88);
		trackPaint.setStyle(Paint.Style.FILL);
		trackPaint.setColor(trackColor);
		fillPaint.setStyle(Paint.Style.FILL);
		shimmerPaint.setStyle(Paint.Style.FILL);
		setVisibility(GONE);
	}

	public void beginLoading() {
		removeCallbacks(frameRunner);
		frameScheduled = false;
		animate().cancel();
		finishing = false;
		displayedProgress = INITIAL_PROGRESS;
		reportedProgress = INITIAL_PROGRESS + 6F;
		shimmerOffsetPx = 0F;
		lastFrameTimeMs = 0L;
		setAlpha(1F);
		setVisibility(VISIBLE);
		updateShaderMatrices();
		invalidate();
		scheduleNextFrame();
	}

	public void setLoadingProgress(int progress) {
		float clampedProgress = Math.max(0F, Math.min(progress, 100F));
		if (clampedProgress >= 100F) {
			finishLoading();
			return;
		}
		if (getVisibility() != VISIBLE || finishing) beginLoading();
		reportedProgress = Math.max(reportedProgress, clampedProgress);
		scheduleNextFrame();
	}

	public void finishLoading() {
		if (getVisibility() != VISIBLE) return;
		finishing = true;
		reportedProgress = 100F;
		scheduleNextFrame();
	}

	private void scheduleNextFrame() {
		if (frameScheduled) return;
		frameScheduled = true;
		postOnAnimation(frameRunner);
	}

	private void runFrame() {
		frameScheduled = false;
		if (!isAttachedToWindow()) return;

		long now = SystemClock.uptimeMillis();
		float deltaSeconds = lastFrameTimeMs == 0L
						? 0.016F
						: Math.min(0.05F, (now - lastFrameTimeMs) / 1000F);
		lastFrameTimeMs = now;

		final float targetProgress;
		if (finishing) {
			targetProgress = 100F;
		} else {
			final float trickleCeiling;
			final float trickleVelocity;
			if (reportedProgress < 15F) {
				trickleCeiling = 22F;
				trickleVelocity = 12F;
			} else if (reportedProgress < 35F) {
				trickleCeiling = 50F;
				trickleVelocity = 7.8F;
			} else if (reportedProgress < 65F) {
				trickleCeiling = 78F;
				trickleVelocity = 4.4F;
			} else if (reportedProgress < 85F) {
				trickleCeiling = 92F;
				trickleVelocity = 2.2F;
			} else {
				trickleCeiling = MAX_VISIBLE_PROGRESS;
				trickleVelocity = 0.75F;
			}
			float trickledProgress = displayedProgress + trickleVelocity * deltaSeconds;
			targetProgress = Math.min(trickleCeiling, Math.max(reportedProgress, trickledProgress));
		}
		float gap = Math.max(0F, targetProgress - displayedProgress);
		if (gap > 0F) {
			float step = Math.max(
							gap * Math.min(1F, deltaSeconds * (finishing ? 10F : 5.2F)),
							deltaSeconds * (finishing ? 180F : 18F)
			);
			displayedProgress = Math.min(targetProgress, displayedProgress + step);
		}

		shimmerOffsetPx += deltaSeconds * (finishing ? dpToPx(156F) : dpToPx(88F));
		updateShaderMatrices();
		invalidate();

		if (finishing && displayedProgress >= 99.7F) {
			displayedProgress = 100F;
			updateShaderMatrices();
			invalidate();
			lastFrameTimeMs = 0L;
			animate().cancel();
			animate().alpha(0F).setDuration(FADE_OUT_DURATION_MS).withEndAction(() -> {
				removeCallbacks(frameRunner);
				frameScheduled = false;
				finishing = false;
				displayedProgress = 0F;
				reportedProgress = 0F;
				shimmerOffsetPx = 0F;
				lastFrameTimeMs = 0L;
				setVisibility(GONE);
				setAlpha(1F);
				invalidate();
			}).start();
			return;
		}

		if (getVisibility() == VISIBLE) scheduleNextFrame();
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		removeCallbacks(frameRunner);
		frameScheduled = false;
		animate().cancel();
		lastFrameTimeMs = 0L;
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		float contentWidth = Math.max(1F, w - getPaddingLeft() - getPaddingRight());
		int startColor = Color.parseColor("#736EFE");
		int middleColor = ColorUtils.blendARGB(startColor, Color.WHITE, 0.14F);
		int endColor = Color.parseColor("#5EFCE8");
		fillGradient = new LinearGradient(
						0F,
						0F,
						contentWidth,
						0F,
						new int[]{startColor, middleColor, endColor},
						new float[]{0F, 0.55F, 1F},
						Shader.TileMode.CLAMP
		);
		fillPaint.setShader(fillGradient);

		shimmerCycleWidthPx = Math.max(dpToPx(56F), contentWidth / 4.2F);
		int shimmerColor = ColorUtils.setAlphaComponent(Color.WHITE, 92);
		shimmerGradient = new LinearGradient(
						0F,
						0F,
						shimmerCycleWidthPx,
						0F,
						new int[]{Color.TRANSPARENT, shimmerColor, Color.TRANSPARENT},
						new float[]{0F, 0.5F, 1F},
						Shader.TileMode.REPEAT
		);
		shimmerPaint.setShader(shimmerGradient);
		updateShaderMatrices();
	}

	private void updateShaderMatrices() {
		if (fillGradient != null) {
			fillMatrix.reset();
			fillMatrix.setTranslate(getPaddingLeft(), 0F);
			fillGradient.setLocalMatrix(fillMatrix);
		}
		if (shimmerGradient != null && shimmerCycleWidthPx > 0F) {
			shimmerMatrix.reset();
			shimmerMatrix.setTranslate(getPaddingLeft() + (shimmerOffsetPx % shimmerCycleWidthPx), 0F);
			shimmerGradient.setLocalMatrix(shimmerMatrix);
		}
	}

	@Override
	protected void onDraw(@NonNull Canvas canvas) {
		super.onDraw(canvas);
		float left = getPaddingLeft();
		float top = getPaddingTop();
		float right = getWidth() - getPaddingRight();
		float bottom = getHeight() - getPaddingBottom();
		if (right <= left || bottom <= top) return;

		trackRect.set(left, top, right, bottom);
		float radius = trackRect.height() / 2F;
		canvas.drawRoundRect(trackRect, radius, radius, trackPaint);

		float progressRight = left + trackRect.width() * (displayedProgress / 100F);
		if (progressRight <= left) return;

		canvas.save();
		canvas.clipRect(left, top, progressRight, bottom);
		canvas.drawRoundRect(trackRect, radius, radius, fillPaint);
		canvas.drawRoundRect(trackRect, radius, radius, shimmerPaint);
		canvas.restore();
	}

	private float dpToPx(float dp) {
		return dp * getResources().getDisplayMetrics().density;
	}




}
