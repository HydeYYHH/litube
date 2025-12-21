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
import android.widget.LinearLayout;
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
import java.util.Objects;

@OptIn(markerClass = UnstableApi.class)
@SuppressLint("InflateParams")
public class PlayerController implements IControllerInternal {

	private static final int CONTROLLER_AUTO_HIDE_MS = 3000;
	private final Activity activity;
	private final PlayerView playerView;
	private final IPlayerInternal player;
	private final IEngineInternal engine;
	private final ZoomTouchListener zoomListener;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private TextView resetBtn;
	private boolean controllerVisible = true;
	private TextView hintText;
	private boolean isFs = false;
	private boolean longPress = false;
	private boolean isLocked = false;
	private boolean isGesturing = false;
	private long lastTouchUpTime = 0;
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
	public PlayerController(Activity activity, PlayerView playerView) {
		this.activity = activity;
		this.player = PlayerContext.getInstance().getPlayerInternal();
		this.engine = PlayerContext.getInstance().getEngineInternal();
		this.playerView = playerView;
		this.zoomListener = new ZoomTouchListener(activity, playerView, show -> showReset(show && isFs && controllerVisible));

		setupHintOverlay();
		setupPlayerView();
		setupListeners();
	}

	@Override
	public void canSkipToNext(boolean can) {
		playerView.setShowNextButton(can);
	}

	@Override
	public void canSkipToPrevious(boolean can) {
		playerView.setShowPreviousButton(can);
	}

	@Override
	public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
		if (isInPictureInPictureMode) {
			// Handle entering PiP mode
			setControlsVisible(false);
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
		ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) playerView.getLayoutParams();
		params.topMargin = (int) (48 * activity.getResources().getDisplayMetrics().density);
		params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
		int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
		// Explicitly set to 16:9 aspect ratio
		params.height = (int) (screenWidth * 9 / 16.0);
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
				if (ev.getAction() == MotionEvent.ACTION_UP) setControlsVisible(!controllerVisible);
				return true;
			}
			if (ev.getAction() == MotionEvent.ACTION_DOWN)
				if (controllerVisible) handler.removeCallbacks(hideControls);

			boolean handledByDetector = false;
			if (ev.getAction() == MotionEvent.ACTION_MOVE) {
				if (System.currentTimeMillis() - lastTouchUpTime >= 500) {
					handledByDetector = detector.onTouchEvent(ev);
				}
			} else handledByDetector = detector.onTouchEvent(ev);
			if (!handledByDetector && isFs) zoomListener.onTouch(ev);

			if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
				lastTouchUpTime = System.currentTimeMillis();
				boolean wasInteracting = isGesturing || longPress;
				isGesturing = false;

				if (longPress) {
					engine.setSpeed(player.getPrefs().getSpeed());
					longPress = false;
					hideHint();
				}

				if (controllerVisible && wasInteracting) hideControlsAutomatically();
			}
			return true;
		});

		DefaultTimeBar bar = playerView.findViewById(R.id.exo_progress);
		bar.addListener(new TimeBar.OnScrubListener() {
			@Override
			public void onScrubStart(@NonNull TimeBar bar, long position) {
				handler.removeCallbacks(hideControls);
			}

			@Override
			public void onScrubMove(@NonNull TimeBar bar, long position) {
				handleScrubMove(position);
			}

			@Override
			public void onScrubStop(@NonNull TimeBar bar, long position, boolean canceled) {
				hideControlsAutomatically();
			}
		});

		playerView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
			if (right - left != playerWidth || bottom - top != playerHeight) {
				playerWidth = right - left;
				playerHeight = bottom - top;
			}
		});

		player.addListener(new Player.Listener() {
			@Override
			public void onPlaybackStateChanged(int state) {
				if (state == Player.STATE_READY || state == Player.STATE_ENDED) hideControlsAutomatically();
				else if (state == Player.STATE_BUFFERING && controllerVisible) setControlsVisible(true);
			}

			@Override
			public void onIsPlayingChanged(boolean isPlaying) {
				updatePlayPauseButtons(isPlaying);
				if (!isPlaying && controllerVisible) hideControlsAutomatically();
			}

			@Override
			public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
				if (!isFs) {
					int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
					ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) playerView.getLayoutParams();
					params.height = (int) (screenWidth * 9 / 16.0);
					playerView.setLayoutParams(params);
					player.getWebview().evaluateJavascript("window.changePlayerHeight();", null);
				}
			}

		});

		setupButtonListeners();
		playerView.showController();
		hideControlsAutomatically();
	}

	private void setupButtonListeners() {
		View play = playerView.findViewById(exo_play);
		View pause = playerView.findViewById(exo_pause);
		play.setOnClickListener(v -> {
			engine.play();
			setControlsVisible(true);
		});
		pause.setOnClickListener(v -> {
			engine.pause();
			setControlsVisible(true);
		});

		lockBtn = playerView.findViewById(R.id.btn_lock);
		lockBtn.setOnClickListener(v -> toggleLock());

		ImageButton fullscreenBtn = playerView.findViewById(R.id.btn_fullscreen);
		fullscreenBtn.setImageResource(isFs ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
		fullscreenBtn.setOnClickListener(v -> {
			onToggleFullscreen();
			setControlsVisible(true);
		});

		TextView speedView = playerView.findViewById(R.id.btn_speed);
		TextView qualityView = playerView.findViewById(R.id.btn_quality);
		speedView.setText(String.format(Locale.getDefault(), "%sx", player.getSpeed()));
		qualityView.setText(player.getQuality());

		speedView.setOnClickListener(v -> {
			setControlsVisible(true);
			PopupMenu menu = new PopupMenu(activity, v);
			float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
			for (float s : speeds)
				menu.getMenu().add(s + "x");
			menu.setOnMenuItemClickListener(item -> {
				String label = Objects.requireNonNull(item.getTitle()).toString();
				float target = Float.parseFloat(label.replace("x", ""));
				player.onSpeedSelected(target);
				speedView.setText(label);
				hideControlsAutomatically();
				return true;
			});
			menu.show();
		});

		qualityView.setOnClickListener(v -> {
			setControlsVisible(true);
			PopupMenu menu = new PopupMenu(activity, v);
			for (String res : player.getAvailableResolutions())
				menu.getMenu().add(res);
			menu.setOnMenuItemClickListener(item -> {
				String res = Objects.requireNonNull(item.getTitle()).toString();
				player.onQualitySelected(res);
				qualityView.setText(res);
				hideControlsAutomatically();
				return true;
			});
			menu.show();
		});

		ImageButton subtitlesBtn = playerView.findViewById(R.id.btn_subtitles);
		subtitlesBtn.setImageResource(engine.areSubtitlesEnabled() ? R.drawable.ic_subtitles_on : R.drawable.ic_subtitles_off);
		subtitlesBtn.setOnClickListener(v -> {
			setControlsVisible(true);
			PopupMenu menu = new PopupMenu(activity, v);

			// Add option for turning off caption
			boolean subtitlesAvailable = !engine.getSubtitles().isEmpty();
			if (subtitlesAvailable)
				menu.getMenu().add(0, 0, Menu.NONE, activity.getString(R.string.subtitles_off));
			else menu.getMenu().add(0, 0, Menu.NONE, activity.getString(R.string.no_subtitles));

			// Add all available captions
			List<String> availableSubtitles = engine.getSubtitles();
			for (int i = 0; i < availableSubtitles.size(); i++) {
				String subtitle = availableSubtitles.get(i);
				menu.getMenu().add(0, i + 1, Menu.NONE, subtitle);
			}

			menu.setOnMenuItemClickListener(item -> {
				int itemId = item.getItemId();
				if (itemId == 0) {
					// Turn off subtitles
					engine.setSubtitlesEnabled(false);
					player.getPrefs().setSubtitleLanguage(null);
					subtitlesBtn.setImageResource(R.drawable.ic_subtitles_off);
					if (subtitlesAvailable) showHint(activity.getString(R.string.subtitles_off), 1000);
				} else {
					// Select subtitle
					int index = itemId - 1;
					String selectedSubtitle = availableSubtitles.get(index);
					engine.setSubtitleLanguage(index);
					player.getPrefs().setSubtitleLanguage(selectedSubtitle);
					subtitlesBtn.setImageResource(R.drawable.ic_subtitles_on);
					showHint(activity.getString(R.string.subtitles_on) + ": " + selectedSubtitle, 1000);
				}
				hideControlsAutomatically();
				return true;
			});
			menu.show();
		});

		// Segments button listener
		ImageButton segmentsBtn = playerView.findViewById(R.id.btn_segments);
		segmentsBtn.setOnClickListener(v -> {
			setControlsVisible(true);

			ListPopupWindow listPopup = new ListPopupWindow(activity);
			listPopup.setAnchorView(v);

			// Add all available segments
			List<StreamSegment> segments = player.getStreamSegments();
			String[] segmentTitles = new String[segments.size()];
			for (int i = 0; i < segments.size(); i++) {
				StreamSegment segment = segments.get(i);
				String time = DateUtils.formatElapsedTime(Math.max(segment.getStartTimeSeconds(), 0));
				segmentTitles[i] = time + " - " + segment.getTitle();
			}

			// Create adapter
			ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, R.layout.list_item_segment, segmentTitles);
			listPopup.setAdapter(adapter);

			// Measure content width dynamically
			int maxWidth = 0;
			for (int i = 0; i < segmentTitles.length; i++) {
				View measureView = adapter.getView(i, null, new ListView(activity));
				measureView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
				int width = measureView.getMeasuredWidth();
				if (width > maxWidth) maxWidth = width;
			}
			int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
			listPopup.setWidth(Math.min(maxWidth, (int) (screenWidth * 0.8)));

			// Set click listener for normal taps
			listPopup.setOnItemClickListener((parent, view, position, id) -> {
				StreamSegment selectedSegment = segments.get(position);
				engine.seekTo(selectedSegment.getStartTimeSeconds() * 1000L);
				showHint(activity.getString(R.string.jumped_to_segment, selectedSegment.getTitle()), 1500);
				hideControlsAutomatically();
				listPopup.dismiss();
			});

			listPopup.show();
			ListView internalListView = listPopup.getListView();

			// Set long press listener for segment details
			if (internalListView != null) {
				internalListView.setOnItemLongClickListener((parent, view, position, id) -> {
					StreamSegment selectedSegment = segments.get(position);

					// Create dialog to show segment details
					var builder = new MaterialAlertDialogBuilder(activity);
					View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_segment, null);

					// Set segment details
					TextView titleView = dialogView.findViewById(R.id.segment_title);
					titleView.setMaxLines(3);
					titleView.setMovementMethod(new ScrollingMovementMethod());
					TextView timeView = dialogView.findViewById(R.id.segment_time);
					ImageView thumbnailView = dialogView.findViewById(R.id.segment_thumbnail);

					titleView.setText(selectedSegment.getTitle());
					String time = DateUtils.formatElapsedTime(Math.max(selectedSegment.getStartTimeSeconds(), 0));
					timeView.setText(time);
					// Load thumbnail
					if (selectedSegment.getPreviewUrl() != null)
						Picasso.get().load(selectedSegment.getPreviewUrl()).into(thumbnailView);
					else Picasso.get().load(player.getThumbnail()).into(thumbnailView);

					builder.setView(dialogView).setPositiveButton(R.string.jump, (dialog, which) -> {
						engine.seekTo(selectedSegment.getStartTimeSeconds() * 1000L);
						showHint(activity.getString(R.string.jumped_to_segment, selectedSegment.getTitle()), 1500);
						hideControlsAutomatically();
					}).setNegativeButton(R.string.close, null);
					builder.show();
					listPopup.dismiss();
					return true;
				});
			}
		});

		ImageButton loopBtn = playerView.findViewById(R.id.btn_loop);
		boolean enabled = player.getPrefs().isLoopEnabled();
		engine.setRepeatMode(enabled ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
		loopBtn.setImageResource(enabled ? R.drawable.ic_repeat_on : R.drawable.ic_repeat_off);
		loopBtn.setOnClickListener(v -> {
			boolean newEnabled = !player.getPrefs().isLoopEnabled();
			player.getPrefs().setLoopEnabled(newEnabled);
			engine.setRepeatMode(newEnabled ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
			loopBtn.setImageResource(newEnabled ? R.drawable.ic_repeat_on : R.drawable.ic_repeat_off);
			showHint(activity.getString(newEnabled ? R.string.repeat_on : R.string.repeat_off), 1000);
			setControlsVisible(true);
		});

		resetBtn = playerView.findViewById(R.id.btn_reset);
		resetBtn.setOnClickListener(v -> {
			onResetZoom();
			setControlsVisible(true);
		});

		// More button listener
		ImageButton moreBtn = playerView.findViewById(R.id.btn_more);
		moreBtn.setOnClickListener(v -> {
			setControlsVisible(true);
			showMoreOptionsBottomSheet();
		});
	}

	/**
	 * Shows the more options bottom sheet dialog.
	 */
	private void showMoreOptionsBottomSheet() {
		BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(activity);
		View bottomSheetView = activity.getLayoutInflater().inflate(R.layout.bottom_sheet_more_options, null);
		bottomSheetDialog.setContentView(bottomSheetView);

		// ResizeMode option
		LinearLayout resizeModeOption = bottomSheetView.findViewById(R.id.option_resize_mode);
		resizeModeOption.setOnClickListener(v -> {
			showResizeModeOptions();
			bottomSheetDialog.dismiss();
		});

		// PiP option
		LinearLayout pipOption = bottomSheetView.findViewById(R.id.option_pip);
		pipOption.setOnClickListener(v -> {
			Rational aspectRatio = new Rational(16, 9);
			PictureInPictureParams params = new PictureInPictureParams.Builder().setAspectRatio(aspectRatio).build();
			activity.enterPictureInPictureMode(params);
			bottomSheetDialog.dismiss();
		});

		// Stream details option
		LinearLayout streamDetailsOption = bottomSheetView.findViewById(R.id.option_stream_details);
		streamDetailsOption.setOnClickListener(v -> {
			showVideoDetails();
			bottomSheetDialog.dismiss();
		});

		// Audio track option
		LinearLayout audioTrackOption = bottomSheetView.findViewById(R.id.option_audio_track);
		audioTrackOption.setOnClickListener(v -> {
			showAudioTrackOptions();
			bottomSheetDialog.dismiss();
		});

		bottomSheetDialog.show();
	}

	/**
	 * Shows resize mode options.
	 */
	private void showResizeModeOptions() {
		String[] options = {activity.getString(R.string.resize_fit), activity.getString(R.string.resize_fill), activity.getString(R.string.resize_zoom), activity.getString(R.string.resize_fixed_width), activity.getString(R.string.resize_fixed_height)};
		int[] modes = {AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH, AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT};
		ListAdapter adapter = getAdapter(modes, options);

		new MaterialAlertDialogBuilder(activity).setTitle(R.string.resize_mode).setAdapter(adapter, (dialog, which) -> {
			int selectedMode = modes[which];
			playerView.setResizeMode(selectedMode);
			player.getPrefs().setResizeMode(selectedMode);
			showHint(options[which], 1000);
			dialog.dismiss();
			hideControlsAutomatically();
		}).setNegativeButton(R.string.cancel, null).show();
	}

	@NonNull
	private ListAdapter getAdapter(int[] modes, String[] options) {
		int[] icons = {R.drawable.ic_resize_fit, R.drawable.ic_resize_fill, R.drawable.ic_resize_zoom, R.drawable.ic_resize_width, R.drawable.ic_resize_height};

		// Find index
		int mode = player.getPrefs().getResizeMode();
		int tempChecked = 0;
		for (int i = 0; i < modes.length; i++) if (modes[i] == mode) tempChecked = i;
		final int checkedItem = tempChecked;

		// Highlight selected item
		return new ArrayAdapter<>(activity, R.layout.dialog_resize_mode_item, options) {
			@NonNull
			@Override
			public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
				if (convertView == null)
					convertView = activity.getLayoutInflater().inflate(R.layout.dialog_resize_mode_item, parent, false);

				ImageView icon = convertView.findViewById(R.id.icon);
				TextView text = convertView.findViewById(R.id.text);

				icon.setImageResource(icons[position]);
				text.setText(getItem(position));

				// Highlight selected item
				if (position == checkedItem) {
					TypedValue tv = new TypedValue();
					activity.getTheme().resolveAttribute(android.R.attr.colorPrimary, tv, true);
					icon.setColorFilter(tv.data);
					text.setTextColor(tv.data);
				} else {
					icon.setColorFilter(activity.getColor(android.R.color.darker_gray));
					text.setTextColor(activity.getColor(android.R.color.primary_text_dark));
				}

				return convertView;
			}
		};
	}

	/**
	 * Shows video details dialog.
	 */
	private void showVideoDetails() {
		StreamDetails details = player.getStreamDetails();
		if (details == null) {
			showHint(activity.getString(R.string.unable_to_get_stream_info), 1500);
			hideControlsAutomatically();
			return;
		}

		final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
		builder.setTitle(R.string.video_details);
		builder.setPositiveButton(R.string.confirm, null);

		final String[] info = {getVideoDetailsText(details)};
		builder.setMessage(info[0]);

		builder.setNeutralButton(R.string.copy, (dialog, which) -> {
			ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
			ClipData clip = ClipData.newPlainText("Video Details", info[0]);
			clipboard.setPrimaryClip(clip);
			showHint(activity.getString(R.string.debug_info_copied), 1000);
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
	}	private final Runnable hideControls = () -> setControlsVisible(false);

	/**
	 * Generates the text for video details.
	 */
	private String getVideoDetailsText(StreamDetails details) {
		StringBuilder info = new StringBuilder();

		Format videoFormat = player.getVideoFormat();
		Format audioFormat = player.getAudioFormat();
		DecoderCounters decoderCounters = player.getVideoDecoderCounters();

		if (decoderCounters != null) {
			long now = System.currentTimeMillis();
			if (lastFpsUpdateTime > 0) {
				long timeDiff = now - lastFpsUpdateTime;
				if (timeDiff >= 500) {
					long countDiff = decoderCounters.renderedOutputBufferCount - lastVideoRenderedCount;
					fps = (countDiff * 1000f) / timeDiff;
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

		if (videoFormat != null) {
			info.append(activity.getString(R.string.video_format)).append(": ").append(videoFormat.sampleMimeType).append("\n");
			info.append(activity.getString(R.string.resolution)).append(": ").append(videoFormat.width).append("x").append(videoFormat.height).append("\n");
			info.append(activity.getString(R.string.bitrate)).append(": ").append(videoFormat.bitrate / 1000).append(" kbps\n");
		}

		if (audioFormat != null) {
			info.append(activity.getString(R.string.audio_format)).append(": ").append(audioFormat.sampleMimeType).append("\n");
			if (audioFormat.bitrate > 0)
				info.append(activity.getString(R.string.bitrate)).append(": ").append(audioFormat.bitrate / 1000).append(" kbps\n");
			if (audioFormat.channelCount > 0)
				info.append(activity.getString(R.string.channels)).append(": ").append(audioFormat.channelCount).append("\n");
			if (audioFormat.sampleRate > 0)
				info.append(activity.getString(R.string.sample_rate)).append(": ").append(audioFormat.sampleRate).append(" Hz\n");
		}

		String quality = player.getQuality();
		AudioStream audio = player.getAudioStream();

		// Video Streams
		List<VideoStream> videoStreams = details.getVideoStreams();
		boolean hasActiveVideo = false;
		if (videoStreams != null && !videoStreams.isEmpty()) {
			for (int i = 0; i < videoStreams.size(); i++) {
				VideoStream vs = videoStreams.get(i);
				if (vs.getResolution().equals(quality)) {
					hasActiveVideo = true;
					info.append(activity.getString(R.string.active_stream_video, i + 1, activity.getString(R.string.active_label), vs.getResolution(), vs.getFormat() != null ? vs.getFormat().name() : "Unknown", vs.getCodec()));
				}
			}
		}
		if (!hasActiveVideo) info.append(activity.getString(R.string.no_active_video_stream));

		if (audio != null) {
			int bitrate = audio.getAverageBitrate();
			String bitrateStr = bitrate > 0 ? bitrate + "kbps" : activity.getString(R.string.unknown_bitrate);
			info.append(activity.getString(R.string.active_stream_audio, audio.getFormat() != null ? audio.getFormat().name() : "Unknown", audio.getCodec(), bitrateStr));
		} else {
			info.append(activity.getString(R.string.no_active_audio_stream));
		}

		return info.toString();
	}

	/**
	 * Shows audio track options.
	 */
	private void showAudioTrackOptions() {
		StreamDetails details = player.getStreamDetails();
		if (details == null) {
			showHint(activity.getString(R.string.unable_to_get_stream_info), 1500);
			hideControlsAutomatically();
			return;
		}

		List<AudioStream> audioStreams = details.getAudioStreams();
		if (audioStreams == null || audioStreams.isEmpty()) {
			showHint(activity.getString(R.string.no_audio_tracks), 1500);
			hideControlsAutomatically();
			return;
		}

		// Prepare audio track options
		String[] audioOptions = new String[audioStreams.size()];
		for (int i = 0; i < audioStreams.size(); i++) {
			AudioStream as = audioStreams.get(i);
			int bitrate = as.getAverageBitrate();
			String bitrateStr = bitrate > 0 ? bitrate + "kbps" : activity.getString(R.string.unknown_bitrate);

			audioOptions[i] = String.format(Locale.getDefault(), "%s (%s - %s)", as.getAudioTrackName(), as.getFormat(), bitrateStr);
		}

		new MaterialAlertDialogBuilder(activity).setTitle(R.string.select_audio_track).setItems(audioOptions, (dialog, which) -> {
			AudioStream selectedStream = audioStreams.get(which);
			player.switchAudioTrack(selectedStream);
			// Save preference
			player.getPrefs().setPreferredAudioTrack(audioOptions[which]);
			showHint(activity.getString(R.string.switched_to_audio_track, audioOptions[which]), 1500);
		}).setNegativeButton(R.string.cancel, null).show();

		hideControlsAutomatically();
	}

	private void handleScrubMove(long position) {
		long end = player.getSponsor().shouldSkip(position);
		if (end > 0) engine.seekTo(end);
	}

	/**
	 * Sets up the hint overlay for displaying gesture feedback.
	 * Creates a TextView overlay that shows transient hints like "Repeat on" or
	 * "Seek +5s".
	 */
	private void setupHintOverlay() {
		FrameLayout overlayFrame = playerView.getOverlayFrameLayout();
		TextView tv = new TextView(activity);
		int pad = (int) (8 * activity.getResources().getDisplayMetrics().density);
		tv.setBackgroundResource(R.drawable.bg_gesture_hint);
		tv.setTextColor(Color.WHITE);
		tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
		tv.setPadding(pad, pad / 2, pad, pad / 2);
		tv.setVisibility(View.GONE);
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
		lp.topMargin = (int) (24 * activity.getResources().getDisplayMetrics().density);
		if (overlayFrame != null) overlayFrame.addView(tv, lp);
		this.hintText = tv;
	}

	/**
	 * Shows a transient hint message on the player overlay.
	 *
	 * @param text       The text to display in the hint
	 * @param durationMs The duration in milliseconds for the hint to remain visible
	 */
	@Override
	public void showHint(String text, long durationMs) {
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
		PlayerUtils.animateViewAlpha(hintText, 0f, View.GONE);
	}

	private void updatePlayPauseButtons(boolean isPlaying) {
		playerView.findViewById(exo_play).setVisibility(!isPlaying ? View.VISIBLE : View.GONE);
		playerView.findViewById(exo_pause).setVisibility(isPlaying ? View.VISIBLE : View.GONE);
	}

	@Override
	public boolean isFullscreen() {
		return isFs;
	}

	@Override
	public void exitFullscreen() {
		if (isLocked) toggleLock();
		if (isFs) onToggleFullscreen();
	}

	@Override
	public boolean isControlsVisible() {
		return controllerVisible;
	}

	@Override
	public void setControlsVisible(boolean show) {
		controllerVisible = show;
		handler.removeCallbacks(hideControls);


		View centerControls = playerView.findViewById(R.id.center_controls);
		View otherControls = playerView.findViewById(R.id.other_controls);
		DefaultTimeBar bar = playerView.findViewById(R.id.exo_progress);

		int state = engine.getPlaybackState();
		int visibility = show ? View.VISIBLE : View.GONE;

		if (isLocked) {
			centerControls.setVisibility(View.GONE);
			otherControls.setVisibility(View.GONE);
			bar.setVisibility(View.GONE);
			showReset(false);
			lockBtn.setVisibility(show ? View.VISIBLE : View.GONE);
		} else {
			otherControls.setVisibility(visibility);
			centerControls.setVisibility(state == Player.STATE_BUFFERING || state == Player.STATE_IDLE || PlayerUtils.isInPictureInPictureMode(activity) ? View.GONE : visibility);
			bar.setVisibility(visibility);
			showReset(isFs && show && zoomListener.shouldShowReset());
			lockBtn.setVisibility((show && isFs) ? View.VISIBLE : View.GONE);
		}

		if (show) handler.postDelayed(hideControls, CONTROLLER_AUTO_HIDE_MS);
	}

	@Override
	public void setIsGesturing(boolean gesturing) {
		this.isGesturing = gesturing;
	}

	@Override
	public void setLongPress(boolean longPress) {
		this.longPress = longPress;
	}

	private void onResetZoom() {
		zoomListener.reset();
	}

	private void onToggleFullscreen() {
		isFs = !isFs;
		Window window = activity.getWindow();
		if (isFs) enterFullscreen(window);
		else exitFullscreen(window);
		playerView.setResizeMode(player.getPrefs().getResizeMode());
		updateFullscreenButton();
		setControlsVisible(true);
	}

	private void updateFullscreenButton() {
		ImageButton fullscreenBtn = playerView.findViewById(R.id.btn_fullscreen);
		fullscreenBtn.setImageResource(isFs ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
	}

	private void updatePlayerLayout(boolean fullscreen) {
		ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) playerView.getLayoutParams();
		if (fullscreen) {
			params.topMargin = 0;
			params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
			params.height = ConstraintLayout.LayoutParams.MATCH_PARENT;
		} else {
			params.topMargin = (int) (48 * activity.getResources().getDisplayMetrics().density);
			params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
			if (normalHeight > 0) params.height = normalHeight;
			else
				params.height = (int) (activity.getResources().getDisplayMetrics().widthPixels * 9 / 16.0);
		}
		playerView.setLayoutParams(params);
	}

	private void enterFullscreen(Window window) {
		boolean usePortrait = PlayerUtils.isPortrait(engine);

		if (usePortrait) activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
		else activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

		window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
		if (!PlayerUtils.isInPictureInPictureMode(activity)) normalHeight = playerHeight;
		updatePlayerLayout(true);
		playerView.setBackgroundColor(Color.BLACK);
	}

	private void exitFullscreen(Window window) {
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

	private void showReset(boolean show) {
		resetBtn.setVisibility(show ? View.VISIBLE : View.GONE);
	}

	private void toggleLock() {
		isLocked = !isLocked;
		lockBtn.setImageResource(isLocked ? R.drawable.ic_lock : R.drawable.ic_unlock);
		lockBtn.setContentDescription(activity.getString(isLocked ? R.string.unlock_screen : R.string.lock_screen));
		if (isLocked) {
			showHint(activity.getString(R.string.lock_screen), 1000);
			setControlsVisible(true);
		} else {
			showHint(activity.getString(R.string.unlock_screen), 1000);
			setControlsVisible(true);
		}
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

