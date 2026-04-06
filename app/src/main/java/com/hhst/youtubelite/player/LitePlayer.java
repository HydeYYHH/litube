package com.hhst.youtubelite.player;

import android.app.Activity;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.SubtitleView;

import com.hhst.youtubelite.PlaybackService;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.extractor.ExtractionException;
import com.hhst.youtubelite.extractor.ExtractionSession;
import com.hhst.youtubelite.extractor.PlaybackDetails;
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.player.common.PlayerLoopMode;
import com.hhst.youtubelite.player.common.PlayerUtils;
import com.hhst.youtubelite.player.controller.Controller;
import com.hhst.youtubelite.player.engine.Engine;
import com.hhst.youtubelite.player.queue.QueueItem;
import com.hhst.youtubelite.player.queue.QueueNav;
import com.hhst.youtubelite.player.queue.QueueRepository;
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager;
import com.hhst.youtubelite.player.sponsor.SponsorOverlayView;
import com.hhst.youtubelite.ui.ErrorDialog;
import com.hhst.youtubelite.util.DeviceUtils;
import com.hhst.youtubelite.util.ToastUtils;
import com.tencent.mmkv.MMKV;

import org.schabi.newpipe.extractor.stream.AudioStream;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import dagger.hilt.android.scopes.ActivityScoped;
import lombok.Getter;

@UnstableApi
@ActivityScoped
public class LitePlayer {

    public static final String KEY_LAST_AUDIO_LANG = "last_audio_lang";
    public static final String KEY_SUBTITLE_STYLE = "subtitle_style_id";
    public static final String KEY_SUBTITLE_CUSTOM_TEXT_SIZE = "subtitle_custom_text_size";
    public static final String KEY_SUBTITLE_CUSTOM_TEXT_COLOR = "subtitle_custom_text_color";
    public static final String KEY_SUBTITLE_CUSTOM_BG_COLOR = "subtitle_custom_bg_color";
    public static final String KEY_SUBTITLE_CUSTOM_BG_OPACITY = "subtitle_custom_bg_opacity";
    public static final String KEY_SUBTITLE_CUSTOM_EDGE_TYPE = "subtitle_custom_edge_type";
    public static final String KEY_SUBTITLE_CUSTOM_EDGE_COLOR = "subtitle_custom_edge_color";

    private static final String UNKNOWN_LANGUAGE = "und";
    private static final Locale NORMALIZATION_LOCALE = Locale.ROOT;

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
    @NonNull
    private final QueueRepository queueRepository;

    private final MMKV kv = MMKV.defaultMMKV();

    @Nullable
    private PlaybackService playbackService;
    @Nullable
    private CompletableFuture<Void> cf;
    @Nullable
    private String vid = null;
    @Nullable
    private volatile String loadedVideoId;
    @Nullable
    private String currentUrl = null;
    private int retryCount = 0;
    @Nullable
    private ExtractionSession extractionSession;
    @Nullable
    private Runnable onMiniPlayerRestore;
    @Nullable
    private Runnable onMiniPlayerClose;
    @Getter
    private boolean inAppMiniPlayer;
    private boolean wasInPictureInPicture;

    @Inject
    public LitePlayer(@NonNull final Activity activity,
                      @NonNull final YoutubeExtractor extractor,
                      @NonNull final LitePlayerView playerView,
                      @NonNull final Controller controller,
                      @NonNull final Engine engine,
                      @NonNull final SponsorBlockManager sponsor,
                      @NonNull final Executor executor,
                      @NonNull final QueueRepository queueRepository) {
        this.activity = activity;
        this.extractor = extractor;
        this.playerView = playerView;
        this.controller = controller;
        this.engine = engine;
        this.sponsor = sponsor;
        this.executor = executor;
        this.queueRepository = queueRepository;

        playerView.setup();
        setupEngineListeners();
    }

    private void setupEngineListeners() {
        engine.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) retryCount = 0;
                updateServiceProgress(isPlaying);
            }

            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
                saveSelectedTrackLanguage(tracks);
                applySubtitleStyle();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    updateServiceProgress(engine.isPlaying());
                    applySubtitleStyle();
                } else if (state == Player.STATE_ENDED) {
                    skipToNext();
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                invalidatePlaybackCacheIfSourceOpenFailure(error);
                if (shouldRetryOnSourceError(error) && retryCount < 3 && currentUrl != null) {
                    retryCount++;
                    final long pos = engine.position();
                    final String url = currentUrl;
                    vid = null;
                    play(url, pos);
                    return;
                }
                ErrorDialog.show(activity, error.getMessage(), error);
            }
        });
    }

    private boolean shouldRetryOnSourceError(@NonNull PlaybackException error) {
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                || error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
                || error.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED
                || error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
            return true;
        }
        Throwable cause = error.getCause();
        if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
            int code = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
            return code == 403 || code == 429 || code == 410;
        }
        return false;
    }

    public void skipToNext() {
        activity.runOnUiThread(() -> {
            if (!queueRepository.isEnabled()) {
                engine.skipToNext();
                return;
            }

            QueueItem next = queueRepository.findRelative(loadedVideoId, 1);
            if (next != null) {
                String nextUrl = next.getUrl();
                if (nextUrl != null) play(nextUrl);
            } else {
                engine.skipToNext();
            }
        });
    }

    public void skipToPrevious() {
        activity.runOnUiThread(() -> {
            if (!queueRepository.isEnabled()) {
                engine.skipToPrevious();
                return;
            }

            QueueItem prev = queueRepository.findRelative(loadedVideoId, -1);
            if (prev != null) {
                String prevUrl = prev.getUrl();
                if (prevUrl != null) play(prevUrl);
            } else {
                engine.skipToPrevious();
            }
        });
    }

    public void addToQueue(String url, @Nullable String title) {
        QueueItem item = new QueueItem();
        item.setUrl(url);
        item.setTitle(title != null ? title : "Loading...");
        item.setVideoId(YoutubeExtractor.getVideoId(url));
        queueRepository.add(item);
        ToastUtils.show(activity, R.string.queue_item_added);
    }

    public List<QueueItem> getQueue() {
        return queueRepository.getItems();
    }

    public void applySubtitleStyle() {
        playerView.post(() -> {
            SubtitleView subView = playerView.getCustomSubtitleView();
            if (subView == null) return;

            int styleId = kv.decodeInt(KEY_SUBTITLE_STYLE, 1);
            subView.setViewType(SubtitleView.VIEW_TYPE_CANVAS);
            subView.setApplyEmbeddedStyles(false);
            subView.setApplyEmbeddedFontSizes(false);
            subView.setBottomPaddingFraction(0.08f);

            if (styleId == 4) {
                float size = kv.decodeFloat(KEY_SUBTITLE_CUSTOM_TEXT_SIZE, 20f);
                int textColor = kv.decodeInt(KEY_SUBTITLE_CUSTOM_TEXT_COLOR, Color.WHITE);
                int bgColor = kv.decodeInt(KEY_SUBTITLE_CUSTOM_BG_COLOR, Color.BLACK);
                int opacity = kv.decodeInt(KEY_SUBTITLE_CUSTOM_BG_OPACITY, 128);
                int edgeType = kv.decodeInt(KEY_SUBTITLE_CUSTOM_EDGE_TYPE, CaptionStyleCompat.EDGE_TYPE_NONE);
                int edgeColor = kv.decodeInt(KEY_SUBTITLE_CUSTOM_EDGE_COLOR, Color.BLACK);

                int finalBgColor = Color.argb(opacity, Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor));
                subView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, size);

                CaptionStyleCompat style = new CaptionStyleCompat(textColor, finalBgColor, Color.TRANSPARENT, edgeType, edgeColor, null);
                subView.setStyle(style);
            } else {
                subView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
                CaptionStyleCompat style = switch (styleId) {
                    case 2 -> new CaptionStyleCompat(Color.YELLOW, Color.parseColor("#CC000000"), Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, Color.TRANSPARENT, null);
                    case 3 -> new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null);
                    default -> new CaptionStyleCompat(Color.WHITE, Color.parseColor("#CC000000"), Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, Color.TRANSPARENT, null);
                };
                subView.setStyle(style);
            }
            subView.invalidate();
            subView.requestLayout();
        });
    }

    private void saveSelectedTrackLanguage(Tracks tracks) {
        try {
            for (Tracks.Group group : tracks.getGroups()) {
                if (group.getType() == androidx.media3.common.C.TRACK_TYPE_AUDIO && group.isSelected()) {
                    for (int i = 0; i < group.length; i++) {
                        if (group.isTrackSelected(i)) {
                            String lang = group.getTrackFormat(i).language;
                            kv.encode(KEY_LAST_AUDIO_LANG, lang != null ? lang : UNKNOWN_LANGUAGE);
                            return;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void updateServiceProgress(boolean isPlaying) {
        if (playbackService != null)
            playbackService.updateProgress(engine.position(), engine.getPlaybackRate(), isPlaying);
    }

    public void attachPlaybackService(@Nullable PlaybackService service) {
        this.playbackService = service;
        if (service != null) {
            service.initialize(engine);
            refreshQueueNavigationAvailability();
        }
    }

    public void refreshQueueNavigationAvailability() {
        final QueueNav availability = engine.getQueueNavigationAvailability();
        activity.runOnUiThread(() -> controller.refreshQueueNavigationAvailability(availability));
        if (playbackService != null) {
            playbackService.updateQueueNavigationAvailability(availability);
        }
    }

    public void refreshInternalButtonVisibility() {
        controller.refreshInternalButtonVisibility();
    }

    private void applyAudioPreference(@NonNull final StreamDetails streamDetails) {
        final List<AudioStream> audioStreams = streamDetails.getAudioStreams();
        if (audioStreams == null || audioStreams.isEmpty()) return;

        final String savedLanguage = kv.decodeString(KEY_LAST_AUDIO_LANG, UNKNOWN_LANGUAGE);
        final List<AudioStream> reordered = new ArrayList<>(audioStreams);
        reordered.sort((first, second) -> compareAudioStreams(first, second, savedLanguage));
        streamDetails.setAudioStreams(reordered);
    }

    private static int compareAudioStreams(@NonNull final AudioStream first,
                                           @NonNull final AudioStream second,
                                           @NonNull final String savedLanguage) {
        final int originalComparison = Boolean.compare(
                isOriginalAudioTrack(second),
                isOriginalAudioTrack(first));
        if (originalComparison != 0) return originalComparison;

        final int savedLanguageComparison = Boolean.compare(
                matchesSavedLanguage(second, savedLanguage),
                matchesSavedLanguage(first, savedLanguage));
        if (savedLanguageComparison != 0) return savedLanguageComparison;

        return Integer.compare(second.getAverageBitrate(), first.getAverageBitrate());
    }

    private static boolean isOriginalAudioTrack(@NonNull final AudioStream audioStream) {
        final String trackName = audioStream.getAudioTrackName();
        return trackName != null && trackName.toLowerCase(NORMALIZATION_LOCALE).contains("original");
    }

    private static boolean matchesSavedLanguage(@NonNull final AudioStream audioStream,
                                                @NonNull final String savedLanguage) {
        return audioLanguage(audioStream).equalsIgnoreCase(savedLanguage);
    }

    @NonNull
    private static String audioLanguage(@NonNull final AudioStream audioStream) {
        return audioStream.getAudioLocale() != null
                ? audioStream.getAudioLocale().getLanguage()
                : UNKNOWN_LANGUAGE;
    }

    public void reload() {
        if (currentUrl != null) {
            final String url = currentUrl;
            this.vid = null;
            play(url);
        }
    }

    public void play(String url) {
        play(url, -1L);
    }

    public void play(String url, final long initialPositionMs) {
        this.currentUrl = url;
        final String videoId = YoutubeExtractor.getVideoId(url);
        if (videoId == null || (Objects.equals(this.vid, videoId) && initialPositionMs < 0L)) return;
        this.vid = videoId;
        activity.runOnUiThread(() -> {
            if (inAppMiniPlayer) exitInAppMiniPlayer();
            engine.clear();
            playerView.setTitle(null);
            final SponsorOverlayView layer = playerView.findViewById(R.id.sponsor_overlay);
            if (layer != null) layer.setData(null, 0, TimeUnit.MILLISECONDS);
            final DefaultTimeBar bar = playerView.findViewById(R.id.exo_progress);
            if (bar != null) bar.setAdGroupTimesMs(null, null, 0);
            playerView.show();
            controller.syncRotation(
                    DeviceUtils.isRotateOn(activity),
                    activity.getResources().getConfiguration().orientation);
        });

        cancelCurrentExtraction();
        if (cf != null) cf.cancel(true);
        final ExtractionSession session = new ExtractionSession();
        extractionSession = session;

        cf = CompletableFuture.supplyAsync(() -> {
            try {
                sponsor.load(videoId);
                if (session.isCancelled()) throw new InterruptedException("Extraction canceled");
                PlaybackDetails playbackDetails = extractor.getPlaybackDetails(url, session);
                StreamDetails streamDetails = playbackDetails.getStreamDetails();
                streamDetails.setVideoStreams(PlayerUtils.filterBestStreams(streamDetails.getVideoStreams()));
                applyAudioPreference(streamDetails);
                return playbackDetails;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException("Interrupted", e);
            } catch (InterruptedIOException e) {
                throw new CompletionException("Interrupted", e);
            } catch (Exception e) {
                throw new ExtractionException("Extract failed", e);
            }
        }, executor).thenAccept(er -> activity.runOnUiThread(() -> {
            if (this.extractionSession == session) this.extractionSession = null;
            if (!Objects.equals(this.vid, videoId)) return;
            this.loadedVideoId = videoId;
            playerView.setTitle(er.getVideoDetails().getTitle());
            
            final List<QueueItem> items = queueRepository.getItems();
            for (QueueItem item : items) {
                if (Objects.equals(item.getVideoId(), videoId)) {
                    item.setTitle(er.getVideoDetails().getTitle());
                    item.setAuthor(er.getVideoDetails().getAuthor());
                    item.setThumbnailUrl(er.getVideoDetails().getThumbnail());
                    queueRepository.add(item);
                    break;
                }
            }
            
            playerView.updateSkipMarkers(er.getVideoDetails().getDuration(), TimeUnit.SECONDS);

            engine.play(er.getVideoDetails(), er.getStreamDetails());
            if (initialPositionMs >= 0L) {
                engine.seekTo(initialPositionMs);
            }
            controller.updateSegmentsButtonState();
            controller.updateSubtitleButtonState();

            if (playbackService != null) {
                playbackService.showNotification(er.getVideoDetails().getTitle(), er.getVideoDetails().getAuthor(), er.getVideoDetails().getThumbnail(), er.getVideoDetails().getDuration() * 1000);
            }
            refreshQueueNavigationAvailability();
        })).exceptionally(e -> {
            if (this.extractionSession == session) this.extractionSession = null;
            Throwable cause = e instanceof CompletionException ? e.getCause() : e;
            if (cause instanceof ExtractionException) {
                activity.runOnUiThread(() -> {
                    if (!Objects.equals(this.vid, videoId)) return;
                    ErrorDialog.show(activity, cause.getMessage(), cause);
                });
            }
            return null;
        });
    }

    public void hide() {
        this.vid = null;
        this.loadedVideoId = null;
        cancelCurrentExtraction();
        if (cf != null) cf.cancel(true);
        activity.runOnUiThread(() -> {
            engine.clear();
            controller.clearRotation();
            exitInAppMiniPlayer();
            setMiniPlayerCallbacks(null, null);
            playerView.hide();
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

    public void seekToIfLoaded(final long positionMs) {
        if (loadedVideoId == null || positionMs < 0L) return;
        activity.runOnUiThread(() -> engine.seekTo(positionMs));
    }

    public boolean seekLoadedVideo(@Nullable final String url, final long positionMs) {
        if (positionMs < 0L || url == null) return false;
        final String videoId = YoutubeExtractor.getVideoId(url);
        if (videoId == null || !Objects.equals(loadedVideoId, videoId)) return false;
        seekToIfLoaded(positionMs);
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

    public void syncRotation(final boolean autoRotate, final int orientation) {
        controller.syncRotation(autoRotate, orientation);
    }

    public void enterPictureInPicture() {
        playerView.enterPiP();
    }

    public boolean canSuspendWatch() {
        return playerView.getVisibility() == View.VISIBLE;
    }

    public void enterInAppMiniPlayer() {
        inAppMiniPlayer = true;
        playerView.enterInAppMiniPlayer();
        controller.enterMiniPlayer();
    }

    public void exitInAppMiniPlayer() {
        inAppMiniPlayer = false;
        playerView.exitInAppMiniPlayer();
        controller.exitMiniPlayer();
    }

    public void restoreInAppMiniPlayerUiIfNeeded() {
        if (!inAppMiniPlayer) return;
        playerView.show();
        playerView.enterInAppMiniPlayer();
        controller.enterMiniPlayer();
    }

    public void suspendInAppMiniPlayerUiIfNeeded() {
        if (!inAppMiniPlayer) return;
        playerView.hide();
    }

    public void stopAndCloseFromMiniPlayer() {
        hide();
    }

    public void setMiniPlayerCallbacks(@Nullable final Runnable onRestore, @Nullable final Runnable onClose) {
        onMiniPlayerRestore = onRestore;
        onMiniPlayerClose = onClose;
        playerView.setMiniPlayerCallbacks(
                onRestore != null ? this::dispatchMiniPlayerRestore : null,
                onClose != null ? this::dispatchMiniPlayerClose : null);
    }

    public boolean shouldAutoEnterPictureInPicture() {
        return playerView.getVisibility() == View.VISIBLE;
    }

    public void onPictureInPictureModeChanged(final boolean isInPiP) {
        controller.onPictureInPictureModeChanged(isInPiP);
        playerView.onPiPModeChanged(isInPiP);
        if (wasInPictureInPicture && !isInPiP && inAppMiniPlayer && onMiniPlayerRestore != null) {
            dispatchMiniPlayerRestore();
        }
        wasInPictureInPicture = isInPiP;
    }

    public void setHeight(int height) {
        playerView.post(() -> playerView.setHeight(height));
    }

    @Nullable
    public String getLoadedVideoId() {
        return loadedVideoId;
    }

    @NonNull
    public PlayerLoopMode getLoopMode() {
        return controller.getLoopMode();
    }

    public void setLoopMode(@NonNull final PlayerLoopMode mode) {
        controller.setLoopMode(mode);
    }

    public void release() {
        cancelCurrentExtraction();
        if (cf != null) cf.cancel(true);
        loadedVideoId = null;
        wasInPictureInPicture = false;
        onMiniPlayerRestore = null;
        onMiniPlayerClose = null;
        activity.runOnUiThread(() -> playerView.setMiniPlayerCallbacks(null, null));
        inAppMiniPlayer = false;
        engine.release();
    }

    private void dispatchMiniPlayerRestore() {
        if (onMiniPlayerRestore != null) onMiniPlayerRestore.run();
    }

    private void dispatchMiniPlayerClose() {
        final Runnable onClose = onMiniPlayerClose;
        if (onClose == null) return;
        stopAndCloseFromMiniPlayer();
        onClose.run();
    }

    void invalidatePlaybackCacheIfSourceOpenFailure(@NonNull final PlaybackException error) {
        if (loadedVideoId == null) return;
        if (!isPlaybackSourceOpenFailure(error)) return;
        extractor.invalidatePlaybackCacheByVideoId(loadedVideoId);
    }

    static boolean isPlaybackSourceOpenFailure(@Nullable final Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof HttpDataSource.HttpDataSourceException httpException
                    && httpException.type == HttpDataSource.HttpDataSourceException.TYPE_OPEN) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void cancelCurrentExtraction() {
        if (extractionSession == null) return;
        extractionSession.cancel();
        extractionSession = null;
    }
}
