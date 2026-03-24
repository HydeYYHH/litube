package com.hhst.youtubelite.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.view.View;

import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;

import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.player.controller.Controller;
import com.hhst.youtubelite.player.engine.Engine;
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager;
import com.tencent.mmkv.MMKV;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

public class LitePlayerTest {
	private LitePlayer player;
	private LitePlayerView playerView;
	private Controller controller;
	private YoutubeExtractor extractor;
	private MMKV kv;
	private MockedStatic<MMKV> mmkvStatic;

	@Before
	public void setUp() throws Exception {
		final Activity activity = mock(Activity.class);
		extractor = mock(YoutubeExtractor.class);
		playerView = mock(LitePlayerView.class);
		controller = mock(Controller.class);
		final Engine engine = mock(Engine.class);
		final SponsorBlockManager sponsor = mock(SponsorBlockManager.class);
		final Executor executor = Runnable::run;
		kv = mock(MMKV.class);

		mmkvStatic = org.mockito.Mockito.mockStatic(MMKV.class);
		mmkvStatic.when(MMKV::defaultMMKV).thenReturn(kv);
		player = new LitePlayer(activity, extractor, playerView, controller, engine, sponsor, executor);
		setField(player, "loadedVideoId", "video-id");
	}

	@After
	public void tearDown() {
		if (mmkvStatic != null) {
			mmkvStatic.close();
		}
	}

	@Test
	public void playerSourceError_invalidatesCachedPlaybackEntryForCurrentVideo() {
		player.invalidatePlaybackCacheIfSourceOpenFailure(sourceOpenFailure());

		verify(extractor).invalidatePlaybackCacheByVideoId("video-id");
	}

	@Test
	public void nonOpenFailure_doesNotInvalidateCachedPlaybackEntry() {
		player.invalidatePlaybackCacheIfSourceOpenFailure(sourceReadFailure());

		verify(extractor, never()).invalidatePlaybackCacheByVideoId("video-id");
	}

	@Test
	public void sourceError_usesLoadedVideoIdInsteadOfRequestedVideoId() throws Exception {
		setField(player, "vid", "requested-video");
		setField(player, "loadedVideoId", "loaded-video");

		player.invalidatePlaybackCacheIfSourceOpenFailure(sourceOpenFailure());

		verify(extractor).invalidatePlaybackCacheByVideoId("loaded-video");
		verify(extractor, never()).invalidatePlaybackCacheByVideoId("requested-video");
	}

	@Test
	public void applyAudioPreference_prefersOriginalTrackAndKeepsMutableList() throws Exception {
		final AudioStream dubbed = mock(AudioStream.class);
		final AudioStream original = mock(AudioStream.class);
		when(kv.decodeString("last_audio_lang", "und")).thenReturn("en");
		when(dubbed.getAudioTrackName()).thenReturn("French");
		when(original.getAudioTrackName()).thenReturn("English original");
		when(dubbed.getAudioLocale()).thenReturn(Locale.FRENCH);
		when(original.getAudioLocale()).thenReturn(Locale.ENGLISH);
		when(dubbed.getAverageBitrate()).thenReturn(128);
		when(original.getAverageBitrate()).thenReturn(128);

		final StreamDetails streamDetails = new StreamDetails(
						null,
						List.of(dubbed, original),
						null,
						null,
						null,
						StreamType.VIDEO_STREAM);

		invokeApplyAudioPreference(streamDetails);

		assertEquals(2, streamDetails.getAudioStreams().size());
		assertSame(original, streamDetails.getAudioStreams().get(0));
		streamDetails.getAudioStreams().clear();
		assertEquals(0, streamDetails.getAudioStreams().size());
	}

	@Test
	public void isFullscreen_delegatesToControllerState() {
		when(controller.isFullscreen()).thenReturn(true, false);

		assertTrue(player.isFullscreen());
		assertFalse(player.isFullscreen());
	}

	@Test
	public void enterPictureInPicture_delegatesToPlayerView() {
		player.enterPictureInPicture();

		verify(playerView).enterPiP();
	}

	@Test
	public void shouldAutoEnterPictureInPicture_returnsTrueWhenPlayerViewIsVisible() {
		when(playerView.getVisibility()).thenReturn(View.VISIBLE);

		assertTrue(player.shouldAutoEnterPictureInPicture());
	}

	@Test
	public void shouldAutoEnterPictureInPicture_returnsFalseWhenPlayerViewIsHidden() {
		when(playerView.getVisibility()).thenReturn(View.GONE);

		assertFalse(player.shouldAutoEnterPictureInPicture());
	}

	@Test
	public void onPictureInPictureModeChanged_delegatesToController() {
		player.onPictureInPictureModeChanged(true);

		verify(controller).onPictureInPictureModeChanged(true);
	}

	private static PlaybackException sourceOpenFailure() {
		final HttpDataSource.HttpDataSourceException cause = new HttpDataSource.HttpDataSourceException(
						new IOException("GET 403"),
						mock(DataSpec.class),
						PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
						HttpDataSource.HttpDataSourceException.TYPE_OPEN);
		final PlaybackException error = mock(PlaybackException.class);
		when(error.getCause()).thenReturn(cause);
		return error;
	}

	private static PlaybackException sourceReadFailure() {
		final HttpDataSource.HttpDataSourceException cause = new HttpDataSource.HttpDataSourceException(
						new IOException("socket"),
						mock(DataSpec.class),
						PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
						HttpDataSource.HttpDataSourceException.TYPE_READ);
		final PlaybackException error = mock(PlaybackException.class);
		when(error.getCause()).thenReturn(cause);
		return error;
	}

	private static void setField(final Object target, final String fieldName, final Object value) throws Exception {
		final Field field = LitePlayer.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

	private void invokeApplyAudioPreference(final StreamDetails streamDetails) throws Exception {
		final Method method = LitePlayer.class.getDeclaredMethod("applyAudioPreference", StreamDetails.class);
		method.setAccessible(true);
		method.invoke(player, streamDetails);
	}
}
