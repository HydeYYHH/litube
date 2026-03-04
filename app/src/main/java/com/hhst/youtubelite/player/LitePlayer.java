package com.hhst.youtubelite.player;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.DefaultTimeBar;

import com.hhst.youtubelite.PlaybackService;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.player.common.PlayerUtils;
import com.hhst.youtubelite.player.controller.Controller;
import com.hhst.youtubelite.player.engine.Engine;
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager;
import com.hhst.youtubelite.player.sponsor.SponsorOverlayView;
import com.hhst.youtubelite.extractor.ExtractionException;
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.extractor.VideoDetails;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.ui.ErrorDialog;

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
    private final SharedPreferences cachePrefs;

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
        this.cachePrefs = activity.getSharedPreferences("extraction_cache", Context.MODE_PRIVATE);
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

    private static class ExtractionResult {
        final VideoDetails vi;
        final StreamDetails si;
        ExtractionResult(VideoDetails vi, StreamDetails si) {
            this.vi = vi;
            this.si = si;
        }
    }

    /**
     * Moves the ORIGINAL audio track (no language code) to the TOP of the list.
     * This ensures the Engine always picks it as default, even when quality changes.
     */
    private void selectOriginalAudioTrack(StreamDetails si) {
        List<AudioStream> audioStreams = si.getAudioStreams();
        if (audioStreams == null || audioStreams.isEmpty()) return;

        AudioStream originalTrack = null;

        // Find the original track (usually the one with null locale)
        for (AudioStream stream : audioStreams) {
            if (stream.getAudioLocale() == null) {
                originalTrack = stream;
                break;                    // We only need the first one (original)
            }
        }

        if (originalTrack != null) {
            // Create new list with original at position 0
            List<AudioStream> newList = new ArrayList<>(audioStreams);
            newList.remove(originalTrack);
            newList.add(0, originalTrack);

            // Update the StreamDetails with the reordered list
            si.setAudioStreams(newList);
        }
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

                // ← This now guarantees original audio is at the top
                selectOriginalAudioTrack(si);

                return new ExtractionResult(vi, si);
            } catch (InterruptedException | InterruptedIOException e) {
                throw new CompletionException("interrupted", e);
            } catch (Exception e) {
                throw new ExtractionException("extract failed", e);
            }
        }, executor).thenAccept(er -> {
            activity.runOnUiThread(() -> {
                if (!Objects.equals(this.vid, videoId)) return;

                playerView.setTitle(er.vi.getTitle());
                playerView.updateSkipMarkers(er.vi.getDuration(), TimeUnit.SECONDS);

                engine.play(er.vi, er.si);

                if (playbackService != null) {
                    playbackService.showNotification(er.vi.getTitle(), er.vi.getAuthor(), er.vi.getThumbnail(), er.vi.getDuration() * 1000);
                }
            });
        }).exceptionally(e -> {
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

    public boolean isPlaying() { return engine.isPlaying(); }
    public void pause() { engine.pause(); }
    public boolean isFullscreen() { return playerView.isFs(); }
    public void exitFullscreen() { controller.exitFullscreen(); }
    public void onPictureInPictureModeChanged(final boolean isInPiP) {
        controller.onPictureInPictureModeChanged(isInPiP);
    }
    public void setHeight(int height) { playerView.post(() -> playerView.setHeight(height)); }
    public void release() {
        if (cf != null) cf.cancel(true);
        engine.release();
    }
}