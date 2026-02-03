package com.hhst.youtubelite.player;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
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

import java.io.InterruptedIOException;
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

    @NonNull private final Activity activity;
    @NonNull private final YoutubeExtractor extractor;
    @NonNull private final LitePlayerView playerView;
    @NonNull private final Controller controller;
    @NonNull private final Engine engine;
    @NonNull private final SponsorBlockManager sponsor;
    @NonNull private final Executor executor;

    @Nullable private PlaybackService playbackService;
    @Nullable private CompletableFuture<Void> cf;
    @Nullable private String vid = null;

    @Inject
    public LitePlayer(@NonNull Activity activity,
                      @NonNull YoutubeExtractor extractor,
                      @NonNull LitePlayerView playerView,
                      @NonNull Controller controller,
                      @NonNull Engine engine,
                      @NonNull SponsorBlockManager sponsor,
                      @NonNull Executor executor) {
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
            public void onIsPlayingChanged(boolean isPlaying) { updateServiceProgress(isPlaying); }
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) updateServiceProgress(engine.isPlaying());
            }
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                ErrorDialog.show(activity, error.getMessage(), Log.getStackTraceString(error));
            }
        });
    }

    private void updateServiceProgress(boolean isPlaying) {
        if (playbackService != null)
            playbackService.updateProgress(engine.position(), engine.getPlaybackRate(), isPlaying);
    }

    public void attachPlaybackService(@Nullable PlaybackService service) {
        this.playbackService = service;
        if (service != null) service.initialize(engine);
    }

    private record ExtractionResult(VideoDetails vi, StreamDetails si) {}

    public void play(String url) {
        final String vid = YoutubeExtractor.getVideoId(url);
        if (vid == null || Objects.equals(this.vid, vid)) return;
        this.vid = vid;

        activity.runOnUiThread(() -> {
            engine.clear();
            playerView.setTitle(null);
            final SponsorOverlayView layer = playerView.findViewById(R.id.sponsor_overlay);
            if (layer != null) layer.setData(null, 0, TimeUnit.MILLISECONDS);
            final DefaultTimeBar bar = playerView.findViewById(R.id.exo_progress);
            if (bar != null) bar.setAdGroupTimesMs(null, null, 0);
            playerView.show();
        });

        if (cf != null) cf.cancel(true);

        cf = CompletableFuture.supplyAsync(() -> {
            try {
                sponsor.load(vid);
                VideoDetails vi = extractor.getVideoInfo(url);
                StreamDetails si = extractor.getStreamInfo(url);
                si.setVideoStreams(PlayerUtils.filterBestStreams(si.getVideoStreams()));
                return new ExtractionResult(vi, si);
            } catch (InterruptedException | InterruptedIOException e) {
                throw new CompletionException("interrupted", e);
            } catch (Exception e) {
                throw new ExtractionException("extract failed", e);
            }
        }, executor).thenAccept(er -> {
            final VideoDetails vi = er.vi();
            final StreamDetails si = er.si();
            activity.runOnUiThread(() -> {
                if (!Objects.equals(this.vid, vid)) return;
                playerView.setTitle(vi.getTitle());
                playerView.updateSkipMarkers(vi.getDuration(), TimeUnit.SECONDS);
                engine.play(vi, si);
                if (playbackService != null)
                    playbackService.showNotification(vi.getTitle(), vi.getAuthor(), vi.getThumbnail(), vi.getDuration()*1000);
            });
        }).exceptionally(e -> {
            if (e instanceof ExtractionException) {
                activity.runOnUiThread(() -> {
                    if (!Objects.equals(this.vid, vid)) return;
                    ErrorDialog.show(activity, e.getMessage(), Log.getStackTraceString(e));
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
            if (playbackService != null) playbackService.hideNotification();
        });
    }

    public boolean isPlaying() { return engine.isPlaying(); }
    public void pause() { engine.pause(); }

    public boolean isFullscreen() { return playerView.isFs(); }
    public void exitFullscreen() { playerView.exitFullscreen(); }

    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        playerView.onPictureInPictureModeChanged(isInPictureInPictureMode);
    }

    public void setHeight(int height) { playerView.post(() -> playerView.setHeight(height)); }

    public void release() {
        if (cf != null) cf.cancel(true);
        engine.release();
    }
}