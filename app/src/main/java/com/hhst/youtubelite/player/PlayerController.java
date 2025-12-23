package com.hhst.youtubelite.player;

import static androidx.media3.ui.R.id.exo_pause;
import static androidx.media3.ui.R.id.exo_play;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.Rational;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.ListPopupWindow;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TimeBar;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.player.gesture.PlayerGestureListener;
import com.hhst.youtubelite.player.gesture.ZoomTouchListener;
import com.hhst.youtubelite.player.interfaces.IControllerInternal;
import com.hhst.youtubelite.player.interfaces.IEngineInternal;
import com.hhst.youtubelite.player.interfaces.IPlayerInternal;
import com.squareup.picasso.Picasso;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamSegment;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import lombok.Getter;
import lombok.Setter;

@OptIn(markerClass = UnstableApi.class)
@SuppressLint("InflateParams")
public final class PlayerController implements IControllerInternal {

	private static final int CONTROLLER_AUTO_HIDE_MS = 3000;
	private static final int HINT_HIDE_DELAY_MS = 1500;
	private static final int DEFAULT_HINT_HIDE_DELAY_MS = 1000;
	private static final int GESTURE_DETECTION_DELAY_MS = 500;
	private static final float MAX_POPUP_WIDTH_PERCENT = 0.8f;
	private static final double ASPECT_RATIO_16_9_VAL = 9 / 16.0;
	private static final int TOP_MARGIN_DP = 48;
	private static final int ASPECT_RATIO_16 = 16;
	private static final int ASPECT_RATIO_9 = 9;
	private static final int BITRATE_UNIT_DIVISOR = 1000;
	private static final int FPS_UPDATE_INTERVAL_MS = 1000;
	private static final int HINT_PADDING_DP = 8;
	private static final int HINT_TOP_MARGIN_DP = 24;
	private static final float HINT_TEXT_SIZE_SP = 12f;
	private static final int SEGMENT_TIME_UNIT_MS = 1000;

	@NonNull
	private final Activity activity;
	@NonNull
	private final PlayerView playerView;
	@NonNull
	private final IPlayerInternal player;
	@NonNull
	private final IEngineInternal engine;
	@NonNull
	private final ZoomTouchListener zoomListener;
	@NonNull
	private final Handler handler = new Handler(Looper.getMainLooper());

	@Nullable
	private TextView resetBtn;
	@Getter
	private boolean isControlsVisible = true;
	@Nullable
	private TextView hintText;
	private boolean isFs = false;
	@Setter
	private boolean longPress = false;
	private boolean isLocked = false;
	@Setter
	private boolean isGesturing = false;
	private long lastTouchUpTime = 0;
	@Nullable
	private ImageButton lockBtn;
	private int playerWidth = 0;
	private int playerHeight = 0;
	private int normalHeight = 0;
	private long lastVideoRenderedCount = 0;
	private long lastFpsUpdateTime = 0;
	private float fps = 0f;

	/**
	 * Creates a new PlayerController instance.
	 *
	 * @param activity The activity context
	 */
	public PlayerController(@NonNull final Activity activity, @NonNull final PlayerView playerView) {
		this.activity = activity;
		this.player = PlayerContext.getInstance().getPlayerInternal();
		this.engine = PlayerContext.getInstance().getEngineInternal();
		this.playerView = playerView;
		this.zoomListener = new ZoomTouchListener(activity, playerView, show -> showReset(show && isFs && isControlsVisible));

		setupHintOverlay();
		setupPlayerView();
		setupListeners();
	}

	@Override
	public void canSkipToNext(final boolean can) {
		playerView.setShowNextButton(can);
	}

	@Override
	public void canSkipToPrevious(final boolean can) {
		playerView.setShowPreviousButton(can);
	}

	@Override
	public void onPictureInPictureModeChanged(final boolean isInPictureInPictureMode) {
		if (isInPictureInPictureMode) {
			// Handle entering PiP mode
			setControlsVisible(false);
			if (!isFs) normalHeight = playerHeight;
			updatePlayerLayout(true);
			playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
		} else {
			// Handle exiting PiP mode
			setControlsVisible(true);
			updatePlayerLayout(isFs);
			playerView.setResizeMode(player.getPrefs().getResizeMode());
		}
	}

	/**
	 * Sets up the player view with initial configuration.
	 * Configures controller behavior, resize mode, and initial dimensions.
	 */
	private void setupPlayerView() {
		playerView.setControllerAnimationEnabled(false);
		playerView.setControllerHideOnTouch(false);
		playerView.setControllerAutoShow(false);
		playerView.setControllerShowTimeoutMs(0);
		playerView.setResizeMode(player.getPrefs().getResizeMode());
		final ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) playerView.getLayoutParams();
		params.topMargin = (int) (TOP_MARGIN_DP * activity.getResources().getDisplayMetrics().density);
		params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
		final int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
		// Explicitly set to 16:9 aspect ratio
		params.height = (int) (screenWidth * ASPECT_RATIO_16_9_VAL);
		playerView.setLayoutParams(params);
		playerView.setBackgroundColor(Color.BLACK);
	}

	/**
	 * Sets up all listeners for player interactions.
	 * Includes touch gestures, time bar scrubbing, and player state changes.
	 */
	@SuppressLint("ClickableViewAccessibility")
	private void setupListeners() {
		final PlayerGestureListener gestureListener = new PlayerGestureListener(activity, playerView, player, engine, handler, this);

		final GestureDetector detector = new GestureDetector(activity, gestureListener);

		playerView.setOnTouchListener((v, ev) -> {
			if (isLocked) {
				if (ev.getAction() == MotionEvent.ACTION_UP) setControlsVisible(!isControlsVisible);
				return true;
			}
			if (ev.getAction() == MotionEvent.ACTION_DOWN && isControlsVisible)
				handler.removeCallbacks(hideControls);

			boolean handledByDetector = false;
			if (ev.getAction() == MotionEvent.ACTION_MOVE) {
				if (System.currentTimeMillis() - lastTouchUpTime >= GESTURE_DETECTION_DELAY_MS)
					handledByDetector = detector.onTouchEvent(ev);
			} else handledByDetector = detector.onTouchEvent(ev);

			if (!handledByDetector && isFs) zoomListener.onTouch(ev);

			if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
				lastTouchUpTime = System.currentTimeMillis();
				final boolean wasInteracting = isGesturing || longPress;
				isGesturing = false;

				if (longPress) {
					engine.setSpeed(player.getPrefs().getSpeed());
					longPress = false;
					hideHint();
				}

				if (isControlsVisible && wasInteracting) hideControlsAutomatically();
			}
			return true;
		});

		final DefaultTimeBar bar = playerView.findViewById(R.id.exo_progress);
		if (bar != null) {
			bar.addListener(new TimeBar.OnScrubListener() {
				@Override
				public void onScrubStart(@NonNull final TimeBar bar, final long position) {
					handler.removeCallbacks(hideControls);
				}

				@Override
				public void onScrubMove(@NonNull final TimeBar bar, final long position) {
					handleScrubMove(position);
				}

				@Override
				public void onScrubStop(@NonNull final TimeBar bar, final long position, final boolean canceled) {
					hideControlsAutomatically();
				}
			});
		}

		playerView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
			if (right - left != playerWidth || bottom - top != playerHeight) {
				playerWidth = right - left;
				playerHeight = bottom - top;
			}
		});

		player.addListener(new Player.Listener() {
			@Override
			public void onPlaybackStateChanged(final int state) {
				if (state == Player.STATE_READY || state == Player.STATE_ENDED) hideControlsAutomatically();
				else if (state == Player.STATE_BUFFERING && isControlsVisible) setControlsVisible(true);
			}

			@Override
			public void onIsPlayingChanged(final boolean isPlaying) {
				updatePlayPauseButtons(isPlaying);
				if (!isPlaying && isControlsVisible) hideControlsAutomatically();
			}

			@Override
			public void onMediaItemTransition(@Nullable final MediaItem mediaItem, final int reason) {
				if (!isFs) {
					final int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
					final ViewGroup.LayoutParams layoutParams = playerView.getLayoutParams();
					if (layoutParams instanceof ConstraintLayout.LayoutParams params) {
						params.height = (int) (screenWidth * ASPECT_RATIO_16_9_VAL);
						playerView.setLayoutParams(params);
					}
					player.getTabManager().evaluateJavascript("window.changePlayerHeight();", null);
				}
			}

		});

		setupButtonListeners();
		playerView.showController();
		hideControlsAutomatically();
	}

	private void setupButtonListeners() {
		setupPlaybackButtons();
		setupQualityAndSpeedButtons();
		setupSubtitleAndSegmentButtons();
		setupOverlayAndMoreButtons();
	}

	private void setupPlaybackButtons() {
		final View play = playerView.findViewById(exo_play);
		final View pause = playerView.findViewById(exo_pause);
		if (play != null) play.setOnClickListener(v -> {
			engine.play();
			setControlsVisible(true);
		});
		if (pause != null) pause.setOnClickListener(v -> {
			engine.pause();
			setControlsVisible(true);
		});

		lockBtn = playerView.findViewById(R.id.btn_lock);
		if (lockBtn != null) lockBtn.setOnClickListener(v -> toggleLock());

		final ImageButton fullscreenBtn = playerView.findViewById(R.id.btn_fullscreen);
		if (fullscreenBtn != null) {
			fullscreenBtn.setImageResource(isFs ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
			fullscreenBtn.setOnClickListener(v -> {
				onToggleFullscreen();
				setControlsVisible(true);
			});
		}

		final ImageButton loopBtn = playerView.findViewById(R.id.btn_loop);
		if (loopBtn != null) {
			final boolean enabled = player.getPrefs().isLoopEnabled();
			engine.setRepeatMode(enabled ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
			loopBtn.setImageResource(enabled ? R.drawable.ic_repeat_on : R.drawable.ic_repeat_off);
			loopBtn.setOnClickListener(v -> {
				final boolean newEnabled = !player.getPrefs().isLoopEnabled();
				player.getPrefs().setLoopEnabled(newEnabled);
				engine.setRepeatMode(newEnabled ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
				loopBtn.setImageResource(newEnabled ? R.drawable.ic_repeat_on : R.drawable.ic_repeat_off);
				showHint(activity.getString(newEnabled ? R.string.repeat_on : R.string.repeat_off), DEFAULT_HINT_HIDE_DELAY_MS);
				setControlsVisible(true);
			});
		}
	}

	private void setupQualityAndSpeedButtons() {
		final TextView speedView = playerView.findViewById(R.id.btn_speed);
		final TextView qualityView = playerView.findViewById(R.id.btn_quality);
		if (speedView != null) {
			speedView.setText(String.format(Locale.getDefault(), "%sx", player.getSpeed()));
			speedView.setOnClickListener(v -> {
				setControlsVisible(true);
				final PopupMenu menu = new PopupMenu(activity, v);
				final float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
				for (final float s : speeds) menu.getMenu().add(s + "x");
				menu.setOnMenuItemClickListener(item -> {
					final CharSequence title = item.getTitle();
					if (title != null) {
						final String label = title.toString();
						final float target = Float.parseFloat(label.replace("x", ""));
						player.onSpeedSelected(target);
						speedView.setText(label);
						hideControlsAutomatically();
					}
					return true;
				});
				menu.show();
			});
		}

		if (qualityView != null) {
			qualityView.setText(player.getQuality());
			qualityView.setOnClickListener(v -> {
				setControlsVisible(true);
				final PopupMenu menu = new PopupMenu(activity, v);
				for (final String res : player.getAvailableResolutions()) menu.getMenu().add(res);
				menu.setOnMenuItemClickListener(item -> {
					final CharSequence title = item.getTitle();
					if (title != null) {
						final String res = title.toString();
						player.onQualitySelected(res);
						qualityView.setText(res);
						hideControlsAutomatically();
					}
					return true;
				});
				menu.show();
			});
		}
	}

	private void setupSubtitleAndSegmentButtons() {
		final ImageButton subtitlesBtn = playerView.findViewById(R.id.btn_subtitles);
		if (subtitlesBtn != null) {
			subtitlesBtn.setImageResource(engine.areSubtitlesEnabled() ? R.drawable.ic_subtitles_on : R.drawable.ic_subtitles_off);
			subtitlesBtn.setOnClickListener(v -> showSubtitleMenu(subtitlesBtn));
		}

		final ImageButton segmentsBtn = playerView.findViewById(R.id.btn_segments);
		if (segmentsBtn != null) segmentsBtn.setOnClickListener(this::showSegmentsPopup);
	}

	private void showSubtitleMenu(@NonNull final ImageButton subtitlesBtn) {
		setControlsVisible(true);
		final PopupMenu menu = new PopupMenu(activity, subtitlesBtn);
		final List<String> availableSubtitles = engine.getSubtitles();
		final boolean subtitlesAvailable = !availableSubtitles.isEmpty();

		if (subtitlesAvailable)
			menu.getMenu().add(0, 0, Menu.NONE, activity.getString(R.string.subtitles_off));
		else menu.getMenu().add(0, 0, Menu.NONE, activity.getString(R.string.no_subtitles));

		for (int i = 0; i < availableSubtitles.size(); i++)
			menu.getMenu().add(0, i + 1, Menu.NONE, availableSubtitles.get(i));

		menu.setOnMenuItemClickListener(item -> {
			final int itemId = item.getItemId();
			if (itemId == 0) {
				engine.setSubtitlesEnabled(false);
				player.getPrefs().setSubtitleLanguage(null);
				subtitlesBtn.setImageResource(R.drawable.ic_subtitles_off);
				if (subtitlesAvailable)
					showHint(activity.getString(R.string.subtitles_off), DEFAULT_HINT_HIDE_DELAY_MS);
			} else {
				final int index = itemId - 1;
				final String selectedSubtitle = availableSubtitles.get(index);
				engine.setSubtitleLanguage(index);
				player.getPrefs().setSubtitleLanguage(selectedSubtitle);
				subtitlesBtn.setImageResource(R.drawable.ic_subtitles_on);
				showHint(activity.getString(R.string.subtitles_on) + ": " + selectedSubtitle, DEFAULT_HINT_HIDE_DELAY_MS);
			}
			hideControlsAutomatically();
			return true;
		});
		menu.show();
	}

	private void showSegmentsPopup(@NonNull final View anchor) {
		setControlsVisible(true);
		final ListPopupWindow listPopup = new ListPopupWindow(activity);
		listPopup.setAnchorView(anchor);

		final List<StreamSegment> segments = player.getStreamSegments();
		final String[] segmentTitles = new String[segments.size()];
		for (int i = 0; i < segments.size(); i++) {
			final StreamSegment segment = segments.get(i);
			final String time = DateUtils.formatElapsedTime(Math.max(segment.getStartTimeSeconds(), 0));
			segmentTitles[i] = time + " - " + segment.getTitle();
		}

		final ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, R.layout.list_item_segment, segmentTitles);
		listPopup.setAdapter(adapter);

		int maxWidth = 0;
		for (int i = 0; i < segmentTitles.length; i++) {
			final View measureView = adapter.getView(i, null, new ListView(activity));
			measureView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
			maxWidth = Math.max(maxWidth, measureView.getMeasuredWidth());
		}
		final int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
		listPopup.setWidth(Math.min(maxWidth, (int) (screenWidth * MAX_POPUP_WIDTH_PERCENT)));

		listPopup.setOnItemClickListener((parent, view, position, id) -> {
			final StreamSegment selectedSegment = segments.get(position);
			engine.seekTo(selectedSegment.getStartTimeSeconds() * (long) SEGMENT_TIME_UNIT_MS);
			showHint(activity.getString(R.string.jumped_to_segment, selectedSegment.getTitle()), HINT_HIDE_DELAY_MS);
			hideControlsAutomatically();
			listPopup.dismiss();
		});

		listPopup.show();
		final ListView internalListView = listPopup.getListView();
		if (internalListView != null)
			internalListView.setOnItemLongClickListener((parent, view, position, id) -> {
				showSegmentDetailsDialog(segments.get(position));
				listPopup.dismiss();
				return true;
			});
	}

	private void showSegmentDetailsDialog(@NonNull final StreamSegment selectedSegment) {
		final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
		final View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_segment, null);

		final TextView titleView = dialogView.findViewById(R.id.segment_title);
		if (titleView != null) {
			titleView.setMaxLines(3);
			titleView.setMovementMethod(new ScrollingMovementMethod());
			titleView.setText(selectedSegment.getTitle());
		}
		final TextView timeView = dialogView.findViewById(R.id.segment_time);
		if (timeView != null) {
			final String time = DateUtils.formatElapsedTime(Math.max(selectedSegment.getStartTimeSeconds(), 0));
			timeView.setText(time);
		}
		final ImageView thumbnailView = dialogView.findViewById(R.id.segment_thumbnail);
		if (thumbnailView != null) {
			final String thumbnailUrl = selectedSegment.getPreviewUrl() != null ? selectedSegment.getPreviewUrl() : player.getThumbnail();
			Picasso.get().load(thumbnailUrl).into(thumbnailView);
		}

		builder.setView(dialogView).setPositiveButton(R.string.jump, (dialog, which) -> {
			engine.seekTo(selectedSegment.getStartTimeSeconds() * (long) SEGMENT_TIME_UNIT_MS);
			showHint(activity.getString(R.string.jumped_to_segment, selectedSegment.getTitle()), HINT_HIDE_DELAY_MS);
			hideControlsAutomatically();
		}).setNegativeButton(R.string.close, null);
		builder.show();
	}

	private void setupOverlayAndMoreButtons() {
		resetBtn = playerView.findViewById(R.id.btn_reset);
		if (resetBtn != null) {
			resetBtn.setOnClickListener(v -> {
				onResetZoom();
				setControlsVisible(true);
			});
		}

		final ImageButton moreBtn = playerView.findViewById(R.id.btn_more);
		if (moreBtn != null) {
			moreBtn.setOnClickListener(v -> {
				setControlsVisible(true);
				showMoreOptionsBottomSheet();
			});
		}
	}


	/**
	 * Shows the more options bottom sheet dialog.
	 */
	private void showMoreOptionsBottomSheet() {
		final BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(activity);
		final View bottomSheetView = activity.getLayoutInflater().inflate(R.layout.bottom_sheet_more_options, null);
		bottomSheetDialog.setContentView(bottomSheetView);

		setupBottomSheetOption(bottomSheetView, R.id.option_resize_mode, v -> {
			showResizeModeOptions();
			bottomSheetDialog.dismiss();
		});

		setupBottomSheetOption(bottomSheetView, R.id.option_pip, v -> {
			final Rational aspectRatio = new Rational(ASPECT_RATIO_16, ASPECT_RATIO_9);
			final PictureInPictureParams params = new PictureInPictureParams.Builder().setAspectRatio(aspectRatio).build();
			activity.enterPictureInPictureMode(params);
			bottomSheetDialog.dismiss();
		});

		setupBottomSheetOption(bottomSheetView, R.id.option_stream_details, v -> {
			showVideoDetails();
			bottomSheetDialog.dismiss();
		});

		setupBottomSheetOption(bottomSheetView, R.id.option_audio_track, v -> {
			showAudioTrackOptions();
			bottomSheetDialog.dismiss();
		});

		bottomSheetDialog.show();
	}

	private void setupBottomSheetOption(@NonNull final View root, final int id, @NonNull final View.OnClickListener listener) {
		final View option = root.findViewById(id);
		if (option != null) option.setOnClickListener(listener);
	}


	/**
	 * Shows resize mode options.
	 */
	private void showResizeModeOptions() {
		final String[] options = {activity.getString(R.string.resize_fit), activity.getString(R.string.resize_fill), activity.getString(R.string.resize_zoom), activity.getString(R.string.resize_fixed_width), activity.getString(R.string.resize_fixed_height)};
		final int[] modes = {AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH, AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT};
		final ListAdapter adapter = getAdapter(modes, options);

		new MaterialAlertDialogBuilder(activity).setTitle(R.string.resize_mode).setAdapter(adapter, (dialog, which) -> {
			final int selectedMode = modes[which];
			playerView.setResizeMode(selectedMode);
			player.getPrefs().setResizeMode(selectedMode);
			showHint(options[which], DEFAULT_HINT_HIDE_DELAY_MS);
			dialog.dismiss();
			hideControlsAutomatically();
		}).setNegativeButton(R.string.cancel, null).show();
	}

	@NonNull
	private ListAdapter getAdapter(@NonNull final int[] modes, @NonNull final String[] options) {
		final int[] icons = {R.drawable.ic_resize_fit, R.drawable.ic_resize_fill, R.drawable.ic_resize_zoom, R.drawable.ic_resize_width, R.drawable.ic_resize_height};

		// Find index
		final int mode = player.getPrefs().getResizeMode();
		int tempChecked = 0;
		for (int i = 0; i < modes.length; i++)
			if (modes[i] == mode) tempChecked = i;
		final int checkedItem = tempChecked;

		// Highlight selected item
		return new ArrayAdapter<>(activity, R.layout.dialog_resize_mode_item, options) {
			@NonNull
			@Override
			public View getView(final int position, @Nullable final View convertView, @NonNull final ViewGroup parent) {
				View view = convertView;
				if (view == null)
					view = activity.getLayoutInflater().inflate(R.layout.dialog_resize_mode_item, parent, false);

				final ImageView icon = view.findViewById(R.id.icon);
				final TextView text = view.findViewById(R.id.text);

				if (icon != null) icon.setImageResource(icons[position]);
				if (text != null) text.setText(getItem(position));

				// Highlight selected item
				if (position == checkedItem) {
					final TypedValue tv = new TypedValue();
					activity.getTheme().resolveAttribute(android.R.attr.colorPrimary, tv, true);
					if (icon != null) icon.setColorFilter(tv.data);
					if (text != null) text.setTextColor(tv.data);
				} else {
					if (icon != null) icon.setColorFilter(activity.getColor(android.R.color.darker_gray));
					if (text != null) text.setTextColor(activity.getColor(android.R.color.primary_text_dark));
				}

				return view;
			}
		};
	}

	/**
	 * Shows video details dialog.
	 */
	private void showVideoDetails() {
		final StreamDetails details = player.getStreamDetails();
		if (details == null) {
			showHint(activity.getString(R.string.unable_to_get_stream_info), HINT_HIDE_DELAY_MS);
			hideControlsAutomatically();
			return;
		}

		final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
		builder.setTitle(R.string.info);
		builder.setPositiveButton(R.string.confirm, null);

		final String[] info = {getVideoDetailsText(details)};
		builder.setMessage(info[0]);

		builder.setNeutralButton(R.string.copy, (dialog, which) -> {
			final ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
			if (clipboard != null) {
				final ClipData clip = ClipData.newPlainText("Video Details", info[0]);
				clipboard.setPrimaryClip(clip);
				showHint(activity.getString(R.string.debug_info_copied), DEFAULT_HINT_HIDE_DELAY_MS);
			}
		});

		final AlertDialog dialog = builder.show();
		hideControlsAutomatically();

		// Real-time updates
		final Handler updateHandler = new Handler(Looper.getMainLooper());
		final Runnable updateRunnable = new Runnable() {
			@Override
			public void run() {
				if (dialog.isShowing()) {
					info[0] = getVideoDetailsText(details);
					dialog.setMessage(info[0]);
					updateHandler.postDelayed(this, 1000);
				}
			}
		};
		updateHandler.post(updateRunnable);
	}

	/**
	 * Generates the text for video details.
	 */
	@NonNull
	private String getVideoDetailsText(@NonNull final StreamDetails details) {
		final StringBuilder info = new StringBuilder();

		final Optional<Format> videoFormatOpt = player.getVideoFormat();
		final Optional<Format> audioFormatOpt = player.getAudioFormat();
		final DecoderCounters decoderCounters = player.getVideoDecoderCounters();

		if (decoderCounters != null) {
			final long now = System.currentTimeMillis();
			if (lastFpsUpdateTime > 0) {
				final long timeDiff = now - lastFpsUpdateTime;
				if (timeDiff >= GESTURE_DETECTION_DELAY_MS) {
					final long countDiff = decoderCounters.renderedOutputBufferCount - lastVideoRenderedCount;
					fps = (countDiff * (float) FPS_UPDATE_INTERVAL_MS) / timeDiff;
					lastVideoRenderedCount = decoderCounters.renderedOutputBufferCount;
					lastFpsUpdateTime = now;
				}
			} else {
				lastVideoRenderedCount = decoderCounters.renderedOutputBufferCount;
				lastFpsUpdateTime = now;
			}
			info.append(activity.getString(R.string.fps)).append(": ").append(String.format(Locale.getDefault(), "%.2f", fps)).append("\n");
			info.append(activity.getString(R.string.dropped_frames)).append(": ").append(decoderCounters.droppedBufferCount).append("\n");
		}

		videoFormatOpt.ifPresent(videoFormat -> {
			info.append(activity.getString(R.string.video_format)).append(": ").append(videoFormat.sampleMimeType).append("\n");
			info.append(activity.getString(R.string.resolution)).append(": ").append(videoFormat.width).append("x").append(videoFormat.height).append("\n");
			info.append(activity.getString(R.string.bitrate)).append(": ").append(videoFormat.bitrate / BITRATE_UNIT_DIVISOR).append(" kbps\n");
		});

		audioFormatOpt.ifPresent(audioFormat -> {
			info.append(activity.getString(R.string.audio_format)).append(": ").append(audioFormat.sampleMimeType).append("\n");
			if (audioFormat.bitrate > 0)
				info.append(activity.getString(R.string.bitrate)).append(": ").append(audioFormat.bitrate / BITRATE_UNIT_DIVISOR).append(" kbps\n");
			if (audioFormat.channelCount > 0)
				info.append(activity.getString(R.string.channels)).append(": ").append(audioFormat.channelCount).append("\n");
			if (audioFormat.sampleRate > 0)
				info.append(activity.getString(R.string.sample_rate)).append(": ").append(audioFormat.sampleRate).append(" Hz\n");
		});

		final String quality = player.getQuality();
		final AudioStream audio = player.getAudioStream();

		// Video Streams
		final List<VideoStream> videoStreams = details.getVideoStreams();
		if (!videoStreams.isEmpty()) {
			boolean hasActiveVideo = false;
			for (int i = 0; i < videoStreams.size(); i++) {
				final VideoStream vs = videoStreams.get(i);
				if (vs.getResolution().equals(quality)) {
					final int index = i + 1;
					info.append(activity.getString(R.string.active_stream_video, index, activity.getString(R.string.active_label), vs.getResolution(), vs.getFormat() != null ? vs.getFormat().name() : activity.getString(R.string.unknown), vs.getCodec())).append("\n");
					hasActiveVideo = true;
					break;
				}
			}

			if (!hasActiveVideo)
				info.append(activity.getString(R.string.no_active_video_stream)).append("\n");
		} else info.append(activity.getString(R.string.no_active_video_stream)).append("\n");

		if (audio != null) {
			final int bitrate = audio.getAverageBitrate();
			final String bitrateStr = bitrate > 0 ? bitrate + "kbps" : activity.getString(R.string.unknown_bitrate);
			info.append(activity.getString(R.string.active_stream_audio, audio.getFormat() != null ? audio.getFormat().name() : activity.getString(R.string.unknown), audio.getCodec(), bitrateStr));
		} else {
			info.append(activity.getString(R.string.no_active_audio_stream));
		}

		return info.toString();
	}


	/**
	 * Shows audio track options.
	 */
	private void showAudioTrackOptions() {
		final Optional<StreamDetails> detailsOpt = Optional.ofNullable(player.getStreamDetails());
		if (detailsOpt.isEmpty()) {
			showHint(activity.getString(R.string.unable_to_get_stream_info), HINT_HIDE_DELAY_MS);
			hideControlsAutomatically();
			return;
		}

		final List<AudioStream> audioStreams = detailsOpt.get().getAudioStreams();
		if (audioStreams.isEmpty()) {
			showHint(activity.getString(R.string.no_audio_tracks), HINT_HIDE_DELAY_MS);
			hideControlsAutomatically();
			return;
		}

		// Prepare audio track options
		final String[] audioOptions = new String[audioStreams.size()];
		for (int i = 0; i < audioStreams.size(); i++) {
			final AudioStream as = audioStreams.get(i);
			final String bitrate = as.getAverageBitrate() > 0 ? as.getAverageBitrate() + "kbps" : activity.getString(R.string.unknown_bitrate);
			audioOptions[i] = as.getAudioTrackName() != null ? String.format(Locale.getDefault(), "%s (%s - %s)", as.getAudioTrackName(), as.getFormat(), bitrate) : String.format(Locale.getDefault(), "%s - %s", as.getFormat(), bitrate);
		}

		new MaterialAlertDialogBuilder(activity).setTitle(R.string.select_audio_track).setItems(audioOptions, (dialog, which) -> {
			final AudioStream selectedStream = audioStreams.get(which);
			player.switchAudioTrack(selectedStream);
			showHint(activity.getString(R.string.switched_to_audio_track, audioOptions[which]), HINT_HIDE_DELAY_MS);
		}).setNegativeButton(R.string.cancel, null).show();

		hideControlsAutomatically();
	}

	private void handleScrubMove(final long position) {
		final long end = player.getSponsor().shouldSkip(position);
		if (end > 0) engine.seekTo(end);
	}

	/**
	 * Sets up the hint overlay for displaying gesture feedback.
	 * Creates a TextView overlay that shows transient hints like "Repeat on" or
	 * "Seek +5s".
	 */
	private void setupHintOverlay() {
		final FrameLayout overlayFrame = playerView.getOverlayFrameLayout();
		if (overlayFrame == null) return;
		final TextView tv = new TextView(activity);
		final int pad = (int) (HINT_PADDING_DP * activity.getResources().getDisplayMetrics().density);
		tv.setBackgroundResource(R.drawable.bg_gesture_hint);
		tv.setTextColor(Color.WHITE);
		tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, HINT_TEXT_SIZE_SP);
		tv.setPadding(pad, pad / 2, pad, pad / 2);
		tv.setVisibility(View.GONE);
		final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
		lp.topMargin = (int) (HINT_TOP_MARGIN_DP * activity.getResources().getDisplayMetrics().density);
		overlayFrame.addView(tv, lp);
		this.hintText = tv;
	}

	/**
	 * Shows a transient hint message on the player overlay.
	 *
	 * @param text       The text to display in the hint
	 * @param durationMs The duration in milliseconds for the hint to remain visible
	 */
	@Override
	public void showHint(@NonNull final String text, final long durationMs) {
		if (hintText == null) return;
		hintText.setText(text);
		hintText.setTranslationY(0);
		if (hintText.getVisibility() != View.VISIBLE || hintText.getAlpha() < 1f)
			PlayerUtils.animateViewAlpha(hintText, 1f, View.VISIBLE);
		handler.removeCallbacks(this::hideHint);
		if (durationMs > 0) handler.postDelayed(this::hideHint, durationMs);
	}

	/**
	 * Hides the hint message from the player overlay.
	 */
	@Override
	public void hideHint() {
		handler.removeCallbacks(this::hideHint);
		if (hintText != null) PlayerUtils.animateViewAlpha(hintText, 0f, View.GONE);
	}

	private void updatePlayPauseButtons(final boolean isPlaying) {
		final View play = playerView.findViewById(exo_play);
		final View pause = playerView.findViewById(exo_pause);
		if (play != null) play.setVisibility(!isPlaying ? View.VISIBLE : View.GONE);
		if (pause != null) pause.setVisibility(isPlaying ? View.VISIBLE : View.GONE);
	}

	@Override
	public boolean isFullscreen() {
		return isFs;
	}

	@Override
	public void exitFullscreen() {
		if (isLocked) toggleLock();
		if (isFs) onToggleFullscreen();
	}	private final Runnable hideControls = () -> setControlsVisible(false);

	@Override
	public void setControlsVisible(final boolean show) {
		isControlsVisible = show;
		handler.removeCallbacks(hideControls);

		final View centerControls = playerView.findViewById(R.id.center_controls);
		final View otherControls = playerView.findViewById(R.id.other_controls);
		final DefaultTimeBar bar = playerView.findViewById(R.id.exo_progress);

		final int state = engine.getPlaybackState();
		final int visibility = show ? View.VISIBLE : View.GONE;

		if (isLocked) {
			if (centerControls != null) centerControls.setVisibility(View.GONE);
			if (otherControls != null) otherControls.setVisibility(View.GONE);
			if (bar != null) bar.setVisibility(View.GONE);
			showReset(false);
			if (lockBtn != null) lockBtn.setVisibility(show ? View.VISIBLE : View.GONE);
		} else {
			if (otherControls != null) otherControls.setVisibility(visibility);
			if (centerControls != null)
				centerControls.setVisibility(state == Player.STATE_BUFFERING || state == Player.STATE_IDLE || PlayerUtils.isInPictureInPictureMode(activity) ? View.GONE : visibility);
			if (bar != null) bar.setVisibility(visibility);
			showReset(isFs && show && zoomListener.shouldShowReset());
			if (lockBtn != null) lockBtn.setVisibility((show && isFs) ? View.VISIBLE : View.GONE);
		}

		if (show) handler.postDelayed(hideControls, CONTROLLER_AUTO_HIDE_MS);
	}

	private void onResetZoom() {
		zoomListener.reset();
	}

	private void onToggleFullscreen() {
		isFs = !isFs;
		final Window window = activity.getWindow();
		if (window != null) {
			if (isFs) enterFullscreen(window);
			else exitFullscreen(window);
		}
		playerView.setResizeMode(player.getPrefs().getResizeMode());
		updateFullscreenButton();
		setControlsVisible(true);
	}

	private void updateFullscreenButton() {
		final ImageButton fullscreenBtn = playerView.findViewById(R.id.btn_fullscreen);
		if (fullscreenBtn != null)
			fullscreenBtn.setImageResource(isFs ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
	}

	private void updatePlayerLayout(final boolean fullscreen) {
		final ViewGroup.LayoutParams layoutParams = playerView.getLayoutParams();
		if (layoutParams instanceof ConstraintLayout.LayoutParams params) {
			if (fullscreen) {
				params.topMargin = 0;
				params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
				params.height = ConstraintLayout.LayoutParams.MATCH_PARENT;
			} else {
				params.topMargin = (int) (TOP_MARGIN_DP * activity.getResources().getDisplayMetrics().density);
				params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
				if (normalHeight > 0) params.height = normalHeight;
				else
					params.height = (int) (activity.getResources().getDisplayMetrics().widthPixels * ASPECT_RATIO_16_9_VAL);
			}
			playerView.setLayoutParams(params);
		}
	}

	private void enterFullscreen(@NonNull final Window window) {
		final boolean usePortrait = PlayerUtils.isPortrait(engine);

		if (usePortrait) activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
		else activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

		window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
		if (!PlayerUtils.isInPictureInPictureMode(activity)) normalHeight = playerHeight;
		updatePlayerLayout(true);
		playerView.setBackgroundColor(Color.BLACK);
	}

	private void exitFullscreen(@NonNull final Window window) {
		activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
		window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
		updatePlayerLayout(false);
		playerView.setBackgroundColor(Color.BLACK);
		zoomListener.reset();
	}

	/**
	 * Schedules the controls to automatically hide after the configured delay.
	 * Cancels any existing hide schedule before scheduling a new one.
	 */
	private void hideControlsAutomatically() {
		handler.removeCallbacks(hideControls);
		handler.postDelayed(hideControls, CONTROLLER_AUTO_HIDE_MS);
	}

	private void showReset(final boolean show) {
		if (resetBtn != null) resetBtn.setVisibility(show ? View.VISIBLE : View.GONE);
	}

	private void toggleLock() {
		isLocked = !isLocked;
		if (lockBtn != null) {
			lockBtn.setImageResource(isLocked ? R.drawable.ic_lock : R.drawable.ic_unlock);
			lockBtn.setContentDescription(activity.getString(isLocked ? R.string.unlock_screen : R.string.lock_screen));
		}
		if (isLocked) showHint(activity.getString(R.string.lock_screen), DEFAULT_HINT_HIDE_DELAY_MS);
		else showHint(activity.getString(R.string.unlock_screen), DEFAULT_HINT_HIDE_DELAY_MS);
		setControlsVisible(true);
	}

	@Override
	public void release() {
		handler.removeCallbacksAndMessages(null);
		// Clean up touch listeners
		playerView.setOnTouchListener(null);
		// Hide any active hints
		hideHint();
	}

}

