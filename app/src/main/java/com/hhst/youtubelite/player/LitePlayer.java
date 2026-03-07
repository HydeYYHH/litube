package com.hhst.youtubelite.player;

import android.app.Activity;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;import androidx.media3.common.Player;
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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import dagger.hilt.android.scopes.ActivityScoped;

@UnstableApi
@ActivityScoped
public class LitePlayer {
    private static final String KEY_LAST_AUDIO_LANG = "last_audio_lang";
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @NonNull private final Activity activity;
    @NonNull private final YoutubeExtractor extractor;
    @NonNull private final LitePlayerView playerView;
    @NonNull private final Controller controller;
    @NonNull private final Engine engine;
    @NonNull private final SponsorBlockManager sponsor;
    @NonNull private final Executor executor;
    private final MMKV kv = MMKV.defaultMMKV();
    @Nullable private PlaybackService playbackService;
    @Nullable private CompletableFuture<Void> cf;
    @Nullable private String vid = null;

    @Inject
    public LitePlayer(@NonNull final Activity activity, @NonNull final YoutubeExtractor extractor, @NonNull final LitePlayerView playerView, @NonNull final Controller controller, @NonNull final Engine engine, @NonNull final SponsorBlockManager sponsor, @NonNull final Executor executor) {
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
            @Override public void onIsPlayingChanged(boolean isPlaying) { updateServiceProgress(isPlaying); }
            @Override public void onTracksChanged(@NonNull Tracks tracks) { saveSelectedTrackLanguage(tracks); }
            @Override public void onPlaybackStateChanged(int state) { if (state == Player.STATE_READY) updateServiceProgress(engine.isPlaying()); }
            @Override public void onPlayerError(@NonNull PlaybackException error) { ErrorDialog.show(activity, error.getMessage(), Log.getStackTraceString(error)); }
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
        } catch (Exception ignored) {}
    }

    private void updateServiceProgress(boolean isPlaying) { if (playbackService != null) playbackService.updateProgress(engine.position(), engine.getPlaybackRate(), isPlaying); }
    public void attachPlaybackService(@Nullable PlaybackService service) { this.playbackService = service; if (service != null) service.initialize(engine); }

    private void applyAudioPreference(StreamDetails si) {
        List<AudioStream> audioStreams = si.getAudioStreams();
        if (audioStreams == null || audioStreams.isEmpty()) return;
        String savedLang = kv.decodeString(KEY_LAST_AUDIO_LANG, "und");
        List<AudioStream> mutableStreams = new ArrayList<>(audioStreams);
        Collections.sort(mutableStreams, (s1, s2) -> {
            String n1 = s1.getAudioTrackName() != null ? s1.getAudioTrackName().toLowerCase() : "";
            String n2 = s2.getAudioTrackName() != null ? s2.getAudioTrackName().toLowerCase() : "";
            boolean s1O = n1.contains("original");
            boolean s2O = n2.contains("original");
            if (s1O && !s2O) return -1; if (!s1O && s2O) return 1;
            String l1 = (s1.getAudioLocale() != null) ? s1.getAudioLocale().getLanguage() : "und";
            String l2 = (s2.getAudioLocale() != null) ? s2.getAudioLocale().getLanguage() : "und";
            boolean s1M = l1.equalsIgnoreCase(savedLang);
            boolean s2M = l2.equalsIgnoreCase(savedLang);
            if (s1M && !s2M) return -1; if (!s1M && s2M) return 1;
            return Integer.compare(s2.getAverageBitrate(), s1.getAverageBitrate());
        });
        si.getAudioStreams().clear(); si.getAudioStreams().addAll(mutableStreams);
    }

    public void play(String url) {
        final String videoId = YoutubeExtractor.getVideoId(url);
        if (videoId == null || Objects.equals(this.vid, videoId)) return;
        this.vid = videoId;
        activity.runOnUiThread(() -> { engine.clear(); playerView.setTitle(null); playerView.show(); });
        if (cf != null) cf.cancel(true);

        CompletableFuture<ExtractionResult> extractionFuture = CompletableFuture.supplyAsync(() -> {
            try {
                sponsor.load(videoId);
                VideoDetails vi = extractor.getVideoInfo(url);
                StreamDetails si = extractor.getStreamInfo(url);
                si.setVideoStreams(PlayerUtils.filterBestStreams(si.getVideoStreams()));
                applyAudioPreference(si);
                return new ExtractionResult(vi, si);
            } catch (Exception e) { throw new CompletionException(e); }
        }, executor);

        scheduler.schedule(() -> {
            if (!extractionFuture.isDone()) {
                extractionFuture.completeExceptionally(new java.util.concurrent.TimeoutException("Timeout"));
            }
        }, 10, TimeUnit.SECONDS);

        cf = extractionFuture.thenAccept(er -> activity.runOnUiThread(() -> {
            if (!Objects.equals(this.vid, videoId)) return;
            playerView.setTitle(er.vi.getTitle());
            playerView.updateSkipMarkers(er.vi.getDuration(), TimeUnit.SECONDS);
            engine.play(er.vi, er.si);
            if (playbackService != null) playbackService.showNotification(er.vi.getTitle(), er.vi.getAuthor(), er.vi.getThumbnail(), er.vi.getDuration() * 1000);
        })).exceptionally(e -> {
            activity.runOnUiThread(() -> { if (Objects.equals(this.vid, videoId)) ErrorDialog.show(activity, "Wait...", "Slow internet or extraction error."); });
            return null;
        });
    }

    public void hide() { this.vid = null; if (cf != null) cf.cancel(true); activity.runOnUiThread(() -> { playerView.hide(); engine.clear(); if (playbackService != null) playbackService.hideNotification(); }); }
    public boolean isPlaying() { return engine.isPlaying(); }
    public void pause() { engine.pause(); }
    public boolean isFullscreen() { return playerView.isFs(); }
    public void exitFullscreen() { controller.exitFullscreen(); }
    public void onPictureInPictureModeChanged(boolean pip) { controller.onPictureInPictureModeChanged(pip); }
    public void setHeight(int h) { playerView.post(() -> playerView.setHeight(h)); }
    public void release() { if (cf != null) cf.cancel(true); engine.release(); }

    private static class ExtractionResult {
        final VideoDetails vi;
        final StreamDetails si;
        ExtractionResult(VideoDetails vi, StreamDetails si) { this.vi = vi; this.si = si; }
    }
}