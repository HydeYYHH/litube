package com.hhst.youtubelite.player;

import static com.hhst.youtubelite.utils.IOUtils.checkInterrupt;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.PlayerView;

import com.hhst.youtubelite.ErrorDialog;
import com.hhst.youtubelite.PlaybackService;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.TabManager;
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.extractor.VideoDetails;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.extractor.ExtractionException;
import com.hhst.youtubelite.player.exception.PlaybackException;
import com.hhst.youtubelite.player.interfaces.IPlayerInternal;
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager;
import com.hhst.youtubelite.player.sponsor.SponsorOverlayView;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamSegment;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import lombok.Getter;

/**
 * Main YouTube player implementation that handles video playback, stream selection,
 * quality management, and integration with other components like playback service
 * and sponsor block manager.
 */
@UnstableApi
@OptIn(markerClass = UnstableApi.class)
public final class YoutubePlayer implements IPlayerInternal {
	private static final String TAG = "YoutubePlayer";
	private static final int PROGRESS_SAVE_INTERVAL_MS = 1_000;
	private static final long MS_PER_SECOND = 1_000L;
	private static final String JS_NEXT_VIDEO = "document.querySelector('#movie_player')?.nextVideo();";
	private static final String JS_PREV_VIDEO = "document.querySelector('#movie_player')?.previousVideo();";

	@NonNull
	private final Activity activity;
	@NonNull
	private final PlayerEngine engine;
	@NonNull
	@Getter
	private final PlayerPreferences prefs;
	@NonNull
	@Getter
	private final SponsorBlockManager sponsor;
	@NonNull
	@Getter
	private final TabManager tabManager;
	@NonNull
	private final PlayerView playerView;
	@NonNull
	private final Handler handler = new Handler(Looper.getMainLooper());
	@NonNull
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	@Nullable
	private PlaybackService playbackService;
	@Nullable
	@Getter
	private volatile StreamDetails streamDetails;
	@Nullable
	private volatile VideoDetails videoDetails;
	@Nullable
	private volatile String vid;
	@Getter
	private volatile long duration;
	@Nullable
	@Getter
	private AudioStream audioStream;
	@Nullable
	private VideoStream videoStream;
	@Nullable
	private Future<?> task;
	@NonNull
	private List<VideoStream> availableVideoStreams = new ArrayList<>();

	public YoutubePlayer(@NonNull final Activity activity, @NonNull final TabManager tabManager, @NonNull final PlayerEngine engine) {
		this.activity = activity;
		this.playerView = PlayerContext.getInstance().getPlayerView();
		this.tabManager = tabManager;
		this.engine = engine;
		this.prefs = PlayerContext.getInstance().getPreferences();
		this.sponsor = new SponsorBlockManager();
		setupEngine();
	}

	/**
	 * Saves playback progress every second when video is playing.
	 * Also handles sponsor segment skipping and updates playback service.
	 */
	private void saveProgress() {
		if (vid != null && engine.isPlaying()) {
			long position = engine.position();
			final long endTime = sponsor.shouldSkip(position);
			if (endTime > 0 && endTime != position) {
				engine.seekTo(endTime);
				position = endTime;
			}
			prefs.persistProgress(vid, position);
			if (playbackService != null) playbackService.updateProgress(position, prefs.getSpeed(), true);
		}
		handler.postDelayed(this::saveProgress, PROGRESS_SAVE_INTERVAL_MS);
	}

	/**
	 * Sets up listeners for the media engine events.
	 * Configures playback state, navigation, and error handling.
	 */
	private void setupEngine() {
		engine.addListener(createPlayerListener());
		engine.setNavCallback(createNavigationCallback());
		engine.setErrorListener(this::handleError);
	}

	private Player.Listener createPlayerListener() {
		return new Player.Listener() {
			@Override
			public void onPlaybackStateChanged(final int state) {
				if (state == Player.STATE_ENDED) tabManager.evaluateJavascript(JS_NEXT_VIDEO, null);
			}

			@Override
			public void onIsPlayingChanged(final boolean isPlaying) {
				if (playbackService != null)
					playbackService.updateProgress(engine.position(), prefs.getSpeed(), isPlaying);
				if (isPlaying) handler.post(YoutubePlayer.this::saveProgress);
				else handler.removeCallbacks(YoutubePlayer.this::saveProgress);
			}
		};
	}

	private PlayerEngine.NavigationCallback createNavigationCallback() {
		return new PlayerEngine.NavigationCallback() {
			@Override
			public void onNext() {
				tabManager.evaluateJavascript(JS_NEXT_VIDEO, null);
			}

			@Override
			public void onPrev() {
				tabManager.evaluateJavascript(JS_PREV_VIDEO, null);
			}
		};
	}

	/**
	 * Handles playback errors.
	 *
	 * @param e The exception that occurred
	 */
	@Override
	public void handleError(@NonNull final Exception e) {
		if (e instanceof InterruptedException || e instanceof InterruptedIOException) return;
		Log.e("YoutubePlayer", "Play error", e);
		activity.runOnUiThread(() -> launchErrorActivity(e));
	}

	/**
	 * Adds a listener to the player.
	 *
	 * @param listener The listener to add
	 */
	@Override
	public void addListener(@NonNull final Player.Listener listener) {
		engine.addListener(listener);
	}

	/**
	 * Starts playback of a YouTube video from the given URL.
	 *
	 * @param url The YouTube video URL
	 */
	@Override
	public void play(@Nullable final String url) {
		if (url == null || url.isEmpty()) return;
		final String _vid = YoutubeExtractor.getVideoId(url);
		Log.d(TAG, String.format(Locale.getDefault(), "play(%s), parsed vid: %s", url, _vid));
		if (vid != null && Objects.equals(vid, _vid)) return;
		vid = _vid;

		resetPlayerUI();
		task = executor.submit(() -> loadVideoData(url, _vid));
	}

	private void resetPlayerUI() {
		activity.runOnUiThread(() -> {
			if (task != null) task.cancel(true);
			engine.stop();
			engine.clearMediaItems();
			playerView.setVisibility(View.VISIBLE);
			setTitle("");
		});
	}

	private void loadVideoData(final String url, final String vid) {
		try {
			checkInterrupt();

			sponsor.load(vid);
			final VideoDetails details = YoutubeExtractor.getVideoInfo(url);
			final StreamDetails si = YoutubeExtractor.getStreamInfo(url);

			final List<VideoStream> filteredStreams = PlayerUtils.filterBestStreams(si.getVideoStreams());
			si.setVideoStreams(filteredStreams);

			availableVideoStreams = filteredStreams;
			videoDetails = details;
			streamDetails = si;
			duration = details.getDuration() * MS_PER_SECOND;

			final VideoStream videoStream = PlayerUtils.selectVideoStream(availableVideoStreams, prefs.getQuality());
			this.videoStream = videoStream;
			this.audioStream = PlayerUtils.selectAudioStream(si.getAudioStreams(), null);

			final long resume = prefs.getResumePosition(vid, duration);
			final float speed = prefs.getSpeed();

			checkInterrupt();

			activity.runOnUiThread(() -> updatePlayer(details, videoStream, si, resume, speed));
		} catch (final Exception e) {
			if (e instanceof InterruptedException || e instanceof InterruptedIOException) return;
			handleError(new ExtractionException("Failed to load video data", e));
		}
	}

	private void updatePlayer(@NonNull final VideoDetails details, @Nullable final VideoStream videoStream, @NonNull final StreamDetails si, final long resume, final float speed) {
		try {
			setTitle(details.getTitle());
			updateSkipMarkers();
			play(videoStream, this.audioStream, si.getDashUrl(), duration, resume, speed);
		} catch (final Exception e) {
			handleError(new PlaybackException("Failed to update player", e));
		}
	}

	/**
	 * Hides the player and stops playback.
	 */
	@Override
	public void hide() {
		activity.runOnUiThread(() -> {
			vid = null;
			if (task != null) task.cancel(true);
			playerView.setVisibility(View.GONE);
			engine.stop();
			engine.clearMediaItems();
		});
	}

	@Override
	public void setPlayerHeight(final int height) {
		playerView.post(() -> {
			final float density = activity.getResources().getDisplayMetrics().density;
			final int deviceHeight = (int) (height * density);
			if (playerView.getLayoutParams().height == deviceHeight) return;
			playerView.getLayoutParams().height = deviceHeight;
			playerView.requestLayout();
		});
	}

	private void setTitle(@NonNull final String title) {
		final TextView titleView = playerView.findViewById(R.id.tv_title);
		if (titleView != null) {
			titleView.setText(title);
			titleView.setSelected(true);
		}
	}

	/**
	 * Updates sponsor segment markers on the player UI.
	 * Displays sponsor segments on both the time bar and overlay.
	 */
	private void updateSkipMarkers() {
		final List<long[]> segs = sponsor.getSegments();
		if (segs == null || segs.isEmpty()) return;

		final List<long[]> validSegs = new ArrayList<>();
		for (final long[] seg : segs) {
			if (seg != null && seg.length >= 2) validSegs.add(seg);
		}

		if (validSegs.isEmpty()) return;

		final SponsorOverlayView layer = playerView.findViewById(R.id.sponsor_overlay);
		if (layer != null) layer.setData(validSegs, duration);

		final long[] times = new long[validSegs.size() * 2];
		for (int i = 0; i < validSegs.size(); i++) {
			times[i * 2] = validSegs.get(i)[0];
			times[i * 2 + 1] = validSegs.get(i)[1];
		}

		final DefaultTimeBar bar = playerView.findViewById(R.id.exo_progress);
		if (bar != null) bar.setAdGroupTimesMs(times, new boolean[times.length], times.length);
	}

	/**
	 * Shows error dialog with exception details.
	 *
	 * @param e The exception that occurred
	 */
	private void launchErrorActivity(@NonNull final Exception e) {
		ErrorDialog.show(activity, e.getMessage(), Log.getStackTraceString(e));
	}

	/**
	 * Attaches a playback service for background playback functionality.
	 *
	 * @param service The playback service instance
	 */
	@Override
	public void attachPlaybackService(@Nullable final PlaybackService service) {
		this.playbackService = service;
	}

	/**
	 * Releases all resources used by the player.
	 * Cancels tasks, stops handlers, and releases the media engine.
	 */
	@Override
	public void release() {
		if (task != null) task.cancel(true);
		handler.removeCallbacksAndMessages(null);
		executor.shutdownNow();
		engine.release();
	}

	/**
	 * Handles quality selection from UI.
	 * Updates player with selected quality.
	 *
	 * @param res Selected resolution string
	 */
	@Override
	public void onQualitySelected(@Nullable final String res) {
		final var sd = streamDetails;
		if (res == null || sd == null) return;
		prefs.setQuality(res);

		if (availableVideoStreams.isEmpty()) return;

		if (sd.getDashUrl() != null && !sd.getDashUrl().isEmpty()) {
			int actualHeight = PlayerUtils.parseHeight(res);
			final VideoStream match = PlayerUtils.selectVideoStream(availableVideoStreams, res);
			if (match != null) {
				actualHeight = match.getHeight();
				this.videoStream = match;
			}
			engine.setVideoQuality(actualHeight);
		} else {
			final VideoStream selectedStream = PlayerUtils.selectVideoStream(availableVideoStreams, res);
			if (selectedStream != null) {
				this.videoStream = selectedStream;
				final long pos = engine.position();
				final AudioStream currentAudioStream = sd.getAudioStreams().isEmpty() ? null : sd.getAudioStreams().get(0);
				engine.play(selectedStream, currentAudioStream, null, sd.getDashUrl(), duration, pos, prefs.getSpeed());
				final TextView qualityView = playerView.findViewById(R.id.btn_quality);
				if (qualityView != null) qualityView.setText(selectedStream.getResolution());
			}
		}
	}

	/**
	 * Updates playback progress in the attached playback service.
	 *
	 * @param position Current playback position in milliseconds
	 */
	@Override
	public void updateProgress(final long position) {
		if (playbackService != null)
			playbackService.updateProgress(position, prefs.getSpeed(), engine.isPlaying());
	}

	/**
	 * Gets the current playback speed.
	 *
	 * @return Current playback speed multiplier
	 */
	@Override
	public float getSpeed() {
		return prefs.getSpeed();
	}

	/**
	 * Gets the current playback quality.
	 *
	 * @return Current quality string (e.g., "1080p")
	 */
	@NonNull
	@Override
	public String getQuality() {
		if (videoStream != null) return videoStream.getResolution();
		return prefs.getQuality();
	}

	/**
	 * Handles playback speed selection from UI.
	 *
	 * @param speed Selected playback speed multiplier
	 */
	@Override
	public void onSpeedSelected(final float speed) {
		prefs.setSpeed(speed);
		engine.setSpeed(speed);
	}

	/**
	 * Plays the video with the specified streams and parameters.
	 *
	 * @param videoStream The video stream to play
	 * @param audioStream The audio stream to play
	 * @param dashUrl     The DASH manifest URL
	 * @param duration    Video duration in milliseconds
	 * @param resumePos   Resume position in milliseconds
	 * @param speed       Playback speed
	 */
	@Override
	public void play(@Nullable final VideoStream videoStream, @Nullable final AudioStream audioStream, @Nullable final String dashUrl, final long duration, final long resumePos, final float speed) {
		this.audioStream = audioStream;
		activity.runOnUiThread(() -> {
			try {
				final var vd = videoDetails;
				final var sd = streamDetails;
				if (vd == null || sd == null) throw new IllegalArgumentException("VideoDetails or StreamDetails is null");
				engine.play(videoStream, audioStream, sd.getSubtitles(), dashUrl, duration, resumePos, speed);
				this.duration = duration;
				prefs.setSpeed(speed);
				updateQualityDisplay();
				updateSpeedDisplay();

				if (videoDetails != null) {
					// Handle subtitles
					final String savedSubtitle = prefs.getSubtitleLanguage();
					final ImageButton subtitlesBtn = playerView.findViewById(R.id.btn_subtitles);
					if (subtitlesBtn != null) {
						if (savedSubtitle != null && !savedSubtitle.isEmpty() && sd.getSubtitles() != null && !sd.getSubtitles().isEmpty()) {
							engine.setSubtitleLanguage(savedSubtitle);
							subtitlesBtn.setImageResource(R.drawable.ic_subtitles_on);
						} else subtitlesBtn.setImageResource(R.drawable.ic_subtitles_off);
					}

					// Show notification
					if (playbackService != null)
						playbackService.showNotification(vd.getTitle(), vd.getAuthor(), vd.getThumbnail(), duration);
				}
			} catch (final Exception e) {
				handleError(new PlaybackException("Playback execution failed", e));
			}
		});
	}

	/**
	 * Gets the list of available resolutions for the current video.
	 *
	 * @return List of resolution strings
	 */
	@NonNull
	@Override
	public List<String> getAvailableResolutions() {
		final List<String> resolutions = new ArrayList<>();
		for (final VideoStream stream : availableVideoStreams)
			resolutions.add(stream.getResolution());
		return resolutions;
	}

	/**
	 * Updates the quality display in the UI.
	 */
	@Override
	public void updateQualityDisplay() {
		activity.runOnUiThread(() -> {
			final TextView qualityView = playerView.findViewById(R.id.btn_quality);
			if (qualityView != null) qualityView.setText(getQuality());
		});
	}

	/**
	 * Updates the speed display in the UI.
	 */
	@Override
	public void updateSpeedDisplay() {
		activity.runOnUiThread(() -> {
			final TextView speedView = playerView.findViewById(R.id.btn_speed);
			if (speedView != null)
				speedView.setText(String.format(Locale.getDefault(), "%sx", prefs.getSpeed()));
		});
	}

	/**
	 * Gets the list of stream segments for the video.
	 *
	 * @return List of stream segments
	 */
	@NonNull
	@Override
	public List<StreamSegment> getStreamSegments() {
		final var vd = videoDetails;
		if (vd != null && vd.getSegments() != null && !vd.getSegments().isEmpty())
			return vd.getSegments();

		// Create default segment with video title at 0 seconds
		final List<StreamSegment> defaultSegments = new ArrayList<>();
		if (vd != null) defaultSegments.add(new StreamSegment(vd.getTitle(), 0));
		return defaultSegments;
	}

	@Override
	public void switchAudioTrack(@Nullable final AudioStream audioStream) {
		final var sd = streamDetails;
		if (sd == null || audioStream == null) return;

		// Get video stream, playback position, playback speed
		final List<VideoStream> videoStreams = sd.getVideoStreams();
		if (videoStreams.isEmpty()) return;

		// Try to find the playing video stream
		final String quality = getQuality();
		VideoStream videoStream = null;
		for (final VideoStream vs : videoStreams) {
			if (vs.getResolution().equals(quality)) {
				videoStream = vs;
				break;
			}
		}
		if (videoStream == null) videoStream = videoStreams.get(0);

		// Get playback position and speed
		final long position = engine.position();
		final float speed = engine.speed();

		// Replay video with new audio track
		play(videoStream, audioStream, sd.getDashUrl(), duration, position, speed);
	}

	@Override
	@Nullable
	public String getThumbnail() {
		final var vd = videoDetails;
		return vd != null ? vd.getThumbnail() : null;
	}

	@Override
	@NonNull
	public Optional<Format> getVideoFormat() {
		return engine.getVideoFormat();
	}

	@Override
	@NonNull
	public Optional<Format> getAudioFormat() {
		return engine.getAudioFormat();
	}

	@Override
	@Nullable
	public DecoderCounters getVideoDecoderCounters() {
		return engine.getVideoDecoderCounters();
	}

}