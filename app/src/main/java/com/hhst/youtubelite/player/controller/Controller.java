package com.hhst.youtubelite.player.controller;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
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
import androidx.media3.ui.CaptionStyleCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.browser.TabManager;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.player.LitePlayer;
import com.hhst.youtubelite.player.LitePlayerView;
import com.hhst.youtubelite.player.common.PlayerPreferences;
import com.hhst.youtubelite.player.common.PlayerUtils;
import com.hhst.youtubelite.player.controller.gesture.PlayerGestureListener;
import com.hhst.youtubelite.player.controller.gesture.ZoomTouchListener;
import com.hhst.youtubelite.player.engine.Engine;
import com.hhst.youtubelite.util.DeviceUtils;
import com.hhst.youtubelite.util.ViewUtils;
import com.squareup.picasso.Picasso;
import com.tencent.mmkv.MMKV;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamSegment;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

import dagger.Lazy;
import dagger.hilt.android.scopes.ActivityScoped;
import lombok.Getter;
import lombok.Setter;

@ActivityScoped
@UnstableApi
public class Controller {
    private static final int HINT_PADDING_DP = 8;
    private static final int HINT_TOP_MARGIN_DP = 24;
    private static final int CONTROLS_HIDE_DELAY_MS = 1500;

    @NonNull private final Activity activity;
    @NonNull private final LitePlayerView playerView;
    @NonNull private final Engine engine;
    @NonNull private final ZoomTouchListener zoomListener;
    @NonNull private final PlayerPreferences prefs;
    @NonNull private final TabManager tabManager;
    @NonNull private final Lazy<LitePlayer> playerLazy;
    @Getter @NonNull private final ExtensionManager extensionManager;

    @NonNull private final Handler handler = new Handler(Looper.getMainLooper());
    private final MMKV kv = MMKV.defaultMMKV();

    @Nullable private TextView hintText;

    @Getter private boolean isControlsVisible = false;
    @Setter private boolean longPress = false;

    @Getter private boolean isLocked = false;
    private long lastVideoRenderedCount = 0;
    private long lastFpsUpdateTime = 0;
    private float fps = 0;

    @NonNull private final Runnable hideControls = () -> setControlsVisible(false);

    @Inject
    public Controller(@NonNull final Activity activity, @NonNull final LitePlayerView playerView, @NonNull final Engine engine, @NonNull final Lazy<LitePlayer> playerLazy, @NonNull final PlayerPreferences prefs, @NonNull final ZoomTouchListener zoomListener, @NonNull final TabManager tabManager, @NonNull final ExtensionManager extensionManager) {
        this.activity = activity;
        this.playerView = playerView;
        this.engine = engine;
        this.playerLazy = playerLazy;
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

            if (isLocked) {
                if (action == MotionEvent.ACTION_UP) {
                    setControlsVisible(!isControlsVisible);
                }
                return true;
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
        });
    }

    private void setupButtonListeners() {
        setClick(R.id.btn_play, v -> { engine.play(); setControlsVisible(true); });
        setClick(R.id.btn_pause, v -> { engine.pause(); setControlsVisible(true); });
        setClick(R.id.btn_prev, v -> { engine.skipToPrevious(); setControlsVisible(true); });
        setClick(R.id.btn_next, v -> { engine.skipToNext(); setControlsVisible(true); });
        setClick(R.id.btn_speed, v -> showSpeedSliderDialog());
        setClick(R.id.btn_quality, this::showQualityPopup);
        setClick(R.id.btn_subtitles, v -> toggleSubtitles());
        setClick(R.id.btn_segments, this::showSegmentsPopup);
        setClick(R.id.btn_reset, v -> zoomListener.reset());
        setClick(R.id.btn_reload, v -> playerLazy.get().reload());
        setupOverlayAndMoreButtons();
        final ImageButton lockBtn = playerView.findViewById(R.id.btn_lock);
        if (lockBtn != null) {
            lockBtn.setOnClickListener(v -> toggleLock());
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
                showHint(activity.getString(newEnabled ? R.string.repeat_on : R.string.repeat_off), 1000);
                setControlsVisible(true);
            });
        }
    }

    public void showSpeedSliderDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        View v = activity.getLayoutInflater().inflate(R.layout.dialog_speed_slider, null);
        dialog.setContentView(v);

        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from((View) v.getParent());
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);

        TextView speedText = v.findViewById(R.id.speed_value_text);
        Slider slider = v.findViewById(R.id.speed_slider);
        ImageButton btnMinus = v.findViewById(R.id.btn_speed_minus);
        ImageButton btnPlus = v.findViewById(R.id.btn_speed_plus);
        LinearLayout presetContainer = v.findViewById(R.id.preset_container);
        float currentSpeed = engine.getPlaybackRate();
        slider.setValue(currentSpeed);
        speedText.setText(String.format(Locale.getDefault(), "%.2fx", currentSpeed));
        java.util.function.Consumer<Float> updateSpeed = (val) -> {
            float newVal = Math.max(0.25f, Math.min(4.0f, val));
            slider.setValue(newVal);
            speedText.setText(String.format(Locale.getDefault(), "%.2fx", newVal));
            engine.setPlaybackRate(newVal);
            updateSpeedButtonUI(newVal);
            if (extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.REMEMBER_PLAYBACK_SPEED)) {
                prefs.setSpeed(newVal);
            }
        };
        slider.addOnChangeListener((s, value, fromUser) -> { if (fromUser) updateSpeed.accept(value); });
        btnMinus.setOnClickListener(view -> updateSpeed.accept(slider.getValue() - 0.05f));
        btnPlus.setOnClickListener(view -> updateSpeed.accept(slider.getValue() + 0.05f));
        if (presetContainer != null) {
            for (int i = 0; i < presetContainer.getChildCount(); i++) {
                View child = presetContainer.getChildAt(i);
                child.setOnClickListener(btn -> {
                    float speed = Float.parseFloat(btn.getTag().toString());
                    updateSpeed.accept(speed);
                });
            }
        }
        dialog.show();
    }

    public void updateSpeedButtonUI(float speed) {
        final TextView speedView = playerView.findViewById(R.id.btn_speed);
        if (speedView != null) speedView.setText(String.format(Locale.getDefault(), "%.2fx", speed));
    }

    private void showQualityPopup(View v) {
        List<String> available = engine.getAvailableResolutions();
        if (available.isEmpty()) return;
        Map<String, String> map = new LinkedHashMap<>();
        for (String s : available) map.merge(s.replaceAll("(?<=p)\\d+|\\s", ""), s, (o, n) -> n.contains("60") ? n : o);
        String[] labels = map.keySet().toArray(new String[0]);
        String[] values = map.values().toArray(new String[0]);
        int checked = Arrays.asList(values).indexOf(engine.getQuality());
        showSelectionPopup(v, labels, checked, (index, label) -> {
            engine.onQualitySelected(values[index]);
            prefs.setQuality(values[index]);
        });
    }

    private void toggleSubtitles() {
        if (engine.areSubtitlesEnabled()) {
            engine.setSubtitlesEnabled(false);
            showHint(activity.getString(R.string.subtitles_off), 1000);
            updateSubtitleButtonState();
        } else {
            List<String> available = engine.getSubtitles();
            if (available.isEmpty()) {
                showHint(activity.getString(R.string.no_subtitles), 1000);
                return;
            }
            showSelectionPopup(playerView.findViewById(R.id.btn_subtitles), available.toArray(new String[0]), -1, (index, label) -> {
                engine.setSubtitlesEnabled(true);
                engine.setSubtitleLanguage(label);
                showHint(activity.getString(R.string.subtitles_on) + ": " + label, 1000);
                updateSubtitleButtonState();
            });
        }
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
        if (segments.isEmpty()) return;
        String[] titles = new String[segments.size()];
        int currentIdx = -1;
        long posSec = engine.position() / 1000;
        for (int i = 0; i < segments.size(); i++) {
            titles[i] = DateUtils.formatElapsedTime(Math.max(segments.get(i).getStartTimeSeconds(), 0)) + " - " + segments.get(i).getTitle();
            if (posSec >= segments.get(i).getStartTimeSeconds()) currentIdx = i;
        }
        showSelectionPopup(anchor, titles, currentIdx, (index, label) -> engine.seekTo(segments.get(index).getStartTimeSeconds() * 1000L));
    }

    private void setupOverlayAndMoreButtons() {
        setClick(R.id.btn_more, v -> {
            setControlsVisible(true);
            if (activity.isInPictureInPictureMode()) return;
            BottomSheetDialog bsd = new BottomSheetDialog(activity);
            View bsv = activity.getLayoutInflater().inflate(R.layout.bottom_sheet_more_options, null, false);
            bsd.setContentView(bsv);
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from((View) bsv.getParent());
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);

            setupBottomSheetOption(bsv, R.id.option_resize_mode, b -> { showResizeModeOptions(); bsd.dismiss(); });
            setupBottomSheetOption(bsv, R.id.option_audio_track, b -> { showAudioTrackOptions(); bsd.dismiss(); });
            setupBottomSheetOption(bsv, R.id.option_pip, b -> { playerView.enterPiP(); bsd.dismiss(); });
            setupBottomSheetOption(bsv, R.id.option_subtitle_style, b -> {
                String[] styles = {"White on Black", "Yellow on Black", "Custom..."};
                int current = kv.decodeInt(LitePlayer.KEY_SUBTITLE_STYLE, 1);
                int selection = current <= 2 ? current - 1 : 2;
                new MaterialAlertDialogBuilder(activity).setTitle("Subtitle Style").setSingleChoiceItems(styles, selection, (dialog, which) -> {
                    int finalStyle = which < 2 ? which + 1 : 4;
                    kv.encode(LitePlayer.KEY_SUBTITLE_STYLE, finalStyle);
                    if (finalStyle == 4) showCustomSubtitleDialog();
                    else playerLazy.get().applySubtitleStyle();
                    dialog.dismiss();
                    bsd.dismiss();
                }).show();
            });
            setupBottomSheetOption(bsv, R.id.option_stream_details, b -> { showVideoDetails(); bsd.dismiss(); });
            bsd.show();
        });
    }

    private void showCustomSubtitleDialog() {
        String[] options = {"Text Size", "Text Color", "Background Color", "Background Opacity", "Edge Type", "Edge Color"};
        String[] sizes = {"12sp", "16sp", "20sp", "24sp", "28sp", "32sp"};
        float[] sizeVals = {12f, 16f, 20f, 24f, 28f, 32f};
        String[] colors = {"White", "Yellow", "Cyan", "Green", "Magenta", "Red", "Black"};
        int[] colorVals = {Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, Color.MAGENTA, Color.RED, Color.BLACK};
        String[] opacities = {"0%", "25%", "50%", "75%", "100%"};
        int[] opacityVals = {0, 64, 128, 192, 255};
        String[] edges = {"None", "Outline", "Drop Shadow", "Raised", "Depressed"};
        int[] edgeVals = {CaptionStyleCompat.EDGE_TYPE_NONE, CaptionStyleCompat.EDGE_TYPE_OUTLINE, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, CaptionStyleCompat.EDGE_TYPE_RAISED, CaptionStyleCompat.EDGE_TYPE_DEPRESSED};

        new MaterialAlertDialogBuilder(activity)
                .setTitle("Custom Subtitles")
                .setItems(options, (d, which) -> {
                    if (which == 0) new MaterialAlertDialogBuilder(activity).setTitle("Text Size").setItems(sizes, (d2, i) -> { kv.encode(LitePlayer.KEY_SUBTITLE_CUSTOM_TEXT_SIZE, sizeVals[i]); playerLazy.get().applySubtitleStyle(); showCustomSubtitleDialog(); }).show();
                    else if (which == 1) new MaterialAlertDialogBuilder(activity).setTitle("Text Color").setItems(colors, (d2, i) -> { kv.encode(LitePlayer.KEY_SUBTITLE_CUSTOM_TEXT_COLOR, colorVals[i]); playerLazy.get().applySubtitleStyle(); showCustomSubtitleDialog(); }).show();
                    else if (which == 2) new MaterialAlertDialogBuilder(activity).setTitle("Background Color").setItems(colors, (d2, i) -> { kv.encode(LitePlayer.KEY_SUBTITLE_CUSTOM_BG_COLOR, colorVals[i]); playerLazy.get().applySubtitleStyle(); showCustomSubtitleDialog(); }).show();
                    else if (which == 3) new MaterialAlertDialogBuilder(activity).setTitle("Background Opacity").setItems(opacities, (d2, i) -> { kv.encode(LitePlayer.KEY_SUBTITLE_CUSTOM_BG_OPACITY, opacityVals[i]); playerLazy.get().applySubtitleStyle(); showCustomSubtitleDialog(); }).show();
                    else if (which == 4) new MaterialAlertDialogBuilder(activity).setTitle("Edge Type").setItems(edges, (d2, i) -> { kv.encode(LitePlayer.KEY_SUBTITLE_CUSTOM_EDGE_TYPE, edgeVals[i]); playerLazy.get().applySubtitleStyle(); showCustomSubtitleDialog(); }).show();
                    else if (which == 5) new MaterialAlertDialogBuilder(activity).setTitle("Edge Color").setItems(colors, (d2, i) -> { kv.encode(LitePlayer.KEY_SUBTITLE_CUSTOM_EDGE_COLOR, colorVals[i]); playerLazy.get().applySubtitleStyle(); showCustomSubtitleDialog(); }).show();
                }).show();
    }

    private void setupBottomSheetOption(@NonNull View root, int id, @NonNull View.OnClickListener l) {
        View o = root.findViewById(id);
        if (o != null) o.setOnClickListener(l);
    }

    private void showAudioTrackOptions() {
        List<AudioStream> audioTracks = engine.getAvailableAudioTracks();
        if (audioTracks.isEmpty()) return;
        String[] options = new String[audioTracks.size()];
        for (int i = 0; i < audioTracks.size(); i++) options[i] = audioTracks.get(i).getAudioTrackName();
        new MaterialAlertDialogBuilder(activity).setTitle(R.string.audio_track).setItems(options, (d, w) -> engine.setAudioTrack(audioTracks.get(w))).show();
    }

    private void showResizeModeOptions() {
        String[] opts = {activity.getString(R.string.resize_fit), activity.getString(R.string.resize_fill), activity.getString(R.string.resize_zoom)};
        int[] modes = {AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_ZOOM};
        new MaterialAlertDialogBuilder(activity).setTitle(R.string.resize_mode).setItems(opts, (d, w) -> {
            playerView.setResizeMode(modes[w]);
            prefs.setResizeMode(modes[w]);
        }).show();
    }

    private void showVideoDetails() {
        StreamDetails details = engine.getStreamDetails();
        if (details == null) return;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
        builder.setTitle(R.string.info).setPositiveButton(R.string.confirm, null);
        String info = getVideoDetailsText(details);
        builder.setMessage(info).setNeutralButton(R.string.copy, (dialog, which) -> DeviceUtils.copyToClipboard(activity, "Video Details", info));
        builder.show();
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
                if (diff >= 1000) {
                    fps = ((counters.renderedOutputBufferCount - lastVideoRenderedCount) * 1000f) / diff;
                    lastVideoRenderedCount = counters.renderedOutputBufferCount;
                    lastFpsUpdateTime = now;
                }
            } else {
                lastVideoRenderedCount = counters.renderedOutputBufferCount;
                lastFpsUpdateTime = now;
            }
            sb.append(activity.getString(R.string.fps)).append(": ").append(String.format(Locale.getDefault(), "%.2f", fps)).append("\n");
            sb.append(activity.getString(R.string.dropped_frames)).append(": ").append(counters.droppedBufferCount).append("\n");
        }
        if (vF != null) sb.append(activity.getString(R.string.video_format)).append(": ").append(vF.sampleMimeType).append("\n").append(activity.getString(R.string.resolution)).append(": ").append(vF.width).append("x").append(vF.height).append("\n").append(activity.getString(R.string.bitrate)).append(": ").append(vF.bitrate / 1000).append(" kbps\n");
        if (aF != null) sb.append(activity.getString(R.string.audio_format)).append(": ").append(aF.sampleMimeType).append("\n").append(activity.getString(R.string.bitrate)).append(": ").append(aF.bitrate / 1000).append(" kbps\n");
        return sb.toString();
    }

    private void showSelectionPopup(View anchor, String[] options, int checked, SelectionCallback cb) {
        ListPopupWindow popup = new ListPopupWindow(activity);
        popup.setAnchorView(anchor);
        popup.setModal(true);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, R.layout.item_menu_list, options) {
            @NonNull @Override public View getView(int pos, @Nullable View conv, @NonNull ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, conv, parent);
                if (pos == checked) {
                    TypedValue out = new TypedValue();
                    activity.getTheme().resolveAttribute(android.R.attr.colorPrimary, out, true);
                    tv.setTextColor(out.data);
                    tv.setTypeface(null, Typeface.BOLD);
                }
                return tv;
            }
        };
        popup.setAdapter(adapter);
        popup.setWidth(ViewUtils.dpToPx(activity, 200));
        popup.setOnItemClickListener((p, v, pos, id) -> {
            cb.onSelected(pos, options[pos]);
            popup.dismiss();
        });
        popup.show();
    }

    public void setControlsVisible(boolean visible) {
        if (visible && activity.isInPictureInPictureMode()) return;
        this.isControlsVisible = visible;
        handler.removeCallbacks(hideControls);
        View other = playerView.findViewById(R.id.other_controls);
        View center = playerView.findViewById(R.id.center_controls);
        View bar = playerView.findViewById(R.id.exo_progress);
        float alpha = visible ? 1.0f : 0.0f;
        ImageButton lockBtn = playerView.findViewById(R.id.btn_lock);

        if (isLocked) {
            ViewUtils.animateViewAlpha(center, 0f, View.GONE);
            ViewUtils.animateViewAlpha(other, 0f, View.GONE);
            ViewUtils.animateViewAlpha(bar, 0f, View.GONE);
            showReset(false);
        } else {
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
        if (hintText == null) return;
        hintText.setText(text);
        ViewUtils.animateViewAlpha(hintText, 1.0f, View.GONE);
        handler.removeCallbacks(this::hideHint);
        if (durationMs > 0) handler.postDelayed(this::hideHint, durationMs);
    }

    public void hideHint() { if (hintText != null) ViewUtils.animateViewAlpha(hintText, 0.0f, View.GONE); }
    public void enterFullscreen() { playerView.enterFullscreen(PlayerUtils.isPortrait(engine)); setControlsVisible(true); }
    public void exitFullscreen() { if (isLocked) toggleLock(); playerView.exitFullscreen(); setControlsVisible(true); }
    public void onPictureInPictureModeChanged(boolean pip) { playerView.onPictureInPictureModeChanged(pip); setControlsVisible(!pip); }
    private void setClick(int id, View.OnClickListener l) { View v = playerView.findViewById(id); if (v != null) v.setOnClickListener(l); }
    private void toggleLock() { isLocked = !isLocked; ImageButton lb = playerView.findViewById(R.id.btn_lock); if (lb != null) lb.setImageResource(isLocked ? R.drawable.ic_lock : R.drawable.ic_unlock); setControlsVisible(true); showHint(activity.getString(isLocked ? R.string.lock_screen : R.string.unlock_screen), 1000); }
    private interface SelectionCallback { void onSelected(int index, String label); }
}