package com.hhst.youtubelite.player;

import android.app.Activity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.DefaultTimeBar;

import com.hhst.youtubelite.PlaybackService;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.extractor.ExtractionSession;
import com.hhst.youtubelite.extractor.PlaybackDetails;
import com.hhst.youtubelite.extractor.PlaybackPlanner;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.extractor.exception.ExtractionException;
import com.hhst.youtubelite.extractor.exception.LoginRequiredExtractionException;
import com.hhst.youtubelite.player.common.PlayerLoopMode;
import com.hhst.youtubelite.player.common.PlayerPreferences;
import com.hhst.youtubelite.player.controller.Controller;
import com.hhst.youtubelite.player.engine.Engine;
import com.hhst.youtubelite.player.queue.QueueInvalidationListener;
import com.hhst.youtubelite.player.queue.QueueNav;
import com.hhst.youtubelite.player.queue.QueueRepository;
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager;
import com.hhst.youtubelite.player.sponsor.SponsorOverlayView;
import com.hhst.youtubelite.ui.ErrorDialog;
import com.hhst.youtubelite.util.DeviceUtils;
import com.tencent.mmkv.MMKV;

import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import dagger.hilt.android.scopes.ActivityScoped;
import lombok.Getter;

/**
 * Playback facade that tracks the current media session and UI state.
 */
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
	private final QueueRepository queueRepo;
	@NonNull
	private final PlayerPreferences prefs;
	@NonNull
	private final PlayerStateStore stateStore;
	@NonNull
	private final Executor executor;
	private final MMKV kv = MMKV.defaultMMKV();
	@Nullable
	private PlaybackService playbackSvc;
	@NonNull
	private final QueueInvalidationListener queueListener = this::refreshQueueNav;
	@Nullable
	private CompletableFuture<Void> task;
	@Nullable
	private String queuedId;
	@Nullable
	private volatile String activeId;
	@Nullable
	private ExtractionSession extractSession;
	@Nullable
	private Runnable onRestore;
	@Nullable
	private Runnable onClose;
	@Getter
	private boolean inMiniPlayer;
	private boolean wasInPip;

	@Inject
	public LitePlayer(@NonNull Activity activity,
	                  @NonNull YoutubeExtractor extractor,
	                  @NonNull LitePlayerView playerView,
	                  @NonNull Controller controller,
	                  @NonNull Engine engine,
	                  @NonNull SponsorBlockManager sponsor,
	                  @NonNull QueueRepository queueRepo,
	                  @NonNull PlayerPreferences prefs,
	                  @NonNull PlayerStateStore stateStore,
	                  @NonNull Executor executor) {
		this.activity = activity;
		this.extractor = extractor;
		this.playerView = playerView;
		this.controller = controller;
		this.engine = engine;
		this.sponsor = sponsor;
		this.queueRepo = queueRepo;
		this.prefs = prefs;
		this.stateStore = stateStore;
		this.executor = executor;
		playerView.setup();
		queueRepo.addListener(queueListener);
		setupEngineListeners();
	}

	private static boolean containsException(@Nullable Throwable throwable,
	                                         @NonNull Class<? extends Throwable> throwableClass) {
		if (throwable == null) return false;

		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Throwable> pending = new ArrayList<>();
		pending.add(throwable);

		for (int i = 0; i < pending.size(); i++) {
			Throwable th = pending.get(i);
			if (th == null || !visited.add(th)) continue;
			if (throwableClass.isInstance(th)) return true;

			Throwable cause = th.getCause();
			if (cause != null) pending.add(cause);
			Collections.addAll(pending, th.getSuppressed());
		}
		return false;
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
		if (playbackSvc != null) {
			playbackSvc.updateProgress(engine.position(), engine.getPlaybackRate(), isPlaying);
		}
	}

	public void attachPlaybackService(@Nullable PlaybackService service) {
		this.playbackSvc = service;
		if (service != null) {
			service.initialize(engine);
			refreshQueueNav();
		}
	}

	public void refreshQueueNav() {
		QueueNav availability = engine.getQueueNavigationAvailability();
		activity.runOnUiThread(() -> controller.refreshQueueNavigationAvailability(availability));
		if (playbackSvc != null) {
			playbackSvc.updateQueueNavigationAvailability(availability);
		}
	}

	public void play(String url) {
		String videoId = YoutubeExtractor.getVideoId(url);
		if (videoId == null || Objects.equals(this.queuedId, videoId)) return;
		this.queuedId = videoId;

		activity.runOnUiThread(() -> {
			engine.clear();
			playerView.setTitle(null);
			SponsorOverlayView layer = playerView.findViewById(R.id.sponsor_overlay);
			layer.setData(null, 0, TimeUnit.MILLISECONDS);
			DefaultTimeBar bar = playerView.findViewById(R.id.exo_progress);
			bar.setAdGroupTimesMs(null, null, 0);
			playerView.show();
			controller.syncRotation(
							DeviceUtils.isRotateOn(activity),
							activity.getResources().getConfiguration().orientation);
		});

		cancelExtraction();
		if (task != null) task.cancel(true);
		ExtractionSession session = new ExtractionSession();
		extractSession = session;

		task = CompletableFuture.runAsync(() -> {
							sponsor.load(videoId);
							if (session.isCancelled()) {
								throw new CompletionException(new InterruptedException("Extraction canceled"));
							}
						}, executor).thenCompose(ignored -> extractor.getInfo(url, session))
						.thenApply(details -> {
							String lang = kv.decodeString(KEY_LAST_AUDIO_LANG, "und");
							return new PlaybackDetails(
											details.video(),
											details.catalog(),
											details.deliveries(),
											PlaybackPlanner.plan(details.deliveries(), prefs.getQuality(), lang),
											details.segments(),
											details.subtitles());
						}).thenAccept(er -> activity.runOnUiThread(() -> {
							if (this.extractSession == session) this.extractSession = null;
							if (!Objects.equals(this.queuedId, videoId)) return;
							playerView.setTitle(er.video().getTitle());
							playerView.updateSkipMarkers(er.video().getDuration(), TimeUnit.SECONDS);

							try {
								engine.play(er);
							} catch (IllegalArgumentException e) {
								ErrorDialog.show(activity, e.getMessage(), e);
								return;
							}
							this.activeId = videoId;
							stateStore.setVideoId(videoId);

							if (playbackSvc != null) {
								playbackSvc.showNotification(er.video().getTitle(), er.video().getAuthor(), er.video().getThumbnailUrl(), er.video().getDuration() * 1000);
							}
							refreshQueueNav();
						})).exceptionally(e -> {
							if (this.extractSession == session) this.extractSession = null;
							Throwable cause = e instanceof CompletionException ? e.getCause() : e;
							if (cause instanceof CompletionException && cause.getCause() != null) {
								cause = cause.getCause();
							}
							if (cause instanceof Exception && !(cause instanceof ExtractionException)) {
								cause = classifyException((Exception) cause);
							}
							if (cause instanceof ExtractionException) {
								Throwable error = cause;
								activity.runOnUiThread(() -> {
									if (!Objects.equals(this.queuedId, videoId)) return;
									ErrorDialog.show(activity, error.getMessage(), error);
								});
							}
							return null;
						});
	}

	@NonNull
	private ExtractionException classifyException(@NonNull Exception exception) {
		if (containsException(exception, SignInConfirmNotBotException.class)) {
			return new LoginRequiredExtractionException(exception);
		}
		return new ExtractionException("Extract failed", exception);
	}

	public void hide() {
		this.queuedId = null;
		this.activeId = null;
		stateStore.setVideoId(null);
		cancelExtraction();
		if (task != null) task.cancel(true);
		activity.runOnUiThread(() -> {
			controller.clearRotation();
			exitInAppMiniPlayer();
			setMiniPlayerCallbacks(null, null);
			playerView.hide();
			engine.clear();
			if (playbackSvc != null) {
				playbackSvc.hideNotification();
			}
		});
	}

	public boolean isPlaying() {
		return engine.isPlaying();
	}

	public void pause() {
		engine.pause();
	}

	public boolean seekLoadedVideo(@Nullable String url, long positionMs) {
		if (positionMs < 0L || url == null) return false;
		String videoId = YoutubeExtractor.getVideoId(url);
		if (videoId == null || !Objects.equals(activeId, videoId)) return false;
		activity.runOnUiThread(() -> engine.seekTo(positionMs));
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

	public void exitFullscreenImmediately() {
		controller.exitFullscreenImmediately();
	}

	public void syncRotation(boolean autoRotate, int orientation) {
		controller.syncRotation(autoRotate, orientation);
	}

	public void enterPictureInPicture() {
		playerView.enterPiP();
	}

	public boolean shouldAutoEnterPictureInPicture() {
		return playerView.getVisibility() == View.VISIBLE;
	}

	public boolean canSuspendWatch() {
		return playerView.getVisibility() == View.VISIBLE;
	}

	public void enterInAppMiniPlayer() {
		inMiniPlayer = true;
		stateStore.setInMiniPlayer(true);
		playerView.enterInAppMiniPlayer();
		controller.enterMiniPlayer();
	}

	public void exitInAppMiniPlayer() {
		inMiniPlayer = false;
		stateStore.setInMiniPlayer(false);
		playerView.exitInAppMiniPlayer();
		controller.exitMiniPlayer();
	}

	public void restoreInAppMiniPlayerUiIfNeeded() {
		if (!inMiniPlayer) return;
		playerView.show();
		playerView.enterInAppMiniPlayer();
		controller.enterMiniPlayer();
	}

	public void suspendInAppMiniPlayerUiIfNeeded() {
		if (!inMiniPlayer) return;
		playerView.hide();
	}

	public void setMiniPlayerCallbacks(@Nullable Runnable onRestore, @Nullable Runnable onClose) {
		this.onRestore = onRestore;
		this.onClose = onClose;
		playerView.setMiniPlayerCallbacks(
						onRestore,
						onClose == null
										? null
										: () -> {
							hide();
							onClose.run();
						});
	}

	public void onPictureInPictureModeChanged(boolean isInPiP) {
		controller.onPictureInPictureModeChanged(isInPiP);
		if (wasInPip && !isInPiP && inMiniPlayer && onRestore != null) {
			onRestore.run();
		}
		wasInPip = isInPiP;
	}

	public void setHeight(int height) {
		playerView.post(() -> playerView.setHeight(height));
	}

	@Nullable
	public String getVideoId() {
		return activeId;
	}

	@NonNull
	public PlayerLoopMode getLoopMode() {
		return controller.getLoopMode();
	}

	public void setLoopMode(@NonNull PlayerLoopMode mode) {
		controller.setLoopMode(mode);
	}

	@Nullable
	public String getSubtitleLanguage() {
		return engine.getSubtitleLanguage();
	}

	public void setSubtitleLanguage(@Nullable String language) {
		engine.setSubtitleLanguage(language);
	}

	public void release() {
		cancelExtraction();
		if (task != null) task.cancel(true);
		activeId = null;
		queueRepo.removeListener(queueListener);
		stateStore.clear();
		wasInPip = false;
		onRestore = null;
		onClose = null;
		activity.runOnUiThread(() -> playerView.setMiniPlayerCallbacks(null, null));
		inMiniPlayer = false;
		engine.release();
	}

	private void cancelExtraction() {
		if (extractSession == null) return;
		extractSession.cancel();
		extractSession = null;
	}

	@Nullable
	public Runnable getOnClose() {
		return onClose;
	}

	public void setOnClose(@Nullable Runnable onClose) {
		this.onClose = onClose;
	}
}
