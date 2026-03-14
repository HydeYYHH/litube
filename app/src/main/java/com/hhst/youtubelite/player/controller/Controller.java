package com.hhst.youtubelite.player.controller;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import com.google.android.material.slider.Slider;

import com.hhst.youtubelite.R;
import com.hhst.youtubelite.browser.TabManager;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.player.LitePlayerView;
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
    @Getter @NonNull private final ExtensionManager extensionManager;

    @NonNull private final Handler handler = new Handler(Looper.getMainLooper());

    @Nullable private TextView hintText;

    @Getter private boolean isControlsVisible = false;
    @Setter private boolean longPress = false;

    private boolean isLocked = false;
    private long lastVideoRenderedCount = 0;
    private long lastFpsUpdateTime = 0;

    @NonNull private final Runnable hideControls = () -> setControlsVisible(false);

    private float fps = 0;

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
        playerView.post(() -> {
            setupHintOverlay();
            setupListeners();
            setupButtonListeners();
            updatePlayPauseButtons(engine.isPlaying());
            playerView.showController();
        });
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
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayPauseButtons(isPlaying);
                playerView.setKeepScreenOn(isPlaying);
                if (!isPlaying && isControlsVisible) hideControlsAutomatically();
            }
            @Override public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                    hideControlsAutomatically();
                } else if (playbackState == Player.STATE_BUFFERING && isControlsVisible) {
                    setControlsVisible(true);
                }
                if (playbackState == Player.STATE_READY) {
                    playerView.post(() -> {
                        updateSpeedButtonUI(engine.getPlaybackRate());
                        final TextView qualityView = playerView.findViewById(R.id.btn_quality);
                        if (qualityView != null) qualityView.setText(engine.getQuality());
                    });
                }
            }
            @Override public void onTracksChanged(@NonNull Tracks tracks) {
                updateSubtitleButtonState();
                playerView.post(() -> {
                    final TextView qualityView = playerView.findViewById(R.id.btn_quality);
                    if (qualityView != null) qualityView.setText(engine.getQuality());
                });
            }
            @Override public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
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
                if (!playerView.isFs()) enterFullscreen();
                else exitFullscreen();
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
        if (speedView != null) updateSpeedButtonUI(engine.getPlaybackRate());
        if (speedView != null) speedView.setOnClickListener(v -> showSpeedSliderDialog());
        if (qualityView != null) {
            qualityView.setText(engine.getQuality());
            qualityView.setOnClickListener(v -> {
                List<String> available = engine.getAvailableResolutions();
                if (available.isEmpty()) return;
                Map<String, String> map = new LinkedHashMap<>();
                for (String s : available)
                    map.merge(s.replaceAll("(?<=p)\\d+|\\s", ""), s, (o, n) -> n.contains("60") ? n : o);
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

    public void showSpeedSliderDialog() {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(activity);
        View v = activity.getLayoutInflater().inflate(R.layout.dialog_speed_slider, null);
        dialog.setContentView(v);

        BottomSheetBehavior.from((View) v.getParent()).setPeekHeight(ViewUtils.dpToPx(activity, 260));

        TextView speedText = v.findViewById(R.id.speed_value_text);
        Slider slider = v.findViewById(R.id.speed_slider);
        ImageButton btnMinus = v.findViewById(R.id.btn_speed_minus);
        ImageButton btnPlus = v.findViewById(R.id.btn_speed_plus);
        LinearLayout presetContainer = v.findViewById(R.id.preset_container);

        float currentSpeed = engine.getPlaybackRate();
        slider.setValue(currentSpeed);
        speedText.setText(String.format(Locale.getDefault(), "%.2fx", currentSpeed));

        java.util.function.Consumer<Float> updateSpeed = (val) -> {
            float newVal = Math.max(0.25f, Math.min(4.0f, Math.round(val * 100f) / 100f));
            slider.setValue(newVal);
            speedText.setText(String.format(Locale.getDefault(), "%.2fx", newVal));
            engine.setPlaybackRate(newVal);
            updateSpeedButtonUI(newVal);
            if (extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.REMEMBER_PLAYBACK_SPEED)) {
                prefs.setSpeed(newVal);
            }
        };

        slider.addOnChangeListener((s, value, fromUser) -> {
            if (fromUser) updateSpeed.accept(value);
        });

        btnMinus.setOnClickListener(view -> updateSpeed.accept(slider.getValue() - 0.05f));
        btnPlus.setOnClickListener(view -> updateSpeed.accept(slider.getValue() + 0.05f));

        for (int i = 0; i < presetContainer.getChildCount(); i++) {
            View child = presetContainer.getChildAt(i);
            child.setOnClickListener(btn -> {
                float speed = Float.parseFloat(btn.getTag().toString());
                updateSpeed.accept(speed);
            });
        }

        dialog.show();
    }

    public void updateSpeedButtonUI(float speed) {
        final TextView speedView = playerView.findViewById(R.id.btn_speed);
        if (speedView != null) speedView.setText(String.format(Locale.getDefault(), "%.2fx", speed));
    }

    private void setupSubtitleAndSegmentButtons() {
        final ImageButton subBtn = playerView.findViewById(R.id.btn_subtitles);
        updateSubtitleButtonState();
        if (subBtn != null) {
            subBtn.setOnClickListener(v -> {
                List<String> available = engine.getSubtitles();
                if (available.isEmpty()) return;
                if (engine.areSubtitlesEnabled()) {
                    engine.setSubtitlesEnabled(false);
                    updateSubtitleButtonState();
                    return;
                }
                String[] options = available.toArray(new String[0]);
                showSelectionPopup(subBtn, options, -1, (index, label) -> {
                    engine.setSubtitlesEnabled(true);
                    engine.setSubtitleLanguage(label);
                    updateSubtitleButtonState();
                });
            });
        }
        setClick(R.id.btn_segments, this::showSegmentsPopup);
    }

    public void updateSubtitleButtonState() {
        ImageButton subBtn = playerView.findViewById(R.id.btn_subtitles);
        if (subBtn == null) return;
        boolean hasSubtitles = !engine.getSubtitles().isEmpty();
        boolean isEnabled = engine.areSubtitlesEnabled();
        subBtn.setImageResource(isEnabled ? R.drawable.ic_subtitles_on : R.drawable.ic_subtitles_off);
        subBtn.setAlpha(hasSubtitles ? 1.0f : 0.7f);
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
        showSelectionPopup(anchor, titles, currentIdx, (index, label) -> engine.seekTo(segments.get(index).getStartTimeSeconds() * 1000L));
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
        ViewUtils.animateViewAlpha(other, alpha, View.GONE);
        ViewUtils.animateViewAlpha(center, alpha, View.GONE);
        ViewUtils.animateViewAlpha(bar, alpha, View.GONE);
        if (visible) hideControlsAutomatically();
    }

    private void hideControlsAutomatically() {
        handler.removeCallbacks(hideControls);
        if (engine.isPlaying()) handler.postDelayed(hideControls, CONTROLS_HIDE_DELAY_MS);
    }

    public void showHint(@NonNull String text, long durationMs) {
        if (hintText == null) return;
        hintText.setText(text);
        ViewUtils.animateViewAlpha(hintText, 1.0f, View.GONE);
        handler.removeCallbacks(this::hideHint);
        if (durationMs > 0) handler.postDelayed(this::hideHint, durationMs);
    }

    public void hideHint() {
        if (hintText != null) ViewUtils.animateViewAlpha(hintText, 0.0f, View.GONE);
    }

    private void setClick(int id, View.OnClickListener l) {
        View v = playerView.findViewById(id);
        if (v != null) v.setOnClickListener(l);
    }

    public void enterFullscreen() { playerView.enterFullscreen(false); }

    public void exitFullscreen() { playerView.exitFullscreen(); }

    private void showReset(boolean show) { }

    private void setupOverlayAndMoreButtons() { }

    private void showSelectionPopup(View v, String[] s, int i, SelectionCallback c) { }

    private interface SelectionCallback { void onSelected(int index, String label); default void onLongClick(int index, String label) {} }

}