package com.hhst.youtubelite.player;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleExtractor;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import com.hhst.youtubelite.R;
import com.hhst.youtubelite.player.datasource.YoutubeHttpDataSource;
import com.hhst.youtubelite.player.exception.InitializationException;
import com.hhst.youtubelite.player.interfaces.IEngineInternal;

import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import lombok.Getter;
import lombok.Setter;

@OptIn(markerClass = UnstableApi.class)
public class PlayerEngine implements IEngineInternal {
	private static final int MIN_BUFFER_MS = 15_000;
	private static final int MAX_BUFFER_MS = 60_000;
	private static final int BUFFER_FOR_PLAYBACK_MS = 2_500;
	private static final int BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000;
	private static final int BACK_BUFFER_DURATION_MS = 30_000;
	private static final int READ_TIMEOUT_MS = 30_000;
	private static final int CONNECT_TIMEOUT_MS = 30_000;
	private static final int DEFAULT_MAX_BUFFER_MS = 1_000;
	private static final float LIVE_MAX_SPEED = 1.05f;
	private static final long CACHE_SIZE_BYTES = 100 * 1024 * 1024;
	private static final float SUBTITLE_LINE_FRACTION = 0.92f;
	private static final float SUBTITLE_POSITION_FRACTION = 0.5f;
	private static final String TAG = "PlayerEngine";

	private static SimpleCache simpleCache;
	@NonNull
	private final PlayerView playerView;
	@NonNull
	private final Context appContext;
	@NonNull
	private final DefaultTrackSelector trackSelector;
	@Getter
	@NonNull
	private final List<String> subtitles = new ArrayList<>();
	@NonNull
	private final List<SubtitlesStream> subtitleStreams = new ArrayList<>();
	@Nullable
	private ExoPlayer exoPlayer;
	private YoutubeHttpDataSource.Factory dataSourceFactory;
	@Setter
	@Nullable
	private NavigationCallback navCallback;
	@Setter
	@Nullable
	private Consumer<PlaybackException> errorListener;
	@Nullable
	private SubtitleView subtitleView;

	public PlayerEngine() {
		final PlayerContext context = PlayerContext.getInstance();
		this.appContext = context.getActivity().getApplicationContext();
		this.playerView = context.getPlayerView();

		final LeastRecentlyUsedCacheEvictor evictor = new LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES);
		simpleCache = new SimpleCache(new File(appContext.getCacheDir(), "player"), evictor, new StandaloneDatabaseProvider(appContext));

		final ExoTrackSelection.Factory adaptiveTrackSelectionFactory = new AdaptiveTrackSelection.Factory(DEFAULT_MAX_BUFFER_MS, AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS, AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS, AdaptiveTrackSelection.DEFAULT_BANDWIDTH_FRACTION);
		trackSelector = new DefaultTrackSelector(appContext, adaptiveTrackSelectionFactory);

		final DefaultTrackSelector.Parameters params = trackSelector.buildUponParameters().setTunnelingEnabled(true).build();
		trackSelector.setParameters(params);
		initializeDataSourceFactory();
		initializePlayer();
	}

	private void initializeDataSourceFactory() {
		dataSourceFactory = new YoutubeHttpDataSource.Factory().setConnectTimeoutMs(CONNECT_TIMEOUT_MS).setReadTimeoutMs(READ_TIMEOUT_MS);
	}

	private void initializePlayer() {
		final DefaultLoadControl loadControl = createLoadControl();
		final DefaultRenderersFactory renderersFactory = createRenderersFactory();
		final AudioAttributes audioAttributes = createAudioAttributes();

		setupSubtitleView();

		exoPlayer = new ExoPlayer.Builder(appContext, renderersFactory).setLoadControl(loadControl).setTrackSelector(trackSelector).setAudioAttributes(audioAttributes, true).setHandleAudioBecomingNoisy(true).setUsePlatformDiagnostics(false).setMediaSourceFactory(new DefaultMediaSourceFactory(appContext).setLiveMaxSpeed(LIVE_MAX_SPEED)).build();

		setupPlayerListeners();
		configurePlayerView();
	}

	private DefaultLoadControl createLoadControl() {
		return new DefaultLoadControl.Builder().setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, BUFFER_FOR_PLAYBACK_MS, BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS).setBackBuffer(BACK_BUFFER_DURATION_MS, true).setPrioritizeTimeOverSizeThresholds(true).build();
	}

	private DefaultRenderersFactory createRenderersFactory() {
		return new DefaultRenderersFactory(appContext).setEnableDecoderFallback(true).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
	}

	private AudioAttributes createAudioAttributes() {
		return new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build();
	}

	private void setupSubtitleView() {
		// Disable default subtitle view
		final SubtitleView defaultSubtitleView = playerView.getSubtitleView();
		if (defaultSubtitleView != null) defaultSubtitleView.setVisibility(View.GONE);

		// Initialize custom subtitle view
		subtitleView = playerView.findViewById(R.id.custom_subtitle_view);
		if (subtitleView != null) {
			subtitleView.setUserDefaultStyle();
			subtitleView.setUserDefaultTextSize();
		}
	}

	private void setupPlayerListeners() {
		if (exoPlayer == null) return;

		exoPlayer.addListener(new Player.Listener() {
			@Override
			public void onPlayerError(@NonNull final PlaybackException error) {
				if (errorListener != null) errorListener.accept(error);
			}

			@Override
			public void onCues(@NonNull final CueGroup cueGroup) {
				// Display subtitles on the bottom of the player
				final List<Cue> newCues = new ArrayList<>();
				for (final Cue cue : cueGroup.cues) {
					newCues.add(cue.buildUpon().setTextSize(Cue.DIMEN_UNSET, Cue.TEXT_SIZE_TYPE_FRACTIONAL).setSize(Cue.DIMEN_UNSET).setLine(SUBTITLE_LINE_FRACTION, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_END).setPosition(SUBTITLE_POSITION_FRACTION).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE).build());
				}
				if (subtitleView != null) subtitleView.setCues(newCues);
			}
		});

		exoPlayer.addAnalyticsListener(new AnalyticsListener() {
			@Override
			public void onDroppedVideoFrames(@NonNull final EventTime eventTime, final int droppedFrames, final long elapsedMs) {
				Log.w("ExoPlayer", "Dropped frames: " + droppedFrames);
			}
		});
	}

	private void configurePlayerView() {
		if (exoPlayer == null) throw new InitializationException(ExoPlayer.class);
		final Player fp = new ForwardingPlayer(exoPlayer) {
			@Override
			public void seekToNext() {
				if (navCallback != null) navCallback.onNext();
				else super.seekToNext();
			}

			@Override
			public void seekToPrevious() {
				if (navCallback != null) navCallback.onPrev();
				else super.seekToPrevious();
			}

			@Override
			public boolean isCommandAvailable(final int command) {
				return command == COMMAND_SEEK_TO_NEXT || command == COMMAND_SEEK_TO_PREVIOUS || super.isCommandAvailable(command);
			}
		};
		playerView.setPlayer(fp);
		playerView.setShowNextButton(true);
		playerView.setShowPreviousButton(true);
		playerView.setKeepScreenOn(true);
	}

	@Override
	public void play(@Nullable final Stream videoStream, @Nullable final Stream audioStream, @Nullable final List<SubtitlesStream> subtitlesStreams, @Nullable final String dashUrl, final long durationMs, final long resumePosMs, final float speed) {
		if (exoPlayer == null) return;

		final MediaSource videoAudioSource = createVideoAudioSource(videoStream, audioStream, dashUrl, durationMs);
		final MediaSource finalSource = setupSubtitleSources(videoAudioSource, subtitlesStreams);

		exoPlayer.setMediaSource(finalSource);
		exoPlayer.setPlaybackParameters(new PlaybackParameters(speed));

		updateSubtitleOutput();

		if (resumePosMs > 0) exoPlayer.seekTo(resumePosMs);
		exoPlayer.prepare();
		exoPlayer.setPlayWhenReady(true);
	}

	private MediaSource createVideoAudioSource(@Nullable final Stream videoStream, @Nullable final Stream audioStream, @Nullable final String dashUrl, final long durationMs) {
		if (dashUrl != null && !dashUrl.isEmpty()) {
			final CacheDataSource.Factory cacheDataSourceFactory = createCacheDataSourceFactory();
			return new DashMediaSource.Factory(cacheDataSourceFactory).createMediaSource(MediaItem.fromUri(dashUrl));
		} else {
			final MediaSource videoSource = createMediaSource(videoStream, durationMs);
			final MediaSource audioSource = createMediaSource(audioStream, durationMs);
			return new MergingMediaSource(videoSource, audioSource);
		}
	}

	private MediaSource setupSubtitleSources(@NonNull final MediaSource baseSource, @Nullable final List<SubtitlesStream> subtitlesStreams) {
		if (subtitlesStreams == null || subtitlesStreams.isEmpty()) return baseSource;

		final List<MediaSource> subtitleSources = new ArrayList<>();
		subtitles.clear();
		subtitleStreams.clear();
		final DefaultSubtitleParserFactory subtitleParserFactory = new DefaultSubtitleParserFactory();

		for (final SubtitlesStream subtitle : subtitlesStreams) {
			if (subtitle.getFormat() == null) continue;

			final MediaSource subtitleSource = createSubtitleSource(subtitle, subtitleParserFactory);
			subtitleSources.add(subtitleSource);

			String displayName = subtitle.getDisplayLanguageName();
			if (subtitle.isAutoGenerated()) displayName += " (Auto-generated)";

			if (!subtitles.contains(displayName)) {
				subtitles.add(displayName);
				subtitleStreams.add(subtitle);
			}
		}

		final MediaSource[] sources = new MediaSource[subtitleSources.size() + 1];
		sources[0] = baseSource;
		for (int i = 0; i < subtitleSources.size(); i++) {
			sources[i + 1] = subtitleSources.get(i);
		}
		return new MergingMediaSource(true, sources);
	}

	private MediaSource createSubtitleSource(@NonNull final SubtitlesStream subtitle, @NonNull final DefaultSubtitleParserFactory subtitleParserFactory) {
		final var format = subtitle.getFormat();
		String mimeType = null;
		if (format != null) mimeType = format.mimeType;
		final Format subtitleFormat = new Format.Builder().setSampleMimeType(mimeType).setLanguage(subtitle.getLanguageTag()).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).setRoleFlags(C.ROLE_FLAG_SUBTITLE).setLabel(subtitle.getDisplayLanguageName()).build();
		final ExtractorsFactory subtitleExtractorsFactory = () -> new Extractor[]{new SubtitleExtractor(subtitleParserFactory.create(subtitleFormat), subtitleFormat)};
		return new ProgressiveMediaSource.Factory(dataSourceFactory, subtitleExtractorsFactory).createMediaSource(MediaItem.fromUri(Uri.parse(subtitle.getContent())));
	}

	private CacheDataSource.Factory createCacheDataSourceFactory() {
		return new CacheDataSource.Factory().setCache(simpleCache).setUpstreamDataSourceFactory(dataSourceFactory).setCacheReadDataSourceFactory(new FileDataSource.Factory()).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR | CacheDataSource.FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS);
	}

	private MediaSource createMediaSource(@Nullable final Stream stream, final long durationMs) {
		if (stream == null)
			return new ProgressiveMediaSource.Factory(createCacheDataSourceFactory()).createMediaSource(MediaItem.EMPTY);

		final CacheDataSource.Factory cacheDataSourceFactory = createCacheDataSourceFactory();
		try {
			if (stream.getItagItem() != null) {
				final String manifestString = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(stream.getContent(), stream.getItagItem(), durationMs / 1_000);
				final DashManifest manifest = new DashManifestParser().parse(Uri.parse(stream.getContent()), new ByteArrayInputStream(manifestString.getBytes(StandardCharsets.UTF_8)));
				return new DashMediaSource.Factory(cacheDataSourceFactory).createMediaSource(manifest);
			}
		} catch (final Exception e) {
			Log.w(TAG, "Failed to create DashMediaSource", e);
		}
		final MediaItem.Builder mediaItemBuilder = MediaItem.fromUri(stream.getContent()).buildUpon();
		if (stream.getFormat() != null) mediaItemBuilder.setMimeType(stream.getFormat().mimeType);

		return new ProgressiveMediaSource.Factory(cacheDataSourceFactory).createMediaSource(mediaItemBuilder.build());
	}

	@Override
	public boolean isPlaying() {
		if (exoPlayer == null) throw new InitializationException(ExoPlayer.class);
		return exoPlayer.isPlaying();
	}

	@Override
	public void play() {
		if (exoPlayer == null) throw new InitializationException(ExoPlayer.class);
		exoPlayer.play();
	}

	@Override
	public void pause() {
		if (exoPlayer == null) throw new InitializationException(ExoPlayer.class);
		exoPlayer.pause();
	}

	@Override
	public void seekTo(final long pos) {
		if (exoPlayer == null) throw new InitializationException(ExoPlayer.class);
		exoPlayer.seekTo(pos);
	}

	@Override
	public void addListener(final Player.Listener listener) {
		if (exoPlayer == null) throw new InitializationException(ExoPlayer.class);
		exoPlayer.addListener(listener);
	}

	@Override
	public long position() {
		if (exoPlayer == null) throw new InitializationException(ExoPlayer.class);
		return exoPlayer.getCurrentPosition();
	}

	@Override
	public void setSpeed(final float speed) {
		if (exoPlayer == null) throw new InitializationException(ExoPlayer.class);
		exoPlayer.setPlaybackParameters(new PlaybackParameters(speed));
	}

	@Override
	public float speed() {
		if (exoPlayer == null) return 1f;
		return exoPlayer.getPlaybackParameters().speed;
	}

	@Override
	public void setVideoQuality(final int height) {
		trackSelector.setParameters(trackSelector.buildUponParameters().setMaxVideoSize(Integer.MAX_VALUE, height).setMinVideoSize(0, height).build());
	}

	private void updateSubtitleOutput() {
		if (exoPlayer == null) throw new InitializationException(ExoPlayer.class);
		final TrackSelectionParameters params = exoPlayer.getTrackSelectionParameters();
		final TrackSelectionParameters.Builder builder = params.buildUpon();
		final boolean hasPreferredSubtitle = !params.preferredTextLanguages.isEmpty();
		builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !hasPreferredSubtitle);
		exoPlayer.setTrackSelectionParameters(builder.build());
	}

	@Override
	public boolean areSubtitlesEnabled() {
		if (exoPlayer == null) return false;
		final TrackSelectionParameters params = exoPlayer.getTrackSelectionParameters();
		return !params.preferredTextLanguages.isEmpty();
	}

	@Override
	public VideoSize getVideoSize() {
		if (exoPlayer == null) return VideoSize.UNKNOWN;
		return exoPlayer.getVideoSize();
	}

	@Override
	public void setRepeatMode(final int mode) {
		if (exoPlayer != null) exoPlayer.setRepeatMode(mode);
	}

	@Override
	public int getPlaybackState() {
		if (exoPlayer == null) return Player.STATE_IDLE;
		return exoPlayer.getPlaybackState();
	}

	@Override
	public void setSubtitlesEnabled(final boolean enabled) {
		if (exoPlayer == null) return;
		final TrackSelectionParameters params = exoPlayer.getTrackSelectionParameters();
		final TrackSelectionParameters.Builder builder = params.buildUpon();

		if (enabled) {
			if (params.preferredTextLanguages.isEmpty()) builder.setPreferredTextLanguages("en", "und");
		} else {
			builder.setPreferredTextLanguages();
		}

		exoPlayer.setTrackSelectionParameters(builder.build());
		updateSubtitleOutput();
	}

	@Override
	public void setSubtitleLanguage(final String language) {
		if (exoPlayer == null) throw new InitializationException(ExoPlayer.class);
		final TrackSelectionParameters params = exoPlayer.getTrackSelectionParameters();
		final TrackSelectionParameters.Builder builder = params.buildUpon();

		// Extract actual language code from display name
		final String actualLanguage = language.replace(" (Auto-generated)", "");

		// Get language stem
		final String languageStem;
		if (!actualLanguage.contains("(")) languageStem = actualLanguage;
		else if (actualLanguage.startsWith("(")) {
			final String[] parts = actualLanguage.split("\\)");
			languageStem = parts[parts.length - 1].trim();
		} else languageStem = actualLanguage.split("\\(")[0].trim();

		builder.setPreferredTextLanguages(actualLanguage, languageStem).setPreferredTextRoleFlags(C.ROLE_FLAG_SUBTITLE);
		builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
		exoPlayer.setTrackSelectionParameters(builder.build());
	}

	@Override
	public void setSubtitleLanguage(final int index) {
		if (exoPlayer == null) throw new InitializationException(ExoPlayer.class);
		if (index < 0 || index >= subtitleStreams.size()) return;
		final SubtitlesStream stream = subtitleStreams.get(index);
		final TrackSelectionParameters params = exoPlayer.getTrackSelectionParameters();
		final TrackSelectionParameters.Builder builder = params.buildUpon();
		builder.setPreferredTextLanguages(stream.getLanguageTag()).setPreferredTextRoleFlags(C.ROLE_FLAG_SUBTITLE);
		builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
		exoPlayer.setTrackSelectionParameters(builder.build());
	}

	@Override
	public void release() {
		playerView.setPlayer(null);
		if (exoPlayer != null) {
			exoPlayer.release();
			exoPlayer = null;
		}
	}

	@Override
	public void skipToNext() {
		if (navCallback != null) navCallback.onNext();
		else if (exoPlayer != null && exoPlayer.hasNextMediaItem()) exoPlayer.seekToNext();
	}

	@Override
	public Optional<Format> getVideoFormat() {
		if (exoPlayer == null) return Optional.empty();
		for (final Tracks.Group group : exoPlayer.getCurrentTracks().getGroups()) {
			if (group.getType() == C.TRACK_TYPE_VIDEO && group.isSelected()) {
				for (int i = 0; i < group.length; i++) {
					if (group.isTrackSelected(i)) return Optional.of(group.getTrackFormat(i));
				}
			}
		}
		return Optional.empty();
	}

	@Override
	public Optional<Format> getAudioFormat() {
		if (exoPlayer == null) return Optional.empty();
		for (final Tracks.Group group : exoPlayer.getCurrentTracks().getGroups()) {
			if (group.getType() == C.TRACK_TYPE_AUDIO && group.isSelected()) {
				for (int i = 0; i < group.length; i++) {
					if (group.isTrackSelected(i)) return Optional.of(group.getTrackFormat(i));
				}
			}
		}
		return Optional.empty();
	}

	@Override
	public DecoderCounters getVideoDecoderCounters() {
		return exoPlayer != null ? exoPlayer.getVideoDecoderCounters() : null;
	}

	@Override
	public void skipToPrevious() {
		if (navCallback != null) navCallback.onPrev();
		else if (exoPlayer != null && exoPlayer.hasPreviousMediaItem()) exoPlayer.seekToPrevious();
	}

	/**
	 * Stops the player and resets its state.
	 */
	@Override
	public void stop() {
		if (exoPlayer != null) exoPlayer.stop();
	}

	/**
	 * Clears all media items from the player's playlist.
	 */
	@Override
	public void clearMediaItems() {
		if (exoPlayer != null) exoPlayer.clearMediaItems();
	}
}
