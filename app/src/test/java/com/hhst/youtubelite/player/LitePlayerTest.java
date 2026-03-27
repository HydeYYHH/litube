package com.hhst.youtubelite.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.View;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.ui.DefaultTimeBar;

import com.hhst.youtubelite.PlaybackService;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.extractor.ExtractionException;
import com.hhst.youtubelite.extractor.ExtractionSession;
import com.hhst.youtubelite.extractor.PlaybackDetails;
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.extractor.VideoDetails;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.player.controller.Controller;
import com.hhst.youtubelite.player.engine.Engine;
import com.hhst.youtubelite.player.queue.QueueNav;
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager;
import com.hhst.youtubelite.player.sponsor.SponsorOverlayView;
import com.hhst.youtubelite.ui.ErrorDialog;
import com.hhst.youtubelite.util.DeviceUtils;
import com.tencent.mmkv.MMKV;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public class LitePlayerTest {
	private static final String VIDEO_ID = "mAdodMaERp0";
	private static final String WATCH_URL = "https://www.youtube.com/watch?v=" + VIDEO_ID;
	private static final String NEXT_VIDEO_ID = "dQw4w9WgXcQ";
	private static final String NEXT_WATCH_URL = "https://www.youtube.com/watch?v=" + NEXT_VIDEO_ID;

	private LitePlayer player;
	private YoutubeExtractor extractor;
	private MMKV kv;
	private MockedStatic<MMKV> mmkvStatic;
	private MockedStatic<DeviceUtils> deviceUtilsStatic;
	private Activity activity;
	private LitePlayerView playerView;
	private Controller controller;
	private Engine engine;
	private SponsorBlockManager sponsor;
	private SponsorOverlayView sponsorOverlayView;
	private DefaultTimeBar timeBar;
	private Player.Listener listener;

	@Before
	public void setUp() throws Exception {
		activity = mock(Activity.class);
		extractor = mock(YoutubeExtractor.class);
		playerView = mock(LitePlayerView.class);
		controller = mock(Controller.class);
		engine = mock(Engine.class);
		sponsor = mock(SponsorBlockManager.class);
		final Executor executor = Runnable::run;
		kv = mock(MMKV.class);
		sponsorOverlayView = mock(SponsorOverlayView.class);
		timeBar = mock(DefaultTimeBar.class);
		final Resources resources = mock(Resources.class);
		final Configuration configuration = new Configuration();
		configuration.orientation = Configuration.ORIENTATION_PORTRAIT;

		doAnswer(invocation -> {
			invocation.<Runnable>getArgument(0).run();
			return null;
		}).when(activity).runOnUiThread(any(Runnable.class));
		doAnswer(invocation -> {
			final int viewId = invocation.getArgument(0);
			if (viewId == R.id.sponsor_overlay) return sponsorOverlayView;
			if (viewId == R.id.exo_progress) return timeBar;
			return null;
		}).when(playerView).findViewById(any(Integer.class));
		doAnswer(invocation -> {
			invocation.<Runnable>getArgument(0).run();
			return true;
		}).when(playerView).post(any(Runnable.class));
		when(activity.getResources()).thenReturn(resources);
		when(resources.getConfiguration()).thenReturn(configuration);
		when(engine.position()).thenReturn(321L);
		when(engine.getPlaybackRate()).thenReturn(1.25f);
		when(engine.isPlaying()).thenReturn(true);

		mmkvStatic = org.mockito.Mockito.mockStatic(MMKV.class);
		mmkvStatic.when(MMKV::defaultMMKV).thenReturn(kv);
		deviceUtilsStatic = org.mockito.Mockito.mockStatic(DeviceUtils.class);
		deviceUtilsStatic.when(() -> DeviceUtils.isRotateOn(activity)).thenReturn(false);
		player = new LitePlayer(activity, extractor, playerView, controller, engine, sponsor, executor);
		final ArgumentCaptor<Player.Listener> listenerCaptor = ArgumentCaptor.forClass(Player.Listener.class);
		verify(engine).addListener(listenerCaptor.capture());
		listener = listenerCaptor.getValue();
	}

	@After
	public void tearDown() {
		if (mmkvStatic != null) {
			mmkvStatic.close();
		}
		if (deviceUtilsStatic != null) {
			deviceUtilsStatic.close();
		}
	}

	@Test
	public void play_invalidUrl_returnsWithoutSideEffects() {
		player.play("https://example.com/not-a-youtube-watch-url");

		verifyNoInteractions(extractor, sponsor);
		verify(engine, never()).clear();
		verify(playerView, never()).show();
	}

	@Test
	public void play_sameVideoId_returnsWithoutRestartingExtraction() throws Exception {
		stubSuccessfulPlaybackDetails(WATCH_URL, defaultStreamDetails(), "Loaded title", 60L);
		player.play(WATCH_URL);
		clearInvocations(extractor, sponsor, engine, playerView);

		player.play(WATCH_URL);

		verifyNoInteractions(extractor, sponsor);
		verify(engine, never()).clear();
		verify(playerView, never()).show();
	}

	@Test
	public void play_successLoadsPlaybackAndUpdatesUi() throws Exception {
		final VideoDetails videoDetails = new VideoDetails();
		videoDetails.setTitle("Demo title");
		videoDetails.setAuthor("Demo author");
		videoDetails.setThumbnail("https://example.com/thumb.jpg");
		videoDetails.setDuration(60L);
		final StreamDetails streamDetails = new StreamDetails(
						new ArrayList<>(List.of(mock(VideoStream.class))),
						new ArrayList<>(),
						new ArrayList<>(),
						"https://example.com/dash.mpd",
						"https://example.com/hls.m3u8",
						StreamType.VIDEO_STREAM);
		when(extractor.getPlaybackDetails(eq(WATCH_URL), any(ExtractionSession.class)))
						.thenReturn(new PlaybackDetails(videoDetails, streamDetails));

		player.play(WATCH_URL);

		verify(sponsor).load(VIDEO_ID);
		verify(engine).clear();
		verify(playerView).show();
		verify(playerView).setTitle("Demo title");
		verify(playerView).updateSkipMarkers(60L, TimeUnit.SECONDS);
		verify(engine).play(videoDetails, streamDetails);
	}

	@Test
	public void attachPlaybackService_initializesServiceAndUpdatesProgressOnReady() {
		final PlaybackService playbackService = mock(PlaybackService.class);
		when(engine.getQueueNavigationAvailability()).thenReturn(QueueNav.ACTIVE_WITH_PREVIOUS);

		player.attachPlaybackService(playbackService);
		listener.onPlaybackStateChanged(Player.STATE_READY);

		verify(playbackService).initialize(engine);
		verify(controller).refreshQueueNavigationAvailability(QueueNav.ACTIVE_WITH_PREVIOUS);
		verify(playbackService).updateQueueNavigationAvailability(QueueNav.ACTIVE_WITH_PREVIOUS);
		verify(playbackService).updateProgress(321L, 1.25f, true);
	}

	@Test
	public void onIsPlayingChanged_updatesPlaybackServiceProgress() {
		final PlaybackService playbackService = mock(PlaybackService.class);
		when(engine.getQueueNavigationAvailability()).thenReturn(QueueNav.INACTIVE);

		player.attachPlaybackService(playbackService);
		listener.onIsPlayingChanged(false);

		verify(playbackService).initialize(engine);
		verify(controller).refreshQueueNavigationAvailability(QueueNav.INACTIVE);
		verify(playbackService).updateQueueNavigationAvailability(QueueNav.INACTIVE);
		verify(playbackService).updateProgress(321L, 1.25f, false);
	}

	@Test
	public void play_successRefreshesQueueNavigationStateAfterPlaybackStarts() throws Exception {
		final PlaybackService playbackService = mock(PlaybackService.class);
		player.attachPlaybackService(playbackService);
		clearInvocations(controller, playbackService);
		when(engine.getQueueNavigationAvailability()).thenReturn(QueueNav.ACTIVE_WITHOUT_PREVIOUS);
		stubSuccessfulPlaybackDetails(WATCH_URL, defaultStreamDetails(), "Demo title", 60L);

		player.play(WATCH_URL);

		verify(controller).refreshQueueNavigationAvailability(QueueNav.ACTIVE_WITHOUT_PREVIOUS);
		verify(playbackService).updateQueueNavigationAvailability(QueueNav.ACTIVE_WITHOUT_PREVIOUS);
	}

	@Test
	public void attachPlaybackService_refreshesWatchPrevAvailability() {
		final PlaybackService playbackService = mock(PlaybackService.class);
		final QueueNav availability = watch();
		when(engine.getQueueNavigationAvailability()).thenReturn(availability);

		player.attachPlaybackService(playbackService);

		verify(controller).refreshQueueNavigationAvailability(availability);
		verify(playbackService).updateQueueNavigationAvailability(availability);
	}

	@Test
	public void pause_delegatesToEngine() {
		player.pause();

		verify(engine).pause();
	}

	@Test
	public void seekLoadedVideo_seeksWhenTimestampMatchesLoadedVideo() throws Exception {
		setField(player, "loadedVideoId", VIDEO_ID);

		assertTrue(player.seekLoadedVideo(WATCH_URL + "&t=173s", 173_000L));

		verify(engine).seekTo(173_000L);
	}

	@Test
	public void seekLoadedVideo_ignoresTimestampWhenNoVideoLoaded() {
		assertFalse(player.seekLoadedVideo(WATCH_URL + "&t=173s", 173_000L));

		verify(engine, never()).seekTo(anyLong());
	}

	@Test
	public void seekLoadedVideo_ignoresTimestampWhenLoadedVideoDiffers() throws Exception {
		setField(player, "loadedVideoId", VIDEO_ID);

		assertFalse(player.seekLoadedVideo(NEXT_WATCH_URL + "&t=173s", 173_000L));

		verify(engine, never()).seekTo(anyLong());
	}

	@Test
	public void isPlaying_andIsFullscreen_delegateToCollaborators() {
		when(controller.isFullscreen()).thenReturn(true, false);

		assertTrue(player.isPlaying());
		assertTrue(player.isFullscreen());
		assertFalse(player.isFullscreen());
	}

	@Test
	public void fullscreenAndPictureInPictureApis_delegateToController() {
		player.enterFullscreen();
		player.exitFullscreen();
		player.syncRotation(true, Configuration.ORIENTATION_LANDSCAPE);
		player.onPictureInPictureModeChanged(true);

		verify(controller).enterFullscreen();
		verify(controller).exitFullscreen();
		verify(controller).syncRotation(true, Configuration.ORIENTATION_LANDSCAPE);
		verify(controller).onPictureInPictureModeChanged(true);
	}

	@Test
	public void enterPictureInPicture_delegatesToPlayerView() {
		player.enterPictureInPicture();

		verify(playerView).enterPiP();
	}

	@Test
	public void enterInAppMiniPlayer_delegatesToPlayerView() {
		player.enterInAppMiniPlayer();

		verify(playerView).enterInAppMiniPlayer();
		verify(controller).enterMiniPlayer();
	}

	@Test
	public void exitInAppMiniPlayer_delegatesToPlayerView() {
		player.exitInAppMiniPlayer();

		verify(playerView).exitInAppMiniPlayer();
		verify(controller).exitMiniPlayer();
	}

	@Test
	public void shouldAutoEnterPictureInPicture_returnsTrueWhenPlayerViewIsVisible() {
		when(playerView.getVisibility()).thenReturn(View.VISIBLE);

		assertTrue(player.shouldAutoEnterPictureInPicture());
	}

	@Test
	public void shouldAutoEnterPictureInPicture_returnsTrueWhenInAppMiniPlayerIsActiveAndVisible() {
		when(playerView.getVisibility()).thenReturn(View.VISIBLE);
		player.enterInAppMiniPlayer();

		assertTrue(player.shouldAutoEnterPictureInPicture());
	}

	@Test
	public void shouldAutoEnterPictureInPicture_returnsFalseWhenPlayerViewIsHidden() {
		when(playerView.getVisibility()).thenReturn(View.GONE);

		assertFalse(player.shouldAutoEnterPictureInPicture());
	}

	@Test
	public void setHeight_postsHeightUpdateToPlayerView() {
		player.setHeight(480);

		verify(playerView).setHeight(480);
	}

	@Test
	public void seekToIfLoaded_delegatesToEngineWhenVideoIsLoaded() throws Exception {
		setField(player, "loadedVideoId", VIDEO_ID);

		player.seekToIfLoaded(2_000L);

		verify(engine).seekTo(2_000L);
	}

	@Test
	public void seekToIfLoaded_ignoresRequestsWhenNoVideoIsLoaded() {
		player.seekToIfLoaded(2_000L);

		verify(engine, never()).seekTo(anyLong());
	}

	@Test
	public void canSuspendWatch_returnsTrueWhenLoadedAndPlayerViewIsShown() {
		when(playerView.isShown()).thenReturn(true);

		assertTrue(player.canSuspendWatch());
	}

	@Test
	public void canSuspendWatch_returnsTrueWhenPlayerViewIsVisibleEvenIfVideoStillLoading() throws Exception {
		setField(player, "loadedVideoId", null);
		when(playerView.getVisibility()).thenReturn(View.VISIBLE);

		assertTrue(player.canSuspendWatch());
	}

	@Test
	public void onPictureInPictureModeChanged_delegatesToController() {
		player.onPictureInPictureModeChanged(true);

		verify(controller).onPictureInPictureModeChanged(true);
	}

	@Test
	public void hide_cancelsExtractionFutureAndHidesNotification() throws Exception {
		final PlaybackService playbackService = mock(PlaybackService.class);
		final ExtractionSession session = new ExtractionSession();
		final CompletableFuture<Void> future = mock(CompletableFuture.class);
		player.attachPlaybackService(playbackService);
		setField(player, "extractionSession", session);
		setField(player, "cf", future);

		player.hide();

		assertTrue(session.isCancelled());
		verify(future).cancel(true);
		verify(playerView).hide();
		verify(engine).clear();
		verify(playbackService).hideNotification();
	}

	@Test
	public void playerSourceError_invalidatesCachedPlaybackEntryForCurrentVideo() throws Exception {
		stubSuccessfulPlaybackDetails(WATCH_URL, defaultStreamDetails(), "Loaded title", 60L);
		player.play(WATCH_URL);
		clearInvocations(extractor);

		player.invalidatePlaybackCacheIfSourceOpenFailure(sourceOpenFailure());

		verify(extractor).invalidatePlaybackCacheByVideoId(VIDEO_ID);
	}

	@Test
	public void nonOpenFailure_doesNotInvalidateCachedPlaybackEntry() throws Exception {
		stubSuccessfulPlaybackDetails(WATCH_URL, defaultStreamDetails(), "Loaded title", 60L);
		player.play(WATCH_URL);
		clearInvocations(extractor);

		player.invalidatePlaybackCacheIfSourceOpenFailure(sourceReadFailure());

		verify(extractor, never()).invalidatePlaybackCacheByVideoId(VIDEO_ID);
	}

	@Test
	public void sourceError_usesLoadedVideoIdInsteadOfNextRequestedVideoId() throws Exception {
		stubSuccessfulPlaybackDetails(WATCH_URL, defaultStreamDetails(), "Loaded title", 60L);
		when(extractor.getPlaybackDetails(eq(NEXT_WATCH_URL), any(ExtractionSession.class)))
						.thenThrow(new IOException("network down"));

		try (MockedStatic<ErrorDialog> dialog = org.mockito.Mockito.mockStatic(ErrorDialog.class)) {
			player.play(WATCH_URL);
			player.play(NEXT_WATCH_URL);
			clearInvocations(extractor);

			player.invalidatePlaybackCacheIfSourceOpenFailure(sourceOpenFailure());

			verify(extractor).invalidatePlaybackCacheByVideoId(VIDEO_ID);
			verify(extractor, never()).invalidatePlaybackCacheByVideoId(NEXT_VIDEO_ID);
			dialog.verify(() -> ErrorDialog.show(eq(activity), eq("Extract failed"), any(ExtractionException.class)));
		}
	}

	@Test
	public void release_clearsLoadedVideoIdAndReleasesEngine() throws Exception {
		stubSuccessfulPlaybackDetails(WATCH_URL, defaultStreamDetails(), "Loaded title", 60L);
		player.play(WATCH_URL);
		clearInvocations(extractor, engine);

		player.release();
		player.invalidatePlaybackCacheIfSourceOpenFailure(sourceOpenFailure());

		verify(engine).release();
		verifyNoInteractions(extractor);
	}

	@Test
	public void release_whileMiniPlayerActive_doesNotRestoreFullscreenUi() {
		player.enterInAppMiniPlayer();
		clearInvocations(playerView, controller, engine);

		player.release();

		verify(playerView, never()).exitInAppMiniPlayer();
		verify(controller, never()).exitMiniPlayer();
		verify(playerView).setMiniPlayerCallbacks(null, null);
		verify(engine).release();
	}

	@Test
	public void restoreInAppMiniPlayerUiIfNeeded_reappliesMiniPlayerModeWhenActive() {
		player.enterInAppMiniPlayer();
		clearInvocations(playerView, controller);

		player.restoreInAppMiniPlayerUiIfNeeded();

		verify(playerView).enterInAppMiniPlayer();
		verify(controller).enterMiniPlayer();
	}

	@Test
	public void restoreInAppMiniPlayerUiIfNeeded_showsViewAgainAfterMiniPlayerWasSuspended() {
		player.enterInAppMiniPlayer();
		player.suspendInAppMiniPlayerUiIfNeeded();
		clearInvocations(playerView, controller);

		player.restoreInAppMiniPlayerUiIfNeeded();

		verify(playerView).show();
		verify(controller).enterMiniPlayer();
	}

	@Test
	public void suspendInAppMiniPlayerUiIfNeeded_hidesViewWithoutExitingMiniPlayer() {
		player.enterInAppMiniPlayer();
		clearInvocations(playerView, controller);

		player.suspendInAppMiniPlayerUiIfNeeded();

		verify(playerView).hide();
		verify(playerView, never()).exitInAppMiniPlayer();
		verify(controller, never()).exitMiniPlayer();
	}

	@Test
	public void play_prefersOriginalAudioTrackAndKeepsMutableList() throws Exception {
		final AudioStream dubbed = mock(AudioStream.class);
		final AudioStream original = mock(AudioStream.class);
		when(kv.decodeString("last_audio_lang", "und")).thenReturn("fr");
		when(dubbed.getAudioTrackName()).thenReturn("French");
		when(original.getAudioTrackName()).thenReturn("English original");
		when(dubbed.getAudioLocale()).thenReturn(Locale.FRENCH);
		when(original.getAudioLocale()).thenReturn(Locale.ENGLISH);
		when(dubbed.getAverageBitrate()).thenReturn(192);
		when(original.getAverageBitrate()).thenReturn(128);

		final StreamDetails streamDetails = new StreamDetails(
						new ArrayList<>(),
						List.of(dubbed, original),
						null,
						null,
						null,
						StreamType.VIDEO_STREAM);
		final VideoDetails videoDetails = new VideoDetails();
		videoDetails.setDuration(1L);
		when(extractor.getPlaybackDetails(eq(WATCH_URL), any(ExtractionSession.class)))
						.thenReturn(new PlaybackDetails(videoDetails, streamDetails));

		player.play(WATCH_URL);

		verify(engine).play(any(VideoDetails.class), eq(streamDetails));
		assertEquals(2, streamDetails.getAudioStreams().size());
		assertSame(original, streamDetails.getAudioStreams().get(0));
		streamDetails.getAudioStreams().clear();
		assertEquals(0, streamDetails.getAudioStreams().size());
	}

	@Test
	public void play_prefersOriginalAudioTrackUnderTurkishLocale() throws Exception {
		final Locale originalLocale = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			final AudioStream dubbed = mock(AudioStream.class);
			final AudioStream original = mock(AudioStream.class);
			when(kv.decodeString("last_audio_lang", "und")).thenReturn("fr");
			when(dubbed.getAudioTrackName()).thenReturn("French");
			when(original.getAudioTrackName()).thenReturn("ENGLISH ORIGINAL");
			when(dubbed.getAudioLocale()).thenReturn(Locale.FRENCH);
			when(original.getAudioLocale()).thenReturn(Locale.ENGLISH);
			when(dubbed.getAverageBitrate()).thenReturn(192);
			when(original.getAverageBitrate()).thenReturn(128);

			final StreamDetails streamDetails = new StreamDetails(
							new ArrayList<>(),
							List.of(dubbed, original),
							null,
							null,
							null,
							StreamType.VIDEO_STREAM);
			final VideoDetails videoDetails = new VideoDetails();
			videoDetails.setDuration(1L);
			when(extractor.getPlaybackDetails(eq(WATCH_URL), any(ExtractionSession.class)))
							.thenReturn(new PlaybackDetails(videoDetails, streamDetails));

			player.play(WATCH_URL);

			assertSame(original, streamDetails.getAudioStreams().get(0));
		} finally {
			Locale.setDefault(originalLocale);
		}
	}

	@Test
	public void play_withAttachedPlaybackServiceShowsNotification() throws Exception {
		final PlaybackService playbackService = mock(PlaybackService.class);
		player.attachPlaybackService(playbackService);
		stubSuccessfulPlaybackDetails(WATCH_URL, defaultStreamDetails(), "Demo title", 60L);

		player.play(WATCH_URL);

		verify(playbackService).initialize(engine);
		verify(playbackService).showNotification(
						"Demo title",
						"Demo author",
						"https://example.com/thumb.jpg",
						60_000L);
	}

	@Test
	public void play_extractionFailureShowsDialogWithoutStartingPlayback() throws Exception {
		when(extractor.getPlaybackDetails(eq(WATCH_URL), any(ExtractionSession.class)))
						.thenThrow(new IOException("network down"));

		try (MockedStatic<ErrorDialog> dialog = org.mockito.Mockito.mockStatic(ErrorDialog.class)) {
			player.play(WATCH_URL);

			verify(engine, never()).play(any(VideoDetails.class), any(StreamDetails.class));
			dialog.verify(() -> ErrorDialog.show(eq(activity), eq("Extract failed"), any(ExtractionException.class)));
		}
	}

	@Test
	public void onPlayerError_invalidatesCacheAndShowsDialog() throws Exception {
		stubSuccessfulPlaybackDetails(WATCH_URL, defaultStreamDetails(), "Loaded title", 60L);
		player.play(WATCH_URL);
		clearInvocations(extractor);
		final PlaybackException error = sourceOpenFailure();
		try (MockedStatic<ErrorDialog> dialog = org.mockito.Mockito.mockStatic(ErrorDialog.class)) {
			listener.onPlayerError(error);

			verify(extractor).invalidatePlaybackCacheByVideoId(VIDEO_ID);
			dialog.verify(() -> ErrorDialog.show(activity, error.getMessage(), error));
		}
	}

	private void stubSuccessfulPlaybackDetails(final String url,
	                                          final StreamDetails streamDetails,
	                                          final String title,
	                                          final long durationSeconds) throws Exception {
		final VideoDetails videoDetails = new VideoDetails();
		videoDetails.setTitle(title);
		videoDetails.setAuthor("Demo author");
		videoDetails.setThumbnail("https://example.com/thumb.jpg");
		videoDetails.setDuration(durationSeconds);
		when(extractor.getPlaybackDetails(eq(url), any(ExtractionSession.class)))
						.thenReturn(new PlaybackDetails(videoDetails, streamDetails));
	}

	private StreamDetails defaultStreamDetails() {
		return new StreamDetails(
						new ArrayList<>(List.of(mock(VideoStream.class))),
						new ArrayList<>(),
						new ArrayList<>(),
						"https://example.com/dash.mpd",
						"https://example.com/hls.m3u8",
						StreamType.VIDEO_STREAM);
	}

	@Test
	public void onPictureInPictureModeChanged_exitsToWatchWhenMiniPlayerIsActive() {
		final Runnable onRestore = mock(Runnable.class);
		player.setMiniPlayerCallbacks(onRestore, null);
		player.enterInAppMiniPlayer();

		player.onPictureInPictureModeChanged(true);
		player.onPictureInPictureModeChanged(false);

		verify(controller).onPictureInPictureModeChanged(true);
		verify(controller).onPictureInPictureModeChanged(false);
		verify(onRestore).run();
	}

	@Test
	public void onPictureInPictureModeChanged_doesNotExitToWatchWithoutPriorPip() {
		final Runnable onRestore = mock(Runnable.class);
		player.setMiniPlayerCallbacks(onRestore, null);
		player.enterInAppMiniPlayer();

		player.onPictureInPictureModeChanged(false);

		verify(controller).onPictureInPictureModeChanged(false);
		verify(onRestore, never()).run();
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

	private static QueueNav watch() {
		return QueueNav.from(true, true, true, true, false, true);
	}

	private static void setField(final Object target, final String fieldName, final Object value) throws Exception {
		final Field field = LitePlayer.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}

