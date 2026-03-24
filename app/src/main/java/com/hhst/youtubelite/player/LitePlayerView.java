package com.hhst.youtubelite.player;


import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Rational;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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
	private static final int MINI_CONTROL_DEFAULT_SPACE_DP = 18;
	private static final int MINI_SIDE_CONTROL_SIZE_DP = 30;
	private static final int MINI_CENTER_CONTROL_SIZE_DP = 34;
	private static final int[] MINI_PLAYER_TAP_TARGET_IDS = {
					R.id.btn_mini_queue,
					R.id.btn_mini_close,
					R.id.btn_mini_prev,
					R.id.btn_mini_play,
					R.id.btn_mini_pause,
					R.id.btn_mini_next,
					R.id.btn_mini_restore
	};
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
	// LitePlayer owns session state; the view mirrors only its current UI mode.
	private boolean inAppMiniPlayer = false;
	@Nullable
	private Runnable onMiniPlayerRestore;
	@Nullable
	private Runnable onMiniPlayerClose;
	@Nullable
	private Runnable onMiniPlayerBackgroundTap;
	private int miniPlayerRestoreResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
	private boolean miniPlayerRestoreFullscreen;
	@NonNull
	private final Rect miniPlayerTouchBounds = new Rect();
	@NonNull
	private final int[] miniPlayerLocationOnScreen = new int[2];
	private float miniPlayerTouchDownRawX;
	private float miniPlayerTouchDownRawY;
	private float miniPlayerStartTranslationX;
	private float miniPlayerStartTranslationY;
	private float miniPlayerSavedTranslationX;
	private float miniPlayerSavedTranslationY;
	private boolean miniPlayerTranslationStashedForFullscreen;
	private boolean miniPlayerTouchCaptured;
	private boolean miniPlayerDragging;
	private boolean miniPlayerResizing;
	private float miniPlayerPinchStartDistancePx;
	private int miniPlayerPinchStartWidthPx;
	private int miniPlayerWidthOverrideDp = MiniPlayerLayout.NO_WIDTH_OVERRIDE_DP;

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
				case NORMAL, MINI_PLAYER -> applyNormalState(defaultResizeMode);
				case FULLSCREEN_UNLOCKED, FULLSCREEN_LOCKED ->
								applyFullscreenState(previousState, isPortraitVideo, defaultResizeMode);
				case PIP -> applyPictureInPictureState(previousState);
			}
		});
	}

	public void updatePlayerLayout(final boolean fullscreen) {
		final ViewGroup.LayoutParams layoutParams = getLayoutParams();
		if (layoutParams instanceof ConstraintLayout.LayoutParams params) {
			if (inAppMiniPlayer && !fullscreen) {
				applyMiniPlayerLayout(params);
				applySavedMiniPlayerTranslation();
				miniPlayerTranslationStashedForFullscreen = false;
				return;
			}
			if (fullscreen && inAppMiniPlayer) {
				if (!miniPlayerTranslationStashedForFullscreen) {
					saveCurrentMiniPlayerTranslation();
					miniPlayerTranslationStashedForFullscreen = true;
				}
				setTranslationX(0.0f);
				setTranslationY(0.0f);
			} else {
				resetMiniPlayerTranslation();
			}
			applyStandardPlayerAnchors(params);
			if (fullscreen) {
				params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
				params.height = ConstraintLayout.LayoutParams.MATCH_PARENT;
				params.topMargin = 0;
				params.rightMargin = 0;
				params.bottomMargin = 0;
			} else {
				params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
				params.height = normalHeight > 0 ? normalHeight : (int) (ViewUtils.getScreenWidth(activity) * 9 / 16.0);
				params.topMargin = ViewUtils.dpToPx(activity, Constant.TOP_MARGIN_DP);
				params.rightMargin = 0;
				params.bottomMargin = 0;
			}
			setLayoutParams(params);
		}
	}

	public void enterPiP() {
		if (activity.isInPictureInPictureMode()) return;
		if (!isFs && !inAppMiniPlayer) normalHeight = playerHeight;
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

	public void enterInAppMiniPlayer() {
		if (inAppMiniPlayer) return;
		miniPlayerRestoreResizeMode = getResizeMode();
		miniPlayerRestoreFullscreen = isFs;
		inAppMiniPlayer = true;
		loadPersistedMiniPlayerLayoutState();
		updatePlayerLayout(false);
		updateMiniPlayerInteractionHandlers();
	}

	public void exitInAppMiniPlayer() {
		if (!inAppMiniPlayer) return;
		inAppMiniPlayer = false;
		resetMiniPlayerTouchTracking();
		miniPlayerWidthOverrideDp = MiniPlayerLayout.NO_WIDTH_OVERRIDE_DP;
		resetMiniPlayerTranslation();
		updatePlayerLayout(miniPlayerRestoreFullscreen);
		setResizeMode(miniPlayerRestoreResizeMode);
		updateMiniPlayerInteractionHandlers();
	}

	public boolean isInAppMiniPlayer() {
		return inAppMiniPlayer;
	}

	public void setMiniPlayerCallbacks(@Nullable final Runnable onRestore, @Nullable final Runnable onClose) {
		onMiniPlayerRestore = onRestore;
		onMiniPlayerClose = onClose;
		updateMiniPlayerInteractionHandlers();
	}

	public void setOnMiniPlayerBackgroundTap(@Nullable final Runnable onBackgroundTap) {
		onMiniPlayerBackgroundTap = onBackgroundTap;
	}

	private void updateMiniPlayerInteractionHandlers() {
		final ImageButton closeButton = findViewById(R.id.btn_mini_close);
		final ImageButton restoreButton = findViewById(R.id.btn_mini_restore);
		setMiniPlayerButtonAction(closeButton, inAppMiniPlayer ? onMiniPlayerClose : null);
		setMiniPlayerButtonAction(restoreButton, inAppMiniPlayer ? onMiniPlayerRestore : null);
	}

	private void setMiniPlayerButtonAction(@Nullable final ImageButton button, @Nullable final Runnable action) {
		if (button == null) return;
		button.setOnClickListener(action == null ? null : v -> action.run());
	}

	public boolean handleMiniPlayerTouch(@NonNull final MotionEvent event) {
		if (!inAppMiniPlayer) return false;
		final int action = event.getActionMasked();
		switch (action) {
			case MotionEvent.ACTION_DOWN -> {
				if (resolveMiniPlayerTapTarget(event) != null) {
					resetMiniPlayerTouchTracking();
					return false;
				}
				captureMiniPlayerTouchStart(event);
				return true;
			}
			case MotionEvent.ACTION_POINTER_DOWN -> {
				if (event.getPointerCount() < 2 || isAnyPointerOnMiniPlayerTapTarget(event)) {
					return miniPlayerTouchCaptured;
				}
				startMiniPlayerResize(event);
				return true;
			}
			case MotionEvent.ACTION_MOVE -> {
				if (miniPlayerResizing) {
					updateMiniPlayerSizeByPinch(event);
					return true;
				}
				if (!miniPlayerTouchCaptured) return false;
				final float deltaX = event.getRawX() - miniPlayerTouchDownRawX;
				final float deltaY = event.getRawY() - miniPlayerTouchDownRawY;
				if (!miniPlayerDragging && exceedsTouchSlop(deltaX, deltaY)) {
					miniPlayerDragging = true;
				}
				if (miniPlayerDragging) {
					updateMiniPlayerTranslation(
									miniPlayerStartTranslationX + deltaX,
									miniPlayerStartTranslationY + deltaY);
				}
				return true;
			}
			case MotionEvent.ACTION_POINTER_UP -> {
				if (!miniPlayerResizing) return miniPlayerTouchCaptured;
				finishMiniPlayerResize();
				return true;
			}
			case MotionEvent.ACTION_UP -> {
				if (miniPlayerResizing) {
					finishMiniPlayerResize();
					return true;
				}
				if (!miniPlayerTouchCaptured) return false;
				final boolean wasDragging = miniPlayerDragging;
				resetMiniPlayerTouchTracking();
				if (!wasDragging && onMiniPlayerBackgroundTap != null) {
					onMiniPlayerBackgroundTap.run();
				}
				return true;
			}
			case MotionEvent.ACTION_CANCEL -> {
				if (miniPlayerResizing) {
					finishMiniPlayerResize();
					return true;
				}
				if (!miniPlayerTouchCaptured) return false;
				resetMiniPlayerTouchTracking();
				return true;
			}
			default -> {
				return miniPlayerTouchCaptured;
			}
		}
	}

	@Override
	public boolean dispatchTouchEvent(@NonNull final MotionEvent event) {
		if (inAppMiniPlayer && handleMiniPlayerTouch(event)) {
			return true;
		}
		return super.dispatchTouchEvent(event);
	}

	private void applyMiniPlayerLayout(@NonNull final ConstraintLayout.LayoutParams params) {
		final MiniPlayerLayout.Spec spec = MiniPlayerLayout.computeSpec(
						resolveScreenWidthDp(),
						resolveBottomInsetDp(),
						miniPlayerWidthOverrideDp);
		params.width = ViewUtils.dpToPx(activity, spec.widthDp);
		params.height = ViewUtils.dpToPx(activity, spec.heightDp);
		params.topMargin = 0;
		params.rightMargin = ViewUtils.dpToPx(activity, spec.rightMarginDp);
		params.bottomMargin = ViewUtils.dpToPx(activity, spec.bottomMarginDp);
		params.topToTop = ConstraintLayout.LayoutParams.UNSET;
		params.startToStart = ConstraintLayout.LayoutParams.UNSET;
		params.endToEnd = ConstraintLayout.LayoutParams.UNSET;
		params.rightToRight = ConstraintLayout.LayoutParams.PARENT_ID;
		params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
		setLayoutParams(params);
		updateMiniPlayerControlSpacing(spec.widthDp);
		setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
	}

	private void applyStandardPlayerAnchors(@NonNull final ConstraintLayout.LayoutParams params) {
		params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
		params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
		params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
		params.topToBottom = ConstraintLayout.LayoutParams.UNSET;
		params.bottomToTop = ConstraintLayout.LayoutParams.UNSET;
		params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
		params.leftToLeft = ConstraintLayout.LayoutParams.UNSET;
		params.leftToRight = ConstraintLayout.LayoutParams.UNSET;
		params.rightToLeft = ConstraintLayout.LayoutParams.UNSET;
		params.rightToRight = ConstraintLayout.LayoutParams.UNSET;
		params.startToEnd = ConstraintLayout.LayoutParams.UNSET;
		params.endToStart = ConstraintLayout.LayoutParams.UNSET;
	}

	private boolean exceedsTouchSlop(final float deltaX, final float deltaY) {
		final int touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
		return Math.abs(deltaX) > touchSlop || Math.abs(deltaY) > touchSlop;
	}

	@Nullable
	private View resolveMiniPlayerTapTarget(@NonNull final MotionEvent event) {
		return resolveMiniPlayerTapTarget(event, 0);
	}

	@Nullable
	private View resolveMiniPlayerTapTarget(@NonNull final MotionEvent event, final int pointerIndex) {
		if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return null;
		for (int targetId : MINI_PLAYER_TAP_TARGET_IDS) {
			final View target = findViewById(targetId);
			if (isPointInsideVisibleView(event, pointerIndex, target)) {
				return target;
			}
		}
		return null;
	}

	private boolean isPointInsideVisibleView(@NonNull final MotionEvent event,
	                                         final int pointerIndex,
	                                         @Nullable final View view) {
		if (view == null || view.getVisibility() != View.VISIBLE) return false;
		if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return false;
		if (!view.getGlobalVisibleRect(miniPlayerTouchBounds)) return false;
		getLocationOnScreen(miniPlayerLocationOnScreen);
		final int pointerRawX = Math.round(miniPlayerLocationOnScreen[0] + event.getX(pointerIndex));
		final int pointerRawY = Math.round(miniPlayerLocationOnScreen[1] + event.getY(pointerIndex));
		return miniPlayerTouchBounds.contains(pointerRawX, pointerRawY);
	}

	private boolean isAnyPointerOnMiniPlayerTapTarget(@NonNull final MotionEvent event) {
		for (int i = 0; i < event.getPointerCount(); i++) {
			if (resolveMiniPlayerTapTarget(event, i) != null) {
				return true;
			}
		}
		return false;
	}

	private void startMiniPlayerResize(@NonNull final MotionEvent event) {
		miniPlayerPinchStartDistancePx = calculatePointerDistancePx(event);
		if (miniPlayerPinchStartDistancePx <= 0.0f) return;
		miniPlayerResizing = true;
		miniPlayerTouchCaptured = true;
		miniPlayerDragging = false;
		miniPlayerPinchStartWidthPx = resolveCurrentMiniPlayerWidthPx();
	}

	private void captureMiniPlayerTouchStart(@NonNull final MotionEvent event) {
		miniPlayerTouchCaptured = true;
		miniPlayerDragging = false;
		miniPlayerResizing = false;
		miniPlayerTouchDownRawX = event.getRawX();
		miniPlayerTouchDownRawY = event.getRawY();
		miniPlayerStartTranslationX = getTranslationX();
		miniPlayerStartTranslationY = getTranslationY();
	}

	private void updateMiniPlayerSizeByPinch(@NonNull final MotionEvent event) {
		if (!miniPlayerResizing || event.getPointerCount() < 2 || miniPlayerPinchStartDistancePx <= 0.0f) {
			return;
		}
		final float currentDistancePx = calculatePointerDistancePx(event);
		if (currentDistancePx <= 0.0f) return;
		final float scale = currentDistancePx / miniPlayerPinchStartDistancePx;
		final int targetWidthPx = Math.round(miniPlayerPinchStartWidthPx * scale);
		applyMiniPlayerSizeOverridePx(targetWidthPx);
	}

	private float calculatePointerDistancePx(@NonNull final MotionEvent event) {
		if (event.getPointerCount() < 2) return 0.0f;
		final float dx = event.getX(0) - event.getX(1);
		final float dy = event.getY(0) - event.getY(1);
		return (float) Math.hypot(dx, dy);
	}

	private int resolveCurrentMiniPlayerWidthPx() {
		if (getWidth() > 0) return getWidth();
		final ViewGroup.LayoutParams params = getLayoutParams();
		if (params != null && params.width > 0) return params.width;
		final MiniPlayerLayout.Spec spec = MiniPlayerLayout.computeSpec(
						resolveScreenWidthDp(),
						resolveBottomInsetDp(),
						miniPlayerWidthOverrideDp);
		return ViewUtils.dpToPx(activity, spec.widthDp);
	}

	private void applyMiniPlayerSizeOverridePx(final int widthPx) {
		if (!(getLayoutParams() instanceof ConstraintLayout.LayoutParams params)) return;
		final int targetWidthDp = Math.max(1, pxToDp(widthPx));
		miniPlayerWidthOverrideDp = MiniPlayerLayout.clampWidthDp(resolveScreenWidthDp(), targetWidthDp);
		final int nextWidthPx = ViewUtils.dpToPx(activity, miniPlayerWidthOverrideDp);
		final int nextHeightPx = ViewUtils.dpToPx(activity, MiniPlayerLayout.computeHeightDp(miniPlayerWidthOverrideDp));
		updateMiniPlayerControlSpacing(miniPlayerWidthOverrideDp);
		if (params.width == nextWidthPx && params.height == nextHeightPx) return;
		params.width = nextWidthPx;
		params.height = nextHeightPx;
		setLayoutParams(params);
		applySavedMiniPlayerTranslation();
	}

	private void finishMiniPlayerResize() {
		resetMiniPlayerTouchTracking();
	}

	private void updateMiniPlayerControlSpacing(final int currentWidthDp) {
		final int minWidthDp = MiniPlayerLayout.minWidthDpForScreen(resolveScreenWidthDp());
		final int startSpacingDp = MiniPlayerLayout.computeGapByCenterDistanceRatio(
						currentWidthDp,
						minWidthDp,
						MINI_CONTROL_DEFAULT_SPACE_DP,
						MINI_SIDE_CONTROL_SIZE_DP,
						MINI_CENTER_CONTROL_SIZE_DP);
		final int endSpacingDp = MiniPlayerLayout.computeGapByCenterDistanceRatio(
						currentWidthDp,
						minWidthDp,
						MINI_CONTROL_DEFAULT_SPACE_DP,
						MINI_CENTER_CONTROL_SIZE_DP,
						MINI_SIDE_CONTROL_SIZE_DP);
		updateMiniControlSpaceWidth(R.id.mini_controls_space_start, ViewUtils.dpToPx(activity, startSpacingDp));
		updateMiniControlSpaceWidth(R.id.mini_controls_space_end, ViewUtils.dpToPx(activity, endSpacingDp));
	}

	private void updateMiniControlSpaceWidth(final int spaceViewId, final int widthPx) {
		final View space = findViewById(spaceViewId);
		if (space == null) return;
		final ViewGroup.LayoutParams params = space.getLayoutParams();
		if (params == null || params.width == widthPx) return;
		params.width = widthPx;
		space.setLayoutParams(params);
	}

	private void updateMiniPlayerTranslation(final float translationX, final float translationY) {
		if (!(getParent() instanceof View parent)) return;
		miniPlayerSavedTranslationX = MiniPlayerLayout.clampTranslation(translationX, getLeft(), getWidth(), parent.getWidth());
		miniPlayerSavedTranslationY = MiniPlayerLayout.clampTranslation(translationY, getTop(), getHeight(), parent.getHeight());
		setTranslationX(miniPlayerSavedTranslationX);
		setTranslationY(miniPlayerSavedTranslationY);
		persistMiniPlayerLayoutState();
	}

	private void loadPersistedMiniPlayerLayoutState() {
		final PlayerPreferences.MiniPlayerLayoutState state = prefs.getMiniPlayerLayoutState();
		final int screenWidthDp = resolveScreenWidthDp();
		final int defaultWidthDp = MiniPlayerLayout.minWidthDpForScreen(screenWidthDp);
		miniPlayerWidthOverrideDp = state.widthDp() > 0
						? MiniPlayerLayout.clampWidthDp(screenWidthDp, state.widthDp())
						: defaultWidthDp;
		miniPlayerSavedTranslationX = dpToPx(state.translationXDp());
		miniPlayerSavedTranslationY = dpToPx(state.translationYDp());
		miniPlayerTranslationStashedForFullscreen = false;
		clearViewTranslation();
	}

	private void persistMiniPlayerLayoutState() {
		if (!inAppMiniPlayer) return;
		prefs.persistMiniPlayerLayoutState(
						miniPlayerWidthOverrideDp,
						pxToDp(miniPlayerSavedTranslationX),
						pxToDp(miniPlayerSavedTranslationY));
	}

	private void resetMiniPlayerTouchTracking() {
		miniPlayerTouchCaptured = false;
		miniPlayerDragging = false;
		miniPlayerResizing = false;
		miniPlayerPinchStartDistancePx = 0.0f;
		miniPlayerPinchStartWidthPx = 0;
	}

	private void resetMiniPlayerTranslation() {
		miniPlayerSavedTranslationX = 0.0f;
		miniPlayerSavedTranslationY = 0.0f;
		miniPlayerTranslationStashedForFullscreen = false;
		clearViewTranslation();
	}

	private void clearViewTranslation() {
		setTranslationX(0.0f);
		setTranslationY(0.0f);
	}

	private void saveCurrentMiniPlayerTranslation() {
		miniPlayerSavedTranslationX = getTranslationX();
		miniPlayerSavedTranslationY = getTranslationY();
	}

	private void applySavedMiniPlayerTranslation() {
		post(() -> {
			if (!inAppMiniPlayer || !isAttachedToWindow()) return;
			if (!(getParent() instanceof View parent)) return;
			if (getWidth() <= 0 || getHeight() <= 0 || parent.getWidth() <= 0 || parent.getHeight() <= 0) {
				post(this::applySavedMiniPlayerTranslation);
				return;
			}
			updateMiniPlayerTranslation(miniPlayerSavedTranslationX, miniPlayerSavedTranslationY);
		});
	}

	private int resolveScreenWidthDp() {
		final int screenWidthDp = getResources().getConfiguration().screenWidthDp;
		if (screenWidthDp != Configuration.SCREEN_WIDTH_DP_UNDEFINED) {
			return screenWidthDp;
		}
		return pxToDp(ViewUtils.getScreenWidth(activity));
	}

	private int resolveBottomInsetDp() {
		final WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(this);
		if (insets == null) return 0;
		final Insets systemInsets = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars());
		return pxToDp(systemInsets.bottom);
	}

	private int pxToDp(final int px) {
		return Math.round(px / getResources().getDisplayMetrics().density);
	}

	private float pxToDp(final float px) {
		return px / getResources().getDisplayMetrics().density;
	}

	private float dpToPx(final float dp) {
		return dp * getResources().getDisplayMetrics().density;
	}

	private void applyNormalState(final int defaultResizeMode) {
		isFs = false;
		activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
		ViewUtils.setFullscreen(activity.getWindow().getDecorView(), false);
		updatePlayerLayout(false);
		setResizeMode(inAppMiniPlayer ? AspectRatioFrameLayout.RESIZE_MODE_FIT : defaultResizeMode);
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
		if (previousState == ControllerMachine.State.NORMAL) {
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
