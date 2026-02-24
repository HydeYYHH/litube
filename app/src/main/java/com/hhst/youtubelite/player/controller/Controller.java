package com.hhst.youtubelite.player.controller;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Typeface;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.ListPopupWindow;
import android.widget.ListView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.ui.AspectRatioFrameLayout;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.browser.TabManager;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.player.LitePlayerView;
import com.hhst.youtubelite.player.common.Constant;
import com.hhst.youtubelite.player.common.PlayerPreferences;
import com.hhst.youtubelite.player.common.PlayerUtils;
import com.hhst.youtubelite.player.controller.gesture.PlayerGestureListener;
import com.hhst.youtubelite.player.controller.gesture.ZoomTouchListener;
import com.hhst.youtubelite.player.engine.Engine;
import com.hhst.youtubelite.util.DeviceUtils;
import com.hhst.youtubelite.util.ViewUtils;
import com.squareup.picasso.Picasso;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamSegment;
import org.schabi.newpipe.extractor.stream.VideoStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import dagger.hilt.android.scopes.ActivityScoped;
import lombok.Getter;
import lombok.Setter;

@ActivityScoped
@UnstableApi
public class Controller {
    private static final int HINT_PADDING_DP = 8;
    private static final int HINT_TOP_MARGIN_DP = 24;
    private static final int CONTROLS_HIDE_DELAY_MS = 1000;
    @NonNull private final Activity activity;
    @NonNull private final LitePlayerView playerView;
    @NonNull private final Engine engine;
    @NonNull private final ZoomTouchListener zoomListener;
    @NonNull private final PlayerPreferences prefs;
    @NonNull private final TabManager tabManager;
    @NonNull private final ExtensionManager extensionManager;
    @NonNull private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable private TextView hintText;
    @Getter private boolean isControlsVisible = false;
    @Setter private boolean longPress = false;
    private boolean isLocked = false;
    private long lastVideoRenderedCount = 0;
    private long lastFpsUpdateTime = 0;
    @NonNull private final Runnable hideControls = () -> setControlsVisible(false);
    private float fps = 0;
    private final OrientationEventListener orientationListener;

    @Inject
    public Controller(@NonNull final Activity activity, @NonNull final LitePlayerView playerView, @NonNull final Engine engine, @NonNull final PlayerPreferences prefs, @NonNull final ZoomTouchListener zoomListener, @NonNull final TabManager tabManager, @NonNull final ExtensionManager extensionManager) {
        this.activity = activity;
        this.playerView = playerView;
        this.engine = engine;
        this.prefs = prefs;
        this.zoomListener = zoomListener;
        this.tabManager = tabManager;
        this.extensionManager = extensionManager;
        this.zoomListener.setOnShowReset(show -> showReset(show && playerView.isFs() && isControlsVisible));

        orientationListener = new OrientationEventListener(activity, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN || isLocked) return;
                final int TOLERANCE = 10;
                boolean isPortrait = (orientation >= 360 - TOLERANCE || orientation <= TOLERANCE) || (orientation >= 180 - TOLERANCE && orientation <= 180 + TOLERANCE);
                boolean isLandscape = (orientation >= 90 - TOLERANCE && orientation <= 90 + TOLERANCE) || (orientation >= 270 - TOLERANCE && orientation <= 270 + TOLERANCE);

                if (isPortrait && playerView.isFs()) {
                    exitFullscreen();
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                } else if (isLandscape && !playerView.isFs()) {
                    playerView.enterFullscreen(PlayerUtils.isPortrait(engine));
                    playerView.setResizeMode(prefs.getResizeMode());
                    setControlsVisible(true);
                    final ImageButton fsBtn = playerView.findViewById(R.id.btn_fullscreen);
                    if (fsBtn != null) fsBtn.setImageResource(R.drawable.ic_fullscreen_exit);
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                }
            }
        };

        playerView.post(() -> {
            setupHintOverlay();
            setupListeners();
            setupButtonListeners();
            updatePlayPauseButtons(engine.isPlaying());
            playerView.showController();
            orientationListener.enable();
        });
    }

    public ExtensionManager getExtensionManager() {
        return extensionManager;
    }

    private void updatePlayPauseButtons(boolean isPlaying) {
        final View play = playerView.findViewById(R.id.btn_play);
        final View pause = playerView.findViewById(R.id.btn_pause);
        if (play != null) play.setVisibility(!isPlaying ? View.VISIBLE : View.GONE);
        if (pause != null) pause.setVisibility(isPlaying ? View.VISIBLE : View.GONE);
    }

    private void setupHintOverlay() {
        this.hintText = playerView.findViewById(R.id.hint_text);
        if (this.hintText != null) {
            final int pad = ViewUtils.dpToPx(activity, HINT_PADDING_DP);
            this.hintText.setPadding(pad, pad / 2, pad, pad / 2);
            final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) this.hintText.getLayoutParams();
            lp.topMargin = ViewUtils.dpToPx(activity, HINT_TOP_MARGIN_DP);
            this.hintText.setLayoutParams(lp);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupListeners() {
        final PlayerGestureListener gestureListener = new PlayerGestureListener(activity, playerView, engine, this);
        final GestureDetector detector = new GestureDetector(activity, gestureListener);
        playerView.setOnTouchListener((v, ev) -> {
            final int action = ev.getAction();
            if (action == MotionEvent.ACTION_DOWN && isControlsVisible) {
                handler.removeCallbacks(hideControls);
            }
            boolean handled = detector.onTouchEvent(ev);
            if (!handled && playerView.isFs()) zoomListener.onTouch(ev);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                gestureListener.onTouchRelease();
                if (longPress) {
                    longPress = false;
                    engine.setPlaybackRate(prefs.getSpeed());
                    hideHint();
                }
                if (isControlsVisible) hideControlsAutomatically();
            }
            return handled;
        });

        engine.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayPauseButtons(isPlaying);
                playerView.setKeepScreenOn(isPlaying);
                if (!isPlaying && isControlsVisible) hideControlsAutomatically();
            }
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                    hideControlsAutomatically();
                } else if (playbackState == Player.STATE_BUFFERING && isControlsVisible) {
                    setControlsVisible(true);
                }
                if (playbackState == Player.STATE_READY) {
                    playerView.post(() -> {
                        final TextView speedView = playerView.findViewById(R.id.btn_speed);
                        if (speedView != null) {
                            speedView.setText(String.format(Locale.getDefault(), "%sx", engine.getPlaybackRate()));
                        }
                        final TextView qualityView = playerView.findViewById(R.id.btn_quality);
                        if (qualityView != null) qualityView.setText(engine.getQuality());
                    });
                }
            }
            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
                updateSubtitleButtonState();
                playerView.post(() -> {
                    final TextView qualityView = playerView.findViewById(R.id.btn_quality);
                    if (qualityView != null) qualityView.setText(engine.getQuality());
                });
            }
            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    handler.removeCallbacks(hideControls);
                    setControlsVisible(true);
                }
            }
        });
    }

    private void setupButtonListeners() {
        setupPlaybackButtons();
        setupQualityAndSpeedButtons();
        setupSubtitleAndSegmentButtons();
        setupOverlayAndMoreButtons();
    }

    private void setupPlaybackButtons() {
        setClick(R.id.btn_play, v -> { engine.play(); setControlsVisible(true); });
        setClick(R.id.btn_pause, v -> { engine.pause(); setControlsVisible(true); });
        setClick(R.id.btn_prev, v -> { engine.skipToPrevious(); setControlsVisible(true); });
        setClick(R.id.btn_next, v -> { engine.skipToNext(); setControlsVisible(true); });

        final ImageButton lockBtn = playerView.findViewById(R.id.btn_lock);
        if (lockBtn != null) {
            lockBtn.setOnClickListener(v -> {
                isLocked = !isLocked;
                lockBtn.setImageResource(isLocked ? R.drawable.ic_lock : R.drawable.ic_unlock);
                setControlsVisible(true);
                showHint(activity.getString(isLocked ? R.string.lock_screen : R.string.unlock_screen), com.hhst.youtubelite.player.common.Constant.HINT_HIDE_DELAY_MS);
            });
        }

        final ImageButton fsBtn = playerView.findViewById(R.id.btn_fullscreen);
        if (fsBtn != null) {
            fsBtn.setOnClickListener(v -> {
                if (!playerView.isFs()) {
                    playerView.enterFullscreen(PlayerUtils.isPortrait(engine));
                    playerView.setResizeMode(prefs.getResizeMode());
                    setControlsVisible(true);
                    fsBtn.setImageResource(R.drawable.ic_fullscreen_exit);
                } else {
                    exitFullscreen();
                }
            });
        }

        final ImageButton loopBtn = playerView.findViewById(R.id.btn_loop);
        if (loopBtn != null) {
            boolean loopEnabled = prefs.isLoopEnabled();
            engine.setRepeatMode(loopEnabled ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
            loopBtn.setImageResource(loopEnabled ? R.drawable.ic_repeat_on : R.drawable.ic_repeat_off);
            loopBtn.setOnClickListener(v -> {
                boolean newEnabled = !prefs.isLoopEnabled();
                prefs.setLoopEnabled(newEnabled);
                engine.setRepeatMode(newEnabled ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
                loopBtn.setImageResource(newEnabled ? R.drawable.ic_repeat_on : R.drawable.ic_repeat_off);
                showHint(activity.getString(newEnabled ? R.string.repeat_on : R.string.repeat_off), com.hhst.youtubelite.player.common.Constant.HINT_HIDE_DELAY_MS);
                setControlsVisible(true);
            });
        }

        setClick(R.id.btn_reset, v -> zoomListener.reset());
    }

    private void setupQualityAndSpeedButtons() {
        final TextView speedView = playerView.findViewById(R.id.btn_speed);
        final TextView qualityView = playerView.findViewById(R.id.btn_quality);
        if (speedView != null) speedView.setText(String.format(Locale.getDefault(), "%sx", engine.getPlaybackRate()));

        if (speedView != null) {
            speedView.setOnClickListener(v -> {
                final float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f};
                final String[] options = new String[speeds.length];
                int checked = -1;
                float currentSpeed = engine.getPlaybackRate();
                for (int i = 0; i < speeds.length; i++) {
                    options[i] = speeds[i] + "x";
                    if (Math.abs(speeds[i] - currentSpeed) < 0.01) checked = i;
                }
                showSelectionPopup(v, options, checked, (index, label) -> {
                    engine.setPlaybackRate(speeds[index]);
                    prefs.setSpeed(speeds[index]);
                    speedView.setText(label);
                });
            });
        }

        if (qualityView != null) {
            qualityView.setText(engine.getQuality());
            qualityView.setOnClickListener(v -> {
                List<String> available = engine.getAvailableResolutions();
                if (available.isEmpty()) return;
                Map<String, String> map = new LinkedHashMap<>();
                for (String s : available) map.merge(s.replaceAll("(?<=p)\\d+|\\s", ""), s, (o, n) -> n.contains("60") ? n : o);
                String[] labels = map.keySet().toArray(new String[0]);
                String[] values = map.values().toArray(new String[0]);
                int checked = Arrays.asList(values).indexOf(engine.getQuality());
                showSelectionPopup(v, labels, checked, (index, label) -> {
                    String selected = values[index];
                    engine.onQualitySelected(selected);
                    prefs.setQuality(selected);
                    qualityView.setText(label);
                    String js = String.format("(function(t){const p=document.querySelector('#movie_player');const ls=p.getAvailableQualityLabels();const v=l=>parseInt(l.replace(/\\D/g,''));const target=v(t);const closest=ls.reduce((b,c,i)=>Math.abs(v(c)-target)<Math.abs(v(ls[b])-target)?i:b,0);const quality=p.getAvailableQualityLevels()[closest];p.setPlaybackQualityRange(quality,quality);})('%s')", label);
                    tabManager.evaluateJavascript(js, null);
                });
            });
        }
    }

    private void setupSubtitleAndSegmentButtons() {
        final ImageButton subBtn = playerView.findViewById(R.id.btn_subtitles);
        updateSubtitleButtonState();
        if (subBtn != null) {
            subBtn.setOnClickListener(v -> {
                List<String> available = engine.getSubtitles();
                if (available.isEmpty()) {
                    showHint(activity.getString(R.string.no_subtitles), com.hhst.youtubelite.player.common.Constant.HINT_HIDE_DELAY_MS);
                    hideControlsAutomatically();
                    return;
                }
                String[] options = available.toArray(new String[0]);
                String current = engine.getSelectedSubtitle();
                int checked = (engine.areSubtitlesEnabled() && current != null) ? available.indexOf(current) : -1;
                showSelectionPopup(subBtn, options, checked, (index, label) -> {
                    if (index == checked) {
                        engine.setSubtitlesEnabled(false);
                        showHint(activity.getString(R.string.subtitles_off), com.hhst.youtubelite.player.common.Constant.HINT_HIDE_DELAY_MS);
                    } else {
                        engine.setSubtitlesEnabled(true);
                        engine.setSubtitleLanguage(label);
                        showHint(activity.getString(R.string.subtitles_on) + ": " + label, com.hhst.youtubelite.player.common.Constant.HINT_HIDE_DELAY_MS);
                    }
                    updateSubtitleButtonState();
                });
            });
        }
        setClick(R.id.btn_segments, this::showSegmentsPopup);
    }

    private void showSegmentsPopup(@NonNull final View anchor) {
        List<StreamSegment> segments = engine.getSegments();
        String[] titles = new String[segments.size()];
        int currentIdx = -1;
        long posSec = engine.position() / 1000;
        for (int i = 0; i < segments.size(); i++) {
            StreamSegment seg = segments.get(i);
            titles[i] = DateUtils.formatElapsedTime(Math.max(seg.getStartTimeSeconds(), 0)) + " - " + seg.getTitle();
            if (posSec >= seg.getStartTimeSeconds()) currentIdx = i;
        }
        showSelectionPopup(anchor, titles, currentIdx, new SelectionCallback() {
            @Override public void onSelected(int index, String label) {
                engine.seekTo(segments.get(index).getStartTimeSeconds() * 1000L);
                showHint(activity.getString(R.string.jumped_to_segment, segments.get(index).getTitle()), com.hhst.youtubelite.player.common.Constant.HINT_HIDE_DELAY_MS);
            }
            @Override public void onLongClick(int index, String label) { showSegmentDetailsDialog(segments.get(index)); }
        });
    }

    private void updateSubtitleButtonState() {
        ImageButton subBtn = playerView.findViewById(R.id.btn_subtitles);
        if (subBtn == null) return;
        boolean hasSubtitles = !engine.getSubtitles().isEmpty();
        boolean isEnabled = engine.areSubtitlesEnabled();
        subBtn.setImageResource(isEnabled ? R.drawable.ic_subtitles_on : R.drawable.ic_subtitles_off);
        subBtn.setAlpha(hasSubtitles ? 1.0f : 0.7f);
    }

    private void setupOverlayAndMoreButtons() {
        setClick(R.id.btn_more, v -> {
            setControlsVisible(true);
            if (activity.isInPictureInPictureMode()) return;
            BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(activity);
            View bottomSheetView = activity.getLayoutInflater().inflate(R.layout.bottom_sheet_more_options, null, false);
            bottomSheetDialog.setContentView(bottomSheetView);

            FrameLayout bottomSheet = bottomSheetDialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
                bottomSheetView.measure(View.MeasureSpec.makeMeasureSpec(ViewUtils.getScreenWidth(activity), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                behavior.setPeekHeight(bottomSheetView.getMeasuredHeight());
            }

            if (activity instanceof LifecycleOwner lifecycleOwner) {
                LifecycleEventObserver observer = (source, event) -> { if (event == Lifecycle.Event.ON_PAUSE && activity.isInPictureInPictureMode()) bottomSheetDialog.dismiss(); };
                lifecycleOwner.getLifecycle().addObserver(observer);
                bottomSheetDialog.setOnDismissListener(dialog -> { lifecycleOwner.getLifecycle().removeObserver(observer); hideControlsAutomatically(); });
            } else {
                bottomSheetDialog.setOnDismissListener(dialog -> hideControlsAutomatically());
            }

            setupBottomSheetOption(bottomSheetView, R.id.option_resize_mode, b -> { showResizeModeOptions(); bottomSheetDialog.dismiss(); });
            setupBottomSheetOption(bottomSheetView, R.id.option_audio_track, b -> { showAudioTrackOptions(); bottomSheetDialog.dismiss(); });
            setupBottomSheetOption(bottomSheetView, R.id.option_pip, b -> { playerView.enterPiP(); bottomSheetDialog.dismiss(); });
            setupBottomSheetOption(bottomSheetView, R.id.option_stream_details, b -> { showVideoDetails(); bottomSheetDialog.dismiss(); });
            bottomSheetDialog.show();
        });
    }

    private void showAudioTrackOptions() {
        List<AudioStream> audioTracks = engine.getAvailableAudioTracks();
        if (audioTracks.isEmpty()) return;
        String[] options = new String[audioTracks.size()];
        for (int i = 0; i < audioTracks.size(); i++) {
            AudioStream s = audioTracks.get(i);
            int bitrate = s.getAverageBitrate() > 0 ? s.getAverageBitrate() : s.getBitrate();
            options[i] = s.getAudioTrackName() == null ? bitrate + "kbps" : String.format("%s (%s)", s.getAudioTrackName(), bitrate + "kbps");
        }
        int checked = -1;
        AudioStream current = engine.getAudioTrack();
        if (current != null) {
            for (int i = 0; i < audioTracks.size(); i++) if (audioTracks.get(i).getContent().equals(current.getContent())) checked = i;
        }
        new MaterialAlertDialogBuilder(activity).setTitle(R.string.audio_track).setAdapter(getAdapter(checked, options), (dialog, which) -> {
            engine.setAudioTrack(audioTracks.get(which));
            showHint(options[which], com.hhst.youtubelite.player.common.Constant.HINT_HIDE_DELAY_MS);
            hideControlsAutomatically();
        }).setNegativeButton(R.string.cancel, null).show();
    }

    @NonNull
    private ListAdapter getAdapter(int checkedItem, String[] options) {
        return new ArrayAdapter<>(activity, R.layout.dialog_resize_mode_item, options) {
            @NonNull @Override public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = (convertView == null) ? activity.getLayoutInflater().inflate(R.layout.dialog_resize_mode_item, parent, false) : convertView;
                ImageView icon = view.findViewById(R.id.icon);
                TextView text = view.findViewById(R.id.text);
                icon.setImageResource(R.drawable.ic_track);
                text.setText(getItem(position));
                TypedValue tv = new TypedValue();
                if (position == checkedItem) {
                    activity.getTheme().resolveAttribute(android.R.attr.colorPrimary, tv, true);
                    icon.setColorFilter(tv.data);
                    text.setTextColor(tv.data);
                    text.setTypeface(null, Typeface.BOLD);
                } else {
                    activity.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true);
                    icon.setColorFilter(activity.getColor(android.R.color.darker_gray));
                    text.setTextColor(tv.data);
                    text.setTypeface(null, Typeface.NORMAL);
                }
                return view;
            }
        };
    }

    private void showVideoDetails() {
        StreamDetails details = engine.getStreamDetails();
        if (details == null) { showHint(activity.getString(R.string.unable_to_get_stream_info), com.hhst.youtubelite.player.common.Constant.HINT_HIDE_DELAY_MS); hideControlsAutomatically(); return; }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
        builder.setTitle(R.string.info).setPositiveButton(R.string.confirm, null);
        String[] info = {getVideoDetailsText(details)};
        builder.setMessage(info[0]).setNeutralButton(R.string.copy, (dialog, which) -> {
            DeviceUtils.copyToClipboard(activity, "Video Details", info[0]);
            showHint(activity.getString(R.string.debug_info_copied), com.hhst.youtubelite.player.common.Constant.HINT_HIDE_DELAY_MS);
        });
        AlertDialog dialog = builder.show();
        hideControlsAutomatically();
        Handler updateHandler = new Handler(Looper.getMainLooper());
        updateHandler.post(new Runnable() {
            @Override public void run() { if (dialog.isShowing()) { info[0] = getVideoDetailsText(details); dialog.setMessage(info[0]); updateHandler.postDelayed(this, 1000); } }
        });
    }

    @NonNull
    private String getVideoDetailsText(@NonNull StreamDetails details) {
        StringBuilder sb = new StringBuilder();
        Format vF = engine.getVideoFormat();
        Format aF = engine.getAudioFormat();
        DecoderCounters counters = engine.getVideoDecoderCounters();
        if (counters != null) {
            long now = System.currentTimeMillis();
            if (lastFpsUpdateTime > 0) {
                long diff = now - lastFpsUpdateTime;
                if (diff >= 1000) { fps = ((counters.renderedOutputBufferCount - lastVideoRenderedCount) * 1000f) / diff; lastVideoRenderedCount = counters.renderedOutputBufferCount; lastFpsUpdateTime = now; }
            } else { lastVideoRenderedCount = counters.renderedOutputBufferCount; lastFpsUpdateTime = now; }
            sb.append(activity.getString(R.string.fps)).append(": ").append(String.format(Locale.getDefault(), "%.2f", fps)).append("\n");
            sb.append(activity.getString(R.string.dropped_frames)).append(": ").append(counters.droppedBufferCount).append("\n");
        }
        if (vF != null) sb.append(activity.getString(R.string.video_format)).append(": ").append(vF.sampleMimeType).append("\n").append(activity.getString(R.string.resolution)).append(": ").append(vF.width).append("x").append(vF.height).append("\n").append(activity.getString(R.string.bitrate)).append(": ").append(vF.bitrate / 1000).append(" kbps\n");
        if (aF != null) {
            sb.append(activity.getString(R.string.audio_format)).append(": ").append(aF.sampleMimeType).append("\n");
            if (aF.bitrate > 0) sb.append(activity.getString(R.string.bitrate)).append(": ").append(aF.bitrate / 1000).append(" kbps\n");
            if (aF.channelCount > 0) sb.append(activity.getString(R.string.channels)).append(": ").append(aF.channelCount).append("\n");
            if (aF.sampleRate > 0) sb.append(activity.getString(R.string.sample_rate)).append(": ").append(aF.sampleRate).append(" Hz\n");
        }
        String q = engine.getQuality();
        for (VideoStream vs : details.getVideoStreams()) if (vs.getResolution().equals(q)) { sb.append(activity.getString(R.string.active_stream_video, 1, activity.getString(R.string.active_label), vs.getResolution(), vs.getFormat() != null ? vs.getFormat().name() : activity.getString(R.string.unknown), vs.getCodec())).append("\n"); break; }
        int aIdx = engine.getSelectedAudioTrackIndex();
        if (aIdx >= 0) { AudioStream a = engine.getAvailableAudioTracks().get(aIdx); sb.append(activity.getString(R.string.active_stream_audio, a.getFormat() != null ? a.getFormat().name() : activity.getString(R.string.unknown), a.getCodec(), a.getAverageBitrate() > 0 ? a.getAverageBitrate() + "kbps" : activity.getString(R.string.unknown_bitrate))); }
        return sb.toString();
    }

    private void setupBottomSheetOption(@NonNull View root, int id, @NonNull View.OnClickListener l) {
        View o = root.findViewById(id);
        if (o != null) o.setOnClickListener(l);
    }

    private void toggleLock() {
        isLocked = !isLocked;
        ImageButton lockBtn = playerView.findViewById(R.id.btn_lock);
        if (lockBtn != null) lockBtn.setImageResource(isLocked ? R.drawable.ic_lock : R.drawable.ic_unlock);
    }

    public void exitFullscreen() {
        if (isLocked) toggleLock();
        playerView.exitFullscreen();
        zoomListener.reset();
        setControlsVisible(true);
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    public void onPictureInPictureModeChanged(boolean isInPiP) {
        playerView.onPictureInPictureModeChanged(isInPiP);
        setControlsVisible(!isInPiP);
    }

    public void setControlsVisible(boolean visible) {
        if (visible && activity.isInPictureInPictureMode()) return;
        this.isControlsVisible = visible;
        handler.removeCallbacks(hideControls);
        View center = playerView.findViewById(R.id.center_controls);
        View other = playerView.findViewById(R.id.other_controls);
        View bar = playerView.findViewById(R.id.exo_progress);
        float alpha = visible ? 1.0f : 0.0f;
        ImageButton lockBtn = playerView.findViewById(R.id.btn_lock);
        if (isLocked) { ViewUtils.animateViewAlpha(center, 0f, View.GONE); ViewUtils.animateViewAlpha(other, 0f, View.GONE); ViewUtils.animateViewAlpha(bar, 0f, View.GONE); showReset(false); }
        else {
            ViewUtils.animateViewAlpha(other, alpha, View.GONE);
            ViewUtils.animateViewAlpha(center, (visible && engine.getPlaybackState() != Player.STATE_BUFFERING) ? 1.0f : 0.0f, View.GONE);
            ViewUtils.animateViewAlpha(bar, alpha, View.GONE);
            showReset(playerView.isFs() && visible && zoomListener.isZoomed());
        }
        ViewUtils.animateViewAlpha(lockBtn, (visible && playerView.isFs()) ? 1.0f : 0.0f, View.GONE);
        if (visible) hideControlsAutomatically();
    }

    private void showReset(boolean show) {
        View btn = playerView.findViewById(R.id.btn_reset);
        if (btn != null) btn.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void hideControlsAutomatically() {
        handler.removeCallbacks(hideControls);
        if (engine.isPlaying()) handler.postDelayed(hideControls, CONTROLS_HIDE_DELAY_MS);
    }

    public void showHint(@NonNull String text, long durationMs) {
        if (activity.isInPictureInPictureMode() || hintText == null) return;
        hintText.setText(text);
        ViewUtils.animateViewAlpha(hintText, 1.0f, View.GONE);
        handler.removeCallbacks(this::hideHint);
        if (durationMs > 0) handler.postDelayed(this::hideHint, durationMs);
    }

    public void hideHint() {
        if (hintText != null) ViewUtils.animateViewAlpha(hintText, 0.0f, View.GONE);
    }

    private void showResizeModeOptions() {
        setControlsVisible(true);
        String[] opts = {activity.getString(R.string.resize_fit), activity.getString(R.string.resize_fill), activity.getString(R.string.resize_zoom), activity.getString(R.string.resize_fixed_width), activity.getString(R.string.resize_fixed_height)};
        int[] modes = {AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH, AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT};
        final ListAdapter adapter = getResizeAdapter(modes, opts);
        new MaterialAlertDialogBuilder(activity).setTitle(R.string.resize_mode).setAdapter(adapter, (d, w) -> {
            playerView.setResizeMode(modes[w]);
            prefs.setResizeMode(modes[w]);
            showHint(opts[w], com.hhst.youtubelite.player.common.Constant.HINT_HIDE_DELAY_MS);
            hideControlsAutomatically();
        }).setNegativeButton(R.string.cancel, null).show();
    }

    @NonNull
    private ListAdapter getResizeAdapter(@NonNull int[] modes, @NonNull String[] options) {
        int[] icons = {R.drawable.ic_resize_fit, R.drawable.ic_resize_fill, R.drawable.ic_resize_zoom, R.drawable.ic_resize_width, R.drawable.ic_resize_height};
        int currentMode = playerView.getResizeMode();
        int checked = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i] == currentMode) checked = i;
        final int finalChecked = checked;
        return new ArrayAdapter<>(activity, R.layout.dialog_resize_mode_item, options) {
            @NonNull @Override public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = (convertView == null) ? activity.getLayoutInflater().inflate(R.layout.dialog_resize_mode_item, parent, false) : convertView;
                ImageView icon = view.findViewById(R.id.icon);
                TextView text = view.findViewById(R.id.text);
                icon.setImageResource(icons[position]);
                text.setText(getItem(position));
                TypedValue tv = new TypedValue();
                if (position == finalChecked) { activity.getTheme().resolveAttribute(android.R.attr.colorPrimary, tv, true); icon.setColorFilter(tv.data); text.setTextColor(tv.data); text.setTypeface(null, Typeface.BOLD); }
                else { activity.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true); icon.setColorFilter(activity.getColor(android.R.color.darker_gray)); text.setTextColor(tv.data); text.setTypeface(null, Typeface.NORMAL); }
                return view;
            }
        };
    }

    private void showSelectionPopup(@NonNull View anchor, @NonNull String[] options, int checkedIndex, @NonNull SelectionCallback callback) {
        setControlsVisible(true);
        ListPopupWindow popup = new ListPopupWindow(activity);
        popup.setAnchorView(anchor);
        popup.setModal(true);
        ArrayAdapter<String> adapter = createSelectionAdapter(checkedIndex, options);
        popup.setAdapter(adapter);
        popup.setWidth(calculatePopupWidth(adapter, options.length));
        popup.setOnItemClickListener((p, v, pos, id) -> { callback.onSelected(pos, options[pos]); popup.dismiss(); hideControlsAutomatically(); });
        popup.show();
        ListView lv = popup.getListView();
        if (lv != null) lv.setOnItemLongClickListener((p, v, pos, id) -> { callback.onLongClick(pos, options[pos]); popup.dismiss(); return true; });
    }

    private ArrayAdapter<String> createSelectionAdapter(int checked, @NonNull String[] options) {
        return new ArrayAdapter<>(activity, R.layout.item_menu_list, options) {
            @NonNull @Override public View getView(int pos, @Nullable View conv, @NonNull ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, conv, parent);
                TypedValue out = new TypedValue();
                if (pos == checked) { activity.getTheme().resolveAttribute(android.R.attr.colorPrimary, out, true); tv.setTextColor(out.data); tv.setTypeface(null, Typeface.BOLD); }
                else { activity.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, out, true); tv.setTextColor(out.data); tv.setTypeface(null, Typeface.NORMAL); }
                return tv;
            }
        };
    }

    private int calculatePopupWidth(@NonNull ListAdapter adapter, int itemCount) {
        int maxWidth = 0;
        for (int i = 0; i < itemCount; i++) {
            View view = adapter.getView(i, null, new ListView(activity));
            view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            maxWidth = Math.max(maxWidth, view.getMeasuredWidth());
        }
        return Math.min(maxWidth, (int) (ViewUtils.getScreenWidth(activity) * 0.8));
    }

    private void showSegmentDetailsDialog(@NonNull StreamSegment seg) {
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(activity);
        View v = activity.getLayoutInflater().inflate(R.layout.dialog_segment, null, false);
        ((TextView)v.findViewById(R.id.segment_title)).setText(seg.getTitle());
        ((TextView)v.findViewById(R.id.segment_time)).setText(DateUtils.formatElapsedTime(Math.max(seg.getStartTimeSeconds(), 0)));
        Picasso.get().load(seg.getPreviewUrl() != null ? seg.getPreviewUrl() : engine.getThumbnail()).into((ImageView)v.findViewById(R.id.segment_thumbnail));
        b.setView(v).setPositiveButton(R.string.jump, (d, w) -> { engine.seekTo(seg.getStartTimeSeconds() * 1000L); showHint(activity.getString(R.string.jumped_to_segment, seg.getTitle()), com.hhst.youtubelite.player.common.Constant.HINT_HIDE_DELAY_MS); hideControlsAutomatically(); }).setNegativeButton(R.string.close, null).show();
    }

    private void setClick(int id, View.OnClickListener l) { View v = playerView.findViewById(id); if (v != null) v.setOnClickListener(l); }

    private interface SelectionCallback { void onSelected(int index, String label); default void onLongClick(int index, String label) {} }
}