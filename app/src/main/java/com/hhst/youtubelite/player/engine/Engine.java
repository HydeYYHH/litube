package com.hhst.youtubelite.player.engine;

import android.content.Context;
import android.net.Uri;
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

import android.os.Handler;
import android.os.Looper;

import com.hhst.youtubelite.player.sponsor.SponsorBlockManager;
import com.hhst.youtubelite.Constant;
import com.hhst.youtubelite.player.LitePlayerView;
import com.hhst.youtubelite.player.common.PlayerPreferences;
import com.hhst.youtubelite.player.engine.datasource.YoutubeHttpDataSource;
import com.hhst.youtubelite.player.common.PlayerUtils;
import com.hhst.youtubelite.util.StringUtils;
import com.hhst.youtubelite.browser.TabManager;
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.extractor.VideoDetails;

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
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.android.scopes.ActivityScoped;

@UnstableApi
@ActivityScoped
public class Engine {
	private static final String JS_NEXT_VIDEO = "document.querySelector('#movie_player')?.nextVideo();";
	private static final String JS_PREV_VIDEO = "document.querySelector('#movie_player')?.previousVideo();";
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
	@Nullable
	private String vid;
	@Nullable
	private VideoDetails videoDetails;
	@Nullable
	private StreamDetails streamDetails;
	@Nullable
	private VideoStream videoStream;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable onTimeUpdate = new Runnable() {
		@Override
		public void run() {
			if (!player.isPlaying()) return;
			final long pos = player.getCurrentPosition();
			final long duration = player.getDuration();
			// Save progress
			if (vid != null && duration > 0 && prefs.getExtensionManager().isEnabled(Constant.REMEMBER_LAST_POSITION)) {
				if (pos > SAFE_ZONE_MS && pos < duration - SAFE_ZONE_MS) {
					prefs.persistProgress(vid, pos, duration, TimeUnit.MILLISECONDS);
				}
			}
			// Skip sponsors
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

	@Inject
	public Engine(@NonNull @ApplicationContext final Context context,
	              @NonNull final LitePlayerView playerView,
	              @Nullable final SimpleCache simpleCache,
	              @NonNull final PlayerPreferences prefs,
	              @NonNull final TabManager tabManager,
	              @NonNull final SponsorBlockManager sponsor) {
		this.simpleCache = simpleCache;
		this.prefs = prefs;
		this.tabManager = tabManager;
		this.sponsor = sponsor;
		final DefaultTrackSelector trackSelector = new DefaultTrackSelector(context, new AdaptiveTrackSelection.Factory());
		trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(true).build());
		this.player = new ExoPlayer.Builder(context)
						.setTrackSelector(trackSelector)
						.setLoadControl(new DefaultLoadControl())
						.setAudioAttributes(new AudioAttributes.Builder()
										.setUsage(C.USAGE_MEDIA)
										.setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
										.build(), true)
						.setHandleAudioBecomingNoisy(true)
						.setUsePlatformDiagnostics(false)
						.setMediaSourceFactory(
										new DefaultMediaSourceFactory(context)
														.setLiveMaxSpeed(1.25f)
						).build();
		this.player.addListener(new Player.Listener() {

			@Override
			public void onPlaybackStateChanged(final int state) {
				if (state == Player.STATE_ENDED) skipToNext();
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

	public boolean isPlaying() {
		return this.player.isPlaying();
	}

	public void play(@NonNull final VideoDetails vi, @NonNull final StreamDetails si) {
		this.vid = vi.getId();
		this.videoDetails = vi;
		this.streamDetails = si;

		final var videoStream = PlayerUtils.selectVideoStream(si.getVideoStreams(), prefs.getQuality());
		final var audioStream = PlayerUtils.selectAudioStream(si.getAudioStreams(), null);
		this.videoStream = videoStream;

		long duration = vi.getDuration() * 1000;
		final MediaItem.Builder builder = new MediaItem.Builder();
		if (si.getDashUrl() != null && !si.getDashUrl().isEmpty()) builder.setUri(si.getDashUrl());
		else if (videoStream != null) builder.setUri(videoStream.getContent());

		// Subtitle
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

		final boolean enabled = this.prefs.isSubtitleEnabled();
		setSubtitlesEnabled(enabled);
		final String saved = this.prefs.getSubtitleLanguage();
		if (enabled && saved != null && !saved.isEmpty() && !si.getSubtitles().isEmpty()) {
			setSubtitleLanguage(saved);
		}

		// Stream source
		final MediaSource source = createFinalMediaSource(videoStream, audioStream, si.getDashUrl(), si.getStreamType(), duration, TimeUnit.MILLISECONDS, builder.build(), si.getSubtitles());
		this.player.setMediaSource(source);
		this.player.setPlaybackParameters(new PlaybackParameters(this.prefs.getSpeed()));

		// Resume position
		if (prefs.getExtensionManager().isEnabled(Constant.REMEMBER_LAST_POSITION)) {
			final long resumePos = prefs.getResumePosition(vid);
			if (resumePos > SAFE_ZONE_MS && resumePos < duration - SAFE_ZONE_MS) {
				this.player.seekTo(resumePos);
			}
		}

		this.player.prepare();
		this.player.setPlayWhenReady(true);
	}

	private MediaSource createFinalMediaSource(@Nullable final Stream video, @Nullable final Stream audio, @Nullable final String dashUrl, @NonNull final StreamType streamType, final long duration, TimeUnit unit, @NonNull final MediaItem item, @Nullable final List<SubtitlesStream> subs) {
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

		if (!dashUrl.isEmpty())
			baseSource = new DashMediaSource.Factory(cacheFactory).createMediaSource(item);
		else {
			final MediaSource vSource = createMediaSource(video, duration, unit, cacheFactory);
			final MediaSource aSource = createMediaSource(audio, duration, unit, cacheFactory);
			baseSource = new MergingMediaSource(vSource, aSource);
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

	private MediaSource createMediaSource(@Nullable final Stream stream, final long duration, TimeUnit unit, @NonNull final CacheDataSource.Factory cacheFactory) {
		if (stream == null)
			return new ProgressiveMediaSource.Factory(cacheFactory)
							.createMediaSource(MediaItem.EMPTY);

		try {
			if (stream.getItagItem() != null) {
				final String manifest = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(stream.getContent(), stream.getItagItem(), unit.toMillis(duration) / 1_000);
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

	public void setPlaybackRate(final float rate) {
		this.player.setPlaybackParameters(new PlaybackParameters(rate));
	}

	public float getPlaybackRate() {
		return this.player.getPlaybackParameters().speed;
	}

	public void addListener(@NonNull final Player.Listener listener) {
		this.player.addListener(listener);
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

	public long getDuration() {
		return this.player.getDuration();
	}

	public long position() {
		return this.player.getCurrentPosition();
	}

	public void skipToNext() {
		this.tabManager.evaluateJavascript(JS_NEXT_VIDEO, null);
	}

	public void skipToPrevious() {
		this.tabManager.evaluateJavascript(JS_PREV_VIDEO, null);
	}

	@Nullable
	public Format getVideoFormat() {
		for (final Tracks.Group group : this.player.getCurrentTracks().getGroups()) {
			if (group.getType() == C.TRACK_TYPE_VIDEO && group.isSelected()) {
				for (int i = 0; i < group.length; i++)
					if (group.isTrackSelected(i)) return group.getTrackFormat(i);
			}
		}
		return null;
	}

	@Nullable
	public Format getAudioFormat() {
		for (final Tracks.Group group : this.player.getCurrentTracks().getGroups()) {
			if (group.getType() == C.TRACK_TYPE_AUDIO && group.isSelected()) {
				for (int i = 0; i < group.length; i++)
					if (group.isTrackSelected(i)) return group.getTrackFormat(i);
			}
		}
		return null;
	}
	public List<String> getAvailableResolutions() {
		final List<String> resolutions = new ArrayList<>();
		if (streamDetails != null) {
			final List<VideoStream> filtered = PlayerUtils.filterBestStreams(streamDetails.getVideoStreams());
			for (final VideoStream stream : filtered) {
				final String res = stream.getResolution();
				if (!resolutions.contains(res)) resolutions.add(res);
			}
		}
		// If empty, fall back to current tracks (e.g. for DASH/HLS)
		if (resolutions.isEmpty()) {
			for (final Tracks.Group group : this.player.getCurrentTracks().getGroups()) {
				if (group.getType() == C.TRACK_TYPE_VIDEO) {
					for (int i = 0; i < group.length; i++) {
						final Format format = group.getTrackFormat(i);
						if (format.height != Format.NO_VALUE) {
							final String res = format.height + "p";
							if (!resolutions.contains(res)) resolutions.add(res);
						}
					}
				}
			}
		}
		resolutions.sort((a, b) -> {
			try {
				int h1 = Integer.parseInt(a.replace("p", ""));
				int h2 = Integer.parseInt(b.replace("p", ""));
				return Integer.compare(h2, h1);
			} catch (NumberFormatException e) {
				return a.compareTo(b);
			}
		});
		return resolutions;
	}

	public void onQualitySelected(@Nullable final String res) {
		if (res == null || streamDetails == null) return;
		prefs.setQuality(res);

		if (streamDetails.getDashUrl() != null && !streamDetails.getDashUrl().isEmpty()) {
			int actualHeight = StringUtils.parseHeight(res);
			final VideoStream match = PlayerUtils.selectVideoStream(streamDetails.getVideoStreams(), res);
			if (match != null) {
				actualHeight = match.getHeight();
			}
			setVideoQuality(actualHeight);
		} else {
			final VideoStream selectedStream = PlayerUtils.selectVideoStream(streamDetails.getVideoStreams(), res);
			if (selectedStream != null) {
				final long pos = this.player.getCurrentPosition();
				final float speed = this.player.getPlaybackParameters().speed;
				play(videoDetails, streamDetails);
				this.player.seekTo(pos);
				this.player.setPlaybackParameters(new PlaybackParameters(speed));
			}
		}
	}

	public void setVideoQuality(final int height) {
		final DefaultTrackSelector trackSelector = (DefaultTrackSelector) this.player.getTrackSelector();
		trackSelector.setParameters(trackSelector.buildUponParameters()
			.setMaxVideoSize(Integer.MAX_VALUE, height)
			.setMinVideoSize(0, height)
			.build());
	}

	public String getQuality() {

		if (videoStream != null) return videoStream.getResolution();
		final Format format = getVideoFormat();
		return format != null ? format.height + "p" : prefs.getQuality();
	}

	public void setRepeatMode(final int mode) {
		this.player.setRepeatMode(mode);
	}

	public int getPlaybackState() {
		return this.player.getPlaybackState();
	}

	public boolean areSubtitlesEnabled() {
		return !this.player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
	}

	@Nullable
	public String getSelectedSubtitle() {
		for (final Tracks.Group group : this.player.getCurrentTracks().getGroups()) {
			if (group.getType() == C.TRACK_TYPE_TEXT && group.isSelected()) {
				for (int i = 0; i < group.length; i++) {
					if (group.isTrackSelected(i)) {
						final Format format = group.getTrackFormat(i);
						return format.label != null ? format.label : format.language;
					}
				}
			}
		}
		return null;
	}

	public List<String> getSubtitles() {
		final List<String> subtitles = new ArrayList<>();
		for (final Tracks.Group group : this.player.getCurrentTracks().getGroups()) {
			if (group.getType() == C.TRACK_TYPE_TEXT) {
				for (int i = 0; i < group.length; i++) {
					final Format format = group.getTrackFormat(i);
					if (format.label != null) subtitles.add(format.label);
					else if (format.language != null) subtitles.add(format.language);
				}
			}
		}
		return subtitles;
	}

	public List<StreamSegment> getSegments() {
		if (this.videoDetails != null && !this.videoDetails.getSegments().isEmpty())
			return this.videoDetails.getSegments();

		// Create default segment with video title at 0 seconds
		final List<StreamSegment> segments = new ArrayList<>();
		if (this.videoDetails != null) segments.add(new StreamSegment(this.videoDetails.getTitle(), 0));
		return segments;
	}

	public String getThumbnail() {
		return videoDetails != null ? videoDetails.getThumbnail() : null;
	}

	@Nullable
	public StreamDetails getStreamDetails() {
		return streamDetails;
	}

	@Nullable
	public DecoderCounters getVideoDecoderCounters() {
		return player.getVideoDecoderCounters();
	}

	@NonNull
	public List<AudioStream> getAvailableAudioTracks() {
		return streamDetails != null ? streamDetails.getAudioStreams() : Collections.emptyList();
	}

	@Nullable
	public AudioStream getAudioTrack() {
		if (streamDetails == null) return null;
		return PlayerUtils.selectAudioStream(streamDetails.getAudioStreams(), null);
	}

	public void setAudioTrack(@NonNull final AudioStream stream) {
		if (streamDetails == null) return;
		final AudioStream current = PlayerUtils.selectAudioStream(streamDetails.getAudioStreams(), null);
		if (current != null && current.getContent().equals(stream.getContent())) return;

		final VideoStream vs = PlayerUtils.selectVideoStream(streamDetails.getVideoStreams(), prefs.getQuality());
		final long pos = player.getCurrentPosition();
		final boolean playWhenReady = player.getPlayWhenReady();

		final MediaSource source = createFinalMediaSource(vs, stream, streamDetails.getDashUrl(), streamDetails.getStreamType(), videoDetails.getDuration() * 1000, TimeUnit.MILLISECONDS, player.getCurrentMediaItem(), streamDetails.getSubtitles());
		player.setMediaSource(source);
		player.seekTo(pos);
		player.setPlayWhenReady(playWhenReady);
		player.prepare();
	}

	public int getSelectedAudioTrackIndex() {
		final Format format = player.getAudioFormat();
		if (format == null || streamDetails == null) return -1;
		for (int i = 0; i < streamDetails.getAudioStreams().size(); i++) {
			if (streamDetails.getAudioStreams().get(i).getCodec().equals(format.codecs)) return i;
		}
		return -1;
	}

	public void clear() {
		handler.removeCallbacks(onTimeUpdate);
		this.player.stop();
		this.player.clearMediaItems();
	}

	public void release() {
		handler.removeCallbacks(onTimeUpdate);
		this.player.release();
	}
}
