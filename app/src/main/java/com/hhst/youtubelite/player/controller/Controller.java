package com.hhst.youtubelite.player.controller;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
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
import com.hhst.youtubelite.Constant;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.browser.TabManager;
import com.hhst.youtubelite.browser.YoutubeFragment;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.player.LitePlayer;
import com.hhst.youtubelite.player.LitePlayerView;
import com.hhst.youtubelite.player.common.PlayerLoopMode;
import com.hhst.youtubelite.player.common.PlayerPreferences;
import com.hhst.youtubelite.player.common.PlayerUtils;
import com.hhst.youtubelite.player.controller.gesture.PlayerGestureListener;
import com.hhst.youtubelite.player.controller.gesture.ZoomTouchListener;
import com.hhst.youtubelite.player.engine.Engine;
import com.hhst.youtubelite.player.queue.QueueNav;
import com.hhst.youtubelite.util.DeviceUtils;
import com.hhst.youtubelite.util.UrlUtils;
import com.hhst.youtubelite.util.ViewUtils;
import com.tencent.mmkv.MMKV;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamSegment;

import java.util.ArrayList;
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
	static final float DISABLED_BUTTON_ALPHA = 0.38f;

	@NonNull
	private final Activity activity;
	@NonNull
	private final LitePlayerView playerView;
	@NonNull
	private final Engine engine;
	@NonNull
	private final ZoomTouchListener zoomListener;
	@NonNull
	private final PlayerPreferences prefs;
	@NonNull
	private final TabManager tabManager;
	@NonNull
	private final Lazy<LitePlayer> playerLazy;
	@Getter
	@NonNull
	private final ExtensionManager extensionManager;

	@NonNull
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final MMKV kv = MMKV.defaultMMKV();

	@Nullable
	private TextView hintText;
	@Setter
	private boolean longPress = false;
	@NonNull
	private final ControllerMachine stateMachine = new ControllerMachine();
	private boolean block = false;
	private boolean pending = false;
	private boolean autoFs = false;
	private long lastVideoRenderedCount = 0;
	private long lastFpsUpdateTime = 0;
	private float fps = 0;

	@NonNull
	private final Runnable hideControls = () -> setControlsVisible(false);

	@Inject
	public Controller(@NonNull final Activity activity,
					  @NonNull final LitePlayerView playerView,
					  @NonNull final Engine engine,
					  @NonNull final Lazy<LitePlayer> playerLazy,
					  @NonNull final PlayerPreferences prefs,
					  @NonNull final ZoomTouchListener zoomListener,
					  @NonNull final TabManager tabManager,
					  @NonNull final ExtensionManager extensionManager) {
		this.activity = activity;
		this.playerView = playerView;
		this.engine = engine;
		this.playerLazy = playerLazy;
		this.prefs = prefs;
		this.zoomListener = zoomListener;
		this.tabManager = tabManager;
		this.extensionManager = extensionManager;

		this.playerView.setOnMiniPlayerBackgroundTap(() -> setControlsVisible(!isControlsVisible()));
		this.zoomListener.setOnShowReset(show ->
				showReset(show && isControlsVisible() && stateMachine.getState() == ControllerMachine.State.FULLSCREEN_UNLOCKED));

		playerView.post(() -> {
			setupHintOverlay();
			setupListeners();
			setupButtonListeners();
			updatePlayPauseButtons(engine.isPlaying());
			refreshQueueNavigationAvailability(engine.getQueueNavigationAvailability());
			refreshInternalButtonVisibility();
			playerView.showController();
		});
	}

	public void refreshInternalButtonVisibility() {
		updateVisibility(R.id.btn_speed, extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.PLAYER_SHOW_SPEED));
		updateVisibility(R.id.btn_quality, extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.PLAYER_SHOW_QUALITY));
		updateVisibility(R.id.btn_subtitles, extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.PLAYER_SHOW_SUBTITLES));
		updateVisibility(R.id.btn_segments, extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.PLAYER_SHOW_SEGMENTS));
		updateVisibility(R.id.btn_loop, extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.PLAYER_SHOW_LOOP));
		updateVisibility(R.id.btn_reload, extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.PLAYER_SHOW_RELOAD));
		updateVisibility(R.id.btn_lock, extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.PLAYER_SHOW_LOCK));
		updateVisibility(R.id.btn_next, extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.PLAYER_SHOW_NEXT));
		updateVisibility(R.id.btn_prev, extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.PLAYER_SHOW_PREVIOUS));

		updateSubtitleButtonState();
		updateSegmentsButtonState();
	}

	public static boolean shouldEnablePrevious(@NonNull final QueueNav availability) {
		return availability.isPreviousActionEnabled();
	}

	public static boolean shouldEnableNext(@NonNull final QueueNav availability) {
		return availability.isNextActionEnabled();
	}

	static float previousButtonAlpha(@NonNull final QueueNav availability) {
		return shouldEnablePrevious(availability) ? 1.0f : DISABLED_BUTTON_ALPHA;
	}

	static float nextButtonAlpha(@NonNull final QueueNav availability) {
		return shouldEnableNext(availability) ? 1.0f : DISABLED_BUTTON_ALPHA;
	}

	public void refreshQueueNavigationAvailability(@NonNull final QueueNav availability) {
		playerView.post(() -> {
			applyPreviousButtonState(availability);
			applyNextButtonState(availability);
		});
	}

	private void updatePlayPauseButtons(boolean isPlaying) {
		updatePlayPauseVisibility(R.id.btn_play, R.id.btn_pause, isPlaying);
		updatePlayPauseVisibility(R.id.btn_mini_play, R.id.btn_mini_pause, isPlaying);
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
			if (stateMachine.isInMiniPlayer()) {
				return false;
			}
			final int action = ev.getAction();
			if (action == MotionEvent.ACTION_DOWN && isControlsVisible()) {
				handler.removeCallbacks(hideControls);
			}

			if (stateMachine.isLocked()) {
				if (action == MotionEvent.ACTION_UP) {
					setControlsVisible(!isControlsVisible());
				}
				return true;
			}

			boolean handled = detector.onTouchEvent(ev);
			if (!handled && isFullscreen()) zoomListener.onTouch(ev);
			if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
				gestureListener.onTouchRelease();
				if (longPress) {
					longPress = false;
					engine.setPlaybackRate(prefs.getSpeed());
					hideHint();
				}
				if (isControlsVisible()) hideControlsAutomatically();
			}
			return handled;
		});

		engine.addListener(new Player.Listener() {
			@Override
			public void onIsPlayingChanged(boolean isPlaying) {
				updatePlayPauseButtons(isPlaying);
				playerView.setKeepScreenOn(isPlaying);
				if (isControlsVisible()) hideControlsAutomatically();
			}

			@Override
			public void onPlaybackStateChanged(int playbackState) {
				if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
					hideControlsAutomatically();
				} else if (playbackState == Player.STATE_BUFFERING && isControlsVisible()) {
					setControlsVisible(true);
				}
				if (playbackState == Player.STATE_READY) {
					playerView.post(() -> {
						updateSpeedButtonUI(engine.getPlaybackRate());
						final TextView qualityView = playerView.findViewById(R.id.btn_quality);
						if (qualityView != null) qualityView.setText(engine.getQualityLabel());
						updateSegmentsButtonState();
					});
				}
			}

			@Override
			public void onTracksChanged(@NonNull Tracks tracks) {
				updateSubtitleButtonState();
				playerView.post(() -> {
					final TextView qualityView = playerView.findViewById(R.id.btn_quality);
					if (qualityView != null) qualityView.setText(engine.getQualityLabel());
				});
			}
		});
	}

	private void setupButtonListeners() {
		setClicks(new int[]{R.id.btn_play, R.id.btn_mini_play}, v -> {
			engine.play();
			setControlsVisible(true);
		});
		setClicks(new int[]{R.id.btn_pause, R.id.btn_mini_pause}, v -> {
			engine.pause();
			setControlsVisible(true);
		});
		setClick(R.id.btn_prev, v -> {
			engine.skipToPrevious();
			setControlsVisible(true);
		});
		setClick(R.id.btn_next, v -> {
			engine.skipToNext();
			setControlsVisible(true);
		});

		setClick(R.id.btn_speed, v -> showSpeedSliderDialog());
		setClick(R.id.btn_quality, this::showQualityPopup);
		setClick(R.id.btn_subtitles, v -> toggleSubtitles());
		setClick(R.id.btn_segments, this::showSegmentsPopup);
		setClick(R.id.btn_reset, v -> zoomListener.reset());
		setClick(R.id.btn_reload, v -> playerLazy.get().reload());

		final ImageButton lockBtn = playerView.findViewById(R.id.btn_lock);
		if (lockBtn != null) {
			lockBtn.setOnClickListener(v -> {
				toggleLockState();
				showHint(activity.getString(stateMachine.isLocked() ? R.string.lock_screen : R.string.unlock_screen), 1000);
			});
		}

		final ImageButton fsBtn = playerView.findViewById(R.id.btn_fullscreen);
		if (fsBtn != null) {
			fsBtn.setOnClickListener(v -> {
				if (!isFullscreen()) enterFullscreen();
				else exitFullscreen();
			});
		}

		final ImageButton loopBtn = playerView.findViewById(R.id.btn_loop);
		if (loopBtn != null) {
			applyLoopMode(loopBtn, prefs.getLoopMode());
			loopBtn.setOnClickListener(v -> {
				final PlayerLoopMode newMode = getLoopMode().next();
				setLoopMode(newMode);
				showHint(activity.getString(getLoopModeLabelRes(newMode)), 1000);
				setControlsVisible(true);
			});
		}
		setupOverlayAndMoreButtons();
	}

	public void showSpeedSliderDialog() {
		BottomSheetDialog dialog = new BottomSheetDialog(activity);
		@SuppressLint("InflateParams") View v = activity.getLayoutInflater().inflate(R.layout.dialog_speed_slider, null, false);
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
		if (slider != null) slider.setValue(currentSpeed);
		if (speedText != null) speedText.setText(String.format(Locale.getDefault(), "%.2fx", currentSpeed));
		
		java.util.function.Consumer<Float> updateSpeed = (val) -> {
			float newVal = Math.max(0.25f, Math.min(4.0f, val));
			if (slider != null) slider.setValue(newVal);
			if (speedText != null) speedText.setText(String.format(Locale.getDefault(), "%.2fx", newVal));
			engine.setPlaybackRate(newVal);
			updateSpeedButtonUI(newVal);
			prefs.setSpeed(newVal);
		};
		if (slider != null) {
			slider.addOnChangeListener((s, value, fromUser) -> {
				if (fromUser) updateSpeed.accept(value);
			});
		}
		if (btnMinus != null) btnMinus.setOnClickListener(view -> {
			if (slider != null) updateSpeed.accept(slider.getValue() - 0.05f);
		});
		if (btnPlus != null) btnPlus.setOnClickListener(view -> {
			if (slider != null) updateSpeed.accept(slider.getValue() + 0.05f);
		});
		if (presetContainer != null) {
			for (int i = 0; i < presetContainer.getChildCount(); i++) {
				View child = presetContainer.getChildAt(i);
				child.setOnClickListener(btn -> {
					Object tag = btn.getTag();
					if (tag != null) {
						float speed = Float.parseFloat(tag.toString());
						updateSpeed.accept(speed);
					}
				});
			}
		}
		dialog.show();
	}

	private void applyLoopMode(@NonNull final ImageButton loopBtn, @NonNull final PlayerLoopMode mode) {
		engine.setLoopMode(mode);
		loopBtn.setImageResource(getLoopModeIconRes(mode));
		loopBtn.setContentDescription(activity.getString(getLoopModeLabelRes(mode)));
	}

	public void setLoopMode(@NonNull final PlayerLoopMode mode) {
		prefs.setLoopMode(mode);
		final ImageButton loopBtn = playerView.findViewById(R.id.btn_loop);
		if (loopBtn != null) {
			applyLoopMode(loopBtn, mode);
		} else {
			engine.setLoopMode(mode);
		}
	}

	@NonNull
	public PlayerLoopMode getLoopMode() {
		return prefs.getLoopMode();
	}

	private int getLoopModeIconRes(@NonNull final PlayerLoopMode mode) {
		return switch (mode) {
			case PLAYLIST_NEXT -> R.drawable.ic_playback_end_next;
			case LOOP_ONE -> R.drawable.ic_playback_end_loop;
			case PAUSE_AT_END -> R.drawable.ic_playback_end_pause;
			case PLAYLIST_RANDOM -> R.drawable.ic_playback_end_shuffle;
		};
	}

	private int getLoopModeLabelRes(@NonNull final PlayerLoopMode mode) {
		return switch (mode) {
			case PLAYLIST_NEXT -> R.string.playback_end_next;
			case LOOP_ONE -> R.string.playback_end_loop;
			case PAUSE_AT_END -> R.string.playback_end_pause;
			case PLAYLIST_RANDOM -> R.string.playback_end_playlist_random;
		};
	}

	public void updateSpeedButtonUI(float speed) {
		final TextView speedView = playerView.findViewById(R.id.btn_speed);
		if (speedView != null) speedView.setText(String.format(Locale.getDefault(), "%.2fx", speed));
	}

	private void showQualityPopup(View v) {
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
			final TextView qualityView = playerView.findViewById(R.id.btn_quality);
			if (qualityView != null) qualityView.setText(label);
			String js = String.format(Locale.US, "(function(t){const p=document.querySelector('#movie_player');const ls=p.getAvailableQualityLabels();const v=l=>parseInt(l.replace(/\\\\D/g,''));const target=v(t);const closest=ls.reduce((b,c,i)=>Math.abs(v(c)-target)<Math.abs(v(ls[b])-target)?i:b,0);const quality=p.getAvailableQualityLevels()[closest];p.setPlaybackQualityRange(quality,quality);})('%s')", label);
			tabManager.evaluateJavascript(js, null);
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
			if (available.size() == 1) {
				String label = available.get(0);
				engine.setSubtitlesEnabled(true);
				engine.setSubtitleLanguage(label);
				showHint(activity.getString(R.string.subtitles_on) + ": " + label, 1000);
				updateSubtitleButtonState();
			} else {
				showSelectionPopup(playerView.findViewById(R.id.btn_subtitles), available.toArray(new String[0]), -1, (index, label) -> {
					engine.setSubtitlesEnabled(true);
					engine.setSubtitleLanguage(label);
					showHint(activity.getString(R.string.subtitles_on) + ": " + label, 1000);
					updateSubtitleButtonState();
				});
			}
		}
	}

	public void updateSubtitleButtonState() {
		ImageButton subBtn = playerView.findViewById(R.id.btn_subtitles);
		if (subBtn == null) return;
		if (!extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.PLAYER_SHOW_SUBTITLES)) {
			subBtn.setVisibility(View.GONE);
			return;
		}
		boolean hasSubtitles = !engine.getSubtitles().isEmpty();
		boolean isEnabled = engine.areSubtitlesEnabled();
		subBtn.setImageResource(isEnabled ? R.drawable.ic_subtitles_on : R.drawable.ic_subtitles_off);
		subBtn.setAlpha(hasSubtitles ? 1.0f : DISABLED_BUTTON_ALPHA);
	}

	public void updateSegmentsButtonState() {
		View segmentsBtn = playerView.findViewById(R.id.btn_segments);
		if (segmentsBtn == null) return;
		if (!extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.PLAYER_SHOW_SEGMENTS)) {
			segmentsBtn.setVisibility(View.GONE);
			return;
		}
		boolean hasSegments = !engine.getSegments().isEmpty();
		segmentsBtn.setEnabled(hasSegments);
		segmentsBtn.setAlpha(hasSegments ? 1.0f : DISABLED_BUTTON_ALPHA);
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
			@SuppressLint("InflateParams") View bsv = activity.getLayoutInflater().inflate(R.layout.bottom_sheet_more_options, null, false);
			bsd.setContentView(bsv);
			BottomSheetBehavior<View> behavior = BottomSheetBehavior.from((View) bsv.getParent());
			behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
			behavior.setSkipCollapsed(true);

			setupBottomSheetOption(bsv, R.id.option_resize_mode, b -> {
				showResizeModeOptions();
				bsd.dismiss();
			});
			setupBottomSheetOption(bsv, R.id.option_audio_track, b -> {
				showAudioTrackOptions();
				bsd.dismiss();
			});
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
			setupBottomSheetOption(bsv, R.id.option_stream_details, b -> {
				showVideoDetails();
				bsd.dismiss();
			});
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
					if (which == 0)
						new MaterialAlertDialogBuilder(activity).setTitle("Text Size").setItems(sizes, (d2, i) -> {
							kv.encode(LitePlayer.KEY_SUBTITLE_CUSTOM_TEXT_SIZE, sizeVals[i]);
							playerLazy.get().applySubtitleStyle();
							showCustomSubtitleDialog();
						}).show();
					else if (which == 1)
						new MaterialAlertDialogBuilder(activity).setTitle("Text Color").setItems(colors, (d2, i) -> {
							kv.encode(LitePlayer.KEY_SUBTITLE_CUSTOM_TEXT_COLOR, colorVals[i]);
							playerLazy.get().applySubtitleStyle();
							showCustomSubtitleDialog();
						}).show();
					else if (which == 2)
						new MaterialAlertDialogBuilder(activity).setTitle("Background Color").setItems(colors, (d2, i) -> {
							kv.encode(LitePlayer.KEY_SUBTITLE_CUSTOM_BG_COLOR, colorVals[i]);
							playerLazy.get().applySubtitleStyle();
							showCustomSubtitleDialog();
						}).show();
					else if (which == 3)
						new MaterialAlertDialogBuilder(activity).setTitle("Background Opacity").setItems(opacities, (d2, i) -> {
							kv.encode(LitePlayer.KEY_SUBTITLE_CUSTOM_BG_OPACITY, opacityVals[i]);
							playerLazy.get().applySubtitleStyle();
							showCustomSubtitleDialog();
						}).show();
					else if (which == 4)
						new MaterialAlertDialogBuilder(activity).setTitle("Edge Type").setItems(edges, (d2, i) -> {
							kv.encode(LitePlayer.KEY_SUBTITLE_CUSTOM_EDGE_TYPE, edgeVals[i]);
							playerLazy.get().applySubtitleStyle();
							showCustomSubtitleDialog();
						}).show();
					else if (which == 5)
						new MaterialAlertDialogBuilder(activity).setTitle("Edge Color").setItems(colors, (d2, i) -> {
							kv.encode(LitePlayer.KEY_SUBTITLE_CUSTOM_EDGE_COLOR, colorVals[i]);
							playerLazy.get().applySubtitleStyle();
							showCustomSubtitleDialog();
						}).show();
				}).show();
	}

	private void setupBottomSheetOption(@NonNull View root, int id, @NonNull View.OnClickListener l) {
		View o = root.findViewById(id);
		if (o != null) o.setOnClickListener(l);
	}

	private void showAudioTrackOptions() {
		List<AudioStream> audioTracks = engine.getAvailableAudioTracks();
		if (audioTracks.isEmpty()) {
			showHint("No audio tracks available", 1000);
			return;
		}

		final Map<String, AudioStream> uniqueTrackMap = new LinkedHashMap<>();
		for (AudioStream s : audioTracks) {
			String langCode = s.getAudioLocale() != null ? s.getAudioLocale().getLanguage() : "und";
			String name = s.getAudioTrackName();
			boolean isOriginal = name != null && name.toLowerCase(Locale.ROOT).contains("original");

			String key = langCode + "_" + isOriginal;

			AudioStream existing = uniqueTrackMap.get(key);
			if (existing == null || s.getAverageBitrate() > existing.getAverageBitrate()) {
				uniqueTrackMap.put(key, s);
			}
		}

		final List<AudioStream> displayTracks = new ArrayList<>(uniqueTrackMap.values());
		String[] options = new String[displayTracks.size()];
		for (int i = 0; i < displayTracks.size(); i++) {
			options[i] = getStringBuilder(displayTracks.get(i), i);
		}

		int checked = -1;
		AudioStream current = engine.getAudioTrack();
		if (current != null) {
			for (int i = 0; i < displayTracks.size(); i++) {
				if (displayTracks.get(i).getContent().equals(current.getContent())) {
					checked = i;
					break;
				}
			}
		}

		new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.audio_track)
				.setAdapter(getAudioAdapter(checked, options), (d, w) -> {
					engine.setAudioTrack(displayTracks.get(w));
					showHint(options[w], 1000);
					hideControlsAutomatically();
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	@NonNull
	private ListAdapter getAudioAdapter(int checkedItem, String[] options) {
		return new ArrayAdapter<>(activity, R.layout.dialog_resize_mode_item, options) {
			@NonNull
			@Override
			public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
				View view = (convertView == null) ? activity.getLayoutInflater().inflate(R.layout.dialog_resize_mode_item, parent, false) : convertView;
				ImageView icon = view.findViewById(R.id.icon);
				TextView text = view.findViewById(R.id.text);
				if (icon != null) icon.setImageResource(R.drawable.ic_track);
				if (text != null) text.setText(getItem(position));
				TypedValue tv = new TypedValue();
				if (position == checkedItem) {
					activity.getTheme().resolveAttribute(android.R.attr.colorPrimary, tv, true);
					if (icon != null) icon.setColorFilter(tv.data);
					if (text != null) {
						text.setTextColor(tv.data);
						text.setTypeface(null, Typeface.BOLD);
					}
				} else {
					activity.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true);
					if (icon != null) icon.setColorFilter(activity.getColor(android.R.color.darker_gray));
					if (text != null) {
						text.setTextColor(tv.data);
						text.setTypeface(null, Typeface.NORMAL);
					}
				}
				return view;
			}
		};
	}

	@NonNull
	private String getStringBuilder(AudioStream track, int i) {
		String name = track.getAudioTrackName();
		String lang = track.getAudioLocale() != null ? track.getAudioLocale().getDisplayLanguage() : null;
		boolean isOriginal = name != null && name.toLowerCase(Locale.ROOT).contains("original");

		StringBuilder sb = new StringBuilder();
		if (isOriginal) {
			if (lang != null && !lang.isEmpty()) sb.append(lang).append(" (Original)");
			else sb.append("Original");
		} else if (lang != null && !lang.isEmpty()) {
			sb.append(lang);

			if (name != null && !name.isEmpty() && !name.equalsIgnoreCase(lang) && !name.toLowerCase(Locale.ROOT).contains("original")) {
				sb.append(" (").append(name).append(")");
			}
		} else if (name != null && !name.isEmpty()) {
			sb.append(name);
		} else {
			sb.append("Audio Track ").append(i + 1);
		}

		return sb.toString();
	}

	private void showResizeModeOptions() {
		setControlsVisible(true);
		String[] opts = {activity.getString(R.string.resize_fit), activity.getString(R.string.resize_fill), activity.getString(R.string.resize_zoom), activity.getString(R.string.resize_fixed_width), activity.getString(R.string.resize_fixed_height)};
		int[] modes = {AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH, AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT};
		final ListAdapter adapter = getResizeAdapter(modes, opts);
		new MaterialAlertDialogBuilder(activity).setTitle(R.string.resize_mode).setAdapter(adapter, (d, w) -> {
			playerView.setResizeMode(modes[w]);
			prefs.setResizeMode(modes[w]);
			showHint(opts[w], 1000);
			hideControlsAutomatically();
		}).setNegativeButton(R.string.cancel, null).show();
	}

	@NonNull
	private ListAdapter getResizeAdapter(@NonNull int[] modes, @NonNull String[] options) {
		int currentMode = playerView.getResizeMode();
		int checked = 0;
		for (int i = 0; i < modes.length; i++) if (modes[i] == currentMode) checked = i;
		final int finalChecked = checked;
		return new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, options) {
			@NonNull
			@Override
			public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
				TextView text = (TextView) super.getView(position, convertView, parent);
				text.setText(getItem(position));
				TypedValue tv = new TypedValue();
				if (position == finalChecked) {
					activity.getTheme().resolveAttribute(android.R.attr.colorPrimary, tv, true);
					text.setTextColor(tv.data);
					text.setTypeface(null, Typeface.BOLD);
				} else {
					activity.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true);
					text.setTextColor(tv.data);
					text.setTypeface(null, Typeface.NORMAL);
				}
				return text;
			}
		};
	}

	private void showVideoDetails() {
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
		builder.setTitle(R.string.info).setPositiveButton(R.string.confirm, null);
		String info = getVideoDetailsText();
		builder.setMessage(info).setNeutralButton(R.string.copy, (dialog, which) -> DeviceUtils.copyToClipboard(activity, "Video Details", info));
		builder.show();
	}

	@NonNull
	private String getVideoDetailsText() {
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
		if (vF != null)
			sb.append(activity.getString(R.string.video_format)).append(": ").append(vF.sampleMimeType).append("\n").append(activity.getString(R.string.resolution)).append(": ").append(vF.width).append("x").append(vF.height).append("\n").append(activity.getString(R.string.bitrate)).append(": ").append(vF.bitrate / 1000).append(" kbps\n");
		if (aF != null)
			sb.append(activity.getString(R.string.audio_format)).append(": ").append(aF.sampleMimeType).append("\n").append(activity.getString(R.string.bitrate)).append(": ").append(aF.bitrate / 1000).append(" kbps\n");
		return sb.toString();
	}

	private void showSelectionPopup(@NonNull View anchor, @NonNull String[] options, int checkedIndex, @NonNull SelectionCallback callback) {
		setControlsVisible(true);
		ListPopupWindow popup = new ListPopupWindow(activity);
		popup.setAnchorView(anchor);
		popup.setModal(true);
		ArrayAdapter<String> adapter = createSelectionAdapter(checkedIndex, options);
		popup.setAdapter(adapter);
		popup.setWidth(calculatePopupWidth(adapter, options.length));
		popup.setOnItemClickListener((p, v, pos, id) -> {
			callback.onSelected(pos, options[pos]);
			popup.dismiss();
			hideControlsAutomatically();
		});
		popup.show();
		ListView lv = popup.getListView();
		if (lv != null) lv.setOnItemLongClickListener((p, v, pos, id) -> {
			callback.onLongClick();
			popup.dismiss();
			return true;
		});
	}

	private ArrayAdapter<String> createSelectionAdapter(int checked, @NonNull String[] options) {
		return new ArrayAdapter<>(activity, R.layout.item_menu_list, options) {
			@NonNull
			@Override
			public View getView(int pos, @Nullable View conv, @NonNull ViewGroup parent) {
				TextView tv = (TextView) super.getView(pos, conv, parent);
				TypedValue out = new TypedValue();
				if (pos == checked) {
					activity.getTheme().resolveAttribute(android.R.attr.colorPrimary, out, true);
					tv.setTextColor(out.data);
					tv.setTypeface(null, Typeface.BOLD);
				} else {
					activity.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, out, true);
					tv.setTextColor(out.data);
					tv.setTypeface(null, Typeface.NORMAL);
				}
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

	public static boolean shouldEnterFs(final boolean isWatch,
										 final boolean autoRotate,
										 final boolean playerVisible,
										 final boolean isFullscreen,
										 final boolean isInPiP,
										 final boolean isInMiniPlayer,
										 final int orientation,
										 final boolean block) {
		return isWatch
				&& autoRotate
				&& playerVisible
				&& !isFullscreen
				&& !isInPiP
				&& !isInMiniPlayer
				&& orientation == Configuration.ORIENTATION_LANDSCAPE
				&& !block;
	}

	public static boolean shouldExitFs(final boolean isWatch, final boolean isFullscreen,
										final int orientation) {
		return isWatch && isFullscreen
				&& orientation == Configuration.ORIENTATION_PORTRAIT;
	}

	public static boolean shouldLockPortrait(final boolean isWatch,
											  final boolean isFullscreen,
											  final int orientation) {
		return isWatch
				&& isFullscreen
				&& orientation == Configuration.ORIENTATION_LANDSCAPE;
	}

	public static int fsOrientation(final boolean autoFs, final boolean isPortrait) {
		if (autoFs) return ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
		return isPortrait ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT :
						ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
	}

	public void enterFullscreen() {
		if (stateMachine.isFullscreen()) return;
		autoFs = false;
		final ControllerMachine.State previousState = stateMachine.getState();
		stateMachine.enterFullscreen();
		applyControllerState(previousState, true);
	}

	@SuppressLint("SourceLockedOrientationActivity")
	public void exitFullscreen() {
		if (orientation() == Configuration.ORIENTATION_LANDSCAPE) {
			block = true;
		}
		exitNow();
	}

	@SuppressLint("SourceLockedOrientationActivity")
    private void exitNow() {
		pending = false;
		autoFs = false;
		final ControllerMachine.State previousState = stateMachine.getState();
		stateMachine.exitFullscreen();
		applyControllerState(previousState, true);
		zoomListener.reset();
		if (orientation() == Configuration.ORIENTATION_LANDSCAPE) {
			activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
		}
	}

	public void syncRotation(final boolean autoRotate, final int orientation) {
		if (!isWatch()
				|| stateMachine.isInPictureInPicture()
				|| stateMachine.isInMiniPlayer()
				|| playerView.getVisibility() != View.VISIBLE) {
			clearRotation();
			return;
		}
		if (orientation == Configuration.ORIENTATION_PORTRAIT) {
			if (pending) {
				pending = false;
				if (stateMachine.isFullscreen()) exitNow();
				return;
			}
			block = false;
			if (shouldExitFs(true, stateMachine.isFullscreen(), orientation)) {
				exitNow();
			}
			return;
		}
		if (orientation != Configuration.ORIENTATION_LANDSCAPE || pending) return;
		if (shouldEnterFs(
				true,
				autoRotate,
				playerView.getVisibility() == View.VISIBLE,
				stateMachine.isFullscreen(),
				stateMachine.isInPictureInPicture(),
				stateMachine.isInMiniPlayer(),
				orientation,
				block)) {
			enterAutoFs();
		}
	}

	public void clearRotation() {
		block = false;
		pending = false;
		autoFs = false;
	}

	public void onPictureInPictureModeChanged(boolean isInPiP) {
		final ControllerMachine.State previousState = stateMachine.getState();
		stateMachine.onPictureInPictureModeChanged(isInPiP);
		applyControllerState(previousState, !isInPiP);
	}

	public void enterMiniPlayer() {
		final ControllerMachine.State previousState = stateMachine.getState();
		stateMachine.enterMiniPlayer();
		applyControllerState(previousState, true);
	}

	public void exitMiniPlayer() {
		final ControllerMachine.State previousState = stateMachine.getState();
		stateMachine.exitMiniPlayer();
		applyControllerState(previousState, true);
	}

	public void setControlsVisible(boolean visible) {
		stateMachine.setControlsVisible(visible);
		handler.removeCallbacks(hideControls);
		final ControllerMachine.RenderState renderState = stateMachine.currentRenderState(
				engine.getPlaybackState() == Player.STATE_BUFFERING,
				zoomListener.isZoomed());
		applyRenderState(renderState);
		if (renderState.controlsVisible()) {
			hideControlsAutomatically();
		}
	}

	private void applyRenderState(@NonNull final ControllerMachine.RenderState renderState) {
		View center = playerView.findViewById(R.id.center_controls);
		View other = playerView.findViewById(R.id.other_controls);
		View bar = playerView.findViewById(R.id.exo_progress);
		ImageButton lockBtn = playerView.findViewById(R.id.btn_lock);
		updateLockButton(lockBtn);
		if (center != null) {
			ViewUtils.animateViewAlpha(center, renderState.showCenterControls() ? 1.0f : 0.0f, View.GONE);
		}
		if (other != null) {
			ViewUtils.animateViewAlpha(other, renderState.showOtherControls() ? 1.0f : 0.0f, View.GONE);
		}
		if (bar != null) {
			ViewUtils.animateViewAlpha(bar, renderState.showProgressBar() ? 1.0f : 0.0f, View.GONE);
		}
		showReset(renderState.showResetButton());
		if (lockBtn != null) {
			boolean show = renderState.showLockButton() && extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.PLAYER_SHOW_LOCK);
			ViewUtils.animateViewAlpha(lockBtn, show ? 1.0f : 0.0f, View.GONE);
		}
		updateMiniControls(renderState.showMiniControls(), renderState.showMiniScrim());
	}

	private void hideControlsAutomatically() {
		handler.removeCallbacks(hideControls);
		if (shouldAutoHideControls(engine.isPlaying(), stateMachine.isInPictureInPicture())) {
			handler.postDelayed(hideControls, CONTROLS_HIDE_DELAY_MS);
		}
	}

	static boolean shouldAutoHideControls(final boolean isPlaying, final boolean isInPictureInPicture) {
		return isPlaying && !isInPictureInPicture;
	}

	public void showHint(@NonNull String text, long durationMs) {
		if (hintText == null || activity.isInPictureInPictureMode() || stateMachine.isInPictureInPicture() || stateMachine.isInMiniPlayer())
			return;
		hintText.setText(text);
		ViewUtils.animateViewAlpha(hintText, 1.0f, View.GONE);
		handler.removeCallbacks(this::hideHint);
		if (durationMs > 0) handler.postDelayed(this::hideHint, durationMs);
	}

	public boolean isFullscreen() {
		return stateMachine.isFullscreen();
	}

	public boolean isControlsVisible() {
		return stateMachine.isControlsVisible();
	}

	private void toggleLockState() {
		final ControllerMachine.State previousState = stateMachine.getState();
		stateMachine.toggleLock();
		applyControllerState(previousState, true);
	}

	private void applyControllerState(@NonNull final ControllerMachine.State previousState,
									  final boolean controlsVisible) {
		playerView.applyControllerState(
				previousState,
				stateMachine.getState(),
				PlayerUtils.isPortrait(engine),
				fsOrientation(autoFs, PlayerUtils.isPortrait(engine)),
				prefs.getResizeMode());
		if (stateMachine.isInPictureInPicture() || stateMachine.isInMiniPlayer()) {
			hideHint();
		}
		setControlsVisible(controlsVisible);
	}

	private void enterAutoFs() {
		autoFs = true;
		pending = false;
		final ControllerMachine.State previousState = stateMachine.getState();
		stateMachine.enterFullscreen();
		applyControllerState(previousState, true);
	}

	private int orientation() {
		return activity.getResources().getConfiguration().orientation;
	}

	private boolean isWatch() {
		final YoutubeFragment tab = tabManager.getTab();
		if (tab == null) return false;
		if (Constant.PAGE_WATCH.equals(tab.getMTag())) return true;
		final String url = tab.getUrl();
		return url != null && Constant.PAGE_WATCH.equals(UrlUtils.getPageClass(url));
	}

	private void updateLockButton(@Nullable final ImageButton lockBtn) {
		if (lockBtn == null) return;
		lockBtn.setImageResource(stateMachine.isLocked() ? R.drawable.ic_lock : R.drawable.ic_unlock);
		lockBtn.setContentDescription(activity.getString(
				stateMachine.isLocked() ? R.string.lock_screen : R.string.unlock_screen));
	}

	public void hideHint() {
		if (hintText != null) ViewUtils.animateViewAlpha(hintText, 0.0f, View.GONE);
	}

	private void showReset(boolean show) {
		View btn = playerView.findViewById(R.id.btn_reset);
		if (btn != null) btn.setVisibility(show ? View.VISIBLE : View.GONE);
	}

	private void setClick(int id, View.OnClickListener l) {
		View v = playerView.findViewById(id);
		if (v != null) v.setOnClickListener(l);
	}

	private void setClicks(@NonNull int[] ids, @NonNull View.OnClickListener listener) {
		for (int id : ids) {
			setClick(id, listener);
		}
	}

	private void updatePlayPauseVisibility(final int playId, final int pauseId, final boolean isPlaying) {
		final View play = playerView.findViewById(playId);
		final View pause = playerView.findViewById(pauseId);
		if (play != null) play.setVisibility(!isPlaying ? View.VISIBLE : View.GONE);
		if (pause != null) pause.setVisibility(isPlaying ? View.VISIBLE : View.GONE);
	}

	private void applyPreviousButtonState(@NonNull final QueueNav availability) {
		applyPreviousButtonState(R.id.btn_prev, availability);
	}

	private void applyPreviousButtonState(final int viewId,
										  @NonNull final QueueNav availability) {
		final View button = playerView.findViewById(viewId);
		if (button == null) return;
		button.setEnabled(shouldEnablePrevious(availability));
		button.setAlpha(previousButtonAlpha(availability));
	}

	private void applyNextButtonState(@NonNull final QueueNav availability) {
		applyNextButtonState(R.id.btn_next, availability);
	}

	private void applyNextButtonState(final int viewId,
									  @NonNull final QueueNav availability) {
		final View button = playerView.findViewById(viewId);
		if (button == null) return;
		button.setEnabled(shouldEnableNext(availability));
		button.setAlpha(nextButtonAlpha(availability));
	}

	private void updateMiniControls(final boolean showControls, final boolean showScrim) {
		final View scrim = playerView.findViewById(R.id.mini_controller_scrim);
		if (scrim != null) {
			ViewUtils.animateViewAlpha(scrim, showScrim ? 1.0f : 0.0f, View.GONE);
		}
		updateVisibility(R.id.btn_mini_close, showControls);
		updateVisibility(R.id.btn_mini_restore, showControls);
		updateVisibility(R.id.mini_bottom_controls, showControls);
	}

	private void updateVisibility(final int viewId, final boolean visible) {
		final View view = playerView.findViewById(viewId);
		if (view != null) {
			view.setVisibility(visible ? View.VISIBLE : View.GONE);
		}
	}

	private interface SelectionCallback {
		void onSelected(int index, String label);

		default void onLongClick() {
		}
	}
}
