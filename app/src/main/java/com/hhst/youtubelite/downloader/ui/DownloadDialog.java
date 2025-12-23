package com.hhst.youtubelite.downloader.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.util.UnstableApi;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hhst.youtubelite.ErrorDialog;
import com.hhst.youtubelite.MainActivity;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.downloader.core.DownloadTask;
import com.hhst.youtubelite.downloader.service.DownloadService;
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.extractor.VideoDetails;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.image.FullScreenImageActivity;
import com.squareup.picasso.Picasso;
import com.tencent.mmkv.MMKV;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = UnstableApi.class)
public class DownloadDialog {
	private static final String KEY_THREAD_COUNT = "download_thread_count";
	private final Context context;
	private final String url;
	private final ExecutorService executor;
	private final CountDownLatch videoLatch;
	private final CountDownLatch streamLatch;
	private final View dialogView;
	private VideoDetails videoDetails;
	private StreamDetails streamDetails;
	private boolean videoSel;
	private boolean thumbSel;
	private boolean audioSel;
	private boolean subtitleSel;
	private VideoStream streamSel;
	private AudioStream audioSelStream;
	private SubtitlesStream subtitleSelStream;
	private int threadCount = 4;

	public DownloadDialog(String url, Context context) {
		this.url = url;
		this.context = context;
		this.dialogView = LayoutInflater.from(context).inflate(R.layout.download_dialog, new FrameLayout(context), false);
		executor = Executors.newCachedThreadPool();
		videoLatch = new CountDownLatch(1);
		streamLatch = new CountDownLatch(1);
		executor.submit(() -> {
			try {
				// try to get details from cache
				videoDetails = YoutubeExtractor.getVideoInfo(url);
				videoLatch.countDown();
				streamDetails = YoutubeExtractor.getStreamInfo(url);
				streamLatch.countDown();
			} catch (Exception e) {
				if (e instanceof InterruptedIOException) return;
				Log.e("DownloadDialog", "Failed to load video details", e);
				new Handler(Looper.getMainLooper()).post(() -> {
					Toast.makeText(context, R.string.failed_to_load_video_details, Toast.LENGTH_SHORT).show();
					ErrorDialog.show(context, e.getMessage(), Log.getStackTraceString(e));
				});
			}
		});
	}

	public static String formatSize(long length) {
		if (length <= 0) return "0";

		int unitIndex = 0;

		String[] UNITS = {"B", "KB", "MB", "GB", "TB"};
		double size = length;

		while (size >= 1024 && unitIndex < UNITS.length - 1) {
			size /= 1024.0;
			unitIndex++;
		}

		return String.format(Locale.US, "%.1f %s", size, UNITS[unitIndex]);
	}

	public void show() {
		ProgressBar progressBar = dialogView.findViewById(R.id.loadingBar);
		if (videoDetails == null) progressBar.setVisibility(View.VISIBLE);

		AlertDialog dialog = new MaterialAlertDialogBuilder(context).setTitle(context.getString(R.string.download)).setView(dialogView).setCancelable(true).create();

		dialog.setOnDismissListener(dialogInterface -> executor.shutdownNow());

		ImageView imageView = dialogView.findViewById(R.id.download_image);
		EditText editText = dialogView.findViewById(R.id.download_edit_text);
		Button videoButton = dialogView.findViewById(R.id.button_video);
		Button thumbnailButton = dialogView.findViewById(R.id.button_thumbnail);
		final Button audioButton = dialogView.findViewById(R.id.button_audio);
		final Button subtitleButton = dialogView.findViewById(R.id.button_subtitle);
		final Button cancelButton = dialogView.findViewById(R.id.button_cancel);
		final Button downloadButton = dialogView.findViewById(R.id.button_download);
		final SeekBar threadsSeekBar = dialogView.findViewById(R.id.threads_seekbar);
		final TextView threadsCountText = dialogView.findViewById(R.id.threads_count);

		// load threadCount from MMKV
		threadCount = MMKV.defaultMMKV().decodeInt(KEY_THREAD_COUNT, 4);
		threadsSeekBar.setProgress(threadCount - 1);
		threadsCountText.setText(String.valueOf(threadCount));

		executor.submit(() -> {
			try {
				videoLatch.await();
				if (progressBar.getVisibility() == View.VISIBLE)
					dialogView.post(() -> progressBar.setVisibility(View.GONE));
			} catch (InterruptedException ignored) {
			}
		});

		// state
		videoSel = false;
		thumbSel = false;
		audioSel = false;
		subtitleSel = false;
		streamSel = null;
		audioSelStream = null;
		subtitleSelStream = null;

		// set button default background color
		videoButton.setBackgroundColor(context.getColor(android.R.color.darker_gray));
		thumbnailButton.setBackgroundColor(context.getColor(android.R.color.darker_gray));
		audioButton.setBackgroundColor(context.getColor(android.R.color.darker_gray));
		subtitleButton.setBackgroundColor(context.getColor(android.R.color.darker_gray));

		// get theme color
		TypedValue value = new TypedValue();
		context.getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, value, true);

		// on video button clicked
		videoButton.setOnClickListener(v -> showVideoQualityDialog(videoButton, value.data));

		// on audio button clicked
		audioButton.setOnClickListener(v -> showAudioSelectionDialog(audioButton, value.data));

		// on subtitle button clicked
		subtitleButton.setOnClickListener(v -> showSubtitleSelectionDialog(subtitleButton, value.data));

		// on thumbnail button clicked
		thumbnailButton.setOnClickListener(v -> {
			if (videoDetails == null) return;
			thumbSel = !thumbSel;
			thumbnailButton.setSelected(thumbSel);
			if (thumbSel) thumbnailButton.setBackgroundColor(value.data);
			else thumbnailButton.setBackgroundColor(context.getColor(android.R.color.darker_gray));
		});

		// threads seekbar
		threadsSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				threadCount = progress + 1;
				threadsCountText.setText(String.valueOf(threadCount));
				MMKV.defaultMMKV().encode(KEY_THREAD_COUNT, threadCount);
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
			}
		});

		// on download button clicked
		downloadButton.setOnClickListener(v -> {
			// fixed in live page
			if (videoDetails == null) {
				dialog.dismiss();
				return;
			}

			if (!videoSel && !thumbSel && !audioSel && !subtitleSel) {
				dialogView.post(() -> Toast.makeText(context, R.string.select_something_first, Toast.LENGTH_SHORT).show());
				return;
			}

			String fileName = editText.getText().toString();
			if (fileName.isEmpty()) fileName = videoDetails.getTitle();
			fileName = sanitizeFileName(fileName);

			// download thumbnail
			if (thumbSel) {
				Intent thumbnailIntent = new Intent(context, DownloadService.class);
				thumbnailIntent.setAction("DOWNLOAD_THUMBNAIL");
				thumbnailIntent.putExtra("thumbnail", videoDetails.getThumbnail());
				thumbnailIntent.putExtra("filename", fileName);
				context.startService(thumbnailIntent);
			}

			// download video/audio/subtitle
			if (videoSel || audioSel || subtitleSel) {
				Intent downloadIntent = new Intent(context, DownloadService.class);
				DownloadTask downloadTask = getTask(fileName);

				// Start download service
				context.startService(downloadIntent);

				// Get service and initiate download
				if (context instanceof MainActivity activity) {
					DownloadService service = activity.getDownloadService();
					if (service != null) service.initiateDownload(downloadTask);
				}
			}

			dialog.dismiss();
		});

		// on cancel button clicked
		cancelButton.setOnClickListener(v -> dialog.dismiss());

		dialog.setOnShowListener(l -> {
			loadImage(imageView);
			loadVideoName(editText);
			dialogView.post(() -> bindDeferredImageClick(imageView));
		});
		dialog.show();
	}

	@NonNull
	private DownloadTask getTask(String fileName) {
		DownloadTask downloadTask = new DownloadTask();
		downloadTask.setUrl(url);
		downloadTask.setFileName(fileName);
		downloadTask.setThumbnail(videoDetails.getThumbnail());
		downloadTask.setVideoStream(videoSel ? streamSel : null);

		// Set audio stream if video or audio is selected
		if (videoSel || audioSel)
			downloadTask.setAudioStream(audioSel ? audioSelStream : (streamDetails != null && !streamDetails.getAudioStreams().isEmpty() ? streamDetails.getAudioStreams().get(0) : null));

		downloadTask.setSubtitlesStream(subtitleSel ? subtitleSelStream : null);
		downloadTask.setAudio(audioSel && !videoSel); // Only audio if video is not selected
		downloadTask.setSubtitle(subtitleSel && !videoSel && !audioSel); // Standalone subtitle only if nothing else is selected
		downloadTask.setThreadCount(threadCount);
		return downloadTask;
	}

	private void loadImage(ImageView imageView) {
		if (videoDetails != null && videoDetails.getThumbnail() != null)
			dialogView.post(() -> Picasso.get().load(videoDetails.getThumbnail()).noFade().placeholder(new ColorDrawable(Color.TRANSPARENT)).error(R.drawable.ic_broken_image).into(imageView));
	}

	private void bindDeferredImageClick(ImageView imageView) {
		if (videoDetails == null || videoDetails.getThumbnail() == null) return;
		imageView.setOnClickListener(view -> executor.submit(() -> {
			Intent intent = new Intent(context, FullScreenImageActivity.class);
			ArrayList<String> urls = new ArrayList<>();
			urls.add(videoDetails.getThumbnail());
			intent.putStringArrayListExtra("thumbnails", urls);
			intent.putExtra("filename", String.format("%s-%s", videoDetails.getTitle(), videoDetails.getAuthor()).trim());
			context.startActivity(intent);
		}));
	}

	private void loadVideoName(EditText editText) {
		if (videoDetails != null)
			dialogView.post(() -> editText.setText(String.format("%s-%s", videoDetails.getTitle(), videoDetails.getAuthor())));
	}

	private void showVideoQualityDialog(Button videoButton, int themeColor) {
		View dialogView = LayoutInflater.from(context).inflate(R.layout.quality_selector, new FrameLayout(context), false);
		ProgressBar progressBar = dialogView.findViewById(R.id.loadingBar2);
		if (streamDetails == null) progressBar.setVisibility(View.VISIBLE);

		AlertDialog qualityDialog = new MaterialAlertDialogBuilder(context).setTitle(context.getString(R.string.video_quality)).setView(dialogView).create();
		LinearLayout qualitySelector = dialogView.findViewById(R.id.quality_container);
		Button cancelButton = dialogView.findViewById(R.id.button_cancel);
		Button confirmButton = dialogView.findViewById(R.id.button_confirm);

		CheckBox[] checkedBoxRef = new CheckBox[1];
		executor.submit(() -> {
			try {
				streamLatch.await();
			} catch (InterruptedException ignored) {
			}
			if (videoDetails == null || streamDetails == null || streamDetails.getVideoStreams().isEmpty()) {
				new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, R.string.failed_to_load_video_formats, Toast.LENGTH_SHORT).show());
				return;
			}
			if (progressBar.getVisibility() == View.VISIBLE)
				dialogView.post(() -> progressBar.setVisibility(View.GONE));

			// Find a default audio stream to calculate total size
			AudioStream defaultAudio = streamDetails.getAudioStreams().isEmpty() ? null : streamDetails.getAudioStreams().get(0);
			long audioSize = (defaultAudio != null && defaultAudio.getItagItem() != null) ? defaultAudio.getItagItem().getContentLength() : 0;

			new Handler(Looper.getMainLooper()).post(() -> {
				for (var stream : streamDetails.getVideoStreams()) {
					// Muxer restriction: filter for compatible video formats (H.264/AVC in MP4)
					if (stream.getFormat() != MediaFormat.MPEG_4) continue;

					CheckBox choice = new CheckBox(context);
					String sizeText = formatSize(audioSize + (stream.getItagItem() != null ? stream.getItagItem().getContentLength() : 0));
					choice.setText(String.format("%s (%s)", stream.getResolution(), sizeText));
					choice.setLayoutParams(new RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT));
					choice.setOnCheckedChangeListener((v, isChecked) -> {
						if (isChecked) {
							if (checkedBoxRef[0] != null) checkedBoxRef[0].setChecked(false);
							streamSel = stream;
							checkedBoxRef[0] = (CheckBox) v;
						} else {
							streamSel = null;
							checkedBoxRef[0] = null;
						}
					});
					qualitySelector.addView(choice);
					if (streamSel != null && streamSel.equals(stream)) choice.setChecked(true);
				}
			});
		});

		cancelButton.setOnClickListener(v -> qualityDialog.dismiss());
		confirmButton.setOnClickListener(v -> {
			if (checkedBoxRef[0] == null) {
				streamSel = null;
				videoSel = false;
				videoButton.setBackgroundColor(context.getColor(android.R.color.darker_gray));
			} else {
				videoSel = true;
				videoButton.setBackgroundColor(themeColor);
			}
			qualityDialog.dismiss();
		});
		qualityDialog.show();
	}

	private void showAudioSelectionDialog(Button audioButton, int themeColor) {
		View dialogView = LayoutInflater.from(context).inflate(R.layout.quality_selector, new FrameLayout(context), false);
		ProgressBar progressBar = dialogView.findViewById(R.id.loadingBar2);
		if (streamDetails == null) progressBar.setVisibility(View.VISIBLE);

		AlertDialog audioDialog = new MaterialAlertDialogBuilder(context).setTitle(context.getString(R.string.select_audio)).setView(dialogView).create();
		LinearLayout container = dialogView.findViewById(R.id.quality_container);
		Button cancelButton = dialogView.findViewById(R.id.button_cancel);
		Button confirmButton = dialogView.findViewById(R.id.button_confirm);

		CheckBox[] checkedBoxRef = new CheckBox[1];
		executor.submit(() -> {
			try {
				streamLatch.await();
			} catch (InterruptedException ignored) {
			}
			if (streamDetails == null || streamDetails.getAudioStreams().isEmpty()) {
				new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, R.string.no_audio_tracks, Toast.LENGTH_SHORT).show());
				return;
			}
			if (progressBar.getVisibility() == View.VISIBLE)
				dialogView.post(() -> progressBar.setVisibility(View.GONE));

			new Handler(Looper.getMainLooper()).post(() -> {
				for (var stream : streamDetails.getAudioStreams()) {
					// Muxer restriction: filter for compatible audio formats (AAC in M4A)
					if (stream.getFormat() != MediaFormat.M4A) continue;

					CheckBox choice = new CheckBox(context);
					int bitrate = stream.getAverageBitrate();
					if (bitrate <= 0) bitrate = stream.getBitrate();
					String bitrateStr = bitrate > 0 ? bitrate + "kbps" : context.getString(R.string.unknown_bitrate);
					String info = stream.getAudioTrackName() != null ? String.format(Locale.getDefault(), "%s (%s - %s)", stream.getAudioTrackName(), stream.getFormat().getName(), bitrateStr) : String.format(Locale.getDefault(), "%s - %s", stream.getFormat().getName(), bitrateStr);
					choice.setText(info);
					choice.setLayoutParams(new RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT));
					choice.setOnCheckedChangeListener((v, isChecked) -> {
						if (isChecked) {
							if (checkedBoxRef[0] != null) checkedBoxRef[0].setChecked(false);
							audioSelStream = stream;
							checkedBoxRef[0] = (CheckBox) v;
						} else {
							audioSelStream = null;
							checkedBoxRef[0] = null;
						}
					});
					container.addView(choice);
					if (audioSelStream != null && audioSelStream.equals(stream)) choice.setChecked(true);
				}
			});
		});

		cancelButton.setOnClickListener(v -> audioDialog.dismiss());
		confirmButton.setOnClickListener(v -> {
			if (checkedBoxRef[0] == null) {
				audioSelStream = null;
				audioSel = false;
				audioButton.setBackgroundColor(context.getColor(android.R.color.darker_gray));
			} else {
				audioSel = true;
				audioButton.setBackgroundColor(themeColor);
			}
			audioDialog.dismiss();
		});
		audioDialog.show();
	}

	private void showSubtitleSelectionDialog(Button subtitleButton, int themeColor) {
		View dialogView = LayoutInflater.from(context).inflate(R.layout.quality_selector, new FrameLayout(context), false);
		ProgressBar progressBar = dialogView.findViewById(R.id.loadingBar2);
		if (streamDetails == null) progressBar.setVisibility(View.VISIBLE);

		AlertDialog subtitleDialog = new MaterialAlertDialogBuilder(context).setTitle(context.getString(R.string.select_subtitle)).setView(dialogView).create();
		LinearLayout container = dialogView.findViewById(R.id.quality_container);
		Button cancelButton = dialogView.findViewById(R.id.button_cancel);
		Button confirmButton = dialogView.findViewById(R.id.button_confirm);

		CheckBox[] checkedBoxRef = new CheckBox[1];
		executor.submit(() -> {
			try {
				streamLatch.await();
			} catch (InterruptedException ignored) {
			}
			if (streamDetails == null || streamDetails.getSubtitles().isEmpty()) {
				new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, R.string.no_subtitles, Toast.LENGTH_SHORT).show());
				return;
			}
			if (progressBar.getVisibility() == View.VISIBLE)
				dialogView.post(() -> progressBar.setVisibility(View.GONE));

			new Handler(Looper.getMainLooper()).post(() -> {
				for (var stream : streamDetails.getSubtitles()) {
					CheckBox choice = new CheckBox(context);
					choice.setText(stream.getDisplayLanguageName());
					choice.setLayoutParams(new RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT));
					choice.setOnCheckedChangeListener((v, isChecked) -> {
						if (isChecked) {
							if (checkedBoxRef[0] != null) checkedBoxRef[0].setChecked(false);
							subtitleSelStream = stream;
							checkedBoxRef[0] = (CheckBox) v;
						} else {
							subtitleSelStream = null;
							checkedBoxRef[0] = null;
						}
					});
					container.addView(choice);
					if (subtitleSelStream != null && subtitleSelStream.equals(stream))
						choice.setChecked(true);
				}
			});
		});

		cancelButton.setOnClickListener(v -> subtitleDialog.dismiss());
		confirmButton.setOnClickListener(v -> {
			if (checkedBoxRef[0] == null) {
				subtitleSelStream = null;
				subtitleSel = false;
				subtitleButton.setBackgroundColor(context.getColor(android.R.color.darker_gray));
			} else {
				subtitleSel = true;
				subtitleButton.setBackgroundColor(themeColor);
			}
			subtitleDialog.dismiss();
		});
		subtitleDialog.show();
	}

	private String sanitizeFileName(String fileName) {
		// Remove invalid characters for file names
		return fileName.replaceAll("[<>:\"/|?*]", "_");
	}
}
