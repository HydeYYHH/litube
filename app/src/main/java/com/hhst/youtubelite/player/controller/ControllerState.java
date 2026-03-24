package com.hhst.youtubelite.player.controller;

import androidx.annotation.DrawableRes;

import com.hhst.youtubelite.R;

import java.util.Objects;

public final class ControllerState {

	public enum Mode {
		NORMAL,
		FULLSCREEN_UNLOCK,
		FULLSCREEN_LOCK,
		PIP;

		public boolean isFullscreen() {
			return this == FULLSCREEN_UNLOCK || this == FULLSCREEN_LOCK;
		}
	}

	public record UiState(
					boolean fullscreen,
					boolean fullscreenLayout,
					boolean otherControlsVisible,
					boolean centerControlsVisible,
					boolean progressVisible,
					boolean lockButtonVisible,
					boolean resetVisible,
					@DrawableRes int fullscreenIconRes,
					@DrawableRes int lockIconRes
	) {
	}

	private final Mode mode;
	private final Mode previousModeBeforePip;
	private final boolean controlsVisible;

	private ControllerState(final Mode mode,
	                        final Mode previousModeBeforePip,
	                        final boolean controlsVisible) {
		this.mode = Objects.requireNonNull(mode);
		this.previousModeBeforePip = Objects.requireNonNull(previousModeBeforePip);
		this.controlsVisible = controlsVisible;
	}

	public static ControllerState initial() {
		return new ControllerState(Mode.NORMAL, Mode.NORMAL, false);
	}

	public Mode mode() {
		return mode;
	}

	public boolean controlsVisible() {
		return controlsVisible;
	}

	public boolean isLocked() {
		return mode == Mode.FULLSCREEN_LOCK;
	}

	public boolean isInPictureInPicture() {
		return mode == Mode.PIP;
	}

	public ControllerState withControlsVisible(final boolean visible) {
		final boolean nextVisible = !isInPictureInPicture() && visible;
		if (controlsVisible == nextVisible) return this;
		return new ControllerState(mode, previousModeBeforePip, nextVisible);
	}

	public ControllerState enterFullscreen() {
		return new ControllerState(Mode.FULLSCREEN_UNLOCK, previousModeBeforePip, true);
	}

	public ControllerState exitFullscreen() {
		return new ControllerState(Mode.NORMAL, Mode.NORMAL, true);
	}

	public ControllerState toggleLock() {
		if (mode == Mode.FULLSCREEN_UNLOCK) {
			return new ControllerState(Mode.FULLSCREEN_LOCK, previousModeBeforePip, true);
		}
		if (mode == Mode.FULLSCREEN_LOCK) {
			return new ControllerState(Mode.FULLSCREEN_UNLOCK, previousModeBeforePip, true);
		}
		return this;
	}

	public ControllerState enterPip() {
		if (mode == Mode.PIP) return this;
		return new ControllerState(Mode.PIP, mode, false);
	}

	public ControllerState exitPip() {
		if (mode != Mode.PIP) return this;
		return new ControllerState(previousModeBeforePip, previousModeBeforePip, true);
	}

	public UiState toUiState(final boolean isBuffering, final boolean isZoomed) {
		final boolean locked = isLocked();
		final boolean fullscreen = mode.isFullscreen();
		final boolean fullscreenLayout = fullscreen || isInPictureInPicture();
		final boolean overlaysVisible = controlsVisible && !locked && !isInPictureInPicture();
		return new UiState(
						fullscreen,
						fullscreenLayout,
						overlaysVisible,
						overlaysVisible && !isBuffering,
						overlaysVisible,
						controlsVisible && fullscreen,
						overlaysVisible && fullscreen && isZoomed,
						fullscreen ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen,
						locked ? R.drawable.ic_lock : R.drawable.ic_unlock);
	}
}
