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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.R.attr;
import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.util.UnstableApi;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.downloader.core.Task;
import com.hhst.youtubelite.downloader.service.DownloadService;
import com.hhst.youtubelite.extractor.ExtractionSession;
import com.hhst.youtubelite.extractor.PlaybackInfo;
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.extractor.VideoDetails;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.gallery.GalleryActivity;
import com.hhst.youtubelite.util.DownloadStorageUtils;
import com.squareup.picasso.Picasso;
import com.tencent.mmkv.MMKV;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UnstableApi
public class DownloadDialog {
	private static final String KEY_THREAD_COUNT = "download_thread_count";

	private final Context context;
	private final ExecutorService executor;
	private final CountDownLatch videoLatch;
	private final CountDownLatch streamLatch;
	private final View dialogView;
	private final ExtractionSession extractionSession = new ExtractionSession();
	private final MMKV kv = MMKV.defaultMMKV();
	private VideoDetails videoDetails;
	private StreamDetails streamDetails;
	private boolean videoSel, thumbSel, audioSel, subtitleSel;
	private VideoStream videoSelStream;
	private AudioStream audioSelStream;
	private SubtitlesStream subtitleSelStream;
	private int threadCount = 4;
	private DownloadService downloadService;
	private boolean bindingRequested = false;
	private boolean isBound = false;
	private boolean isDismissed = false;
	@Nullable
	private AlertDialog dialog;
	@Nullable
	private Button downloadButton;
	@Nullable
	private List<Task> pendingTasks;
	private final ServiceConnection connection = new ServiceConnection() {
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

	public DownloadDialog(String url, Context context, YoutubeExtractor youtubeExtractor) {
		this.context = context;
		this.dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_download, new FrameLayout(context), false);
		executor = Executors.newCachedThreadPool();
		videoLatch = new CountDownLatch(1);
		streamLatch = new CountDownLatch(1);

		executor.submit(() -> {
			try {
				final PlaybackInfo playbackInfo = youtubeExtractor.getPlaybackInfo(url, extractionSession);
				videoDetails = playbackInfo.getVideoDetails();
				streamDetails = playbackInfo.getStreamDetails();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (InterruptedIOException ignored) {
			} catch (Exception e) {
				if (!extractionSession.isCancelled()) {
					new Handler(Looper.getMainLooper()).post(() ->
									Toast.makeText(context, R.string.failed_to_load_video_details, Toast.LENGTH_SHORT).show());
				}
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

		threadCount = kv.decodeInt(KEY_THREAD_COUNT, 4);
		threadsSeekBar.setProgress(threadCount - 1);
		threadsCountText.setText(String.valueOf(threadCount));

		TypedValue value = new TypedValue();
		context.getTheme().resolveAttribute(attr.colorPrimary, value, true);
		int primaryColor = value.data;
		int grayColor = context.getColor(android.R.color.darker_gray);

		// INITIAL STATE: Everything Gray (Fixed Highlight Glitch)
		videoButton.setBackgroundColor(grayColor);
		audioButton.setBackgroundColor(grayColor);
		thumbnailButton.setBackgroundColor(grayColor);
		subtitleButton.setBackgroundColor(grayColor);

		executor.submit(() -> {
			try {
				videoLatch.await();
				dialogView.post(() -> {
					progressBar.setVisibility(View.GONE);
					if (videoDetails != null) {
						Picasso.get().load(videoDetails.getThumbnail()).into(imageView);
						editText.setText(String.format("%s-%s", videoDetails.getTitle(), videoDetails.getAuthor()));

						// GALLERY FIX: Restore full ArrayList logic
						imageView.setOnClickListener(v -> executor.submit(() -> {
							Intent intent = new Intent(context, GalleryActivity.class);
							ArrayList<String> urls = new ArrayList<>();
							urls.add(videoDetails.getThumbnail());
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
			if (streamDetails != null) showVideoQualityDialog(videoButton, primaryColor);
		});
		audioButton.setOnClickListener(v -> {
			if (streamDetails != null) showAudioSelectionDialog(audioButton, primaryColor);
		});
		subtitleButton.setOnClickListener(v -> {
			if (streamDetails != null) showSubtitleSelectionDialog(subtitleButton, primaryColor);
		});

		thumbnailButton.setOnClickListener(v -> {
			thumbSel = !thumbSel;
			thumbnailButton.setBackgroundColor(thumbSel ? primaryColor : grayColor);
		});

		threadsSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar s, int p, boolean f) {
				threadCount = p + 1;
				threadsCountText.setText(String.valueOf(threadCount));
				kv.encode(KEY_THREAD_COUNT, threadCount);
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
			final String fileName = sanitizeFileName(editText.getText().toString().isEmpty() ? videoDetails.getTitle() : editText.getText().toString());
			final List<Task> tasks;
			try {
				tasks = getTasks(fileName);
			} catch (RuntimeException e) {
				Toast.makeText(context, R.string.failed_to_download, Toast.LENGTH_SHORT).show();
				return;
			}
			if (tasks.isEmpty()) {
				Toast.makeText(context, R.string.select_something_first, Toast.LENGTH_SHORT).show();
				return;
			}
			if (isBound && downloadService != null) {
				dispatchDownloadTasks(tasks);
				if (dialog != null) dialog.dismiss();
			} else {
				pendingTasks = tasks;
				if (DownloadDialog.this.downloadButton != null)
					DownloadDialog.this.downloadButton.setEnabled(false);
				Toast.makeText(context, R.string.preparing_download, Toast.LENGTH_SHORT).show();
			}
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
	}

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
		} finally {
			bindingRequested = false;
		}
	}

	private void showVideoQualityDialog(Button btn, int color) {
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
					setupVideoContainer(v, container, d, btn, color);
				});
			} catch (InterruptedException ignored) {
			}
		});
		d.show();
	}

	private void setupVideoContainer(View dialogView, LinearLayout container, AlertDialog d, Button btn, int color) {
		if (streamDetails == null) return;
		CheckBox[] refs = new CheckBox[1];
		long audioSize = (!streamDetails.getAudioStreams().isEmpty()) ? streamDetails.getAudioStreams().get(0).getItagItem().getContentLength() : 0;

		for (VideoStream s : streamDetails.getVideoStreams()) {
			if (s.getFormat() != MediaFormat.MPEG_4) continue;
			CheckBox cb = new CheckBox(context);
			cb.setText(String.format(Locale.US, "%s (%s)", s.getResolution(), formatSize(s.getItagItem().getContentLength() + audioSize)));
			cb.setOnCheckedChangeListener((v, is) -> {
				if (is) {
					if (refs[0] != null) refs[0].setChecked(false);
					videoSelStream = s;
					refs[0] = cb;
				}
			});
			container.addView(cb);
			if (videoSelStream != null && videoSelStream.getResolution().equals(s.getResolution())) {
				cb.setChecked(true);
				refs[0] = cb;
			}
		}

		dialogView.findViewById(R.id.button_confirm).setOnClickListener(v1 -> {
			videoSel = refs[0] != null;
			if (videoSel) {
				List<AudioStream> audioStreams = new ArrayList<>();
				for (AudioStream as : streamDetails.getAudioStreams()) {
					if (as.getFormat() == MediaFormat.M4A) audioStreams.add(as);
				}
				if (audioStreams.size() > 1) {
					showAudioTrackSelectionForVideo(audioStreams, () -> {
						btn.setBackgroundColor(color);
						d.dismiss();
					});
				} else {
					if (!audioStreams.isEmpty()) audioSelStream = audioStreams.get(0);
					btn.setBackgroundColor(color);
					d.dismiss();
				}
			} else {
				btn.setBackgroundColor(context.getColor(android.R.color.darker_gray));
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

		for (AudioStream s : streams) {
			CheckBox cb = new CheckBox(context);
			String label = (s.getAudioTrackName() != null && !s.getAudioTrackName().isEmpty()) ? s.getAudioTrackName() + " - " : "";
			cb.setText(String.format(Locale.US, "%s%dkbps (%s)", label, s.getAverageBitrate(), formatSize(s.getItagItem().getContentLength())));
			cb.setOnCheckedChangeListener((v1, is) -> {
				if (is) {
					if (refs[0] != null) refs[0].setChecked(false);
					audioSelStream = s;
					refs[0] = cb;
				}
			});
			container.addView(cb);
			if (refs[0] == null) {
				cb.setChecked(true);
				audioSelStream = s;
				refs[0] = cb;
			}
		}

		v.findViewById(R.id.button_cancel).setOnClickListener(v1 -> d.dismiss());
		v.findViewById(R.id.button_confirm).setOnClickListener(v1 -> {
			onSelected.run();
			d.dismiss();
		});
		d.show();
	}

	private void showAudioSelectionDialog(Button btn, int color) {
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
					setupAudioContainer(v, container, d, btn, color);
				});
			} catch (InterruptedException ignored) {
			}
		});
		d.show();
	}

	private void setupAudioContainer(View dialogView, LinearLayout container, AlertDialog d, Button btn, int color) {
		if (streamDetails == null) return;
		CheckBox[] refs = new CheckBox[1];
		for (AudioStream s : streamDetails.getAudioStreams()) {
			if (s.getFormat() != MediaFormat.M4A) continue;
			CheckBox cb = new CheckBox(context);
			String label = (s.getAudioTrackName() != null && !s.getAudioTrackName().isEmpty()) ? s.getAudioTrackName() + " - " : "";
			cb.setText(String.format(Locale.US, "%s%dkbps (%s)", label, s.getAverageBitrate(), formatSize(s.getItagItem().getContentLength())));
			cb.setOnCheckedChangeListener((v, is) -> {
				if (is) {
					if (refs[0] != null) refs[0].setChecked(false);
					audioSelStream = s;
					refs[0] = cb;
				}
			});
			container.addView(cb);
			if (audioSelStream != null && audioSelStream.getAverageBitrate() == s.getAverageBitrate()) {
				cb.setChecked(true);
				refs[0] = cb;
			}
		}
		dialogView.findViewById(R.id.button_confirm).setOnClickListener(v1 -> {
			audioSel = refs[0] != null;
			btn.setBackgroundColor(audioSel ? color : context.getColor(android.R.color.darker_gray));
			d.dismiss();
		});
	}

	private void showSubtitleSelectionDialog(Button btn, int color) {
		View v = LayoutInflater.from(context).inflate(R.layout.quality_selector, new FrameLayout(context), false);
		LinearLayout container = v.findViewById(R.id.quality_container);
		AlertDialog d = new MaterialAlertDialogBuilder(context).setTitle(R.string.subtitles).setView(v).create();
		v.findViewById(R.id.button_cancel).setOnClickListener(v1 -> d.dismiss());

		executor.submit(() -> {
			try {
				streamLatch.await();
				new Handler(Looper.getMainLooper()).post(() -> {
					if (streamDetails == null) return;
					CheckBox[] refs = new CheckBox[1];
					for (SubtitlesStream s : streamDetails.getSubtitles()) {
						CheckBox cb = new CheckBox(context);
						cb.setText(s.getDisplayLanguageName());
						cb.setOnCheckedChangeListener((v1, is) -> {
							if (is) {
								if (refs[0] != null) refs[0].setChecked(false);
								subtitleSelStream = s;
								refs[0] = cb;
							}
						});
						container.addView(cb);
					}
				});
			} catch (InterruptedException ignored) {
			}
		});
		v.findViewById(R.id.button_confirm).setOnClickListener(v1 -> {
			subtitleSel = subtitleSelStream != null;
			btn.setBackgroundColor(subtitleSel ? color : context.getColor(android.R.color.darker_gray));
			d.dismiss();
		});
		d.show();
	}

	private String sanitizeFileName(String f) {
		return f.replaceAll("[<>:\"/|?*]", "_");
	}

	private String formatSize(long bytes) {
		return bytes <= 0 ? "Unknown" : String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
	}

	private void dispatchDownloadTasks(@NonNull final List<Task> tasks) {
		if (downloadService == null) return;
		downloadService.download(tasks);
		final CharSequence message = tasks.size() == 1
						? context.getString(R.string.download_tasks_added)
						: context.getString(R.string.download_tasks_added_count, tasks.size());
		Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
	}

	@NonNull
	private List<Task> getTasks(String f) {
		List<Task> t = new ArrayList<>();
		File d = DownloadStorageUtils.getWorkingDirectory(context);
		if (videoSel && videoSelStream != null) {
			AudioStream audio = (audioSelStream != null) ? audioSelStream : streamDetails.getAudioStreams().get(0);
			t.add(new Task(videoDetails.getId() + ":v", videoSelStream, audio, null, null, f, d, threadCount));
		} else if (audioSel && audioSelStream != null) {
			t.add(new Task(videoDetails.getId() + ":a", null, audioSelStream, null, null, f, d, threadCount));
		}
		if (subtitleSel && subtitleSelStream != null) {
			t.add(new Task(videoDetails.getId() + ":s", null, null, subtitleSelStream, null, f, d, threadCount));
		}
		if (thumbSel)
			t.add(new Task(videoDetails.getId() + ":t", null, null, null, videoDetails.getThumbnail(), f, d, threadCount));
		return t;
	}
}


