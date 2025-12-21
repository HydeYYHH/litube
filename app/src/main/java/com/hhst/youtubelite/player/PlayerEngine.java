package com.hhst.youtubelite.player;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
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
import com.hhst.youtubelite.player.interfaces.IEngineInternal;

import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
	private static final long CACHE_SIZE_BYTES = 512 * 1024 * 1024;
	private static SimpleCache simpleCache;
	private final PlayerView playerView;
	private final Context appContext;
	private final DefaultTrackSelector trackSelector;
	@Getter
	private final List<String> subtitles = new ArrayList<>();
	private final List<SubtitlesStream> subtitleStreams = new ArrayList<>();
	private ExoPlayer exoPlayer;
	private YoutubeHttpDataSource.Factory dataSourceFactory;
	@Setter
	private NavigationCallback navCallback;
	@Setter
	private Consumer<androidx.media3.common.PlaybackException> errorListener;
	private SubtitleView subtitleView;

	public PlayerEngine() {
		PlayerContext context = PlayerContext.getInstance();
		this.appContext = context.getActivity().getApplicationContext();
		this.playerView = context.getPlayerView();

		LeastRecentlyUsedCacheEvictor evictor = new LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES);
		simpleCache = new SimpleCache(new File(appContext.getCacheDir(), "player"), evictor, new StandaloneDatabaseProvider(appContext));

		ExoTrackSelection.Factory adaptiveTrackSelectionFactory = new AdaptiveTrackSelection.Factory(1000, AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS, AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS, AdaptiveTrackSelection.DEFAULT_BANDWIDTH_FRACTION);
		trackSelector = new DefaultTrackSelector(appContext, adaptiveTrackSelectionFactory);

		DefaultTrackSelector.Parameters params = trackSelector.buildUponParameters().setTunnelingEnabled(true).build();
		trackSelector.setParameters(params);
		initializeDataSourceFactory();
		initializePlayer();
	}

	private void initializeDataSourceFactory() {
		dataSourceFactory = new YoutubeHttpDataSource.Factory().setConnectTimeoutMs(CONNECT_TIMEOUT_MS).setReadTimeoutMs(READ_TIMEOUT_MS);
	}

	private void initializePlayer() {
		DefaultLoadControl loadControl = new DefaultLoadControl.Builder().setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, BUFFER_FOR_PLAYBACK_MS, BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS).setBackBuffer(BACK_BUFFER_DURATION_MS, true).setPrioritizeTimeOverSizeThresholds(true).build();

		DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(appContext).setEnableDecoderFallback(true).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
		AudioAttributes audioAttributes = new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build();

		// Disable default subtitle view
		SubtitleView defaultSubtitleView = playerView.getSubtitleView();
		if (defaultSubtitleView != null) defaultSubtitleView.setVisibility(View.GONE);

		// Initialize custom subtitle view
		subtitleView = playerView.findViewById(R.id.custom_subtitle_view);
		subtitleView.setUserDefaultStyle();
		subtitleView.setUserDefaultTextSize();

		exoPlayer = new ExoPlayer.Builder(appContext, renderersFactory).setLoadControl(loadControl).setTrackSelector(trackSelector).setAudioAttributes(audioAttributes, true).setHandleAudioBecomingNoisy(true).setUsePlatformDiagnostics(false).setMediaSourceFactory(new DefaultMediaSourceFactory(appContext).setLiveMaxSpeed(1.05f)).build();
		exoPlayer.addListener(new Player.Listener() {
			@Override
			public void onPlayerError(@NonNull PlaybackException error) {
				if (errorListener != null) errorListener.accept(error);
			}

			@Override
			public void onCues(@NonNull CueGroup cueGroup) {
				// Display subtitles on the bottom of the player
				List<Cue> newCues = new ArrayList<>();
				for (Cue cue : cueGroup.cues) {
					Cue.Builder builder = cue.buildUpon().setTextSize(Cue.DIMEN_UNSET, Cue.TEXT_SIZE_TYPE_FRACTIONAL).setSize(Cue.DIMEN_UNSET).setLine(0.9f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_END).setPosition(0.5f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE);
					newCues.add(builder.build());
				}
				if (subtitleView != null) subtitleView.setCues(newCues);
			}
		});
		exoPlayer.addAnalyticsListener(new AnalyticsListener() {
			@Override
			public void onDroppedVideoFrames(@NonNull EventTime eventTime, int droppedFrames, long elapsedMs) {
				Log.w("ExoPlayer", "Dropped frames: " + droppedFrames);
			}
		});
		configurePlayerView();
	}

	private void configurePlayerView() {
		Player forwardingPlayer = new ForwardingPlayer(exoPlayer) {
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
			public boolean isCommandAvailable(int command) {
				return command == COMMAND_SEEK_TO_NEXT || command == COMMAND_SEEK_TO_PREVIOUS || super.isCommandAvailable(command);
			}
		};
		playerView.setPlayer(forwardingPlayer);
		playerView.setShowNextButton(true);
		playerView.setShowPreviousButton(true);
		playerView.setKeepScreenOn(true);
	}

	@Override
	public void play(Stream videoStream, Stream audioStream, List<SubtitlesStream> subtitlesStreams, String dashUrl, long durationMs, long resumePosMs, float speed) {
		MediaSource videoAudioSource;
		// Build media source, DASH first
		if (dashUrl != null && !dashUrl.isEmpty()) {
			CacheDataSource.Factory cacheDataSourceFactory = new CacheDataSource.Factory().setCache(simpleCache).setUpstreamDataSourceFactory(dataSourceFactory).setCacheReadDataSourceFactory(new FileDataSource.Factory()).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR | CacheDataSource.FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS);
			videoAudioSource = new DashMediaSource.Factory(cacheDataSourceFactory).createMediaSource(MediaItem.fromUri(dashUrl));
		} else {
			MediaSource videoSource = createMediaSource(videoStream, durationMs);
			MediaSource audioSource = createMediaSource(audioStream, durationMs);
			videoAudioSource = new MergingMediaSource(videoSource, audioSource);
		}

		if (subtitlesStreams != null && !subtitlesStreams.isEmpty()) {
			// Build subtitle sources
			List<MediaSource> subtitleSources = new ArrayList<>();
			subtitles.clear();
			subtitleStreams.clear();
			DefaultSubtitleParserFactory subtitleParserFactory = new DefaultSubtitleParserFactory();
			for (SubtitlesStream subtitle : subtitlesStreams) {
				if (subtitle.getFormat() == null) continue;
				String mimeType = subtitle.getFormat().mimeType;
				Format subtitleFormat = new Format.Builder().setSampleMimeType(mimeType).setLanguage(subtitle.getLanguageTag()).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).setRoleFlags(C.ROLE_FLAG_SUBTITLE).setLabel(subtitle.getDisplayLanguageName()).build();
				ExtractorsFactory subtitleExtractorsFactory = () -> new Extractor[]{new SubtitleExtractor(subtitleParserFactory.create(subtitleFormat), subtitleFormat)};
				MediaSource subtitleSource = new ProgressiveMediaSource.Factory(dataSourceFactory, subtitleExtractorsFactory).createMediaSource(MediaItem.fromUri(Uri.parse(subtitle.getContent())));
				subtitleSources.add(subtitleSource);
				String displayName = subtitle.getDisplayLanguageName();
				if (subtitle.isAutoGenerated()) displayName += " (Auto-generated)";
				if (!subtitles.contains(displayName)) {
					subtitles.add(displayName);
					subtitleStreams.add(subtitle);
				}
			}

			// Merge video and subtitle sources
			MediaSource[] sources = new MediaSource[subtitleSources.size() + 1];
			sources[0] = videoAudioSource;
			for (int i = 0; i < subtitleSources.size(); i++)
				sources[i + 1] = subtitleSources.get(i);
			videoAudioSource = new MergingMediaSource(true, sources);
		}

		exoPlayer.setMediaSource(videoAudioSource);
		exoPlayer.setPlaybackParameters(new PlaybackParameters(speed));

		updateSubtitleOutput();

		if (resumePosMs > 0) exoPlayer.seekTo(resumePosMs);
		exoPlayer.prepare();
		exoPlayer.setPlayWhenReady(true);
	}

	private MediaSource createMediaSource(Stream stream, long durationMs) {
		CacheDataSource.Factory cacheDataSourceFactory = new CacheDataSource.Factory().setCache(simpleCache).setUpstreamDataSourceFactory(dataSourceFactory).setCacheReadDataSourceFactory(new FileDataSource.Factory()).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR | CacheDataSource.FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS);
		try {
			String manifestString = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(stream.getContent(), Objects.requireNonNull(stream.getItagItem()), durationMs / 1000);
			DashManifest manifest = new DashManifestParser().parse(Uri.parse(stream.getContent()), new ByteArrayInputStream(manifestString.getBytes(StandardCharsets.UTF_8)));
			return new DashMediaSource.Factory(cacheDataSourceFactory).createMediaSource(manifest);
		} catch (Exception e) {
			MediaItem.Builder mediaItemBuilder = MediaItem.fromUri(stream.getContent()).buildUpon();
			if (stream.getFormat() != null) mediaItemBuilder.setMimeType(stream.getFormat().mimeType);
			return new ProgressiveMediaSource.Factory(cacheDataSourceFactory).createMediaSource(mediaItemBuilder.build());
		}
	}

	@Override
	public boolean isPlaying() {
		return exoPlayer.isPlaying();
	}

	@Override
	public void play() {
		exoPlayer.play();
	}

	@Override
	public void pause() {
		exoPlayer.pause();
	}

	@Override
	public void seekTo(long pos) {
		exoPlayer.seekTo(pos);
	}

	@Override
	public void addListener(Player.Listener listener) {
		exoPlayer.addListener(listener);
	}

	@Override
	public long position() {
		return exoPlayer.getCurrentPosition();
	}

	@Override
	public void setSpeed(float speed) {
		exoPlayer.setPlaybackParameters(new PlaybackParameters(speed));
	}

	@Override
	public float speed() {
		if (exoPlayer == null) return 1f;
		return exoPlayer.getPlaybackParameters().speed;
	}

	@Override
	public void setVideoQuality(int height) {
		trackSelector.setParameters(trackSelector.buildUponParameters().setMaxVideoSize(Integer.MAX_VALUE, height).setMinVideoSize(0, height).build());
	}

	private void updateSubtitleOutput() {
		TrackSelectionParameters params = exoPlayer.getTrackSelectionParameters();
		TrackSelectionParameters.Builder builder = params.buildUpon();
		boolean hasPreferredSubtitle = !params.preferredTextLanguages.isEmpty();
		builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !hasPreferredSubtitle);
		exoPlayer.setTrackSelectionParameters(builder.build());
	}

	@Override
	public boolean areSubtitlesEnabled() {
		if (exoPlayer == null) return false;
		TrackSelectionParameters params = exoPlayer.getTrackSelectionParameters();
		return !params.preferredTextLanguages.isEmpty();
	}

	@Override
	public VideoSize getVideoSize() {
		if (exoPlayer == null) return VideoSize.UNKNOWN;
		return exoPlayer.getVideoSize();
	}

	@Override
	public void setRepeatMode(int mode) {
		if (exoPlayer != null) exoPlayer.setRepeatMode(mode);
	}

	@Override
	public int getPlaybackState() {
		if (exoPlayer == null) return Player.STATE_IDLE;
		return exoPlayer.getPlaybackState();
	}

	@Override
	public void setSubtitlesEnabled(boolean enabled) {
		if (exoPlayer == null) return;
		TrackSelectionParameters params = exoPlayer.getTrackSelectionParameters();
		TrackSelectionParameters.Builder builder = params.buildUpon();

		if (enabled) {
			if (params.preferredTextLanguages.isEmpty()) builder.setPreferredTextLanguages("en", "und");
		} else builder.setPreferredTextLanguages();

		exoPlayer.setTrackSelectionParameters(builder.build());
		updateSubtitleOutput();
	}

	@Override
	public void setSubtitleLanguage(String language) {
		TrackSelectionParameters params = exoPlayer.getTrackSelectionParameters();
		TrackSelectionParameters.Builder builder = params.buildUpon();

		// Extract actual language code from display name
		String actualLanguage = language.replace(" (Auto-generated)", "");

		// Get language stem
		String languageStem;
		if (!actualLanguage.contains("(")) languageStem = actualLanguage;
		else if (actualLanguage.startsWith("(")) {
			String[] parts = actualLanguage.split("\\)");
			languageStem = parts[parts.length - 1].trim();
		} else languageStem = actualLanguage.split("\\(")[0].trim();

		builder.setPreferredTextLanguages(actualLanguage, languageStem).setPreferredTextRoleFlags(C.ROLE_FLAG_SUBTITLE);
		builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
		exoPlayer.setTrackSelectionParameters(builder.build());
	}

	@Override
	public void setSubtitleLanguage(int index) {
		if (index < 0 || index >= subtitleStreams.size()) return;
		SubtitlesStream stream = subtitleStreams.get(index);
		TrackSelectionParameters params = exoPlayer.getTrackSelectionParameters();
		TrackSelectionParameters.Builder builder = params.buildUpon();
		builder.setPreferredTextLanguages(stream.getLanguageTag()).setPreferredTextRoleFlags(C.ROLE_FLAG_SUBTITLE);
		builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
		exoPlayer.setTrackSelectionParameters(builder.build());
	}

	@Override
	public void release() {
		if (playerView != null) {
			playerView.setPlayer(null);
		}
		if (exoPlayer != null) {
			exoPlayer.release();
			exoPlayer = null;
		}
	}

	@Override
	public void skipToNext() {
		if (navCallback != null) navCallback.onNext();
		else if (exoPlayer.hasNextMediaItem()) exoPlayer.seekToNext();
	}

	@Override
	public Format getVideoFormat() {
		if (exoPlayer == null) return null;
		Tracks tracks = exoPlayer.getCurrentTracks();
		for (Tracks.Group group : tracks.getGroups()) {
			if (group.getType() == C.TRACK_TYPE_VIDEO && group.isSelected()) {
				for (int i = 0; i < group.length; i++)
					if (group.isTrackSelected(i)) return group.getTrackFormat(i);
			}
		}
		return null;
	}

	@Override
	public Format getAudioFormat() {
		if (exoPlayer == null) return null;
		Tracks tracks = exoPlayer.getCurrentTracks();
		for (Tracks.Group group : tracks.getGroups()) {
			if (group.getType() == C.TRACK_TYPE_AUDIO && group.isSelected()) {
				for (int i = 0; i < group.length; i++) {
					if (group.isTrackSelected(i)) return group.getTrackFormat(i);
				}
			}
		}
		return null;
	}

	@Override
	public DecoderCounters getVideoDecoderCounters() {
		return exoPlayer != null ? exoPlayer.getVideoDecoderCounters() : null;
	}

	@Override
	public void skipToPrevious() {
		if (navCallback != null) navCallback.onPrev();
		else if (exoPlayer.hasPreviousMediaItem()) exoPlayer.seekToPrevious();
	}

	/**
	 * Stops the player and resets its state.
	 */
	@Override
	public void stop() {
		exoPlayer.stop();
	}

	/**
	 * Clears all media items from the player's playlist.
	 */
	@Override
	public void clearMediaItems() {
		exoPlayer.clearMediaItems();
	}
}
