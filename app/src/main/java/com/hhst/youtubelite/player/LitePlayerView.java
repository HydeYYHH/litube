package com.hhst.youtubelite.player;


import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import com.hhst.youtubelite.R;
import com.hhst.youtubelite.player.common.Constant;
import com.hhst.youtubelite.player.common.PlayerPreferences;
import com.hhst.youtubelite.player.controller.ControllerMachine;
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager;
import com.hhst.youtubelite.player.sponsor.SponsorOverlayView;
import com.hhst.youtubelite.util.ViewUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import dagger.hilt.android.scopes.ActivityScoped;
import lombok.Getter;

/**
 * Custom player view.
 */
@UnstableApi
@AndroidEntryPoint
@ActivityScoped
public class LitePlayerView extends PlayerView {

	private static final float SUBTITLE_LINE_FRACTION = 0.92f;
	private static final float SUBTITLE_POSITION_FRACTION = 0.5f;
	@Inject
	SponsorBlockManager sponsor;
	@Inject
	Activity activity;
	@Inject
	PlayerPreferences prefs;
	@Nullable
	private SubtitleView subtitleView;
	@Getter
	private boolean isFs = false;
	private int playerWidth = 0;
	private int playerHeight = 0;
	private int normalHeight = 0;

	public LitePlayerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	public LitePlayerView(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
	}

	public LitePlayerView(Context context) {
		super(context);
	}

	public void setup() {
		setControllerAnimationEnabled(false);
		setControllerHideOnTouch(false);
		setControllerAutoShow(false);
		setControllerShowTimeoutMs(0);
		setResizeMode(prefs.getResizeMode());
		final ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) getLayoutParams();
		params.topMargin = ViewUtils.dpToPx(activity, Constant.TOP_MARGIN_DP);
		params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
		final int screenWidth = ViewUtils.getScreenWidth(activity);
		params.height = (int) (screenWidth * 9 / 16.0);
		setLayoutParams(params);

		addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
			if (right - left != playerWidth || bottom - top != playerHeight) {
				playerWidth = right - left;
				playerHeight = bottom - top;
			}
		});
	}

	public void applyControllerState(@NonNull final ControllerMachine.State previousState,
	                                 @NonNull final ControllerMachine.State newState,
	                                 final boolean isPortraitVideo,
	                                 final int defaultResizeMode) {
		post(() -> {
			switch (newState) {
				case NORMAL -> applyNormalState(defaultResizeMode);
				case FULLSCREEN_UNLOCKED, FULLSCREEN_LOCKED ->
								applyFullscreenState(previousState, isPortraitVideo, defaultResizeMode);
				case PIP -> applyPictureInPictureState(previousState);
			}
		});
	}

	public void updatePlayerLayout(final boolean fullscreen) {
		final ViewGroup.LayoutParams layoutParams = getLayoutParams();
		if (layoutParams instanceof ConstraintLayout.LayoutParams params) {
			if (fullscreen) {
				params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
				params.height = ConstraintLayout.LayoutParams.MATCH_PARENT;
				params.topMargin = 0;
			} else {
				params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
				params.height = normalHeight > 0 ? normalHeight : (int) (ViewUtils.getScreenWidth(activity) * 9 / 16.0);
				params.topMargin = ViewUtils.dpToPx(activity, Constant.TOP_MARGIN_DP);
			}
			setLayoutParams(params);
		}
	}

	public void enterPiP() {
		if (activity.isInPictureInPictureMode()) return;
		if (!isFs) normalHeight = playerHeight;
		updatePlayerLayout(true);
		setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
		final Rational aspectRatio = new Rational(16, 9);
		final PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
						.setAspectRatio(aspectRatio);
		final Rect sourceRectHint = new Rect();
		if (getGlobalVisibleRect(sourceRectHint)) {
			builder.setSourceRectHint(sourceRectHint);
		}
		final PictureInPictureParams params = builder.build();
		activity.enterPictureInPictureMode(params);
	}

	private void applyNormalState(final int defaultResizeMode) {
		isFs = false;
		activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
		ViewUtils.setFullscreen(activity.getWindow().getDecorView(), false);
		updatePlayerLayout(false);
		setResizeMode(defaultResizeMode);
		updateFullscreenButton(false);
	}

	private void applyFullscreenState(@NonNull final ControllerMachine.State previousState,
	                                  final boolean isPortraitVideo,
	                                  final int defaultResizeMode) {
		isFs = true;
		if (previousState == ControllerMachine.State.NORMAL && !activity.isInPictureInPictureMode()) {
			normalHeight = playerHeight;
		}
		activity.setRequestedOrientation(isPortraitVideo
						? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
						: ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
		ViewUtils.setFullscreen(activity.getWindow().getDecorView(), true);
		updatePlayerLayout(true);
		setResizeMode(defaultResizeMode);
		updateFullscreenButton(true);
	}

	private void applyPictureInPictureState(@NonNull final ControllerMachine.State previousState) {
		isFs = false;
		if (!previousState.isFullscreen()) {
			normalHeight = playerHeight;
		}
		updatePlayerLayout(true);
		setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
	}

	private void updateFullscreenButton(final boolean fullscreen) {
		final ImageButton fullscreenButton = findViewById(R.id.btn_fullscreen);
		if (fullscreenButton != null) {
			fullscreenButton.setImageResource(
							fullscreen ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
		}
	}

	public void cueing(@NonNull final CueGroup cueGroup) {
		if (subtitleView == null) {
			final SubtitleView defaultSubtitleView = getSubtitleView();
			if (defaultSubtitleView != null) defaultSubtitleView.setVisibility(View.GONE);
			subtitleView = findViewById(R.id.custom_subtitle_view);
		}
		final List<Cue> cues = new ArrayList<>();
		for (final Cue cue : cueGroup.cues)
			cues.add(cue.buildUpon()
							.setLine(SUBTITLE_LINE_FRACTION, Cue.LINE_TYPE_FRACTION)
							.setLineAnchor(Cue.ANCHOR_TYPE_END)
							.setPosition(SUBTITLE_POSITION_FRACTION)
							.setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
							.build());
		subtitleView.setCues(cues);
	}

	@Override
	public void setResizeMode(int resizeMode) {
		super.setResizeMode(resizeMode);
		final View videoSurfaceView = getVideoSurfaceView();
		if (videoSurfaceView instanceof AspectRatioFrameLayout frameLayout) {
			frameLayout.setResizeMode(resizeMode);
		}
	}

	public void show() {
		setVisibility(View.VISIBLE);
	}

	public void hide() {
		setVisibility(View.GONE);
	}

	public void setTitle(@Nullable String title) {
		final TextView titleView = findViewById(R.id.tv_title);
		titleView.setText(title);
		titleView.setSelected(true);
	}

	public void updateSkipMarkers(long duration, TimeUnit unit) {
		final List<long[]> segs = sponsor.getSegments();
		final List<long[]> validSegs = new ArrayList<>();
		for (final long[] seg : segs) if (seg != null && seg.length >= 2) validSegs.add(seg);

		final SponsorOverlayView layer = findViewById(R.id.sponsor_overlay);
		layer.setData(validSegs.isEmpty() ? null : validSegs, duration, unit);

		final DefaultTimeBar bar = findViewById(R.id.exo_progress);
		if (validSegs.isEmpty()) {
			bar.setAdGroupTimesMs(null, null, 0);
		} else {
			final long[] times = new long[validSegs.size() * 2];
			for (int i = 0; i < validSegs.size(); i++) {
				times[i * 2] = validSegs.get(i)[0];
				times[i * 2 + 1] = validSegs.get(i)[1];
			}
			bar.setAdGroupTimesMs(times, new boolean[times.length], times.length);
		}
	}

	public void setHeight(int height) {
		if (activity.isInPictureInPictureMode() || isFs || height <= 0) return;
		final int deviceHeight = ViewUtils.dpToPx(activity, height);
		if (getLayoutParams().height == deviceHeight) return;
		getLayoutParams().height = deviceHeight;
		if (!isFs) normalHeight = deviceHeight;
		requestLayout();
	}

}
