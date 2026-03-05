package com.hhst.youtubelite.player;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.DefaultTimeBar;

import com.hhst.youtubelite.PlaybackService;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.extractor.ExtractionException;
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.extractor.VideoDetails;
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
				ErrorDialog.show(activity, error.getMessage(), Log.getStackTraceString(error));
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

	private void applyAudioPreference(StreamDetails si) {
		List<AudioStream> audioStreams = si.getAudioStreams();
		if (audioStreams == null || audioStreams.isEmpty()) return;

		String savedLang = kv.decodeString(KEY_LAST_AUDIO_LANG, "und");
		List<AudioStream> mutableStreams = new ArrayList<>(audioStreams);

		mutableStreams.sort((s1, s2) -> {

			String n1 = s1.getAudioTrackName() != null ? s1.getAudioTrackName().toLowerCase() : "";
			String n2 = s2.getAudioTrackName() != null ? s2.getAudioTrackName().toLowerCase() : "";
			boolean s1IsOriginal = n1.contains("original");
			boolean s2IsOriginal = n2.contains("original");

			if (s1IsOriginal && !s2IsOriginal) return -1;
			if (!s1IsOriginal && s2IsOriginal) return 1;


			String l1 = (s1.getAudioLocale() != null) ? s1.getAudioLocale().getLanguage() : "und";
			String l2 = (s2.getAudioLocale() != null) ? s2.getAudioLocale().getLanguage() : "und";

			boolean s1Matches = l1.equalsIgnoreCase(savedLang);
			boolean s2Matches = l2.equalsIgnoreCase(savedLang);

			if (s1Matches && !s2Matches) return -1;
			if (!s1Matches && s2Matches) return 1;

			return Integer.compare(s2.getAverageBitrate(), s1.getAverageBitrate());
		});

		si.getAudioStreams().clear();
		si.getAudioStreams().addAll(mutableStreams);
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

		if (cf != null) cf.cancel(true);

		cf = CompletableFuture.supplyAsync(() -> {
			try {
				sponsor.load(videoId);
				VideoDetails vi = extractor.getVideoInfo(url);
				StreamDetails si = extractor.getStreamInfo(url);
				si.setVideoStreams(PlayerUtils.filterBestStreams(si.getVideoStreams()));

				applyAudioPreference(si);

				return new ExtractionResult(vi, si);
			} catch (InterruptedException | InterruptedIOException e) {
				throw new CompletionException("interrupted", e);
			} catch (Exception e) {
				throw new ExtractionException("extract failed", e);
			}
		}, executor).thenAccept(er -> activity.runOnUiThread(() -> {
			if (!Objects.equals(this.vid, videoId)) return;
			playerView.setTitle(er.vi.getTitle());
			playerView.updateSkipMarkers(er.vi.getDuration(), TimeUnit.SECONDS);

			engine.play(er.vi, er.si);

			if (playbackService != null) {
				playbackService.showNotification(er.vi.getTitle(), er.vi.getAuthor(), er.vi.getThumbnail(), er.vi.getDuration() * 1000);
			}
		})).exceptionally(e -> {
			Throwable cause = e instanceof CompletionException ? e.getCause() : e;
			if (cause instanceof ExtractionException) {
				activity.runOnUiThread(() -> {
					if (!Objects.equals(this.vid, videoId)) return;
					ErrorDialog.show(activity, cause.getMessage(), Log.getStackTraceString(cause));
				});
			}
			return null;
		});
	}

	public void hide() {
		this.vid = null;
		if (cf != null) cf.cancel(true);
		activity.runOnUiThread(() -> {
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

	public boolean isFullscreen() {
		return playerView.isFs();
	}

	public void exitFullscreen() {
		controller.exitFullscreen();
	}

	public void onPictureInPictureModeChanged(final boolean isInPiP) {
		controller.onPictureInPictureModeChanged(isInPiP);
	}

	public void setHeight(int height) {
		playerView.post(() -> playerView.setHeight(height));
	}

	public void release() {
		if (cf != null) cf.cancel(true);
		engine.release();
	}

	private record ExtractionResult(VideoDetails vi, StreamDetails si) {
	}
}