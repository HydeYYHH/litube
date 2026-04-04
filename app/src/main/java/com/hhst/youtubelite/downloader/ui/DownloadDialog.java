package com.hhst.youtubelite.downloader.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.R.attr;
import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.util.UnstableApi;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.downloader.core.DownloadPrefs;
import com.hhst.youtubelite.downloader.core.DownloadSelectionConfig;
import com.hhst.youtubelite.downloader.core.DownloadTaskFactory;
import com.hhst.youtubelite.downloader.core.Task;
import com.hhst.youtubelite.downloader.service.DownloadService;
import com.hhst.youtubelite.extractor.ExtractionSession;
import com.hhst.youtubelite.extractor.StreamCatalog;
import com.hhst.youtubelite.extractor.VideoDetails;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.gallery.GalleryActivity;
import com.hhst.youtubelite.util.DownloadStorageUtils;
import com.hhst.youtubelite.util.ImageUtils;
import com.hhst.youtubelite.util.PermissionUtils;
import com.hhst.youtubelite.util.ToastUtils;
import com.tencent.mmkv.MMKV;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dialog that lets the user choose media streams to download.
 */
@UnstableApi
public class DownloadDialog {
	private final Context context;
	private final ExecutorService executor;
	private final CountDownLatch videoLatch;
	private final CountDownLatch streamLatch;
	private final View dialogView;
	private final ExtractionSession extractionSession = new ExtractionSession();
	private final DownloadPrefs prefs = new DownloadPrefs(MMKV.defaultMMKV());
	private VideoDetails videoDetails;
	private List<SubtitlesStream> subtitles = List.of();
	private StreamCatalog catalog;
	private boolean videoEnabled, thumbEnabled, audioEnabled, subtitleEnabled;
	private VideoStream selectedVideo;
	private AudioStream selectedAudio;
	private SubtitlesStream selectedSubtitle;
	private int threadCount = 4;
	private int primaryColor;
	private int grayColor;
	private DownloadService downloadService;
	private boolean bindingRequested;
	private boolean isBound;
	private boolean isDismissed;
	@Nullable
	private AlertDialog dialog;
	@Nullable
	private Button downloadButton;
	@Nullable
	private List<Task> pendingTasks;

	public DownloadDialog(String url, Context context, YoutubeExtractor youtubeExtractor) {
		this.context = context;
		this.dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_download, new FrameLayout(context), false);
		executor = Executors.newCachedThreadPool();
		videoLatch = new CountDownLatch(1);
		streamLatch = new CountDownLatch(1);

		youtubeExtractor.getInfo(url, extractionSession).whenComplete((playbackDetails, error) -> {
			try {
				if (error == null && playbackDetails != null) {
					videoDetails = playbackDetails.video();
					subtitles = playbackDetails.subtitles();
					catalog = playbackDetails.catalog();
					return;
				}
				Throwable cause = error instanceof CompletionException
								&& error.getCause() != null
								? error.getCause()
								: error;
				if (cause instanceof InterruptedException) {
					Thread.currentThread().interrupt();
					return;
				}
				if (cause instanceof InterruptedIOException || extractionSession.isCancelled()) {
					return;
				}
				ToastUtils.show(context, R.string.failed_to_load_video_details);
			} finally {
				videoLatch.countDown();
				streamLatch.countDown();
			}
		});
	}

	public void show() {
		ProgressBar progressBar = dialogView.findViewById(R.id.loadingBar);
		ImageView imageView = dialogView.findViewById(R.id.download_image);
		EditText editText = dialogView.findViewById(R.id.download_edit_text);
		Button videoButton = dialogView.findViewById(R.id.button_video);
		Button thumbnailButton = dialogView.findViewById(R.id.button_thumbnail);
		Button audioButton = dialogView.findViewById(R.id.button_audio);
		Button subtitleButton = dialogView.findViewById(R.id.button_subtitle);
		Button downloadButtonView = dialogView.findViewById(R.id.button_download);
		Button cancelButton = dialogView.findViewById(R.id.button_cancel);
		SeekBar threadsSeekBar = dialogView.findViewById(R.id.threads_seekbar);
		TextView threadsCountText = dialogView.findViewById(R.id.threads_count);
		editText.setHorizontallyScrolling(false);
		editText.setVerticalScrollBarEnabled(true);

		dialog = new MaterialAlertDialogBuilder(context)
						.setTitle(context.getString(R.string.download))
						.setView(dialogView)
						.setCancelable(true)
						.create();
		isDismissed = false;
		requestServiceBinding();
		this.downloadButton = downloadButtonView;

		threadCount = prefs.getThreadCount();
		threadsSeekBar.setProgress(threadCount - 1);
		threadsCountText.setText(String.valueOf(threadCount));

		TypedValue value = new TypedValue();
		context.getTheme().resolveAttribute(attr.colorPrimary, value, true);
		primaryColor = value.data;
		grayColor = context.getColor(android.R.color.darker_gray);

		// Keep options gray until saved state is restored.
		videoButton.setBackgroundColor(grayColor);
		audioButton.setBackgroundColor(grayColor);
		thumbnailButton.setBackgroundColor(grayColor);
		subtitleButton.setBackgroundColor(grayColor);
		restoreSavedState();
		updateSelectionColors(videoButton, audioButton, thumbnailButton, subtitleButton);
		executor.submit(() -> {
			try {
				streamLatch.await();
				dialogView.post(() -> {
					restoreStreamSelections();
					updateSelectionColors(videoButton, audioButton, thumbnailButton, subtitleButton);
				});
			} catch (InterruptedException ignored) {
			}
		});

		executor.submit(() -> {
			try {
				videoLatch.await();
				dialogView.post(() -> {
					progressBar.setVisibility(View.GONE);
					if (videoDetails != null) {
						ImageUtils.loadThumb(imageView, videoDetails.getThumbnailUrl());
						editText.setText(String.format("%s-%s", videoDetails.getTitle(), videoDetails.getAuthor()));

						// Open the gallery for the selected thumbnails.
						imageView.setOnClickListener(v -> executor.submit(() -> {
							Intent intent = new Intent(context, GalleryActivity.class);
							ArrayList<String> urls = new ArrayList<>();
							urls.add(videoDetails.getThumbnailUrl());
							intent.putStringArrayListExtra("thumbnails", urls);
							intent.putExtra("filename", videoDetails.getTitle());
							context.startActivity(intent);
						}));
					}
				});
			} catch (InterruptedException ignored) {
			}
		});

		videoButton.setOnClickListener(v -> {
			if (catalog != null) showVideoQualityDialog(videoButton);
		});
		audioButton.setOnClickListener(v -> {
			if (catalog != null) showAudioSelectionDialog(audioButton);
		});
		subtitleButton.setOnClickListener(v -> {
			if (catalog != null) showSubtitleSelectionDialog(subtitleButton);
		});

		thumbnailButton.setOnClickListener(v -> {
			thumbEnabled = !thumbEnabled;
			prefs.setThumbnailEnabled(thumbEnabled);
			thumbnailButton.setBackgroundColor(thumbEnabled ? primaryColor : grayColor);
		});

		threadsSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar s, int p, boolean f) {
				threadCount = p + 1;
				threadsCountText.setText(String.valueOf(threadCount));
				prefs.setThreadCount(threadCount);
			}

			@Override
			public void onStartTrackingTouch(SeekBar s) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar s) {
			}
		});

		downloadButtonView.setOnClickListener(v -> {
			if (videoDetails == null) {
				if (dialog != null) dialog.dismiss();
				return;
			}
			String text = editText.getText().toString();
			String fileName = text.isEmpty() ? videoDetails.getTitle() : text;
			final List<Task> tasks;
			try {
				tasks = buildTasks(fileName);
			} catch (RuntimeException e) {
				ToastUtils.show(context, R.string.failed_to_download);
				return;
			}
			if (tasks.isEmpty()) {
				ToastUtils.show(context, R.string.select_something_first);
				return;
			}
			startDownloads(tasks);
		});

		cancelButton.setOnClickListener(v -> {
			if (dialog != null) dialog.dismiss();
		});
		dialog.setOnDismissListener(di -> {
			isDismissed = true;
			pendingTasks = null;
			DownloadDialog.this.downloadButton = null;
			dialog = null;
			extractionSession.cancel();
			executor.shutdownNow();
			safelyUnbindService();
		});
		dialog.show();
	}	private final ServiceConnection connection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			DownloadService.DownloadBinder binder = (DownloadService.DownloadBinder) service;
			downloadService = binder.getService();
			isBound = true;
			if (isDismissed) {
				safelyUnbindService();
				return;
			}
			if (downloadButton != null) downloadButton.setEnabled(true);
			if (pendingTasks != null && downloadService != null) {
				dispatchDownloadTasks(pendingTasks);
				pendingTasks = null;
				if (dialog != null && dialog.isShowing()) dialog.dismiss();
			}
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			downloadService = null;
			isBound = false;
			if (downloadButton != null) downloadButton.setEnabled(true);
		}
	};

	private void requestServiceBinding() {
		if (bindingRequested) return;
		bindingRequested = context.bindService(new Intent(context, DownloadService.class), connection, Context.BIND_AUTO_CREATE);
	}

	private void safelyUnbindService() {
		downloadService = null;
		isBound = false;
		if (!bindingRequested) return;
		try {
			context.unbindService(connection);
		} catch (IllegalArgumentException ignored) {
		}
		bindingRequested = false;
	}

	private void showVideoQualityDialog(Button btn) {
		View v = LayoutInflater.from(context).inflate(R.layout.quality_selector, new FrameLayout(context), false);
		LinearLayout container = v.findViewById(R.id.quality_container);
		ProgressBar loading = v.findViewById(R.id.loadingBar2);
		AlertDialog d = new MaterialAlertDialogBuilder(context).setTitle(R.string.video_quality).setView(v).create();
		v.findViewById(R.id.button_cancel).setOnClickListener(v1 -> d.dismiss());

		executor.submit(() -> {
			try {
				streamLatch.await();
				new Handler(Looper.getMainLooper()).post(() -> {
					loading.setVisibility(View.GONE);
					setupVideoContainer(v, container, d, btn);
				});
			} catch (InterruptedException ignored) {
			}
		});
		d.show();
	}

	private void setupVideoContainer(View dialogView, LinearLayout container, AlertDialog d, Button btn) {
		if (catalog == null) return;
		CheckBox[] refs = new CheckBox[1];
		List<VideoStream> videos = videoChoices();
		Map<String, Integer> counts = countVideoResolutions(videos);
		selectedVideo = prefs.restoreVideoSelection(videos);

		for (VideoStream s : videos) {
			CheckBox cb = new CheckBox(context);
			cb.setText(String.format(Locale.US, "%s (%s)", formatVideoLabel(s, counts), formatSize(s.getItagItem().getContentLength() + (!catalog.getAudioStreams().isEmpty() ? catalog.getAudioStreams().get(0).getItagItem().getContentLength() : 0))));
			cb.setOnCheckedChangeListener((v, is) -> {
				if (is) {
					if (refs[0] != null) refs[0].setChecked(false);
					selectedVideo = s;
					refs[0] = cb;
				}
			});
			container.addView(cb);
			if (selectedVideo != null && selectedVideo.getItag() == s.getItag()) {
				cb.setChecked(true);
				refs[0] = cb;
			}
		}

		dialogView.findViewById(R.id.button_confirm).setOnClickListener(v1 -> {
			videoEnabled = refs[0] != null;
			prefs.setSingleVideoEnabled(videoEnabled);
			if (videoEnabled) prefs.saveVideoSelection(selectedVideo);
			btn.setBackgroundColor(videoEnabled ? primaryColor : grayColor);
			if (videoEnabled) {
				List<AudioStream> audioStreams = new ArrayList<>();
				for (AudioStream as : catalog.getAudioStreams()) {
					if (as.getFormat() == MediaFormat.M4A) audioStreams.add(as);
				}
				if (audioStreams.size() > 1) {
					showAudioTrackSelectionForVideo(audioStreams, () -> {
						btn.setBackgroundColor(primaryColor);
						d.dismiss();
					});
				} else {
					audioEnabled = !audioStreams.isEmpty();
					if (!audioStreams.isEmpty()) selectedAudio = audioStreams.get(0);
					prefs.setSingleAudioEnabled(audioEnabled);
					if (audioEnabled) prefs.saveAudioSelection(selectedAudio);
					btn.setBackgroundColor(primaryColor);
					d.dismiss();
				}
			} else {
				btn.setBackgroundColor(grayColor);
				d.dismiss();
			}
		});
	}

	private void showAudioTrackSelectionForVideo(List<AudioStream> streams, Runnable onSelected) {
		View v = LayoutInflater.from(context).inflate(R.layout.quality_selector, new FrameLayout(context), false);
		LinearLayout container = v.findViewById(R.id.quality_container);
		v.findViewById(R.id.loadingBar2).setVisibility(View.GONE);

		AlertDialog d = new MaterialAlertDialogBuilder(context).setTitle("Select Audio Track").setView(v).setCancelable(false).create();
		CheckBox[] refs = new CheckBox[1];
		selectedAudio = prefs.restoreAudioSelection(streams);
		if (selectedAudio == null && !streams.isEmpty())
			selectedAudio = chooseOriginalAudioStream(streams);

		for (AudioStream s : streams) {
			CheckBox cb = new CheckBox(context);
			cb.setText(String.format(
							Locale.US,
							"%s%dkbps (%s)",
							(s.getAudioTrackName() != null && !s.getAudioTrackName().isEmpty()) ? s.getAudioTrackName() + " - " : "",
							s.getAverageBitrate(),
							formatSize(s.getItagItem().getContentLength())));
			cb.setOnCheckedChangeListener((v1, is) -> {
				if (is) {
					if (refs[0] != null) refs[0].setChecked(false);
					selectedAudio = s;
					refs[0] = cb;
				}
			});
			container.addView(cb);
			if (selectedAudio != null && selectedAudio.getItag() == s.getItag()) {
				cb.setChecked(true);
				refs[0] = cb;
			}
		}

		v.findViewById(R.id.button_cancel).setOnClickListener(v1 -> d.dismiss());
		v.findViewById(R.id.button_confirm).setOnClickListener(v1 -> {
			audioEnabled = true;
			prefs.setSingleAudioEnabled(true);
			prefs.saveAudioSelection(selectedAudio);
			onSelected.run();
			d.dismiss();
		});
		d.show();
	}

	private void showAudioSelectionDialog(Button btn) {
		View v = LayoutInflater.from(context).inflate(R.layout.quality_selector, new FrameLayout(context), false);
		LinearLayout container = v.findViewById(R.id.quality_container);
		ProgressBar loading = v.findViewById(R.id.loadingBar2);
		AlertDialog d = new MaterialAlertDialogBuilder(context).setTitle(R.string.audio_track).setView(v).create();
		v.findViewById(R.id.button_cancel).setOnClickListener(v1 -> d.dismiss());

		executor.submit(() -> {
			try {
				streamLatch.await();
				new Handler(Looper.getMainLooper()).post(() -> {
					loading.setVisibility(View.GONE);
					setupAudioContainer(v, container, d, btn);
				});
			} catch (InterruptedException ignored) {
			}
		});
		d.show();
	}

	private void setupAudioContainer(View dialogView, LinearLayout container, AlertDialog d, Button btn) {
		if (catalog == null) return;
		CheckBox[] refs = new CheckBox[1];
		List<AudioStream> audioStreams = new ArrayList<>();
		for (AudioStream s : catalog.getAudioStreams()) {
			if (s.getFormat() != MediaFormat.M4A) continue;
			audioStreams.add(s);
		}
		selectedAudio = prefs.restoreAudioSelection(audioStreams);
		if (selectedAudio == null && !audioStreams.isEmpty())
			selectedAudio = chooseOriginalAudioStream(audioStreams);
		for (AudioStream s : audioStreams) {
			CheckBox cb = new CheckBox(context);
			cb.setText(String.format(
							Locale.US,
							"%s%dkbps (%s)",
							(s.getAudioTrackName() != null && !s.getAudioTrackName().isEmpty()) ? s.getAudioTrackName() + " - " : "",
							s.getAverageBitrate(),
							formatSize(s.getItagItem().getContentLength())));
			cb.setOnCheckedChangeListener((v, is) -> {
				if (is) {
					if (refs[0] != null) refs[0].setChecked(false);
					selectedAudio = s;
					refs[0] = cb;
				}
			});
			container.addView(cb);
			if (selectedAudio != null && selectedAudio.getItag() == s.getItag()) {
				cb.setChecked(true);
				refs[0] = cb;
			}
		}
		dialogView.findViewById(R.id.button_confirm).setOnClickListener(v1 -> {
			audioEnabled = refs[0] != null;
			prefs.setSingleAudioEnabled(audioEnabled);
			if (audioEnabled) prefs.saveAudioSelection(selectedAudio);
			btn.setBackgroundColor(audioEnabled ? primaryColor : grayColor);
			d.dismiss();
		});
	}

	private void showSubtitleSelectionDialog(Button btn) {
		View v = LayoutInflater.from(context).inflate(R.layout.quality_selector, new FrameLayout(context), false);
		LinearLayout container = v.findViewById(R.id.quality_container);
		AlertDialog d = new MaterialAlertDialogBuilder(context).setTitle(R.string.subtitles).setView(v).create();
		v.findViewById(R.id.button_cancel).setOnClickListener(v1 -> d.dismiss());

		executor.submit(() -> {
			try {
				streamLatch.await();
				new Handler(Looper.getMainLooper()).post(() -> {
					if (videoDetails == null) return;
					CheckBox[] refs = new CheckBox[1];
					List<SubtitlesStream> subtitles = new ArrayList<>(this.subtitles);
					selectedSubtitle = prefs.restoreSubtitleSelection(subtitles);
					for (SubtitlesStream s : subtitles) {
						CheckBox cb = new CheckBox(context);
						cb.setText(s.getDisplayLanguageName());
						cb.setOnCheckedChangeListener((v1, is) -> {
							if (is) {
								if (refs[0] != null) refs[0].setChecked(false);
								selectedSubtitle = s;
								refs[0] = cb;
							}
						});
						container.addView(cb);
						if (selectedSubtitle != null && selectedSubtitle.getDisplayLanguageName().equals(s.getDisplayLanguageName())) {
							cb.setChecked(true);
							refs[0] = cb;
						}
					}
				});
			} catch (InterruptedException ignored) {
			}
		});
		v.findViewById(R.id.button_confirm).setOnClickListener(v1 -> {
			subtitleEnabled = selectedSubtitle != null;
			prefs.setSubtitleEnabled(subtitleEnabled);
			if (subtitleEnabled) prefs.saveSubtitleSelection(selectedSubtitle);
			btn.setBackgroundColor(subtitleEnabled ? primaryColor : grayColor);
			d.dismiss();
		});
		d.show();
	}

	private String formatSize(long bytes) {
		return bytes <= 0 ? "Unknown" : String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
	}

	private void restoreSavedState() {
		videoEnabled = prefs.isSingleVideoEnabled();
		audioEnabled = prefs.isSingleAudioEnabled();
		thumbEnabled = prefs.isThumbnailEnabled();
		subtitleEnabled = prefs.isSubtitleEnabled();
	}

	private void updateSelectionColors(Button videoButton, Button audioButton, Button thumbnailButton, Button subtitleButton) {
		videoButton.setBackgroundColor(videoEnabled ? primaryColor : grayColor);
		audioButton.setBackgroundColor(audioEnabled ? primaryColor : grayColor);
		thumbnailButton.setBackgroundColor(thumbEnabled ? primaryColor : grayColor);
		subtitleButton.setBackgroundColor(subtitleEnabled ? primaryColor : grayColor);
	}

	private void restoreStreamSelections() {
		if (catalog != null) {
			if (videoEnabled) {
				selectedVideo = prefs.restoreVideoSelection(videoChoices());
				videoEnabled = selectedVideo != null;
			} else {
				selectedVideo = null;
			}
			if (audioEnabled) {
				selectedAudio = prefs.restoreAudioSelection(audioChoices());
				audioEnabled = selectedAudio != null;
			} else {
				selectedAudio = null;
			}
		}
		if (videoDetails != null) {
			if (subtitleEnabled) {
				selectedSubtitle = prefs.restoreSubtitleSelection(this.subtitles);
				subtitleEnabled = selectedSubtitle != null;
			} else {
				selectedSubtitle = null;
			}
		}
	}

	@NonNull
	private List<VideoStream> videoChoices() {
		List<VideoStream> result = new ArrayList<>();
		if (catalog == null) return result;
		for (VideoStream stream : catalog.getVideoStreams()) {
			if (stream.getFormat() == MediaFormat.MPEG_4) result.add(stream);
		}
		return result;
	}

	@NonNull
	private Map<String, Integer> countVideoResolutions(@NonNull List<VideoStream> streams) {
		Map<String, Integer> counts = new HashMap<>();
		for (VideoStream stream : streams) {
			counts.merge(stream.getResolution(), 1, Integer::sum);
		}
		return counts;
	}

	@NonNull
	private String formatVideoLabel(@NonNull VideoStream stream, @NonNull Map<String, Integer> counts) {
		String resolution = stream.getResolution();
		int fps = stream.getFps();
		if ((counts.getOrDefault(resolution, 0) <= 1 && fps <= 30) || fps <= 0 || resolution.matches(".*\\d+$"))
			return resolution;
		return resolution + fps;
	}

	@NonNull
	private List<AudioStream> audioChoices() {
		List<AudioStream> result = new ArrayList<>();
		if (catalog == null) return result;
		for (AudioStream stream : catalog.getAudioStreams()) {
			if (stream.getFormat() == MediaFormat.M4A) result.add(stream);
		}
		return result;
	}

	@NonNull
	private AudioStream chooseOriginalAudioStream(@NonNull List<AudioStream> streams) {
		for (AudioStream stream : streams) {
			if (stream.getAudioTrackType() == AudioTrackType.ORIGINAL) return stream;
		}
		return streams.get(0);
	}

	private void dispatchDownloadTasks(@NonNull List<Task> tasks) {
		if (downloadService == null) return;
		downloadService.download(tasks);
		ToastUtils.show(context, tasks.size() == 1
						? context.getString(R.string.download_tasks_added)
						: context.getString(R.string.download_tasks_added_count, tasks.size()));
	}

	private void startDownloads(@NonNull List<Task> tasks) {
		if (!PermissionUtils.needsLegacyStoragePermission()) {
			dispatchOrQueue(tasks);
			return;
		}
		if (PermissionUtils.hasDownloadStoragePermission(context)) {
			dispatchOrQueue(tasks);
			return;
		}
		if (context instanceof DownloadPermissionHost host) {
			host.requestDownloadStoragePermission(() -> dispatchOrQueue(tasks));
			return;
		}
		ToastUtils.show(context, R.string.failed_to_download);
	}

	private void dispatchOrQueue(@NonNull List<Task> tasks) {
		if (isBound && downloadService != null) {
			dispatchDownloadTasks(tasks);
			if (dialog != null) dialog.dismiss();
			return;
		}
		pendingTasks = tasks;
		if (downloadButton != null) downloadButton.setEnabled(false);
		ToastUtils.show(context, R.string.preparing_download);
	}

	@NonNull
	private List<Task> buildTasks(@NonNull String rawFileName) {
		restoreStreamSelections();
		return new DownloadTaskFactory().buildSingleVideoTasks(
						videoDetails,
						catalog,
						new DownloadSelectionConfig(
										videoEnabled
														? DownloadSelectionConfig.PrimaryMediaMode.VIDEO
														: audioEnabled
														? DownloadSelectionConfig.PrimaryMediaMode.AUDIO
														: DownloadSelectionConfig.PrimaryMediaMode.NONE,
										subtitleEnabled,
										thumbEnabled,
										threadCount),
						selectedVideo,
						selectedAudio,
						selectedSubtitle,
						rawFileName,
						DownloadStorageUtils.getWorkingDirectory(context));
	}




}
