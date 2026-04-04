package com.hhst.youtubelite.player.controller;

import androidx.annotation.DrawableRes;

import com.hhst.youtubelite.R;

import java.util.Objects;

/**
 * State snapshot for the playback controller.
 */
public final class ControllerState {

	private final Mode mode;
	private final Mode prevModePip;
	private final Mode prevModeMini;
	private final boolean controlsVisible;

	private ControllerState(Mode mode,
	                        final Mode prevModePip,
	                        final Mode prevModeMini,
	                        final boolean controlsVisible) {
		this.mode = Objects.requireNonNull(mode);
		this.prevModePip = Objects.requireNonNull(prevModePip);
		this.prevModeMini = Objects.requireNonNull(prevModeMini);
		this.controlsVisible = controlsVisible;
	}

	public static ControllerState initial() {
		return new ControllerState(Mode.NORMAL, Mode.NORMAL, Mode.NORMAL, false);
	}

	private static RenderState toRenderState(ControllerState state,
	                                         final boolean buffering,
	                                         final boolean zoomed) {
		UiState uiState = state.uiState(buffering, zoomed);
		return new RenderState(
						state.controlsVisible(),
						uiState.centerVisible(),
						uiState.otherVisible(),
						uiState.progressVisible(),
						uiState.resetVisible(),
						uiState.lockVisible(),
						uiState.miniVisible(),
						uiState.scrimVisible(),
						state.isLocked(),
						uiState.fullscreen(),
						state.isInPictureInPicture(),
						uiState.fullscreenIconRes(),
						uiState.lockIconRes());
	}

	public Mode mode() {
		return mode;
	}

	public boolean controlsVisible() {
		return controlsVisible;
	}

	public boolean isFullscreen() {
		return mode.isFullscreen();
	}

	public boolean isLocked() {
		return mode == Mode.FULLSCREEN_LOCK;
	}

	public boolean isInPictureInPicture() {
		return mode == Mode.PIP;
	}

	public boolean isInMiniPlayer() {
		return mode == Mode.MINI_PLAYER;
	}

	public ControllerState withControlsVisible(boolean visible) {
		boolean nextVisible = !isInPictureInPicture() && visible;
		if (controlsVisible == nextVisible) return this;
		return new ControllerState(mode, prevModePip, prevModeMini, nextVisible);
	}

	public ControllerState enterFullscreen() {
		return new ControllerState(Mode.FULLSCREEN_UNLOCK, prevModePip, prevModeMini, true);
	}

	public ControllerState exitFullscreen() {
		return new ControllerState(Mode.NORMAL, Mode.NORMAL, Mode.NORMAL, true);
	}

	public ControllerState toggleLock() {
		if (mode == Mode.FULLSCREEN_UNLOCK) {
			return new ControllerState(Mode.FULLSCREEN_LOCK, prevModePip, prevModeMini, true);
		}
		if (mode == Mode.FULLSCREEN_LOCK) {
			return new ControllerState(Mode.FULLSCREEN_UNLOCK, prevModePip, prevModeMini, true);
		}
		return this;
	}

	public ControllerState enterMiniPlayer() {
		if (mode == Mode.MINI_PLAYER) return this;
		Mode restoreMode = mode == Mode.PIP ? prevModePip : mode;
		return new ControllerState(Mode.MINI_PLAYER, prevModePip, restoreMode, true);
	}

	public ControllerState exitMiniPlayer() {
		if (mode != Mode.MINI_PLAYER) return this;
		return new ControllerState(prevModeMini, prevModePip, prevModeMini, true);
	}

	public ControllerState enterPip() {
		if (mode == Mode.PIP) return this;
		return new ControllerState(Mode.PIP, mode, prevModeMini, false);
	}

	public ControllerState exitPip() {
		if (mode != Mode.PIP) return this;
		return new ControllerState(prevModePip, prevModePip, prevModeMini, true);
	}

	public RenderState renderState(boolean buffering, boolean zoomed) {
		return toRenderState(this, buffering, zoomed);
	}

	public UiState uiState(boolean buffering, boolean zoomed) {
		boolean locked = isLocked();
		boolean fullscreen = mode.isFullscreen();
		boolean fullscreenLayout = fullscreen || isInPictureInPicture();
		boolean overlaysVisible = controlsVisible && !locked && !isInPictureInPicture() && !isInMiniPlayer();
		return new UiState(
						fullscreen,
						fullscreenLayout,
						overlaysVisible,
						overlaysVisible && !buffering,
						overlaysVisible,
						controlsVisible && fullscreen,
						overlaysVisible && fullscreen && zoomed,
						controlsVisible && isInMiniPlayer(),
						controlsVisible && isInMiniPlayer(),
						fullscreen ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen,
						locked ? R.drawable.ic_lock : R.drawable.ic_unlock);
	}

/**
 * Enumeration of app logic.
 */
	public enum Mode {
		NORMAL,
		FULLSCREEN_UNLOCK,
		FULLSCREEN_LOCK,
		MINI_PLAYER,
		PIP;

		public boolean isFullscreen() {
			return this == FULLSCREEN_UNLOCK || this == FULLSCREEN_LOCK;
		}
	}

/**
 * Value object for app logic.
 */
	public record UiState(
					boolean fullscreen,
					boolean fullscreenLayout,
					boolean otherVisible,
					boolean centerVisible,
					boolean progressVisible,
					boolean lockVisible,
					boolean resetVisible,
					boolean miniVisible,
					boolean scrimVisible,
					@DrawableRes int fullscreenIconRes,
					@DrawableRes int lockIconRes
	) {
	}

/**
 * Value object for app logic.
 */
	public record RenderState(
					boolean controlsVisible,
					boolean centerVisible,
					boolean otherVisible,
					boolean progressVisible,
					boolean resetVisible,
					boolean lockVisible,
					boolean miniVisible,
					boolean scrimVisible,
					boolean locked,
					boolean fullscreen,
					boolean pip,
					@DrawableRes int fullscreenIconRes,
					@DrawableRes int lockIconRes
	) {
	}
}
