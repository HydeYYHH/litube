package com.hhst.youtubelite.player.common;

import androidx.media3.common.Player;

public enum PlayerLoopMode {
	PLAYLIST_NEXT(0, Player.REPEAT_MODE_OFF),
	LOOP_ONE(1, Player.REPEAT_MODE_ONE),
	PAUSE_AT_END(2, Player.REPEAT_MODE_OFF),
	PLAYLIST_RANDOM(3, Player.REPEAT_MODE_OFF);

	private final int persistedValue;
	private final int repeatMode;

	PlayerLoopMode(final int persistedValue, final int repeatMode) {
		this.persistedValue = persistedValue;
		this.repeatMode = repeatMode;
	}

	public int persistedValue() {
		return persistedValue;
	}

	public int repeatMode() {
		return repeatMode;
	}

	public boolean skipsToNextOnEnded() {
		return this == PLAYLIST_NEXT;
	}

	public boolean selectsRandomPlaylistItemOnEnded() {
		return this == PLAYLIST_RANDOM;
	}

	public PlayerLoopMode next() {
		return switch (this) {
			case PLAYLIST_NEXT -> LOOP_ONE;
			case LOOP_ONE -> PAUSE_AT_END;
			case PAUSE_AT_END -> PLAYLIST_RANDOM;
			case PLAYLIST_RANDOM -> PLAYLIST_NEXT;
		};
	}

	public static PlayerLoopMode fromPersistedValue(final int persistedValue) {
		for (final PlayerLoopMode mode : values()) {
			if (mode.persistedValue == persistedValue) return mode;
		}
		return PLAYLIST_NEXT;
	}
}
