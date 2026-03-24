package com.hhst.youtubelite.player.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.tencent.mmkv.MMKV;

import org.junit.Before;
import org.junit.Test;

public class PlayerPreferencesTest {
	private PlayerPreferences preferences;
	private MMKV mmkv;

	@Before
	public void setUp() {
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		mmkv = mock(MMKV.class);
		preferences = new PlayerPreferences(extensionManager, mmkv, new Gson());
	}

	@Test
	public void miniPlayerLayoutState_roundTripsPersistedValues() {
		preferences.persistMiniPlayerLayoutState(230, 12.5f, -8.0f);

		verify(mmkv).encode("mini_player_width_dp", 230);
		verify(mmkv).encode("mini_player_translation_x_dp", 12.5f);
		verify(mmkv).encode("mini_player_translation_y_dp", -8.0f);

		when(mmkv.decodeInt("mini_player_width_dp", -1)).thenReturn(230);
		when(mmkv.decodeFloat("mini_player_translation_x_dp", 0.0f)).thenReturn(12.5f);
		when(mmkv.decodeFloat("mini_player_translation_y_dp", 0.0f)).thenReturn(-8.0f);

		final PlayerPreferences.MiniPlayerLayoutState state = preferences.getMiniPlayerLayoutState();

		assertEquals(230, state.widthDp());
		assertEquals(12.5f, state.translationXDp(), 0.0f);
		assertEquals(-8.0f, state.translationYDp(), 0.0f);
	}

	@Test
	public void loopMode_defaultsToPlaylistNextWhenNothingPersisted() {
		when(mmkv.decodeInt("loop_mode", Integer.MIN_VALUE)).thenReturn(Integer.MIN_VALUE);
		when(mmkv.decodeBool("loop_enabled", false)).thenReturn(false);

		assertSame(PlayerLoopMode.PLAYLIST_NEXT, preferences.getLoopMode());
	}

	@Test
	public void loopMode_fallsBackToLegacyLoopEnabledFlag() {
		when(mmkv.decodeInt("loop_mode", Integer.MIN_VALUE)).thenReturn(Integer.MIN_VALUE);
		when(mmkv.decodeBool("loop_enabled", false)).thenReturn(true);

		assertSame(PlayerLoopMode.LOOP_ONE, preferences.getLoopMode());
	}

	@Test
	public void loopMode_roundTripsPersistedModeAndLegacyFlag() {
		preferences.setLoopMode(PlayerLoopMode.PLAYLIST_RANDOM);

		verify(mmkv).encode("loop_mode", 3);
		verify(mmkv).encode("loop_enabled", false);

		when(mmkv.decodeInt("loop_mode", Integer.MIN_VALUE)).thenReturn(2);

		assertSame(PlayerLoopMode.PAUSE_AT_END, preferences.getLoopMode());
	}
}
