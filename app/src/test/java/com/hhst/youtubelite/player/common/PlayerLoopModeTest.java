package com.hhst.youtubelite.player.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.Player;

import org.junit.Test;

public class PlayerLoopModeTest {
	@Test
	public void next_cyclesThroughAllModes() {
		assertSame(PlayerLoopMode.LOOP_ONE, PlayerLoopMode.PLAYLIST_NEXT.next());
		assertSame(PlayerLoopMode.PAUSE_AT_END, PlayerLoopMode.LOOP_ONE.next());
		assertSame(PlayerLoopMode.PLAYLIST_RANDOM, PlayerLoopMode.PAUSE_AT_END.next());
		assertSame(PlayerLoopMode.PLAYLIST_NEXT, PlayerLoopMode.PLAYLIST_RANDOM.next());
	}

	@Test
	public void persistedValue_unknownFallsBackToPlaylistNext() {
		assertSame(PlayerLoopMode.PLAYLIST_NEXT, PlayerLoopMode.fromPersistedValue(-1));
		assertSame(PlayerLoopMode.PLAYLIST_NEXT, PlayerLoopMode.fromPersistedValue(99));
	}

	@Test
	public void repeatMode_onlyLoopOneRepeatsCurrentItem() {
		assertEquals(Player.REPEAT_MODE_OFF, PlayerLoopMode.PLAYLIST_NEXT.repeatMode());
		assertEquals(Player.REPEAT_MODE_ONE, PlayerLoopMode.LOOP_ONE.repeatMode());
		assertEquals(Player.REPEAT_MODE_OFF, PlayerLoopMode.PAUSE_AT_END.repeatMode());
		assertEquals(Player.REPEAT_MODE_OFF, PlayerLoopMode.PLAYLIST_RANDOM.repeatMode());
	}

	@Test
	public void endedBehavior_flagsOnlyRelevantModes() {
		assertTrue(PlayerLoopMode.PLAYLIST_NEXT.skipsToNextOnEnded());
		assertFalse(PlayerLoopMode.LOOP_ONE.skipsToNextOnEnded());
		assertFalse(PlayerLoopMode.PAUSE_AT_END.skipsToNextOnEnded());
		assertFalse(PlayerLoopMode.PLAYLIST_RANDOM.skipsToNextOnEnded());
		assertFalse(PlayerLoopMode.PLAYLIST_NEXT.selectsRandomPlaylistItemOnEnded());
		assertTrue(PlayerLoopMode.PLAYLIST_RANDOM.selectsRandomPlaylistItemOnEnded());
	}
}
