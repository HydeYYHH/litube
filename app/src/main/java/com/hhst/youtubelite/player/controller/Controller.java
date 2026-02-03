package com.hhst.youtubelite.player.controller;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
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

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.browser.TabManager;
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
	private static final int CONTROLS_HIDE_DELAY_MS = 3000;
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
	private final Handler handler = new Handler(Looper.getMainLooper());
	@Nullable
	private TextView hintText;
	@Getter
	private boolean isControlsVisible = false;
	@Setter
	private boolean longPress = false;
	private boolean isLocked = false;
	private long lastVideoRenderedCount = 0;
	private long lastFpsUpdateTime = 0;	@NonNull
	private final Runnable hideControls = () -> setControlsVisible(false);
	private float fps = 0;
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
		play.setVisibility(!isPlaying ? View.VISIBLE : View.GONE);
		pause.setVisibility(isPlaying ? View.VISIBLE : View.GONE);
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
		// Listen and handle gestures
		final PlayerGestureListener gestureListener = new PlayerGestureListener(activity, playerView, engine, this);
		final GestureDetector detector = new GestureDetector(activity, gestureListener);
		playerView.setOnTouchListener((v, ev) -> {
			final int action = ev.getAction();
			if (action == MotionEvent.ACTION_DOWN && isControlsVisible) {
				handler.removeCallbacks(hideControls);
			}
			// Listen and handle gestures
			if (!detector.onTouchEvent(ev) && playerView.isFs()) zoomListener.onTouch(ev);
			// Listen and cancel long press gesture
			if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
				// if (isControlsVisible && (isGesturing || longPress)) hideControlsAutomatically();
				if (longPress) {
					longPress = false;
					engine.setPlaybackRate(prefs.getSpeed());
					hideHint();
				}
				// Automatically hide controls after a delay
				if (isControlsVisible) hideControlsAutomatically();
			}
			return true;
		});

		// Listen engine state changes for updating UI elements
		engine.addListener(new Player.Listener() {
			@Override
			public void onIsPlayingChanged(boolean isPlaying) {
				updatePlayPauseButtons(isPlaying);
				playerView.setKeepScreenOn(isPlaying); // fixs: screen sleep while playing
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
						speedView.setText(String.format(Locale.getDefault(), "%sx", engine.getPlaybackRate()));
						final TextView qualityView = playerView.findViewById(R.id.btn_quality);
						qualityView.setText(engine.getQuality());
					});
				}
			}

			@Override
			public void onTracksChanged(@NonNull Tracks tracks) {
				updateSubtitleButtonState();
				playerView.post(() -> {
					final TextView qualityView = playerView.findViewById(R.id.btn_quality);
					qualityView.setText(engine.getQuality());
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
		final View play = playerView.findViewById(R.id.btn_play);
		final View pause = playerView.findViewById(R.id.btn_pause);
		play.setOnClickListener(v -> {
			engine.play();
			setControlsVisible(true);
		});
		pause.setOnClickListener(v -> {
			engine.pause();
			setControlsVisible(true);
		});

		final ImageButton prevBtn = playerView.findViewById(R.id.btn_prev);
		prevBtn.setOnClickListener(v -> {
			engine.skipToPrevious();
			setControlsVisible(true);
		});

		final ImageButton nextBtn = playerView.findViewById(R.id.btn_next);
		nextBtn.setOnClickListener(v -> {
			engine.skipToNext();
			setControlsVisible(true);
		});

		final ImageButton lockBtn = playerView.findViewById(R.id.btn_lock);
		lockBtn.setOnClickListener(v -> {
			isLocked = !isLocked;
			lockBtn.setImageResource(isLocked ? R.drawable.ic_lock : R.drawable.ic_unlock);
			setControlsVisible(true);
			showHint(activity.getString(isLocked ? R.string.lock_screen : R.string.unlock_screen), Constant.HINT_HIDE_DELAY_MS);
		});

		final ImageButton fsBtn = playerView.findViewById(R.id.btn_fullscreen);
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

		final ImageButton loopBtn = playerView.findViewById(R.id.btn_loop);
		final boolean enabled = prefs.isLoopEnabled();
		engine.setRepeatMode(enabled ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
		loopBtn.setImageResource(enabled ? R.drawable.ic_repeat_on : R.drawable.ic_repeat_off);
		loopBtn.setOnClickListener(v -> {
			final boolean newEnabled = !prefs.isLoopEnabled();
			prefs.setLoopEnabled(newEnabled);
			engine.setRepeatMode(newEnabled ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
			loopBtn.setImageResource(newEnabled ? R.drawable.ic_repeat_on : R.drawable.ic_repeat_off);
			showHint(activity.getString(newEnabled ? R.string.repeat_on : R.string.repeat_off), Constant.HINT_HIDE_DELAY_MS);
			setControlsVisible(true);
		});

		final TextView resetBtn = playerView.findViewById(R.id.btn_reset);
		resetBtn.setOnClickListener(v -> zoomListener.reset());
	}

	private void setupQualityAndSpeedButtons() {
		final TextView speedView = playerView.findViewById(R.id.btn_speed);
		final TextView qualityView = playerView.findViewById(R.id.btn_quality);

		speedView.setText(String.format(Locale.getDefault(), "%sx", engine.getPlaybackRate()));
		speedView.setOnClickListener(v -> {
			final float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f};
			final String[] options = new String[speeds.length];
			int checked = -1;
			final float currentSpeed = engine.getPlaybackRate();
			for (int i = 0; i < speeds.length; i++) {
				options[i] = speeds[i] + "x";
				if (speeds[i] == currentSpeed) checked = i;
			}
			showSelectionPopup(v, options, checked, (index, label) -> {
				engine.setPlaybackRate(speeds[index]);
				prefs.setSpeed(speeds[index]);
				speedView.setText(label);
			});
		});

		qualityView.setText(engine.getQuality());
		qualityView.setOnClickListener(v -> {
			final List<String> available = engine.getAvailableResolutions();
			if (available.isEmpty()) return;

			final Map<String, String> map = new LinkedHashMap<>();
			for (String s : available) {
				map.merge(s.replaceAll("(?<=p)\\d+|\\s", ""), s, (o, n) -> n.contains("60") ? n : o);
			}

			final String[] labels = map.keySet().toArray(new String[0]);
			final String[] values = map.values().toArray(new String[0]);
			final int checked = Arrays.asList(values).indexOf(engine.getQuality());

			showSelectionPopup(v, labels, checked, (index, label) -> {
				final String selected = values[index];
				engine.onQualitySelected(selected);
				prefs.setQuality(selected);
				qualityView.setText(label);

				// fixs: shorts video low quality issue
				final String js = String.format("""
						(function(t) {
							const p = document.querySelector('#movie_player');
							const ls = p.getAvailableQualityLabels();
							const v = l => parseInt(l.replace(/\\D/g, ''));
							const target = v(t);
							const closest = ls.reduce((b, c, i) => Math.abs(v(c) - target) < Math.abs(v(ls[b]) - target) ? i : b, 0);
							const quality = p.getAvailableQualityLevels()[closest];
							p.setPlaybackQualityRange(quality, quality);
						})('%s')""", label);
				tabManager.evaluateJavascript(js, null);
			});
		});
	}

	private void setupSubtitleAndSegmentButtons() {
		final ImageButton subBtn = playerView.findViewById(R.id.btn_subtitles);
		updateSubtitleButtonState();
		subBtn.setOnClickListener(v -> {
			final List<String> available = engine.getSubtitles();
			if (available.isEmpty()) {
				showHint(activity.getString(R.string.no_subtitles), Constant.HINT_HIDE_DELAY_MS);
				hideControlsAutomatically();
				return;
			}

			final String[] options = available.toArray(new String[0]);
			final String current = engine.getSelectedSubtitle();
			int checked = -1;
			if (engine.areSubtitlesEnabled() && current != null) {
				checked = available.indexOf(current);
			}

			final int finalChecked = checked;
			showSelectionPopup(subBtn, options, checked, (index, label) -> {
				if (index == finalChecked) {
					engine.setSubtitlesEnabled(false);
					showHint(activity.getString(R.string.subtitles_off), Constant.HINT_HIDE_DELAY_MS);
				} else {
					engine.setSubtitlesEnabled(true);
					engine.setSubtitleLanguage(label);
					showHint(activity.getString(R.string.subtitles_on) + ": " + label, Constant.HINT_HIDE_DELAY_MS);
				}
				updateSubtitleButtonState();
			});
		});

		final ImageButton segBtn = playerView.findViewById(R.id.btn_segments);
		segBtn.setOnClickListener(this::showSegmentsPopup);
	}

	private void showSegmentsPopup(@NonNull final View anchor) {
		final List<StreamSegment> segments = engine.getSegments();
		final String[] titles = new String[segments.size()];
		int currentIdx = -1;
		final long posSec = engine.position() / 1000;

		for (int i = 0; i < segments.size(); i++) {
			final StreamSegment seg = segments.get(i);
			titles[i] = DateUtils.formatElapsedTime(Math.max(seg.getStartTimeSeconds(), 0)) + " - " + seg.getTitle();
			if (posSec >= seg.getStartTimeSeconds()) currentIdx = i;
		}

		showSelectionPopup(anchor, titles, currentIdx, new SelectionCallback() {
			@Override
			public void onSelected(int index, String label) {
				final StreamSegment seg = segments.get(index);
				engine.seekTo(seg.getStartTimeSeconds() * 1000L);
				showHint(activity.getString(R.string.jumped_to_segment, seg.getTitle()), Constant.HINT_HIDE_DELAY_MS);
			}

			@Override
			public void onLongClick(int index, String label) {
				showSegmentDetailsDialog(segments.get(index));
			}
		});
	}

	private void updateSubtitleButtonState() {
		final ImageButton subBtn = playerView.findViewById(R.id.btn_subtitles);

		final boolean hasSubtitles = !engine.getSubtitles().isEmpty();
		final boolean isEnabled = engine.areSubtitlesEnabled();

		if (!hasSubtitles) {
			subBtn.setImageResource(R.drawable.ic_subtitles_off);
			subBtn.setAlpha(0.7f);
		} else {
			subBtn.setImageResource(isEnabled ? R.drawable.ic_subtitles_on : R.drawable.ic_subtitles_off);
			subBtn.setAlpha(1.0f);
		}
	}

	private void setupOverlayAndMoreButtons() {
		final View moreBtn = playerView.findViewById(R.id.btn_more);
		moreBtn.setOnClickListener(v -> {
			setControlsVisible(true);
			if (activity.isInPictureInPictureMode()) return;
			final BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(activity);
			bottomSheetDialog.setDismissWithAnimation(true);
			final View bottomSheetView = activity.getLayoutInflater().inflate(R.layout.bottom_sheet_more_options, null);
			bottomSheetDialog.setContentView(bottomSheetView);

			if (activity instanceof LifecycleOwner lifecycleOwner) {
				final LifecycleEventObserver observer = (source, event) -> {
					if (event == Lifecycle.Event.ON_PAUSE && activity.isInPictureInPictureMode()) {
						bottomSheetDialog.dismiss();
					}
				};
				lifecycleOwner.getLifecycle().addObserver(observer);
				bottomSheetDialog.setOnDismissListener(dialog -> {
					lifecycleOwner.getLifecycle().removeObserver(observer);
					hideControlsAutomatically();
				});
			} else {
				bottomSheetDialog.setOnDismissListener(dialog -> hideControlsAutomatically());
			}

			setupBottomSheetOption(bottomSheetView, R.id.option_resize_mode, b -> {
				showResizeModeOptions();
				bottomSheetDialog.dismiss();
			});

			setupBottomSheetOption(bottomSheetView, R.id.option_audio_track, b -> {
				showAudioTrackOptions();
				bottomSheetDialog.dismiss();
			});

			setupBottomSheetOption(bottomSheetView, R.id.option_pip, b -> {
				playerView.enterPiP();
				bottomSheetDialog.dismiss();
			});

			setupBottomSheetOption(bottomSheetView, R.id.option_stream_details, b -> {
				showVideoDetails();
				bottomSheetDialog.dismiss();
			});

			bottomSheetDialog.show();
		});
	}

	private void showAudioTrackOptions() {
		final List<AudioStream> audioTracks = engine.getAvailableAudioTracks();
		if (audioTracks.isEmpty()) return;

		final String[] options = new String[audioTracks.size()];
		for (int i = 0; i < audioTracks.size(); i++) {
			final AudioStream stream = audioTracks.get(i);
			int bitrate = stream.getAverageBitrate();
			bitrate = bitrate > 0 ? bitrate : stream.getBitrate();
			if (stream.getAudioTrackName() == null) options[i] = bitrate + "kbps";
			else options[i] = String.format("%s (%s)", stream.getAudioTrackName(), bitrate + "kbps");
		}

		final AudioStream current = engine.getAudioTrack();
		int checked = -1;
		if (current != null) {
			for (int i = 0; i < audioTracks.size(); i++) {
				if (audioTracks.get(i).getContent().equals(current.getContent())) {
					checked = i;
					break;
				}
			}
		}
		final ListAdapter adapter = getAdapter(checked, options);

		new MaterialAlertDialogBuilder(activity).setTitle(R.string.audio_track).setAdapter(adapter, (dialog, which) -> {
			engine.setAudioTrack(audioTracks.get(which));
			showHint(options[which], Constant.HINT_HIDE_DELAY_MS);
			hideControlsAutomatically();
		}).setNegativeButton(R.string.cancel, null).show();
	}

	@NonNull
	private ListAdapter getAdapter(int tempChecked, String[] options) {
		final int checkedItem = tempChecked;

		return new ArrayAdapter<>(activity, R.layout.dialog_resize_mode_item, options) {
			@NonNull
			@Override
			public View getView(final int position, @Nullable final View convertView, @NonNull final ViewGroup parent) {
				View view = convertView;
				if (view == null)
					view = activity.getLayoutInflater().inflate(R.layout.dialog_resize_mode_item, parent, false);
				final ImageView icon = view.findViewById(R.id.icon);
				final TextView text = view.findViewById(R.id.text);
				icon.setImageResource(R.drawable.ic_track);
				text.setText(getItem(position));
				final TypedValue tv = new TypedValue();
				if (position == checkedItem) {
					activity.getTheme().resolveAttribute(android.R.attr.colorPrimary, tv, true);
					icon.setColorFilter(tv.data);
					text.setTextColor(tv.data);
					text.setTypeface(null, Typeface.BOLD);
				} else {
					activity.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true);
					icon.setColorFilter(activity.getColor(android.R.color.darker_gray));
					text.setTextColor(tv.data);
					text.setTypeface(null, Typeface.NORMAL);
				}
				return view;
			}
		};
	}

	private void showVideoDetails() {
		final StreamDetails details = engine.getStreamDetails();
		if (details == null) {
			showHint(activity.getString(R.string.unable_to_get_stream_info), Constant.HINT_HIDE_DELAY_MS);
			hideControlsAutomatically();
			return;
		}

		final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
		builder.setTitle(R.string.info);
		builder.setPositiveButton(R.string.confirm, null);
		final String[] info = {getVideoDetailsText(details)};
		builder.setMessage(info[0]);
		builder.setNeutralButton(R.string.copy, (dialog, which) -> {
			DeviceUtils.copyToClipboard(activity, "Video Details", info[0]);
			showHint(activity.getString(R.string.debug_info_copied), Constant.HINT_HIDE_DELAY_MS);
		});
		final AlertDialog dialog = builder.show();
		hideControlsAutomatically();
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

	@NonNull
	private String getVideoDetailsText(@NonNull final StreamDetails details) {
		final StringBuilder info = new StringBuilder();
		final Optional<Format> videoFormatOpt = Optional.ofNullable(engine.getVideoFormat());
		final Optional<Format> audioFormatOpt = Optional.ofNullable(engine.getAudioFormat());
		final DecoderCounters decoderCounters = engine.getVideoDecoderCounters();

		if (decoderCounters != null) {
			final long now = System.currentTimeMillis();
			if (lastFpsUpdateTime > 0) {
				final long timeDiff = now - lastFpsUpdateTime;
				if (timeDiff >= 1000) {
					final long countDiff = decoderCounters.renderedOutputBufferCount - lastVideoRenderedCount;
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

		videoFormatOpt.ifPresent(videoFormat -> {
			info.append(activity.getString(R.string.video_format)).append(": ").append(videoFormat.sampleMimeType).append("\n");
			info.append(activity.getString(R.string.resolution)).append(": ").append(videoFormat.width).append("x").append(videoFormat.height).append("\n");
			info.append(activity.getString(R.string.bitrate)).append(": ").append(videoFormat.bitrate / 1000).append(" kbps\n");
		});

		audioFormatOpt.ifPresent(audioFormat -> {
			info.append(activity.getString(R.string.audio_format)).append(": ").append(audioFormat.sampleMimeType).append("\n");
			if (audioFormat.bitrate > 0)
				info.append(activity.getString(R.string.bitrate)).append(": ").append(audioFormat.bitrate / 1000).append(" kbps\n");
			if (audioFormat.channelCount > 0)
				info.append(activity.getString(R.string.channels)).append(": ").append(audioFormat.channelCount).append("\n");
			if (audioFormat.sampleRate > 0)
				info.append(activity.getString(R.string.sample_rate)).append(": ").append(audioFormat.sampleRate).append(" Hz\n");
		});

		final String quality = engine.getQuality();
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
		} else info.append(activity.getString(R.string.no_active_video_stream));

		final int audioIndex = engine.getSelectedAudioTrackIndex();
		if (audioIndex >= 0) {
			final AudioStream audio = engine.getAvailableAudioTracks().get(audioIndex);
			final int bitrate = audio.getAverageBitrate();
			final String bitrateStr = bitrate > 0 ? bitrate + "kbps" : activity.getString(R.string.unknown_bitrate);
			info.append(activity.getString(R.string.active_stream_audio, audio.getFormat() != null ? audio.getFormat().name() : activity.getString(R.string.unknown), audio.getCodec(), bitrateStr));
		} else info.append(activity.getString(R.string.no_active_audio_stream));

		return info.toString();
	}

	private void setupBottomSheetOption(@NonNull final View root, final int id, @NonNull final View.OnClickListener listener) {
		final View option = root.findViewById(id);
		option.setOnClickListener(listener);
	}

	private void toggleLock() {
		isLocked = !isLocked;
		final ImageButton lockBtn = playerView.findViewById(R.id.btn_lock);
		lockBtn.setImageResource(isLocked ? R.drawable.ic_lock : R.drawable.ic_unlock);
	}

	public void exitFullscreen() {
		if (isLocked) toggleLock();
		playerView.exitFullscreen();
		zoomListener.reset();
		setControlsVisible(true);
	}

	public void onPictureInPictureModeChanged(final boolean isInPictureInPictureMode) {
		playerView.onPictureInPictureModeChanged(isInPictureInPictureMode);
		setControlsVisible(!isInPictureInPictureMode);
	}

	public void setControlsVisible(boolean visible) {
		if (visible && activity.isInPictureInPictureMode()) return;
		this.isControlsVisible = visible;
		handler.removeCallbacks(hideControls);

		final View centerControls = playerView.findViewById(R.id.center_controls);
		final View otherControls = playerView.findViewById(R.id.other_controls);
		final View bar = playerView.findViewById(R.id.exo_progress);

		final int state = engine.getPlaybackState();
		final float alpha = visible ? 1.0f : 0.0f;

		final ImageButton lockBtn = playerView.findViewById(R.id.btn_lock);
		if (isLocked) {
			ViewUtils.animateViewAlpha(centerControls, 0.0f, View.GONE);
			ViewUtils.animateViewAlpha(otherControls, 0.0f, View.GONE);
			ViewUtils.animateViewAlpha(bar, 0.0f, View.GONE);
			showReset(false);
		} else {
			ViewUtils.animateViewAlpha(otherControls, alpha, View.GONE);
			final boolean showCenter = visible && (state != Player.STATE_BUFFERING && state != Player.STATE_IDLE && !activity.isInPictureInPictureMode());
			ViewUtils.animateViewAlpha(centerControls, showCenter ? 1.0f : 0.0f, View.GONE);
			ViewUtils.animateViewAlpha(bar, alpha, View.GONE);
			showReset(playerView.isFs() && visible && zoomListener.shouldShowReset());
		}
		ViewUtils.animateViewAlpha(lockBtn, visible && playerView.isFs() ? 1.0f : 0.0f, View.GONE);
		if (visible) hideControlsAutomatically();
	}

	private void showReset(boolean show) {
		final View resetBtn = playerView.findViewById(R.id.btn_reset);
		resetBtn.setVisibility(show ? View.VISIBLE : View.GONE);
	}

	private void hideControlsAutomatically() {
		handler.removeCallbacks(hideControls);
		if (engine.isPlaying()) {
			handler.postDelayed(hideControls, CONTROLS_HIDE_DELAY_MS);
		}
	}

	public void showHint(@NonNull final String text, final long durationMs) {
		if (activity.isInPictureInPictureMode()) return;
		hintText.setText(text);
		hintText.setTranslationY(0);
		ViewUtils.animateViewAlpha(hintText, 1.0f, View.GONE);

		handler.removeCallbacks(this::hideHint);
		if (durationMs > 0) handler.postDelayed(this::hideHint, durationMs);
	}

	public void hideHint() {
		handler.removeCallbacks(this::hideHint);
		ViewUtils.animateViewAlpha(hintText, 0.0f, View.GONE);
	}

	private void showResizeModeOptions() {
		setControlsVisible(true);
		final String[] options = {activity.getString(R.string.resize_fit), activity.getString(R.string.resize_fill), activity.getString(R.string.resize_zoom), activity.getString(R.string.resize_fixed_width), activity.getString(R.string.resize_fixed_height)};
		final int[] modes = {AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH, AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT};
		final ListAdapter adapter = getResizeAdapter(modes, options);

		new MaterialAlertDialogBuilder(activity).setTitle(R.string.resize_mode).setAdapter(adapter, (dialog, which) -> {
			final int selectedMode = modes[which];
			playerView.setResizeMode(selectedMode);
			prefs.setResizeMode(selectedMode);
			showHint(options[which], Constant.HINT_HIDE_DELAY_MS);
			hideControlsAutomatically();
		}).setNegativeButton(R.string.cancel, null).show();
	}

	@NonNull
	private ListAdapter getResizeAdapter(@NonNull final int[] modes, @NonNull final String[] options) {
		final int[] icons = {R.drawable.ic_resize_fit, R.drawable.ic_resize_fill, R.drawable.ic_resize_zoom, R.drawable.ic_resize_width, R.drawable.ic_resize_height};
		final int mode = playerView.getResizeMode();
		int tempChecked = 0;
		for (int i = 0; i < modes.length; i++) if (modes[i] == mode) tempChecked = i;
		final int checkedItem = tempChecked;

		return new ArrayAdapter<>(activity, R.layout.dialog_resize_mode_item, options) {
			@NonNull
			@Override
			public View getView(final int position, @Nullable final View convertView, @NonNull final ViewGroup parent) {
				View view = convertView;
				if (view == null)
					view = activity.getLayoutInflater().inflate(R.layout.dialog_resize_mode_item, parent, false);
				final ImageView icon = view.findViewById(R.id.icon);
				final TextView text = view.findViewById(R.id.text);
				icon.setImageResource(icons[position]);
				text.setText(getItem(position));
				final TypedValue tv = new TypedValue();
				if (position == checkedItem) {
					activity.getTheme().resolveAttribute(android.R.attr.colorPrimary, tv, true);
					icon.setColorFilter(tv.data);
					text.setTextColor(tv.data);
					text.setTypeface(null, Typeface.BOLD);
				} else {
					activity.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true);
					icon.setColorFilter(activity.getColor(android.R.color.darker_gray));
					text.setTextColor(tv.data);
					text.setTypeface(null, Typeface.NORMAL);
				}
				return view;
			}
		};
	}

	private void showSelectionPopup(@NonNull final View anchor, @NonNull final String[] options, final int checkedIndex, @NonNull final SelectionCallback callback) {
		setControlsVisible(true);
		final ListPopupWindow popup = new ListPopupWindow(activity);
		popup.setAnchorView(anchor);
		popup.setModal(true);
		final ArrayAdapter<String> adapter = createSelectionAdapter(checkedIndex, options);
		popup.setAdapter(adapter);
		popup.setWidth(calculatePopupWidth(adapter, options.length));
		popup.setAnimationStyle(android.R.style.Animation_Dialog);
		popup.setOnItemClickListener((parent, view, position, id) -> {
			callback.onSelected(position, options[position]);
			popup.dismiss();
			hideControlsAutomatically();
		});
		popup.show();
		final ListView listView = popup.getListView();
		if (listView != null) listView.setOnItemLongClickListener((parent, view, position, id) -> {
			callback.onLongClick(position, options[position]);
			popup.dismiss();
			return true;
		});
	}

	private ArrayAdapter<String> createSelectionAdapter(final int checkedIndex, @NonNull final String[] options) {
		return new ArrayAdapter<>(activity, R.layout.item_menu_list, options) {
			@NonNull
			@Override
			public View getView(final int pos, @Nullable final View conv, @NonNull final ViewGroup parent) {
				final TextView tv = (TextView) super.getView(pos, conv, parent);
				if (pos == checkedIndex) {
					final TypedValue out = new TypedValue();
					activity.getTheme().resolveAttribute(android.R.attr.colorPrimary, out, true);
					tv.setTextColor(out.data);
					tv.setTypeface(null, Typeface.BOLD);
				} else {
					TypedValue out = new TypedValue();
					activity.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurface, out, true);
					tv.setTextColor(out.data);
					tv.setTypeface(null, Typeface.NORMAL);
				}
				return tv;
			}
		};
	}

	private void showSegmentDetailsDialog(@NonNull final StreamSegment selectedSegment) {
		final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
		final View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_segment, null);

		final TextView titleView = dialogView.findViewById(R.id.segment_title);
		titleView.setMovementMethod(new ScrollingMovementMethod());
		titleView.setText(selectedSegment.getTitle());

		final TextView timeView = dialogView.findViewById(R.id.segment_time);
		final String time = DateUtils.formatElapsedTime(Math.max(selectedSegment.getStartTimeSeconds(), 0));
		timeView.setText(time);

		final ImageView thumbnailView = dialogView.findViewById(R.id.segment_thumbnail);
		final String thumbnailUrl = selectedSegment.getPreviewUrl() != null ? selectedSegment.getPreviewUrl() : engine.getThumbnail();
		Picasso.get().load(thumbnailUrl).into(thumbnailView);

		builder.setView(dialogView).setPositiveButton(R.string.jump, (dialog, which) -> {
			engine.seekTo(selectedSegment.getStartTimeSeconds() * 1000L);
			showHint(activity.getString(R.string.jumped_to_segment, selectedSegment.getTitle()), Constant.HINT_HIDE_DELAY_MS);
			hideControlsAutomatically();
		}).setNegativeButton(R.string.close, null);
		builder.show();
	}

	private int calculatePopupWidth(@NonNull final ListAdapter adapter, final int itemCount) {
		int maxWidth = 0;
		final ListView listView = new ListView(activity);
		for (int i = 0; i < itemCount; i++) {
			final View measureView = adapter.getView(i, null, listView);
			measureView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
			maxWidth = Math.max(maxWidth, measureView.getMeasuredWidth());
		}
		final int screenWidth = ViewUtils.getScreenWidth(activity);
		return Math.min(maxWidth, (int) (screenWidth * 0.8));
	}

	private interface SelectionCallback {
		void onSelected(int index, String label);

		default void onLongClick(int index, String label) {
		}
	}




}
