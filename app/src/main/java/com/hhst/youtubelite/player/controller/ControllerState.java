package com.hhst.youtubelite.player.controller;

import androidx.annotation.DrawableRes;

import com.hhst.youtubelite.R;

import java.util.Objects;

public final class ControllerState {

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

	public record UiState(
					boolean fullscreen,
					boolean fullscreenLayout,
					boolean otherControlsVisible,
					boolean centerControlsVisible,
					boolean progressVisible,
					boolean lockButtonVisible,
					boolean resetVisible,
					boolean miniControlsVisible,
					boolean miniScrimVisible,
					@DrawableRes int fullscreenIconRes,
					@DrawableRes int lockIconRes
	) {
	}

	private final Mode mode;
	private final Mode previousModeBeforePip;
	private final Mode previousModeBeforeMiniPlayer;
	private final boolean controlsVisible;

	private ControllerState(final Mode mode,
	                        final Mode previousModeBeforePip,
	                        final Mode previousModeBeforeMiniPlayer,
	                        final boolean controlsVisible) {
		this.mode = Objects.requireNonNull(mode);
		this.previousModeBeforePip = Objects.requireNonNull(previousModeBeforePip);
		this.previousModeBeforeMiniPlayer = Objects.requireNonNull(previousModeBeforeMiniPlayer);
		this.controlsVisible = controlsVisible;
	}

	public static ControllerState initial() {
		return new ControllerState(Mode.NORMAL, Mode.NORMAL, Mode.NORMAL, false);
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

	public boolean isInMiniPlayer() {
		return mode == Mode.MINI_PLAYER;
	}

	public ControllerState withControlsVisible(final boolean visible) {
		final boolean nextVisible = !isInPictureInPicture() && visible;
		if (controlsVisible == nextVisible) return this;
		return new ControllerState(mode, previousModeBeforePip, previousModeBeforeMiniPlayer, nextVisible);
	}

	public ControllerState enterFullscreen() {
		return new ControllerState(Mode.FULLSCREEN_UNLOCK, previousModeBeforePip, previousModeBeforeMiniPlayer, true);
	}

	public ControllerState exitFullscreen() {
		return new ControllerState(Mode.NORMAL, Mode.NORMAL, Mode.NORMAL, true);
	}

	public ControllerState toggleLock() {
		if (mode == Mode.FULLSCREEN_UNLOCK) {
			return new ControllerState(Mode.FULLSCREEN_LOCK, previousModeBeforePip, previousModeBeforeMiniPlayer, true);
		}
		if (mode == Mode.FULLSCREEN_LOCK) {
			return new ControllerState(Mode.FULLSCREEN_UNLOCK, previousModeBeforePip, previousModeBeforeMiniPlayer, true);
		}
		return this;
	}

	public ControllerState enterMiniPlayer() {
		if (mode == Mode.MINI_PLAYER) return this;
		final Mode restoreMode = mode == Mode.PIP ? previousModeBeforePip : mode;
		return new ControllerState(Mode.MINI_PLAYER, previousModeBeforePip, restoreMode, true);
	}

	public ControllerState exitMiniPlayer() {
		if (mode != Mode.MINI_PLAYER) return this;
		return new ControllerState(previousModeBeforeMiniPlayer, previousModeBeforePip, previousModeBeforeMiniPlayer, true);
	}

	public ControllerState enterPip() {
		if (mode == Mode.PIP) return this;
		return new ControllerState(Mode.PIP, mode, previousModeBeforeMiniPlayer, false);
	}

	public ControllerState exitPip() {
		if (mode != Mode.PIP) return this;
		return new ControllerState(previousModeBeforePip, previousModeBeforePip, previousModeBeforeMiniPlayer, true);
	}

	public UiState toUiState(final boolean isBuffering, final boolean isZoomed) {
		final boolean locked = isLocked();
		final boolean fullscreen = mode.isFullscreen();
		final boolean fullscreenLayout = fullscreen || isInPictureInPicture();
		final boolean overlaysVisible = controlsVisible && !locked && !isInPictureInPicture() && !isInMiniPlayer();
		return new UiState(
						fullscreen,
						fullscreenLayout,
						overlaysVisible,
						overlaysVisible && !isBuffering,
						overlaysVisible,
						controlsVisible && fullscreen,
						overlaysVisible && fullscreen && isZoomed,
						controlsVisible && isInMiniPlayer(),
						controlsVisible && isInMiniPlayer(),
						fullscreen ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen,
						locked ? R.drawable.ic_lock : R.drawable.ic_unlock);
	}
}
