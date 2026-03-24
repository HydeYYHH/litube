package com.hhst.youtubelite.player;

import android.app.Activity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.ui.DefaultTimeBar;

import com.hhst.youtubelite.PlaybackService;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.extractor.ExtractionSession;
import com.hhst.youtubelite.extractor.ExtractionException;
import com.hhst.youtubelite.extractor.PlaybackDetails;
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.player.common.PlayerUtils;
import com.hhst.youtubelite.player.controller.Controller;
import com.hhst.youtubelite.player.engine.Engine;
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager;
import com.hhst.youtubelite.player.sponsor.SponsorOverlayView;
import com.hhst.youtubelite.ui.ErrorDialog;
import com.tencent.mmkv.MMKV;

import org.schabi.newpipe.extractor.stream.AudioStream;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import dagger.hilt.android.scopes.ActivityScoped;

@UnstableApi
@ActivityScoped
public class LitePlayer {
	private static final String KEY_LAST_AUDIO_LANG = "last_audio_lang";
	private static final String UNKNOWN_LANGUAGE = "und";
	private static final Locale NORMALIZATION_LOCALE = Locale.ROOT;

	@NonNull
	private final Activity activity;
	@NonNull
	private final YoutubeExtractor extractor;
	@NonNull
	private final LitePlayerView playerView;
	@NonNull
	private final Controller controller;
	@NonNull
	private final Engine engine;
	@NonNull
	private final SponsorBlockManager sponsor;
	@NonNull
	private final Executor executor;
	private final MMKV kv = MMKV.defaultMMKV();
	@Nullable
	private PlaybackService playbackService;
	@Nullable
	private CompletableFuture<Void> cf;
	@Nullable
	private String vid = null;
	@Nullable
	private volatile String loadedVideoId;
	@Nullable
	private ExtractionSession extractionSession;
	@Nullable
	private Runnable onMiniPlayerRestore;
	@Nullable
	private Runnable onMiniPlayerClose;
	private boolean inAppMiniPlayer;

	@Inject
	public LitePlayer(@NonNull final Activity activity,
	                  @NonNull final YoutubeExtractor extractor,
	                  @NonNull final LitePlayerView playerView,
	                  @NonNull final Controller controller,
	                  @NonNull final Engine engine,
	                  @NonNull final SponsorBlockManager sponsor,
	                  @NonNull final Executor executor) {
		this.activity = activity;
		this.extractor = extractor;
		this.playerView = playerView;
		this.controller = controller;
		this.engine = engine;
		this.sponsor = sponsor;
		this.executor = executor;
		playerView.setup();
		setupEngineListeners();
	}

	private void setupEngineListeners() {
		engine.addListener(new Player.Listener() {
			@Override
			public void onIsPlayingChanged(boolean isPlaying) {
				updateServiceProgress(isPlaying);
			}

			@Override
			public void onTracksChanged(@NonNull Tracks tracks) {
				saveSelectedTrackLanguage(tracks);
			}

			@Override
			public void onPlaybackStateChanged(int state) {
				if (state == Player.STATE_READY) {
					updateServiceProgress(engine.isPlaying());
				}
			}

			@Override
			public void onPlayerError(@NonNull PlaybackException error) {
				invalidatePlaybackCacheIfSourceOpenFailure(error);
				ErrorDialog.show(activity, error.getMessage(), error);
			}
		});
	}

	private void saveSelectedTrackLanguage(Tracks tracks) {
		try {
			for (Tracks.Group group : tracks.getGroups()) {
				if (group.getType() == androidx.media3.common.C.TRACK_TYPE_AUDIO && group.isSelected()) {
					for (int i = 0; i < group.length; i++) {
						if (group.isTrackSelected(i)) {
							String lang = group.getTrackFormat(i).language;
							kv.encode(KEY_LAST_AUDIO_LANG, lang != null ? lang : "und");
							return;
						}
					}
				}
			}
		} catch (Exception ignored) {
		}
	}

	private void updateServiceProgress(boolean isPlaying) {
		if (playbackService != null) {
			playbackService.updateProgress(engine.position(), engine.getPlaybackRate(), isPlaying);
		}
	}

	public void attachPlaybackService(@Nullable PlaybackService service) {
		this.playbackService = service;
		if (service != null) {
			service.initialize(engine);
		}
	}

	private void applyAudioPreference(@NonNull final StreamDetails streamDetails) {
		final List<AudioStream> audioStreams = streamDetails.getAudioStreams();
		if (audioStreams == null || audioStreams.isEmpty()) return;

		final String savedLanguage = kv.decodeString(KEY_LAST_AUDIO_LANG, UNKNOWN_LANGUAGE);
		final List<AudioStream> reordered = new ArrayList<>(audioStreams);
		reordered.sort((first, second) -> compareAudioStreams(first, second, savedLanguage));
		streamDetails.setAudioStreams(reordered);
	}

	private static int compareAudioStreams(@NonNull final AudioStream first,
	                                       @NonNull final AudioStream second,
	                                       @NonNull final String savedLanguage) {
		final int originalComparison = Boolean.compare(
						isOriginalAudioTrack(second),
						isOriginalAudioTrack(first));
		if (originalComparison != 0) return originalComparison;

		final int savedLanguageComparison = Boolean.compare(
						matchesSavedLanguage(second, savedLanguage),
						matchesSavedLanguage(first, savedLanguage));
		if (savedLanguageComparison != 0) return savedLanguageComparison;

		return Integer.compare(second.getAverageBitrate(), first.getAverageBitrate());
	}

	private static boolean isOriginalAudioTrack(@NonNull final AudioStream audioStream) {
		final String trackName = audioStream.getAudioTrackName();
		return trackName != null && trackName.toLowerCase(NORMALIZATION_LOCALE).contains("original");
	}

	private static boolean matchesSavedLanguage(@NonNull final AudioStream audioStream,
	                                            @NonNull final String savedLanguage) {
		return audioLanguage(audioStream).equalsIgnoreCase(savedLanguage);
	}

	@NonNull
	private static String audioLanguage(@NonNull final AudioStream audioStream) {
		return audioStream.getAudioLocale() != null
						? audioStream.getAudioLocale().getLanguage()
						: UNKNOWN_LANGUAGE;
	}

	public void play(String url) {
		final String videoId = YoutubeExtractor.getVideoId(url);
		if (videoId == null || Objects.equals(this.vid, videoId)) return;
		this.vid = videoId;

		activity.runOnUiThread(() -> {
			engine.clear();
			playerView.setTitle(null);
			final SponsorOverlayView layer = playerView.findViewById(R.id.sponsor_overlay);
			layer.setData(null, 0, TimeUnit.MILLISECONDS);
			final DefaultTimeBar bar = playerView.findViewById(R.id.exo_progress);
			bar.setAdGroupTimesMs(null, null, 0);
			playerView.show();
		});

		cancelCurrentExtraction();
		if (cf != null) cf.cancel(true);
		final ExtractionSession session = new ExtractionSession();
		extractionSession = session;

		cf = CompletableFuture.supplyAsync(() -> {
			try {
				sponsor.load(videoId);
				if (session.isCancelled()) throw new InterruptedException("Extraction canceled");
				PlaybackDetails playbackDetails = extractor.getPlaybackDetails(url, session);
				StreamDetails streamDetails = playbackDetails.getStreamDetails();
				streamDetails.setVideoStreams(PlayerUtils.filterBestStreams(streamDetails.getVideoStreams()));
				applyAudioPreference(streamDetails);
				return playbackDetails;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new CompletionException("Interrupted", e);
			} catch (InterruptedIOException e) {
				throw new CompletionException("Interrupted", e);
			} catch (Exception e) {
				throw new ExtractionException("Extract failed", e);
			}
		}, executor).thenAccept(er -> activity.runOnUiThread(() -> {
			if (this.extractionSession == session) this.extractionSession = null;
			if (!Objects.equals(this.vid, videoId)) return;
			this.loadedVideoId = videoId;
			playerView.setTitle(er.getVideoDetails().getTitle());
			playerView.updateSkipMarkers(er.getVideoDetails().getDuration(), TimeUnit.SECONDS);

			engine.play(er.getVideoDetails(), er.getStreamDetails());

			if (playbackService != null) {
				playbackService.showNotification(er.getVideoDetails().getTitle(), er.getVideoDetails().getAuthor(), er.getVideoDetails().getThumbnail(), er.getVideoDetails().getDuration() * 1000);
			}
		})).exceptionally(e -> {
			if (this.extractionSession == session) this.extractionSession = null;
			Throwable cause = e instanceof CompletionException ? e.getCause() : e;
			if (cause instanceof ExtractionException) {
				activity.runOnUiThread(() -> {
					if (!Objects.equals(this.vid, videoId)) return;
					ErrorDialog.show(activity, cause.getMessage(), cause);
				});
			}
			return null;
		});
	}

	public void hide() {
		this.vid = null;
		this.loadedVideoId = null;
		cancelCurrentExtraction();
		if (cf != null) cf.cancel(true);
		activity.runOnUiThread(() -> {
			exitInAppMiniPlayer();
			setMiniPlayerCallbacks(null, null);
			playerView.hide();
			engine.clear();
			if (playbackService != null) {
				playbackService.hideNotification();
			}
		});
	}

	public boolean isPlaying() {
		return engine.isPlaying();
	}

	public void pause() {
		engine.pause();
	}

	public void seekToIfLoaded(final long positionMs) {
		if (loadedVideoId == null || positionMs < 0L) return;
		activity.runOnUiThread(() -> engine.seekTo(positionMs));
	}

	public boolean seekLoadedVideo(@Nullable final String url, final long positionMs) {
		if (positionMs < 0L || url == null) return false;
		final String videoId = YoutubeExtractor.getVideoId(url);
		if (videoId == null || !Objects.equals(loadedVideoId, videoId)) return false;
		seekToIfLoaded(positionMs);
		return true;
	}

	public boolean isFullscreen() {
		return controller.isFullscreen();
	}

	public void enterFullscreen() {
		controller.enterFullscreen();
	}

	public void exitFullscreen() {
		controller.exitFullscreen();
	}

	public void enterPictureInPicture() {
		playerView.enterPiP();
	}

	public boolean isSuspendableWatchSession() {
		return playerView.getVisibility() == View.VISIBLE;
	}

	public void enterInAppMiniPlayer() {
		inAppMiniPlayer = true;
		playerView.enterInAppMiniPlayer();
		controller.enterMiniPlayer();
	}

	public void exitInAppMiniPlayer() {
		inAppMiniPlayer = false;
		playerView.exitInAppMiniPlayer();
		controller.exitMiniPlayer();
	}

	public boolean isInAppMiniPlayer() {
		return inAppMiniPlayer;
	}

	public void stopAndCloseFromMiniPlayer() {
		hide();
	}

	public void setMiniPlayerCallbacks(@Nullable final Runnable onRestore, @Nullable final Runnable onClose) {
		onMiniPlayerRestore = onRestore;
		onMiniPlayerClose = onClose;
		playerView.setMiniPlayerCallbacks(
						onRestore != null ? this::dispatchMiniPlayerRestore : null,
						onClose != null ? this::dispatchMiniPlayerClose : null);
	}

	public boolean shouldAutoEnterPictureInPicture() {
		return playerView.getVisibility() == View.VISIBLE;
	}

	public void onPictureInPictureModeChanged(final boolean isInPiP) {
		controller.onPictureInPictureModeChanged(isInPiP);
		if (!isInPiP && inAppMiniPlayer && onMiniPlayerRestore != null) {
			dispatchMiniPlayerRestore();
		}
	}

	public void setHeight(int height) {
		playerView.post(() -> playerView.setHeight(height));
	}

	public void release() {
		cancelCurrentExtraction();
		if (cf != null) cf.cancel(true);
		loadedVideoId = null;
		onMiniPlayerRestore = null;
		onMiniPlayerClose = null;
		final boolean wasInMiniPlayer = inAppMiniPlayer;
		activity.runOnUiThread(() -> {
			playerView.setMiniPlayerCallbacks(null, null);
			if (wasInMiniPlayer) {
				playerView.exitInAppMiniPlayer();
				controller.exitMiniPlayer();
			}
		});
		inAppMiniPlayer = false;
		engine.release();
	}

	private void dispatchMiniPlayerRestore() {
		if (onMiniPlayerRestore != null) onMiniPlayerRestore.run();
	}

	private void dispatchMiniPlayerClose() {
		final Runnable onClose = onMiniPlayerClose;
		if (onClose == null) return;
		stopAndCloseFromMiniPlayer();
		onClose.run();
	}

	void invalidatePlaybackCacheIfSourceOpenFailure(@NonNull final PlaybackException error) {
		if (loadedVideoId == null) return;
		if (!isPlaybackSourceOpenFailure(error)) return;
		extractor.invalidatePlaybackCacheByVideoId(loadedVideoId);
	}

	static boolean isPlaybackSourceOpenFailure(@Nullable final Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			if (current instanceof HttpDataSource.HttpDataSourceException httpException
							&& httpException.type == HttpDataSource.HttpDataSourceException.TYPE_OPEN) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private void cancelCurrentExtraction() {
		if (extractionSession == null) return;
		extractionSession.cancel();
		extractionSession = null;
	}
}
