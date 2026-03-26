package com.hhst.youtubelite.player.controller;

import androidx.annotation.DrawableRes;

public final class ControllerMachine {

	public enum State {
		NORMAL,
		FULLSCREEN_UNLOCKED,
		FULLSCREEN_LOCKED,
		MINI_PLAYER,
		PIP;

		public boolean isFullscreen() {
			return this == FULLSCREEN_UNLOCKED || this == FULLSCREEN_LOCKED;
		}
	}

	public record RenderState(
					boolean controlsVisible,
					boolean showCenterControls,
					boolean showOtherControls,
					boolean showProgressBar,
					boolean showResetButton,
					boolean showLockButton,
					boolean showMiniControls,
					boolean showMiniScrim,
					boolean locked,
					boolean fullscreen,
					boolean pip,
					@DrawableRes int fullscreenIconRes,
					@DrawableRes int lockIconRes
	) {
	}

	private ControllerState state = ControllerState.initial();

	public State getState() {
		return toState(state.mode());
	}

	public boolean isFullscreen() {
		return state.mode().isFullscreen();
	}

	public boolean isLocked() {
		return state.isLocked();
	}

	public boolean isInPictureInPicture() {
		return state.isInPictureInPicture();
	}

	public boolean isInMiniPlayer() {
		return state.isInMiniPlayer();
	}

	public boolean isControlsVisible() {
		return state.controlsVisible();
	}

	public void setControlsVisible(final boolean visible) {
		state = state.withControlsVisible(visible);
	}

	public void enterFullscreen() {
		state = state.enterFullscreen();
	}

	public void exitFullscreen() {
		state = state.exitFullscreen();
	}

	public void toggleLock() {
		state = state.toggleLock();
	}

	public void enterMiniPlayer() {
		state = state.enterMiniPlayer();
	}

	public void exitMiniPlayer() {
		state = state.exitMiniPlayer();
	}

	public void onPictureInPictureModeChanged(final boolean isInPictureInPictureMode) {
		state = isInPictureInPictureMode ? state.enterPip() : state.exitPip();
	}

	public RenderState buildRenderState(final boolean controlsVisible,
	                                    final boolean isBuffering,
	                                    final boolean isZoomed) {
		return toRenderState(state.withControlsVisible(controlsVisible), isBuffering, isZoomed);
	}

	public RenderState currentRenderState(final boolean isBuffering, final boolean isZoomed) {
		return toRenderState(state, isBuffering, isZoomed);
	}

	private static RenderState toRenderState(final ControllerState state,
	                                         final boolean isBuffering,
	                                         final boolean isZoomed) {
		final ControllerState.UiState uiState = state.toUiState(isBuffering, isZoomed);
		return new RenderState(
						state.controlsVisible(),
						uiState.centerControlsVisible(),
						uiState.otherControlsVisible(),
						uiState.progressVisible(),
						uiState.resetVisible(),
						uiState.lockButtonVisible(),
						uiState.miniControlsVisible(),
						uiState.miniScrimVisible(),
						state.isLocked(),
						uiState.fullscreen(),
						state.isInPictureInPicture(),
						uiState.fullscreenIconRes(),
						uiState.lockIconRes());
	}

	private static State toState(final ControllerState.Mode mode) {
		return switch (mode) {
			case NORMAL -> State.NORMAL;
			case FULLSCREEN_UNLOCK -> State.FULLSCREEN_UNLOCKED;
			case FULLSCREEN_LOCK -> State.FULLSCREEN_LOCKED;
			case MINI_PLAYER -> State.MINI_PLAYER;
			case PIP -> State.PIP;
		};
	}
}
