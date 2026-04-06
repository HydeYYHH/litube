package com.hhst.youtubelite.player.engine;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleExtractor;

import com.hhst.youtubelite.Constant;
import com.hhst.youtubelite.browser.TabManager;
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.extractor.VideoDetails;
import com.hhst.youtubelite.player.LitePlayerView;
import com.hhst.youtubelite.player.common.PlayerLoopMode;
import com.hhst.youtubelite.player.common.PlayerPreferences;
import com.hhst.youtubelite.player.common.PlayerUtils;
import com.hhst.youtubelite.player.queue.QueueItem;
import com.hhst.youtubelite.player.queue.QueueNav;
import com.hhst.youtubelite.player.queue.QueueRepository;
import com.hhst.youtubelite.player.engine.datasource.YoutubeHttpDataSource;
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager;

import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamSegment;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.android.scopes.ActivityScoped;
import lombok.Getter;

@UnstableApi
@ActivityScoped
public class Engine {
	private static final int UPDATE_INTERVAL_MS = 1000;
	private static final int SAFE_ZONE_MS = 5000;

	@NonNull
	private final ExoPlayer player;
	@Nullable
	private final SimpleCache simpleCache;
	@NonNull
	private final PlayerPreferences prefs;
	@NonNull
	private final TabManager tabManager;
	@NonNull
	private final SponsorBlockManager sponsor;
	@NonNull
	private final QueueRepository queueRepository;
	@Getter
	@NonNull
	private PlayerLoopMode loopMode = PlayerLoopMode.PLAYLIST_NEXT;
	private final Handler handler = new Handler(Looper.getMainLooper());
	@Nullable
	private String vid;
	private final Runnable onTimeUpdate = new Runnable() {
		@Override
		public void run() {
			if (!player.isPlaying()) return;
			final long pos = player.getCurrentPosition();
			final long duration = player.getDuration();
			if (vid != null && duration > 0 && prefs.getExtensionManager().isEnabled(Constant.REMEMBER_LAST_POSITION)) {
				if (pos > SAFE_ZONE_MS && pos < duration - SAFE_ZONE_MS) {
					prefs.persistProgress(vid, pos, duration, TimeUnit.MILLISECONDS);
				}
			}
			final List<long[]> segments = sponsor.getSegments();
			for (final long[] segment : segments) {
				if (pos >= segment[0] && pos < segment[1]) {
					player.seekTo(segment[1]);
					break;
				}
			}
			handler.postDelayed(this, UPDATE_INTERVAL_MS);
		}
	};
	@Nullable
	private VideoDetails videoDetails;
	@Nullable
	private StreamDetails streamDetails;
	@Nullable
	private AudioStream currentAudioStream;

	@Inject
	public Engine(@NonNull @ApplicationContext final Context context,
				  @NonNull final LitePlayerView playerView,
				  @Nullable final SimpleCache simpleCache,
				  @NonNull final PlayerPreferences prefs,
				  @NonNull final TabManager tabManager,
				  @NonNull final SponsorBlockManager sponsor,
				  @NonNull final QueueRepository queueRepository) {
		this.simpleCache = simpleCache;
		this.prefs = prefs;
		this.tabManager = tabManager;
		this.sponsor = sponsor;
		this.queueRepository = queueRepository;
		final DefaultTrackSelector trackSelector = new DefaultTrackSelector(context, new AdaptiveTrackSelection.Factory());
		trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(true).build());
		ExoPlayer.Builder playerBuilder = new ExoPlayer.Builder(context)
				.setTrackSelector(trackSelector)
				.setLoadControl(new DefaultLoadControl())
				.setAudioAttributes(new AudioAttributes.Builder()
						.setUsage(C.USAGE_MEDIA)
						.setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
						.build(), true)
				.setHandleAudioBecomingNoisy(true)
				.setUsePlatformDiagnostics(false);
		
		DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(context)
				.setLiveMaxSpeed(1.25f);
		
		playerBuilder.setMediaSourceFactory(mediaSourceFactory);
		this.player = playerBuilder.build();
		
		this.player.addListener(new Player.Listener() {

			@Override
			public void onPlaybackStateChanged(final int state) {
				if (state == Player.STATE_ENDED) handlePlaybackEnded();
			}

			@Override
			public void onIsPlayingChanged(boolean isPlaying) {
				handler.removeCallbacks(onTimeUpdate);
				if (isPlaying) handler.post(onTimeUpdate);
			}

			@Override
			public void onCues(@NonNull final CueGroup cueGroup) {
				playerView.cueing(cueGroup);
			}
		});
		playerView.setPlayer(this.player);
	}

	private void handlePlaybackEnded() {
		if (isShortVideo()) {
			player.seekTo(0);
			player.play();
			return;
		}
		if (loopMode.skipsToNextOnEnded()) {
			skipToNext();
			return;
		}
		if (loopMode.selectsRandomPlaylistItemOnEnded()) {
			playRandomPlaylistItem();
		}
	}

	private boolean isShortVideo() {
		final long duration = player.getDuration();
		return duration > 0 && duration < SAFE_ZONE_MS;
	}

	public boolean isPlaying() {
		return this.player.isPlaying();
	}

	public void play(@NonNull final VideoDetails vi, @NonNull final StreamDetails si) {
		this.vid = vi.getId();
		this.videoDetails = vi;
		this.streamDetails = si;

		final var videoStream = PlayerUtils.selectVideoStream(si.getVideoStreams(), prefs.getQuality());
		final var audioStream = PlayerUtils.selectAudioStream(si.getAudioStreams(), null);
		this.currentAudioStream = audioStream;

		long duration = vi.getDuration() * 1000;
		final MediaItem.Builder builder = new MediaItem.Builder();
		if (si.getDashUrl() != null && !si.getDashUrl().isEmpty()) builder.setUri(si.getDashUrl());
		else if (videoStream != null) builder.setUri(videoStream.getContent());
		else if (audioStream != null) builder.setUri(audioStream.getContent());
		final List<MediaItem.SubtitleConfiguration> configs = new ArrayList<>();
		for (final SubtitlesStream stream : si.getSubtitles()) {
			if (stream.getFormat() != null) {
				String label = stream.getDisplayLanguageName();
				if (stream.isAutoGenerated()) label += " (Auto-generated)";
				configs.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(stream.getContent()))
						.setMimeType(stream.getFormat().mimeType)
						.setLanguage(stream.getLanguageTag())
						.setLabel(label)
						.build());
			}
		}
		builder.setSubtitleConfigurations(configs);

		final MediaSource source = createFinalMediaSource(videoStream, audioStream, si.getDashUrl(), si.getStreamType(), duration, builder.build(), si.getSubtitles());
		this.player.setMediaSource(source);
		this.player.setPlaybackParameters(new PlaybackParameters(this.prefs.getSpeed()));

		if (prefs.getExtensionManager().isEnabled(Constant.REMEMBER_LAST_POSITION)) {
			final long resumePos = prefs.getResumePosition(vid);
			if (resumePos > SAFE_ZONE_MS && resumePos < duration - SAFE_ZONE_MS) {
				this.player.seekTo(resumePos);
			}
		}

		this.player.prepare();

		if (si.getSubtitles() != null && si.getSubtitles().size() == 1) {
			setSubtitlesEnabled(true);
			setSubtitleLanguage(si.getSubtitles().get(0).getDisplayLanguageName());
		} else {
			final boolean enabled = this.prefs.isSubtitleEnabled();
			setSubtitlesEnabled(enabled);
			final String saved = this.prefs.getSubtitleLanguage();
			if (enabled && saved != null && !saved.isEmpty() && !si.getSubtitles().isEmpty()) {
				setSubtitleLanguage(saved);
			}
		}

		this.player.setPlayWhenReady(true);
	}

	private MediaSource createFinalMediaSource(@Nullable final Stream video, @Nullable final Stream audio, @Nullable final String dashUrl, @NonNull final StreamType streamType, final long duration, @NonNull final MediaItem item, @Nullable final List<SubtitlesStream> subs) {
		final boolean isLive = streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM;
		final YoutubeHttpDataSource.Factory factory = new YoutubeHttpDataSource.Factory(Constant.USER_AGENT)
				.setConnectTimeoutMs(30_000)
				.setReadTimeoutMs(30_000)
				.setRangeParameterEnabled(!isLive)
				.setRnParameterEnabled(!isLive);

		final CacheDataSource.Factory cacheFactory = new CacheDataSource.Factory()
				.setCache(simpleCache)
				.setUpstreamDataSourceFactory(factory)
				.setCacheReadDataSourceFactory(new FileDataSource.Factory())
				.setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR | CacheDataSource.FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS);

		MediaSource baseSource;

		if (dashUrl != null && !dashUrl.isEmpty()) {
			baseSource = new DashMediaSource.Factory(cacheFactory).createMediaSource(item);
		} else {
			final MediaSource vSource = createMediaSource(video, duration, cacheFactory);
			final MediaSource aSource = createMediaSource(audio, duration, cacheFactory);

			if (vSource != null && aSource != null) {
				baseSource = new MergingMediaSource(vSource, aSource);
			} else if (vSource != null) {
				baseSource = vSource;
			} else if (aSource != null) {
				baseSource = aSource;
			} else {
				baseSource = new ProgressiveMediaSource.Factory(cacheFactory).createMediaSource(item.localConfiguration != null ? item : MediaItem.fromUri(Uri.EMPTY));
			}
		}

		if (subs == null || subs.isEmpty()) return baseSource;

		final List<MediaSource> subSources = new ArrayList<>();
		final DefaultSubtitleParserFactory parserFactory = new DefaultSubtitleParserFactory();
		for (final SubtitlesStream sub : subs) {
			if (sub.getFormat() == null) continue;
			subSources.add(createSubtitleSource(sub, parserFactory, factory));
		}

		final MediaSource[] sources = new MediaSource[subSources.size() + 1];
		sources[0] = baseSource;
		for (int i = 0; i < subSources.size(); i++) sources[i + 1] = subSources.get(i);
		return new MergingMediaSource(true, sources);
	}

	private MediaSource createSubtitleSource(@NonNull SubtitlesStream sub, @NonNull DefaultSubtitleParserFactory parserFactory, @NonNull YoutubeHttpDataSource.Factory factory) {
		String label = sub.getDisplayLanguageName();
		if (sub.isAutoGenerated()) label += " (Auto-generated)";
		final Format format = new Format.Builder()
				.setSampleMimeType(sub.getFormat() != null ? sub.getFormat().mimeType : null)
				.setLanguage(sub.getLanguageTag())
				.setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
				.setRoleFlags(C.ROLE_FLAG_SUBTITLE)
				.setLabel(label)
				.build();
		final ExtractorsFactory extractorFactory = () -> new Extractor[]{new SubtitleExtractor(parserFactory.create(format), format)};
		return new ProgressiveMediaSource.Factory(factory, extractorFactory)
				.createMediaSource(MediaItem.fromUri(Uri.parse(sub.getContent())));
	}

	@Nullable
	private MediaSource createMediaSource(@Nullable final Stream stream, final long duration, @NonNull final CacheDataSource.Factory cacheFactory) {
		if (stream == null) return null;

		try {
			if (stream.getItagItem() != null) {
				final String manifest = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(stream.getContent(), stream.getItagItem(), duration / 1_000);
				final DashManifest parsed = new DashManifestParser().parse(Uri.parse(stream.getContent()), new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)));
				return new DashMediaSource.Factory(cacheFactory).createMediaSource(parsed);
			}
		} catch (final Exception e) {
			Log.w("Engine", "Failed to create DashMediaSource", e);
		}

		final MediaItem.Builder builder = MediaItem.fromUri(stream.getContent()).buildUpon();
		if (stream.getFormat() != null) builder.setMimeType(stream.getFormat().mimeType);
		return new ProgressiveMediaSource.Factory(cacheFactory).createMediaSource(builder.build());
	}

	public void play() {
		this.player.play();
	}

	public void pause() {
		this.player.pause();
	}

	public void seekTo(final long pos) {
		this.player.seekTo(Math.min(this.player.getDuration(), pos));
	}

	public void seekBy(final long offset) {
		this.player.seekTo(Math.min(this.player.getDuration(), this.player.getCurrentPosition() + offset));
	}

	public float getPlaybackRate() {
		return this.player.getPlaybackParameters().speed;
	}

	public void setPlaybackRate(final float rate) {
		this.player.setPlaybackParameters(new PlaybackParameters(rate));
	}

	public void addListener(@NonNull final Player.Listener listener) {
		this.player.addListener(listener);
	}

	public void removeListener(@NonNull final Player.Listener listener) {
		this.player.removeListener(listener);
	}

	public VideoSize getVideoSize() {
		return this.player.getVideoSize();
	}

	public void setSubtitlesEnabled(final boolean enabled) {
		this.prefs.setSubtitleEnabled(enabled);
		this.player.setTrackSelectionParameters(this.player.getTrackSelectionParameters().buildUpon()
				.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
				.build());
	}

	public void setSubtitleLanguage(@Nullable final String language) {
		if (language == null) return;
		this.prefs.setSubtitleEnabled(true);
		this.prefs.setSubtitleLanguage(language);
		final Tracks tracks = this.player.getCurrentTracks();
		for (final Tracks.Group group : tracks.getGroups()) {
			if (group.getType() == C.TRACK_TYPE_TEXT) {
				for (int i = 0; i < group.length; i++) {
					final Format format = group.getTrackFormat(i);
					if (language.equals(format.label) || language.equals(format.language)) {
						this.player.setTrackSelectionParameters(this.player.getTrackSelectionParameters().buildUpon()
								.clearOverrides()
								.setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), i))
								.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
								.build());
						return;
					}
				}
			}
		}
	}

	public long position() {
		return this.player.getCurrentPosition();
	}

	public void skipToNext() {
		final QueueNav availability = getQueueNavigationAvailability();
		final boolean localQueueEnabled = queueRepository.isEnabled();
		final boolean hasPlaylist = tabManager.watchHasPlaylist();
		if (shouldUseQueueForNext(availability)) {
			navigateWithinQueue(1);
			return;
		}
		if (shouldUsePlaylistForNext(localQueueEnabled, hasPlaylist)) {
			skipByPlaylistOffset(1, null);
		}
	}

	public void skipToPrevious() {
		final QueueNav availability = getQueueNavigationAvailability();
		final boolean localQueueEnabled = queueRepository.isEnabled();
		final boolean inQueue = queueRepository.containsVideo(vid);
		final boolean hasPlaylist = tabManager.watchHasPlaylist();
		final boolean canGoBack = tabManager.canGoBackInWatch();
		if (shouldUseQueueForPrevious(availability)) {
			navigateWithinQueue(-1);
			return;
		}
		if (shouldUsePlaylistForPrevious(localQueueEnabled, hasPlaylist)) {
			tabManager.evaluateJavascriptForWatch(
					buildPlaylistNavigationScript(-1),
					value -> {
						if (didNavigate(value)) return;
						if (shouldFallbackToBackAfterPlaylistMiss(value)) {
							navigateBack();
						}
					});
			return;
		}
		if (shouldUseBackForPrevious(localQueueEnabled, inQueue, hasPlaylist, canGoBack)) {
			navigateBack();
		}
	}

	public void playRandomPlaylistItem() {
		final QueueNav availability = getQueueNavigationAvailability();
		final boolean localQueueEnabled = queueRepository.isEnabled();
		final boolean hasPlaylist = tabManager.watchHasPlaylist();
		if (shouldUseQueueForShuffle(availability)) {
			navigateRandomQueueItem();
			return;
		}
		if (shouldUsePlaylistForShuffle(localQueueEnabled, hasPlaylist)) {
			this.tabManager.evaluateJavascriptForWatch(buildRandomPlaylistNavigationScript(), null);
		}
	}

	private void skipByPlaylistOffset(final int playlistOffset, @Nullable final Runnable miss) {
		this.tabManager.evaluateJavascriptForWatch(
				buildPlaylistNavigationScript(playlistOffset),
				miss == null ? null : value -> {
					if (!didNavigate(value)) miss.run();
				});
	}

	private void navigateWithinQueue(final int offset) {
		if (!queueRepository.isEnabled()) return;
		final QueueItem item = queueRepository.findRelative(vid, offset);
		if (item == null || item.getUrl() == null) return;
		tabManager.playInWatch(item.getUrl());
	}

	private void navigateRandomQueueItem() {
		if (!queueRepository.isEnabled()) return;
		final QueueItem item = queueRepository.findRandom(vid);
		if (item == null || item.getUrl() == null) return;
		tabManager.playInWatch(item.getUrl());
	}

	private void navigateBack() {
		tabManager.goBackInWatch();
	}

	@NonNull
	public QueueNav getQueueNavigationAvailability() {
		final boolean queueEnabled = queueRepository.isEnabled();
		final boolean inQueue = queueRepository.containsVideo(vid);
		final boolean hasPlaylist = tabManager.watchHasPlaylist();
		final boolean canGoBack = tabManager.canGoBackInWatch();
		
		final List<QueueItem> items = queueRepository.getItems();
		int index = -1;
		if (vid != null) {
			for (int i = 0; i < items.size(); i++) {
				if (vid.equals(items.get(i).getVideoId())) {
					index = i;
					break;
				}
			}
		}

		final QueueNav availability = resolveQueueNavigationAvailability(
				queueEnabled,
				!items.isEmpty(),
				inQueue,
				index == 0,
				index == items.size() - 1);

		return availability
				.withNext(queueEnabled ? availability.next() : shouldUsePlaylistForNext(false, hasPlaylist))
				.withPrev(shouldUseBackForPrevious(queueEnabled, inQueue, hasPlaylist, canGoBack)
						|| shouldUsePlaylistForPrevious(queueEnabled, hasPlaylist));
	}

	@NonNull
	public static QueueNav resolveQueueNavigationAvailability(final boolean enabled,
	                                                         final boolean hasItems,
	                                                         final boolean inQueue,
	                                                         final boolean atHead,
	                                                         final boolean atTail) {
		return QueueNav.from(enabled, hasItems, inQueue, atHead, atTail);
	}

	static boolean shouldUseQueueForNext(@NonNull final QueueNav availability) {
		return availability.usesQueueForNext();
	}

	static boolean shouldUseQueueForShuffle(@NonNull final QueueNav availability) {
		return availability.usesQueueForShuffle();
	}

	static boolean shouldUseQueueForPrevious(@NonNull final QueueNav availability) {
		return availability.usesQueueForPrevious();
	}

	static boolean shouldUsePlaylistForNext(final boolean localQueueEnabled,
	                                        final boolean hasPlaylistContext) {
		return !localQueueEnabled && hasPlaylistContext;
	}

	static boolean shouldUsePlaylistForShuffle(final boolean localQueueEnabled,
	                                           final boolean hasPlaylistContext) {
		return !localQueueEnabled && hasPlaylistContext;
	}

	static boolean shouldUsePlaylistForPrevious(final boolean localQueueEnabled,
	                                            final boolean hasPlaylistContext) {
		return !localQueueEnabled && hasPlaylistContext;
	}

	static boolean shouldUseBackForPrevious(final boolean localQueueEnabled,
	                                        final boolean inQueue,
	                                        final boolean hasPlaylistContext,
	                                        final boolean canGoBack) {
		if (!canGoBack) return false;
		if (localQueueEnabled) {
			return !inQueue && !hasPlaylistContext;
		}
		return !hasPlaylistContext;
	}

	static boolean shouldFallbackToBackAfterPlaylistMiss(@Nullable final String value) {
		return "\"missing-playlist\"".equals(value)
				|| "\"missing-current-video-id\"".equals(value)
				|| "\"missing-current-video\"".equals(value);
	}

	static boolean didNavigate(@Nullable final String value) {
		return value != null && value.contains("videoId");
	}

	@NonNull
	private static String buildPlaylistNavigationScript(final int offset) {
		return String.format(Locale.US, "window.dispatchEvent(new CustomEvent(\u0027onSkipByOffset\u0027, { detail: { offset: %d } }));", offset);
	}

	@NonNull
	private static String buildRandomPlaylistNavigationScript() {
		return "window.dispatchEvent(new CustomEvent(\u0027onSkipRandom\u0027));";
	}

	public void setLoopMode(@NonNull final PlayerLoopMode loopMode) {
		this.loopMode = loopMode;
	}

	public void release() {
		this.player.release();
		this.handler.removeCallbacks(onTimeUpdate);
	}

	public int getPlaybackState() {
		return player.getPlaybackState();
	}

	@Nullable
	public StreamDetails getStreamDetails() {
		return streamDetails;
	}

	public String getQuality() {
		return prefs.getQuality();
	}

	public String getQualityLabel() {
		return getQuality();
	}

	public void onQualitySelected(String quality) {
		prefs.setQuality(quality);
	}

	public List<String> getAvailableResolutions() {
		List<String> resolutions = new ArrayList<>();
		if (streamDetails != null) {
			for (VideoStream vs : streamDetails.getVideoStreams()) {
				resolutions.add(vs.getResolution());
			}
		}
		return resolutions;
	}

	public List<String> getSubtitles() {
		List<String> labels = new ArrayList<>();
		if (streamDetails != null) {
			for (SubtitlesStream sub : streamDetails.getSubtitles()) {
				labels.add(sub.getDisplayLanguageName());
			}
		}
		return labels;
	}

	public boolean areSubtitlesEnabled() {
		return prefs.isSubtitleEnabled();
	}

	public List<StreamSegment> getSegments() {
		return videoDetails != null ? videoDetails.getSegments() : new ArrayList<>();
	}

	public String getThumbnail() {
		return videoDetails != null ? videoDetails.getThumbnail() : null;
	}

	@NonNull
	public List<AudioStream> getAvailableAudioTracks() {
		return streamDetails != null ? streamDetails.getAudioStreams() : Collections.emptyList();
	}

	@Nullable
	public AudioStream getAudioTrack() {
		return currentAudioStream;
	}

	public void setAudioTrack(@NonNull final AudioStream stream) {
		if (streamDetails == null || videoDetails == null) return;
		final AudioStream audio = currentAudioStream;
		if (audio != null && audio.getContent().equals(stream.getContent())) return;

		this.currentAudioStream = stream;
		final VideoStream vs = PlayerUtils.selectVideoStream(streamDetails.getVideoStreams(), prefs.getQuality());
		final long pos = player.getCurrentPosition();
		final boolean playWhenReady = player.getPlayWhenReady();
		final MediaItem currentItem = player.getCurrentMediaItem();

		final MediaSource source = createFinalMediaSource(vs, stream, streamDetails.getDashUrl(), streamDetails.getStreamType(), videoDetails.getDuration() * 1000, Objects.requireNonNull(currentItem), streamDetails.getSubtitles());
		player.setMediaSource(source);
		player.seekTo(pos);
		player.setPlayWhenReady(playWhenReady);
		player.prepare();
	}

	public int getSelectedAudioTrackIndex() {
		final Format format = getAudioFormat();
		if (format == null || streamDetails == null) return -1;
		for (int i = 0; i < streamDetails.getAudioStreams().size(); i++) {
			if (streamDetails.getAudioStreams().get(i).getCodec().equals(format.codecs)) return i;
		}
		return -1;
	}

	public Format getVideoFormat() {
		return player.getVideoFormat();
	}

	public Format getAudioFormat() {
		return player.getAudioFormat();
	}

	public DecoderCounters getVideoDecoderCounters() {
		return player.getVideoDecoderCounters();
	}

	public void clear() {
		player.stop();
		player.clearMediaItems();
		vid = null;
		videoDetails = null;
		streamDetails = null;
		currentAudioStream = null;
	}
}
