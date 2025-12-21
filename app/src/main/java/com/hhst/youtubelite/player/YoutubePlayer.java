package com.hhst.youtubelite.player;

import static com.hhst.youtubelite.utils.IOUtils.checkInterrupt;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.Player;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.PlayerView;

import com.hhst.youtubelite.ErrorDialog;
import com.hhst.youtubelite.PlaybackService;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.extractor.VideoDetails;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.player.interfaces.IPlayerInternal;
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager;
import com.hhst.youtubelite.player.sponsor.SponsorOverlayView;
import com.hhst.youtubelite.webview.YoutubeWebview;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamSegment;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
public class YoutubePlayer implements IPlayerInternal {
	private final Activity activity;
	private final PlayerEngine engine;
	@Getter
	private final PlayerPreferences prefs;
	@Getter
	private final SponsorBlockManager sponsor;
	@Getter
	private final YoutubeWebview webview;
	private final PlayerView playerView;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private PlaybackService playbackService;
	@Getter
	private volatile StreamDetails streamDetails;
	private volatile VideoDetails videoDetails;
	private volatile String vid;
	@Getter
	private volatile long duration;
	@Getter
	private AudioStream audioStream;
	private Future<?> task;
	private List<VideoStream> availableVideoStreams = new ArrayList<>();

	public YoutubePlayer(Activity activity, YoutubeWebview webview, PlayerEngine engine) {
		this.activity = activity;
		this.playerView = PlayerContext.getInstance().getPlayerView();
		this.webview = webview;
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
			long endTime = sponsor.shouldSkip(position);
			if (endTime > 0 && endTime != position) {
				engine.seekTo(endTime);
				position = endTime;
			}
			prefs.persistProgress(vid, position);
			if (playbackService != null)
				playbackService.updateProgress(position, prefs.getSpeed(), true);
		}
		handler.postDelayed(this::saveProgress, 1000);
	}


	/**
	 * Sets up listeners for the media engine events.
	 * Configures playback state, navigation, and error handling.
	 */
	private void setupEngine() {
		Runnable playNext = () -> webview.evaluateJavascript("document.querySelector('#movie_player')?.nextVideo();", null);

		engine.addListener(new Player.Listener() {
			@Override
			public void onPlaybackStateChanged(int state) {
				if (state == Player.STATE_ENDED) playNext.run();
			}

			@Override
			public void onIsPlayingChanged(boolean isPlaying) {
				if (playbackService != null)
					playbackService.updateProgress(engine.position(), prefs.getSpeed(), isPlaying);
				if (isPlaying) handler.post(YoutubePlayer.this::saveProgress);
				else handler.removeCallbacks(YoutubePlayer.this::saveProgress);
			}
		});

		engine.setNavCallback(new PlayerEngine.NavigationCallback() {
			@Override
			public void onNext() {
				playNext.run();
			}

			@Override
			public void onPrev() {
				webview.evaluateJavascript("document.querySelector('#movie_player')?.previousVideo();", null);
			}
		});

		engine.setErrorListener(this::handleError);
	}

	/**
	 * Handles playback errors.
	 *
	 * @param e The exception that occurred
	 */
	@Override
	public void handleError(Exception e) {
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
	public void addListener(Player.Listener listener) {
		engine.addListener(listener);
	}

	/**
	 * Starts playback of a YouTube video from the given URL.
	 *
	 * @param url The YouTube video URL
	 */
	@Override
	public void play(String url) {
		var _vid = YoutubeExtractor.getVideoId(url);
		Log.d("YoutubePlayer", String.format(Locale.getDefault(), "play(%s), parsed vid: %s", url, _vid));
		if (vid != null && vid.equals(_vid)) return;
		vid = _vid;

		activity.runOnUiThread(() -> {
			if (task != null) task.cancel(true);
			engine.stop();
			engine.clearMediaItems();
			playerView.setVisibility(View.VISIBLE);
			setTitle("");
		});

		task = executor.submit(() -> {
			try {
				checkInterrupt();

				sponsor.load(vid);
				videoDetails = YoutubeExtractor.getVideoInfo(url);
				StreamDetails si = YoutubeExtractor.getStreamInfo(url);

				availableVideoStreams = PlayerUtils.filterBestStreams(si.getVideoStreams());
				si.setVideoStreams(availableVideoStreams);

				streamDetails = si;
				duration = videoDetails.getDuration() * 1000;

				VideoStream videoStream = PlayerUtils.selectVideoStream(availableVideoStreams, prefs.getQuality());
				this.audioStream = PlayerUtils.selectAudioStream(si.getAudioStreams(), prefs.getPreferredAudioTrack());

				long resume = prefs.getResumePosition(vid, duration);
				float speed = prefs.getSpeed();

				checkInterrupt();

				activity.runOnUiThread(() -> {
					try {
						setTitle(videoDetails.getTitle());
						updateSkipMarkers();
						TextView qualityView = playerView.findViewById(R.id.btn_quality);
						qualityView.setText(videoStream.getResolution());

						play(videoStream, this.audioStream, si.getDashUrl(), duration, resume, speed);
					} catch (Exception e) {
						handleError(e);
					}
				});
			} catch (Exception e) {
				handleError(e);
			}
		});
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
	public void setPlayerHeight(int height) {
		playerView.post(() -> {
			float density = activity.getResources().getDisplayMetrics().density;
			int deviceHeight = (int) (height * density);
			if (playerView.getLayoutParams().height == deviceHeight) return;
			playerView.getLayoutParams().height = deviceHeight;
			playerView.requestLayout();
		});
	}

	private void setTitle(String title) {
		TextView titleView = playerView.findViewById(R.id.tv_title);
		titleView.setText(title);
		titleView.setSelected(true);
	}

	/**
	 * Updates sponsor segment markers on the player UI.
	 * Displays sponsor segments on both the time bar and overlay.
	 */
	private void updateSkipMarkers() {
		List<long[]> segs = sponsor.getSegments();
		if (segs == null || segs.isEmpty()) return;

		List<long[]> validSegs = new ArrayList<>();
		for (long[] seg : segs)
			if (seg != null && seg.length >= 2) validSegs.add(seg);
		if (validSegs.isEmpty()) return;

		SponsorOverlayView layer = playerView.findViewById(R.id.sponsor_overlay);
		layer.setData(validSegs, duration);

		long[] times = new long[validSegs.size() * 2];
		for (int i = 0; i < validSegs.size(); i++) {
			times[i * 2] = validSegs.get(i)[0];
			times[i * 2 + 1] = validSegs.get(i)[1];
		}

		DefaultTimeBar bar = playerView.findViewById(R.id.exo_progress);
		bar.setAdGroupTimesMs(times, new boolean[times.length], times.length);
	}


	/**
	 * Shows error dialog with exception details.
	 *
	 * @param e The exception that occurred
	 */
	private void launchErrorActivity(Exception e) {
		ErrorDialog.show(activity, e.getMessage(), Log.getStackTraceString(e));
	}

	/**
	 * Attaches a playback service for background playback functionality.
	 *
	 * @param service The playback service instance
	 */
	@Override
	public void attachPlaybackService(PlaybackService service) {
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
	public void onQualitySelected(String res) {
		if (streamDetails == null) return;
		prefs.setQuality(res);

		if (availableVideoStreams.isEmpty()) return;

		if (streamDetails.getDashUrl() != null && !streamDetails.getDashUrl().isEmpty()) {
			int actualHeight = PlayerUtils.parseHeight(res);
			VideoStream match = PlayerUtils.selectVideoStream(availableVideoStreams, res);
			if (match != null) actualHeight = match.getHeight();
			engine.setVideoQuality(actualHeight);
		} else {
			VideoStream selectedStream = PlayerUtils.selectVideoStream(availableVideoStreams, res);
			if (selectedStream != null) {
				long pos = engine.position();
				AudioStream audioStream = streamDetails.getAudioStreams().get(0);
				engine.play(selectedStream, audioStream, null, streamDetails.getDashUrl(), duration, pos, prefs.getSpeed());
				TextView qualityView = playerView.findViewById(R.id.btn_quality);
				qualityView.setText(selectedStream.getResolution());
			}
		}
	}

	/**
	 * Updates playback progress in the attached playback service.
	 *
	 * @param position Current playback position in milliseconds
	 */
	@Override
	public void updateProgress(long position) {
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
	@Override
	public String getQuality() {
		return prefs.getQuality();
	}

	/**
	 * Handles playback speed selection from UI.
	 *
	 * @param speed Selected playback speed multiplier
	 */
	@Override
	public void onSpeedSelected(float speed) {
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
	public void play(VideoStream videoStream, AudioStream audioStream, String dashUrl, long duration, long resumePos, float speed) {
		this.audioStream = audioStream;
		activity.runOnUiThread(() -> {
			try {
				engine.play(videoStream, audioStream, videoDetails != null ? videoDetails.getSubtitles() : null, dashUrl, duration, resumePos, speed);
				this.duration = duration;
				prefs.setSpeed(speed);
				updateQualityDisplay();
				updateSpeedDisplay();

				if (videoDetails != null) {
					// Handle subtitles
					String savedSubtitle = prefs.getSubtitleLanguage();
					ImageButton subtitlesBtn = playerView.findViewById(R.id.btn_subtitles);
					if (savedSubtitle != null && !savedSubtitle.isEmpty() && videoDetails.getSubtitles() != null && !videoDetails.getSubtitles().isEmpty()) {
						engine.setSubtitleLanguage(savedSubtitle);
						subtitlesBtn.setImageResource(R.drawable.ic_subtitles_on);
					} else subtitlesBtn.setImageResource(R.drawable.ic_subtitles_off);

					// Show notification
					if (playbackService != null)
						playbackService.showNotification(videoDetails.getTitle(), videoDetails.getAuthor(), videoDetails.getThumbnail(), duration);
				}
			} catch (Exception e) {
				handleError(e);
			}
		});
	}

	/**
	 * Gets the list of available resolutions for the current video.
	 *
	 * @return List of resolution strings
	 */
	@Override
	public List<String> getAvailableResolutions() {
		List<String> resolutions = new ArrayList<>();
		for (VideoStream s : availableVideoStreams)
			resolutions.add(s.getResolution());
		return resolutions;
	}

	/**
	 * Updates the quality display in the UI.
	 */
	@Override
	public void updateQualityDisplay() {
		activity.runOnUiThread(() -> {
			TextView qualityView = playerView.findViewById(R.id.btn_quality);
			qualityView.setText(prefs.getQuality());
		});
	}

	/**
	 * Updates the speed display in the UI.
	 */
	@Override
	public void updateSpeedDisplay() {
		activity.runOnUiThread(() -> {
			TextView speedView = playerView.findViewById(R.id.btn_speed);
			speedView.setText(String.format(Locale.getDefault(), "%sx", prefs.getSpeed()));
		});
	}

	/**
	 * Gets the list of stream segments for the video.
	 *
	 * @return List of stream segments
	 */
	@Override
	public List<StreamSegment> getStreamSegments() {
		if (videoDetails != null && videoDetails.getSegments() != null && !videoDetails.getSegments().isEmpty())
			return videoDetails.getSegments();
		else {
			// Create default segment with video title at 0 seconds
			List<StreamSegment> defaultSegments = new ArrayList<>();
			if (videoDetails != null) defaultSegments.add(new StreamSegment(videoDetails.getTitle(), 0));
			return defaultSegments;
		}
	}

	@Override
	public void switchAudioTrack(AudioStream audioStream) {
		if (streamDetails == null || audioStream == null) return;

		// Get video stream, playback position, playback speed
		VideoStream videoStream = null;
		List<VideoStream> videoStreams = streamDetails.getVideoStreams();
		if (videoStreams != null && !videoStreams.isEmpty()) {
			// Try to find the playing video stream
			String quality = getQuality();
			for (VideoStream vs : videoStreams) {
				if (vs.getResolution().equals(quality)) {
					videoStream = vs;
					break;
				}
			}
			// If no match found, use the first video stream
			if (videoStream == null) videoStream = videoStreams.get(0);
		}

		if (videoStream == null) return;

		// Get playback position and speed
		long position = engine.position();
		float speed = engine.speed();

		// Replay video with new audio track
		play(videoStream, audioStream, streamDetails.getDashUrl(), duration, position, speed);
	}

	@Override
	public String getThumbnail() {
		return videoDetails.getThumbnail();
	}

	@Override
	public Format getVideoFormat() {
		return engine.getVideoFormat();
	}

	@Override
	public Format getAudioFormat() {
		return engine.getAudioFormat();
	}

	@Override
	public DecoderCounters getVideoDecoderCounters() {
		return engine.getVideoDecoderCounters();
	}

}