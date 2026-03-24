package com.hhst.youtubelite.player.engine;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EngineNavigationScriptTest {
	@Test
	public void buildPlaylistNavigationScript_next_usesYtInitialDataPlaylistContents() {
		final String script = Engine.buildPlaylistNavigationScript(1);

		assertTrue(script.contains("globalThis.ytInitialData?.contents?.singleColumnWatchNextResults?.playlist?.playlist?.contents"));
		assertTrue(script.contains("const currentVideoId=globalThis.ytInitialPlayerResponse?.videoDetails?.videoId ?? new URL(location.href).searchParams.get('v');"));
		assertTrue(script.contains("item?.playlistPanelVideoRenderer?.videoId === currentVideoId"));
		assertTrue(script.contains("const targetIndex=currentIndex + 1;"));
		assertTrue(script.contains("targetVideo?.navigationEndpoint?.commandMetadata?.webCommandMetadata?.url"));
		assertTrue(script.contains("location.href = new URL(targetUrl, location.origin).toString();"));
		assertTrue(script.contains("return 'navigating';"));
	}

	@Test
	public void buildPlaylistNavigationScript_previous_hasNoFallbackAndStopsAtOutOfRange() {
		final String script = Engine.buildPlaylistNavigationScript(-1);

		assertTrue(script.contains("const targetIndex=currentIndex + -1;"));
		assertTrue(script.contains("if(targetIndex < 0 || targetIndex >= playlistContents.length) return 'target-out-of-range';"));
		assertTrue(script.contains("return 'missing-playlist';"));
		assertTrue(script.contains("return 'missing-current-video-id';"));
		assertTrue(script.contains("return 'missing-current-video';"));
		assertTrue(script.contains("return 'missing-target-url';"));
	}
}
