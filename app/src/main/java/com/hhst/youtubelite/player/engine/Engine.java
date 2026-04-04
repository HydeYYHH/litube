package com.hhst.youtubelite.player.engine;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import com.hhst.youtubelite.Constant;
import com.hhst.youtubelite.browser.TabManager;
import com.hhst.youtubelite.extractor.Delivery;
import com.hhst.youtubelite.extractor.DeliveryCatalog;
import com.hhst.youtubelite.extractor.PlaybackDetails;
import com.hhst.youtubelite.extractor.PlaybackMode;
import com.hhst.youtubelite.extractor.PlaybackPlan;
import com.hhst.youtubelite.extractor.PlaybackPlanner;
import com.hhst.youtubelite.extractor.StreamCandidate;
import com.hhst.youtubelite.extractor.StreamCatalog;
import com.hhst.youtubelite.extractor.VideoDetails;
import com.hhst.youtubelite.player.LitePlayerView;
import com.hhst.youtubelite.player.common.PlayerLoopMode;
import com.hhst.youtubelite.player.common.PlayerPreferences;
import com.hhst.youtubelite.player.common.PlayerUtils;
import com.hhst.youtubelite.player.queue.QueueItem;
import com.hhst.youtubelite.player.queue.QueueNav;
import com.hhst.youtubelite.player.queue.QueueRepository;
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager;
import com.hhst.youtubelite.util.StringUtils;
import com.hhst.youtubelite.util.UrlUtils;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamSegment;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.android.scopes.ActivityScoped;

/**
 * Coordinates playback state and queue navigation.
 */
@UnstableApi
@ActivityScoped
public class Engine {
	static final String NO_PLAYABLE_SOURCE_MESSAGE = "No supported playable stream URL in StreamCatalog";
	private static final int SAFE_ZONE_MS = 5000;
	@NonNull
	private final ExoPlayer player;
	@NonNull
	private final PlayerPreferences prefs;
	@NonNull
	private final TabManager tabManager;
	@NonNull
	private final SponsorBlockManager sponsor;
	@NonNull
	private final QueueRepository queueRepository;
	@NonNull
	private final PlayerDataSource sources;
	private final Handler handler = new Handler(Looper.getMainLooper());
	@NonNull
	private PlayerLoopMode loopMode = PlayerLoopMode.PLAYLIST_NEXT;
	@Nullable
	private String videoId;
	private final Runnable onTimeUpdate = new Runnable() {
		@Override
		public void run() {
			if (!player.isPlaying()) return;
			long pos = player.getCurrentPosition();
			long duration = player.getDuration();
			// Persist playback progress.
			if (videoId != null && duration > 0 && prefs.getExtensionManager().isEnabled(Constant.REMEMBER_LAST_POSITION)) {
				if (pos > SAFE_ZONE_MS && pos < duration - SAFE_ZONE_MS) {
					prefs.persistProgress(videoId, pos, duration, TimeUnit.MILLISECONDS);
				}
			}
			// Skip sponsor segments.
			List<long[]> segments = sponsor.getSegments();
			for (final long[] segment : segments) {
				if (pos >= segment[0] && pos < segment[1]) {
					player.seekTo(segment[1]);
					break;
				}
			}
			handler.postDelayed(this, 1000);
		}
	};
	@Nullable
	private VideoDetails videoDetails;
	@Nullable
	private List<StreamSegment> segments = List.of();
	@Nullable
	private List<SubtitlesStream> subtitles = List.of();
	@Nullable
	private StreamCatalog streamCatalog;
	@Nullable
	private DeliveryCatalog deliveries;
	@Nullable
	private PlaybackPlan playbackPlan;
	@Nullable
	private VideoStream videoStream;

	@Inject
	public Engine(@NonNull @ApplicationContext Context context,
	              @NonNull LitePlayerView playerView,
	              @Nullable SimpleCache simpleCache,
	              @NonNull PlayerPreferences prefs,
	              @NonNull TabManager tabManager,
	              @NonNull SponsorBlockManager sponsor,
	              @NonNull QueueRepository queueRepository) {
		this.prefs = prefs;
		this.tabManager = tabManager;
		this.sponsor = sponsor;
		this.queueRepository = queueRepository;
		this.sources = new PlayerDataSource(simpleCache);
		DefaultTrackSelector trackSelector = new DefaultTrackSelector(context, new AdaptiveTrackSelection.Factory());
		trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(true).build());
		this.player = new ExoPlayer.Builder(context)
						.setTrackSelector(trackSelector)
						.setLoadControl(PlayerLoadControl.create())
						.setAudioAttributes(new AudioAttributes.Builder()
										.setUsage(C.USAGE_MEDIA)
										.setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
										.build(), true)
						.setHandleAudioBecomingNoisy(true)
						.setUsePlatformDiagnostics(false)
						.setMediaSourceFactory(
										new DefaultMediaSourceFactory(context)
														.setLiveMaxSpeed(1.0f)
						).build();
		this.player.addListener(new Player.Listener() {

			@Override
			public void onPlaybackStateChanged(int state) {
				if (state == Player.STATE_ENDED) {
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
			}

			@Override
			public void onIsPlayingChanged(boolean isPlaying) {
				handler.removeCallbacks(onTimeUpdate);
				if (isPlaying) handler.post(onTimeUpdate);
			}

			@Override
			public void onCues(@NonNull CueGroup cueGroup) {
				playerView.cueing(cueGroup);
			}

			@Override
			public void onTracksChanged(@NonNull Tracks tracks) {
				applyPreferredVideoTrack();
			}
		});
		playerView.setPlayer(this.player);
	}

	@Nullable
	private static VideoStream selectedVideo(@NonNull PlaybackPlan plan) {
		if (plan.getVideoCandidate() != null && plan.getVideoCandidate().getVideoStream() != null) {
			return plan.getVideoCandidate().getVideoStream();
		}
		if (plan.getMuxedCandidate() != null && plan.getMuxedCandidate().getVideoStream() != null) {
			return plan.getMuxedCandidate().getVideoStream();
		}
		return null;
	}

	@Nullable
	private static AudioStream selectedAudio(@NonNull PlaybackPlan plan) {
		if (plan.getAudioCandidate() != null && plan.getAudioCandidate().getAudioStream() != null) {
			return plan.getAudioCandidate().getAudioStream();
		}
		return null;
	}

	static boolean didNavigate(@Nullable String value) {
		return "\"navigating\"".equals(value);
	}

	@Nullable
	private static StreamCandidate findAudioCandidate(@NonNull StreamCatalog catalog,
	                                                  @NonNull AudioStream stream) {
		for (StreamCandidate candidate : catalog.getAudioCandidates()) {
			if (candidate.getAudioStream() != null
							&& stream.getContent().equals(candidate.getAudioStream().getContent())) {
				return candidate;
			}
		}
		return null;
	}

	static String buildPlaylistNavigationScript(int playlistOffset) {
		boolean nextNavigation = playlistOffset > 0;
		return """
						(function(){
						const playlistContents=globalThis.ytInitialData?.contents?.singleColumnWatchNextResults?.playlist?.playlist?.contents;
						if(!Array.isArray(playlistContents) || playlistContents.length===0) return 'missing-playlist';
						const watchUrl=new URL(location.href);
						const videoId=watchUrl.searchParams.get('v') ?? globalThis.ytInitialPlayerResponse?.videoDetails?.videoId;
						if(!videoId) return 'missing-current-video-id';
						const index=playlistContents.findIndex(item => item?.playlistPanelVideoRenderer?.videoId === videoId);
						if(index < 0) return 'missing-current-video';
						let targetIndex;
						if (__NEXT_NAVIGATION__) {
							targetIndex = (index + 1) % playlistContents.length;
						} else {
							if (index === 0) return 'playlist-head';
							targetIndex = index - 1;
						}
						const targetVideo=playlistContents[targetIndex]?.playlistPanelVideoRenderer;
						const targetUrl=targetVideo?.navigationEndpoint?.commandMetadata?.webCommandMetadata?.url;
						if(typeof targetUrl !== 'string' || targetUrl.length === 0) return 'missing-target-url';
						location.href = new URL(targetUrl, location.origin).toString();
						return 'navigating';
						})();
						""".replace("__NEXT_NAVIGATION__", Boolean.toString(nextNavigation));
	}

	static String buildRandomPlaylistNavigationScript() {
		return """
						(function(){
						const playlistContents=globalThis.ytInitialData?.contents?.singleColumnWatchNextResults?.playlist?.playlist?.contents;
						if(!Array.isArray(playlistContents) || playlistContents.length===0) return 'missing-playlist';
						const watchUrl=new URL(location.href);
						const videoId=watchUrl.searchParams.get('v') ?? globalThis.ytInitialPlayerResponse?.videoDetails?.videoId;
						if(!videoId) return 'missing-current-video-id';
						const i=playlistContents.findIndex(item => item?.playlistPanelVideoRenderer?.videoId === videoId);
						if(i < 0) return 'missing-current-video';
						const candidateIndices=playlistContents
							.map((item,index)=>item?.playlistPanelVideoRenderer ? index : -1)
							.filter(index=>index >= 0 && (playlistContents.length === 1 || index !== i));
						if(candidateIndices.length === 0) return 'missing-random-target';
						const targetIndex=candidateIndices[Math.floor(Math.random() * candidateIndices.length)];
						const targetVideo=playlistContents[targetIndex]?.playlistPanelVideoRenderer;
						const targetUrl=targetVideo?.navigationEndpoint?.commandMetadata?.webCommandMetadata?.url;
						if(typeof targetUrl !== 'string' || targetUrl.length === 0) return 'missing-target-url';
						location.href = new URL(targetUrl, location.origin).toString();
						return 'navigating';
						})();
						""";
	}

	private boolean isShortVideo() {
		long duration = player.getDuration();
		return duration > 0 && duration < SAFE_ZONE_MS;
	}

	public boolean isPlaying() {
		return this.player.isPlaying();
	}

	public boolean isCurrentVideoInQueue() {
		String watchId = watchVideoId();
		return queueRepository.containsVideo(watchId);
	}

	public void play(@NonNull PlaybackDetails details) {
		VideoDetails vi = details.video();
		this.videoId = vi.getId();
		this.videoDetails = vi;
		this.streamCatalog = details.catalog();
		this.deliveries = details.deliveries();
		this.playbackPlan = details.plan();
		this.segments = details.segments();
		this.subtitles = details.subtitles();
		applyPlaybackTrackMode();

		this.videoStream = selectedVideo(playbackPlan);
		boolean enabled = this.prefs.isSubtitleEnabled();
		setSubtitlesEnabled(enabled);
		String saved = this.prefs.getSubtitleLanguage();
		if (enabled && saved != null && !saved.isEmpty() && !this.subtitles.isEmpty()) {
			setSubtitleLanguage(saved);
		}

		long duration = vi.getDuration() * 1000;
		this.player.setMediaSource(PlaybackRunner.create(sources, details, playbackPlan));
		this.player.setPlaybackParameters(new PlaybackParameters(this.prefs.getSpeed()));

		// Resume position
		if (prefs.getExtensionManager().isEnabled(Constant.REMEMBER_LAST_POSITION)) {
			long resumePos = prefs.getResumePosition(videoId);
			if (resumePos > SAFE_ZONE_MS && resumePos < duration - SAFE_ZONE_MS) {
				this.player.seekTo(resumePos);
			}
		}

		this.player.prepare();
		this.player.setPlayWhenReady(true);
	}

	public void play() {
		this.player.play();
	}

	public void pause() {
		this.player.pause();
	}

	public void seekTo(long pos) {
		this.player.seekTo(Math.min(this.player.getDuration(), pos));
	}

	public void seekBy(long offset) {
		this.player.seekTo(Math.min(this.player.getDuration(), this.player.getCurrentPosition() + offset));
	}

	public float getPlaybackRate() {
		return this.player.getPlaybackParameters().speed;
	}

	public void setPlaybackRate(float rate) {
		this.player.setPlaybackParameters(new PlaybackParameters(rate));
	}

	public void addListener(@NonNull Player.Listener listener) {
		this.player.addListener(listener);
	}

	public VideoSize getVideoSize() {
		return this.player.getVideoSize();
	}

	public void setSubtitlesEnabled(boolean enabled) {
		this.prefs.setSubtitleEnabled(enabled);
		this.player.setTrackSelectionParameters(this.player.getTrackSelectionParameters().buildUpon()
						.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
						.build());
	}

	@Nullable
	public String getSubtitleLanguage() {
		return this.prefs.getSubtitleLanguage();
	}

	public void setSubtitleLanguage(@Nullable String language) {
		if (language == null) return;
		this.prefs.setSubtitleEnabled(true);
		this.prefs.setSubtitleLanguage(language);
		Tracks tracks = this.player.getCurrentTracks();
		for (final Tracks.Group group : tracks.getGroups()) {
			if (group.getType() == C.TRACK_TYPE_TEXT) {
				for (int i = 0; i < group.length; i++) {
					Format format = group.getTrackFormat(i);
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
		boolean queueEnabled = queueRepository.isEnabled();
		boolean hasQueueItems = queueRepository.hasItems();
		String watchId = watchVideoId();
		boolean hasPlaylist = tabManager.watchHasPlaylist();
		boolean queueContext = queueEnabled && hasQueueItems;
		boolean playlistContext = !queueContext && hasPlaylist;
		if (queueContext) {
			QueueItem item = queueRepository.findRelative(watchId, 1);
			if (item != null && item.getVideoUrl() != null) {
				tabManager.playInWatch(item.getVideoUrl());
			}
			return;
		}
		if (playlistContext) {
			this.tabManager.evalWatchJs(
							buildPlaylistNavigationScript(1),
							null);
		}
	}

	public void skipToPrevious() {
		boolean queueEnabled = queueRepository.isEnabled();
		boolean hasQueueItems = queueRepository.hasItems();
		String watchId = watchVideoId();
		boolean inQueue = queueRepository.containsVideo(watchId);
		boolean hasPlaylist = tabManager.watchHasPlaylist();
		boolean canGoBack = tabManager.canGoBackInWatch();
		boolean queueContext = queueEnabled && hasQueueItems;
		boolean playlistContext = !queueContext && hasPlaylist;
		if (queueContext) {
			if (!inQueue) {
				if (canGoBack) {
					tabManager.goBackInWatch();
				}
				return;
			}
			QueueItem item = queueRepository.findRelative(watchId, -1);
			if (item != null && item.getVideoUrl() != null) {
				tabManager.playInWatch(item.getVideoUrl());
				return;
			}
			if (canGoBack) {
				tabManager.goBackInWatch();
			}
			return;
		}
		if (playlistContext) {
			tabManager.evalWatchJs(
							buildPlaylistNavigationScript(-1),
							value -> {
								if (didNavigate(value)) return;
								if ("\"playlist-head\"".equals(value)) {
									if (canGoBack) tabManager.goBackInWatch();
									return;
								}
								if ("\"missing-playlist\"".equals(value)
												|| "\"missing-current-video-id\"".equals(value)
												|| "\"missing-current-video\"".equals(value)) {
									if (canGoBack) tabManager.goBackInWatch();
								}
							});
			return;
		}
		if (canGoBack) {
			tabManager.goBackInWatch();
		}
	}

	public void playRandomPlaylistItem() {
		boolean queueEnabled = queueRepository.isEnabled();
		boolean hasQueueItems = queueRepository.hasItems();
		String watchId = watchVideoId();
		boolean hasPlaylist = tabManager.watchHasPlaylist();
		boolean queueContext = queueEnabled && hasQueueItems;
		boolean playlistContext = !queueContext && hasPlaylist;
		if (queueContext) {
			QueueItem item = queueRepository.findRandom(watchId);
			if (item != null && item.getVideoUrl() != null) {
				tabManager.playInWatch(item.getVideoUrl());
			}
			return;
		}
		if (playlistContext) {
			this.tabManager.evalWatchJs(buildRandomPlaylistNavigationScript(), null);
		}
	}

	@NonNull
	public QueueNav getQueueNavigationAvailability() {
		boolean queueEnabled = queueRepository.isEnabled();
		boolean hasQueueItems = queueRepository.hasItems();
		String watchId = watchVideoId();
		boolean inQueue = queueRepository.containsVideo(watchId);
		boolean hasPlaylist = tabManager.watchHasPlaylist();
		boolean canGoBack = tabManager.canGoBackInWatch();
		boolean playlistAtHead = UrlUtils.isPlaylistFirstItemUrl(tabManager.getWatchUrl());
		boolean queueContext = queueEnabled && hasQueueItems;
		boolean playlistContext = !queueContext && hasPlaylist;
		boolean queueAtHead = queueContext && queueRepository.findRelative(watchId, -1) == null;
		if (queueContext) {
			boolean queuePrevEnabled = inQueue && !queueAtHead;
			boolean queueBackEnabled = canGoBack && (!inQueue || queueAtHead);
			return new QueueNav(true, true, true, queuePrevEnabled, queueBackEnabled);
		}
		if (playlistContext) {
			boolean playlistPrevEnabled = !playlistAtHead || canGoBack;
			return new QueueNav(false, true, true, false, playlistPrevEnabled);
		}
		return new QueueNav(false, false, false, false, canGoBack);
	}

	@Nullable
	private String watchVideoId() {
		String watchUrl = tabManager.getWatchUrl();
		if (watchUrl == null || watchUrl.isEmpty()) {
			return videoId;
		}
		try {
			String query = URI.create(watchUrl).getRawQuery();
			if (query != null && !query.isBlank()) {
				for (String pair : query.split("&")) {
					int separator = pair.indexOf('=');
					String name = separator >= 0 ? pair.substring(0, separator) : pair;
					if (!"v".equals(name)) continue;
					return separator >= 0 ? pair.substring(separator + 1) : "";
				}
			}
		} catch (IllegalArgumentException ignored) {
			// Fall back to the cached engine id.
		}
		return videoId;
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
		List<String> resolutions = new ArrayList<>();
		if (streamCatalog != null) {
			for (VideoStream stream : PlayerUtils.filterBestStreams(streamCatalog.getVideoStreams())) {
				String res = stream.getResolution();
				if (!resolutions.contains(res)) resolutions.add(res);
			}
		}
		// If empty, fall back to the active tracks, such as DASH or HLS.
		if (resolutions.isEmpty()) {
			for (final Tracks.Group group : this.player.getCurrentTracks().getGroups()) {
				if (group.getType() == C.TRACK_TYPE_VIDEO) {
					for (int i = 0; i < group.length; i++) {
						Format format = group.getTrackFormat(i);
						if (format.height != Format.NO_VALUE) {
							String res = format.height + "p";
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

	public void onQualitySelected(@Nullable String res) {
		if (res == null || deliveries == null || playbackPlan == null || videoDetails == null || streamCatalog == null)
			return;
		prefs.setQuality(res);
		this.playbackPlan = PlaybackPlanner.plan(deliveries, res, null);
		Delivery delivery = playbackPlan.getDelivery();
		if (isLiveMode(playbackPlan) && delivery != null && !delivery.isTrackLock()) {
			applyPlaybackTrackMode();
			return;
		}
		if (delivery != null && delivery.isTrackLock()) {
			int actualHeight = StringUtils.parseHeight(res);
			VideoStream match = selectedVideo(playbackPlan);
			if (match != null) {
				actualHeight = match.getHeight();
			}
			setVideoQuality(actualHeight);
			return;
		}
		long pos = this.player.getCurrentPosition();
		float speed = this.player.getPlaybackParameters().speed;
		play(new PlaybackDetails(videoDetails, streamCatalog, deliveries, playbackPlan, segments, subtitles));
		if (playbackPlan.getMode() != PlaybackMode.LIVE_DASH
						&& playbackPlan.getMode() != PlaybackMode.LIVE_HLS) {
			this.player.seekTo(pos);
		}
		this.player.setPlaybackParameters(new PlaybackParameters(speed));
	}

	public void setVideoQuality(int height) {
		DefaultTrackSelector trackSelector = (DefaultTrackSelector) this.player.getTrackSelector();
		final DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters()
						.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
						.setForceHighestSupportedBitrate(false)
						.setMaxVideoSize(Integer.MAX_VALUE, height)
						.setMinVideoSize(0, height);
		TrackOverride override = findVideoOverride(height);
		if (override != null) {
			builder.setOverrideForType(new TrackSelectionOverride(override.group(), override.track()));
		}
		trackSelector.setParameters(builder.build());
	}

	private void applyPreferredVideoTrack() {
		PlaybackPlan plan = playbackPlan;
		if (plan == null || plan.getDelivery() == null || !plan.getDelivery().isTrackLock()) {
			return;
		}
		String quality = prefs.getQuality();
		if (quality.isEmpty()) {
			return;
		}
		int height = StringUtils.parseHeight(quality);
		if (height > 0) {
			setVideoQuality(height);
		}
	}

	private void applyPlaybackTrackMode() {
		DefaultTrackSelector trackSelector = (DefaultTrackSelector) this.player.getTrackSelector();
		final DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters()
						.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
						.setForceHighestSupportedBitrate(false);
		PlaybackPlan plan = playbackPlan;
		if (plan == null || plan.getDelivery() == null) {
			builder.clearVideoSizeConstraints();
		} else {
			int height = StringUtils.parseHeight(prefs.getQuality());
			if (height > 0) {
				builder.setMaxVideoSize(Integer.MAX_VALUE, height);
				if (plan.getDelivery().isTrackLock()) {
					builder.setMinVideoSize(0, height);
				} else {
					builder.clearVideoSizeConstraints();
					builder.setMaxVideoSize(Integer.MAX_VALUE, height);
				}
			}
		}
		trackSelector.setParameters(builder.build());
	}

	@Nullable
	private TrackOverride findVideoOverride(int preferredHeight) {
		TrackOverride best = null;
		int bestDelta = Integer.MAX_VALUE;
		for (final Tracks.Group group : this.player.getCurrentTracks().getGroups()) {
			if (group.getType() != C.TRACK_TYPE_VIDEO) {
				continue;
			}
			for (int i = 0; i < group.length; i++) {
				Format format = group.getTrackFormat(i);
				if (format.height == Format.NO_VALUE || !group.isTrackSupported(i)) {
					continue;
				}
				int delta = Math.abs(format.height - preferredHeight);
				if (best == null || delta < bestDelta) {
					best = new TrackOverride(group.getMediaTrackGroup(), i);
					bestDelta = delta;
				}
			}
		}
		return best;
	}

	public String getQuality() {

		if (videoStream != null) return videoStream.getResolution();
		Format format = getVideoFormat();
		if (format != null && format.height > 0) {
			int fps = Math.round(format.frameRate);
			return fps > 30 ? format.height + "p" + fps : format.height + "p";
		}
		return prefs.getQuality();
	}

	public String getQualityLabel() {
		String quality = getQuality();
		return quality == null || quality.isEmpty() ? prefs.getQuality() : quality;
	}

	public void setRepeatMode(int mode) {
		this.player.setRepeatMode(mode);
	}

	public void setLoopMode(@NonNull PlayerLoopMode mode) {
		this.loopMode = mode;
		setRepeatMode(mode.repeatMode());
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
						Format format = group.getTrackFormat(i);
						return format.label != null ? format.label : format.language;
					}
				}
			}
		}
		return null;
	}

	public List<String> getSubtitles() {
		List<String> subtitles = new ArrayList<>();
		for (final Tracks.Group group : this.player.getCurrentTracks().getGroups()) {
			if (group.getType() == C.TRACK_TYPE_TEXT) {
				for (int i = 0; i < group.length; i++) {
					Format format = group.getTrackFormat(i);
					if (format.label != null) subtitles.add(format.label);
					else if (format.language != null) subtitles.add(format.language);
				}
			}
		}
		return subtitles;
	}

	public List<StreamSegment> getSegments() {
		if (this.segments != null && !this.segments.isEmpty())
			return this.segments;

		// Create default segment with video title at 0 seconds
		List<StreamSegment> segments = new ArrayList<>();
		if (this.videoDetails != null) segments.add(new StreamSegment(this.videoDetails.getTitle(), 0));
		return segments;
	}

	public String getThumbnailUrl() {
		return videoDetails != null ? videoDetails.getThumbnailUrl() : null;
	}

	@Nullable
	public StreamCatalog getStreamCatalog() {
		return streamCatalog;
	}

	@Nullable
	public DecoderCounters getVideoDecoderCounters() {
		return player.getVideoDecoderCounters();
	}

	@NonNull
	public List<AudioStream> getAvailableAudioTracks() {
		return streamCatalog != null ? streamCatalog.getAudioStreams() : Collections.emptyList();
	}

	@Nullable
	public AudioStream getAudioTrack() {
		if (playbackPlan == null) return null;
		return selectedAudio(playbackPlan);
	}

	public void setAudioTrack(@NonNull AudioStream stream) {
		if (streamCatalog == null || deliveries == null || playbackPlan == null || videoDetails == null)
			return;
		AudioStream audio = selectedAudio(playbackPlan);
		if (audio != null && audio.getContent().equals(stream.getContent())) return;
		long pos = player.getCurrentPosition();
		boolean playWhenReady = player.getPlayWhenReady();
		playbackPlan.setAudioCandidate(findAudioCandidate(streamCatalog, stream));
		player.setMediaSource(PlaybackRunner.create(sources,
						new PlaybackDetails(videoDetails, streamCatalog, deliveries, playbackPlan, segments, subtitles),
						playbackPlan));
		player.seekTo(pos);
		player.setPlayWhenReady(playWhenReady);
		player.prepare();
	}

	public int getSelectedAudioTrackIndex() {
		AudioStream selected = getAudioTrack();
		if (selected == null || streamCatalog == null) return -1;
		for (int i = 0; i < streamCatalog.getAudioStreams().size(); i++) {
			if (streamCatalog.getAudioStreams().get(i).getContent().equals(selected.getContent()))
				return i;
		}
		return -1;
	}

	private boolean isLiveMode(@Nullable PlaybackPlan plan) {
		return plan != null && (plan.getMode() == PlaybackMode.LIVE_DASH
						|| plan.getMode() == PlaybackMode.LIVE_HLS);
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

/**
 * Value object for app logic.
 */
	private record TrackOverride(@NonNull TrackGroup group, int track) {
	}
}
