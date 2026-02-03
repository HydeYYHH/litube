package com.hhst.youtubelite.player.controller;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.AspectRatioFrameLayout;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.browser.TabManager;
import com.hhst.youtubelite.player.LitePlayerView;
import com.hhst.youtubelite.player.common.Constant;
import com.hhst.youtubelite.player.common.PlayerPreferences;
import com.hhst.youtubelite.player.common.PlayerUtils;
import com.hhst.youtubelite.player.controller.gesture.PlayerGestureListener;
import com.hhst.youtubelite.player.controller.gesture.ZoomTouchListener;
import com.hhst.youtubelite.player.engine.Engine;
import com.hhst.youtubelite.util.ViewUtils;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamSegment;

import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import dagger.hilt.android.scopes.ActivityScoped;
import lombok.Getter;
import lombok.Setter;

@ActivityScoped
@UnstableApi
public class Controller {

    private static final int CONTROLS_HIDE_DELAY_MS = 3000;
    @NonNull private final Activity activity;
    @NonNull private final LitePlayerView playerView;
    @NonNull private final Engine engine;
    @NonNull private final ZoomTouchListener zoomListener;
    @NonNull private final PlayerPreferences prefs;
    @NonNull private final TabManager tabManager;
    @NonNull private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable private TextView hintText;
    @Getter private boolean isControlsVisible = false;
    @Setter private boolean longPress = false;
    private boolean isLocked = false;
    private boolean isLooping = false; // Fixed: Defined only once
    @NonNull private final Runnable hideControls = () -> setControlsVisible(false);

    @Inject
    public Controller(@NonNull final Activity activity, @NonNull final LitePlayerView playerView, @NonNull final Engine engine, @NonNull final PlayerPreferences prefs, @NonNull final ZoomTouchListener zoomListener, @NonNull final TabManager tabManager) {
        this.activity = activity;
        this.playerView = playerView;
        this.engine = engine;
        this.prefs = prefs;
        this.zoomListener = zoomListener;
        this.tabManager = tabManager;
        this.zoomListener.setOnShowReset(show -> showReset(show && playerView.isFs() && isControlsVisible));

        playerView.post(() -> {
            this.hintText = playerView.findViewById(R.id.hint_text);
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

    @SuppressLint("ClickableViewAccessibility")
    private void setupListeners() {
        final PlayerGestureListener gestureListener = new PlayerGestureListener(activity, playerView, engine, this);
        final GestureDetector detector = new GestureDetector(activity, gestureListener);

        playerView.setOnTouchListener((v, ev) -> {
            final int action = ev.getAction();
            if (action == MotionEvent.ACTION_DOWN && isControlsVisible) {
                handler.removeCallbacks(hideControls);
            }
            boolean detectorHandled = detector.onTouchEvent(ev);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                gestureListener.onTouchRelease();
                if (isControlsVisible) hideControlsAutomatically();
            }
            if (!detectorHandled && playerView.isFs()) zoomListener.onTouch(ev);
            return true;
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
                }
                if (playbackState == Player.STATE_READY) {
                    playerView.post(() -> {
                        final TextView speedView = playerView.findViewById(R.id.btn_speed);
                        if (speedView != null) speedView.setText(String.format(Locale.getDefault(), "%.2fx", engine.getPlaybackRate()));
                        final TextView qualityView = playerView.findViewById(R.id.btn_quality);
                        if (qualityView != null) qualityView.setText(engine.getQuality());
                    });
                }
            }
        });
    }

    private void setupButtonListeners() {
        setClick(R.id.btn_play, v -> { engine.play(); setControlsVisible(true); });
        setClick(R.id.btn_pause, v -> { engine.pause(); setControlsVisible(true); });
        setClick(R.id.btn_prev, v -> { engine.skipToPrevious(); setControlsVisible(true); });
        setClick(R.id.btn_next, v -> { engine.skipToNext(); setControlsVisible(true); });

        setClick(R.id.btn_speed, this::showSpeedPopup);
        setClick(R.id.btn_quality, this::showQualityPopup);
        setClick(R.id.btn_subtitles, v -> toggleCaptions());
        setClick(R.id.btn_segments, this::showSegmentsPopup);
        setClick(R.id.btn_more, this::showMoreOptionsSheet);

        final ImageButton lockBtn = playerView.findViewById(R.id.btn_lock);
        if (lockBtn != null) lockBtn.setOnClickListener(v -> toggleLock());

        final ImageButton fsBtn = playerView.findViewById(R.id.btn_fullscreen);
        if (fsBtn != null) fsBtn.setOnClickListener(v -> {
            if (!playerView.isFs()) {
                playerView.enterFullscreen(PlayerUtils.isPortrait(engine));
                fsBtn.setImageResource(R.drawable.ic_fullscreen_exit);
            } else {
                exitFullscreen();
                fsBtn.setImageResource(R.drawable.ic_fullscreen);
            }
        });

        setClick(R.id.btn_loop, v -> {
            isLooping = !isLooping;
            engine.setRepeatMode(isLooping ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
            showHint(isLooping ? "Loop On" : "Loop Off", Constant.HINT_HIDE_DELAY_MS);
            ((ImageButton)v).setImageResource(isLooping ? R.drawable.ic_repeat_on : R.drawable.ic_repeat_off);
        });
    }

    private void setClick(int id, View.OnClickListener l) {
        View v = playerView.findViewById(id);
        if (v != null) v.setOnClickListener(l);
    }

    private void toggleCaptions() {
        boolean enabled = !engine.areSubtitlesEnabled();
        engine.setSubtitlesEnabled(enabled);
        showHint(enabled ? "Captions On" : "Captions Off", 1000);
        ImageButton subBtn = playerView.findViewById(R.id.btn_subtitles);
        if (subBtn != null) {
            subBtn.setImageResource(enabled ? R.drawable.ic_subtitles_on : R.drawable.ic_subtitles_off);
        }
    }

    private void showSpeedPopup(View anchor) {
        final float[] speeds = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
        String[] options = new String[speeds.length];
        int checked = -1;
        for (int i = 0; i < speeds.length; i++) {
            options[i] = speeds[i] + "x";
            if (Math.abs(speeds[i] - engine.getPlaybackRate()) < 0.01) checked = i;
        }
        showSelectionPopup(anchor, options, checked, (index, label) -> {
            engine.setPlaybackRate(speeds[index]);
            if (anchor instanceof TextView) ((TextView)anchor).setText(label);
        });
    }

    private void showQualityPopup(View anchor) {
        List<String> available = engine.getAvailableResolutions();
        if (available.isEmpty()) return;
        String[] labels = available.toArray(new String[0]);
        showSelectionPopup(anchor, labels, available.indexOf(engine.getQuality()), (index, label) -> {
            engine.onQualitySelected(label);
            if (anchor instanceof TextView) ((TextView)anchor).setText(label);
        });
    }

    private void showSegmentsPopup(View anchor) {
        final List<StreamSegment> segments = engine.getSegments();
        if (segments.isEmpty()) {
            showHint("No Chapters Found", 1000);
            return;
        }
        String[] titles = new String[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            titles[i] = segments.get(i).getTitle();
        }
        showSelectionPopup(anchor, titles, -1, (index, label) -> {
            engine.seekTo(segments.get(index).getStartTimeSeconds() * 1000L);
        });
    }

    private void showMoreOptionsSheet(View v) {
        final BottomSheetDialog dialog = new BottomSheetDialog(activity);
        View view = activity.getLayoutInflater().inflate(R.layout.bottom_sheet_more_options, null);
        dialog.setContentView(view);

        View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) {
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottomSheet);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
        }

        setupBottomSheetOption(view, R.id.option_pip, b -> {
            playerView.enterPiP();
            dialog.dismiss();
        });

        setupBottomSheetOption(view, R.id.option_resize_mode, b -> {
            showResizeModeOptions();
            dialog.dismiss();
        });

        setupBottomSheetOption(view, R.id.option_audio_track, b -> {
            showAudioTrackOptions();
            dialog.dismiss();
        });

        // Info button removed as requested
        View infoOpt = view.findViewById(R.id.option_stream_details);
        if (infoOpt != null) infoOpt.setVisibility(View.GONE);

        dialog.show();
    }

    private void setupBottomSheetOption(View root, int id, View.OnClickListener l) {
        View opt = root.findViewById(id);
        if (opt != null) opt.setOnClickListener(l);
    }

    private void showAudioTrackOptions() {
        List<AudioStream> audioTracks = engine.getAvailableAudioTracks();
        if (audioTracks.isEmpty()) {
            showHint("No Audio Tracks", 1000);
            return;
        }
        String[] options = new String[audioTracks.size()];
        for (int i = 0; i < audioTracks.size(); i++) {
            AudioStream s = audioTracks.get(i);
            options[i] = s.getAverageBitrate() > 0 ? s.getAverageBitrate() + " kbps" : s.getBitrate() + " kbps";
        }
        showSelectionPopup(playerView.findViewById(R.id.btn_more), options, -1, (index, label) -> {
            engine.setAudioTrack(audioTracks.get(index));
            showHint("Switched Audio: " + label, 1000);
        });
    }

    private void showResizeModeOptions() {
        final String[] options = {"Fit", "Fill", "Zoom", "Stretch"};
        final int[] modes = {AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH};

        new MaterialAlertDialogBuilder(activity)
                .setTitle("Resize Mode")
                .setItems(options, (dialog, which) -> playerView.setResizeMode(modes[which]))
                .show();
    }

    private void showSelectionPopup(View anchor, String[] options, int checkedIndex, SelectionCallback callback) {
        final ListPopupWindow popup = new ListPopupWindow(activity);
        popup.setAnchorView(anchor);
        popup.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, options));
        popup.setWidth(ViewUtils.dpToPx(activity, 180));
        popup.setModal(true);
        popup.setOnItemClickListener((parent, view, position, id) -> {
            callback.onSelected(position, options[position]);
            popup.dismiss();
            hideControlsAutomatically();
        });
        popup.show();
    }

    private void toggleLock() {
        isLocked = !isLocked;
        final ImageButton lockBtn = playerView.findViewById(R.id.btn_lock);
        if (lockBtn != null) {
            lockBtn.setImageResource(isLocked ? R.drawable.ic_lock : R.drawable.ic_unlock);
        }
        setControlsVisible(true);
    }

    public void exitFullscreen() {
        playerView.exitFullscreen();
        zoomListener.reset();
        setControlsVisible(true);
    }

    public void setControlsVisible(boolean visible) {
        this.isControlsVisible = visible;
        handler.removeCallbacks(hideControls);
        View center = playerView.findViewById(R.id.center_controls);
        View other = playerView.findViewById(R.id.other_controls);
        float alpha = visible ? 1.0f : 0.0f;
        if (center != null) ViewUtils.animateViewAlpha(center, alpha, visible ? View.VISIBLE : View.GONE);
        if (other != null) ViewUtils.animateViewAlpha(other, alpha, visible ? View.VISIBLE : View.GONE);
        if (visible) hideControlsAutomatically();
    }

    private void hideControlsAutomatically() {
        handler.removeCallbacks(hideControls);
        if (engine.isPlaying()) handler.postDelayed(hideControls, CONTROLS_HIDE_DELAY_MS);
    }

    public void showHint(@NonNull String text, long durationMs) {
        if (hintText == null) return;
        hintText.setText(text);
        hintText.setVisibility(View.VISIBLE);
        hintText.setAlpha(1.0f);
        handler.removeCallbacks(this::hideHint);
        if (durationMs > 0) handler.postDelayed(this::hideHint, durationMs);
    }

    public void hideHint() {
        if (hintText != null) hintText.setVisibility(View.GONE);
    }

    private void showReset(boolean show) {
        final View resetBtn = playerView.findViewById(R.id.btn_reset);
        if (resetBtn != null) resetBtn.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private interface SelectionCallback {
        void onSelected(int index, String label);
    }
}