package com.hhst.youtubelite.player.common;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlayerLoopModeTest {

	@Test
	public void next_cyclesThroughAllModes() {
		assertEquals(PlayerLoopMode.LOOP_ONE, PlayerLoopMode.PLAYLIST_NEXT.next());
		assertEquals(PlayerLoopMode.PAUSE_AT_END, PlayerLoopMode.LOOP_ONE.next());
		assertEquals(PlayerLoopMode.PLAYLIST_RANDOM, PlayerLoopMode.PAUSE_AT_END.next());
		assertEquals(PlayerLoopMode.PLAYLIST_NEXT, PlayerLoopMode.PLAYLIST_RANDOM.next());
	}

	@Test
	public void fromPersistedValue_returnsDefaultForUnknownValue() {
		assertEquals(PlayerLoopMode.PLAYLIST_NEXT, PlayerLoopMode.fromPersistedValue(-1));
		assertEquals(PlayerLoopMode.LOOP_ONE, PlayerLoopMode.fromPersistedValue(1));
	}
}
