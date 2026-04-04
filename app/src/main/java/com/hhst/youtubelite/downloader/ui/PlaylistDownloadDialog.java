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
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.R.attr;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.browser.TabManager;
import com.hhst.youtubelite.browser.YoutubeWebview;
import com.hhst.youtubelite.downloader.core.DownloadPrefs;
import com.hhst.youtubelite.downloader.core.DownloadSelectionConfig;
import com.hhst.youtubelite.downloader.core.DownloadTaskFactory;
import com.hhst.youtubelite.downloader.core.Task;
import com.hhst.youtubelite.downloader.core.history.DownloadRecord;
import com.hhst.youtubelite.downloader.core.history.DownloadStatus;
import com.hhst.youtubelite.downloader.core.history.DownloadType;
import com.hhst.youtubelite.downloader.service.DownloadService;
import com.hhst.youtubelite.extractor.ExtractionSession;
import com.hhst.youtubelite.extractor.PlaybackDetails;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.util.DownloadStorageUtils;
import com.hhst.youtubelite.util.ToastUtils;
import com.hhst.youtubelite.util.ViewUtils;
import com.tencent.mmkv.MMKV;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dialog that batches playlist items into download tasks.
 */
@UnstableApi
public final class PlaylistDownloadDialog {
	private static final long PLAYLIST_LOAD_RETRY_DELAY_MS = 250L;
	private final Context context;
	private final YoutubeExtractor youtubeExtractor;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final DownloadPrefs prefs = new DownloadPrefs(MMKV.defaultMMKV());
	private final Gson gson = new Gson();
	private final DownloadTaskFactory taskFactory = new DownloadTaskFactory();
	@Nullable
	private final TabManager tabManager;
	@Nullable
	private final YoutubeWebview sourceWebView;
	@Nullable
	private final String expectedPlaylistId;
	@Nullable
	private final List<PlaylistDownloadItem> seededItems;
	@Nullable
	private final String seededTitle;
	private final List<PlaylistDownloadItem> items = new ArrayList<>();
	private final PlaylistDownloadItemsAdapter adapter;
	private final View dialogView;
	@Nullable
	private AlertDialog dialog;
	@Nullable
	private Button videoButton;	private final ServiceConnection connection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			final DownloadService.DownloadBinder binder = (DownloadService.DownloadBinder) service;
			downloadService = binder.getService();
			isBound = true;
			if (batchReq != null && !batchRunning && !batchCanceled) {
				startBatchPreparation(batchReq);
				return;
			}
			updatePrimaryActionState();
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			downloadService = null;
			isBound = false;
			updatePrimaryActionState();
		}
	};
	@Nullable
	private Button audioButton;
	@Nullable
	private Button subtitleButton;
	@Nullable
	private Button thumbnailButton;
	@Nullable
	private Button cancelButton;
	@Nullable
	private Button downloadButton;
	@Nullable
	private TextView playlistTitleView;
	@Nullable
	private TextView summaryView;
	@Nullable
	private TextView threadsCountView;
	@Nullable
	private MaterialCheckBox selectAllCheckBox;
	@Nullable
	private SeekBar threadsSeekBar;
	@Nullable
	private ProgressBar loadingBar;
	@Nullable
	private RecyclerView recyclerView;
	@Nullable
	private BatchRequest batchReq;
	@Nullable
	private DownloadRecord parentRecord;
	@Nullable
	private ExtractionSession batchExtractionSession;
	@Nullable
	private String playlistTitle;
	@Nullable
	private String terminalLoadMessage;
	@Nullable
	private DownloadService downloadService;
	private boolean loadingTerminal;
	private boolean loadingCanceled;
	private boolean manualSelectionChanged;
	private boolean batchRunning;
	private boolean batchCanceled;
	private boolean backgroundBatch;
	private boolean resourcesDisposed;
	private boolean bindingRequested;
	private boolean isBound;
	private int threadCount;
	private int batchPreparedCount;
	private int batchTotalCount;
	private int toggleSelectedColor;
	private int toggleUnselectedColor;
	@NonNull
	private DownloadSelectionConfig.PrimaryMediaMode primaryMediaMode = DownloadSelectionConfig.PrimaryMediaMode.VIDEO;
	private boolean subtitleEnabled;
	private boolean thumbnailEnabled;
	public PlaylistDownloadDialog(@Nullable String seededTitle,
	                              @Nullable List<PlaylistDownloadItem> seededItems,
	                              @Nullable YoutubeWebview sourceWebView,
	                              @Nullable String expectedPlaylistId,
	                              @NonNull Context context,
	                              @NonNull YoutubeExtractor youtubeExtractor,
	                              @Nullable TabManager tabManager) {
		this.seededTitle = seededTitle;
		this.seededItems = seededItems;
		this.sourceWebView = sourceWebView;
		this.expectedPlaylistId = expectedPlaylistId;
		this.context = context;
		this.youtubeExtractor = youtubeExtractor;
		this.tabManager = tabManager;
		this.adapter = new PlaylistDownloadItemsAdapter((playlistIndex, selected) -> {
			if (isUiLockedForBatch()) return;
			PlaylistDownloadItem item = findItem(playlistIndex);
			if (item == null || !item.isSelectable()) return;
			manualSelectionChanged = true;
			item.setSelected(selected);
			refreshItems();
			updateSelectionSummary();
			updatePrimaryActionState();
		});
		this.dialogView = LayoutInflater.from(context).inflate(
						R.layout.dialog_playlist_download,
						new FrameLayout(context),
						false);
	}

	public void show() {
		videoButton = dialogView.findViewById(R.id.button_playlist_video);
		audioButton = dialogView.findViewById(R.id.button_playlist_audio);
		subtitleButton = dialogView.findViewById(R.id.button_playlist_subtitle);
		thumbnailButton = dialogView.findViewById(R.id.button_playlist_thumbnail);
		cancelButton = dialogView.findViewById(R.id.button_playlist_cancel);
		downloadButton = dialogView.findViewById(R.id.button_playlist_download);
		playlistTitleView = dialogView.findViewById(R.id.playlist_title);
		summaryView = dialogView.findViewById(R.id.playlist_summary);
		threadsCountView = dialogView.findViewById(R.id.playlist_threads_count);
		selectAllCheckBox = dialogView.findViewById(R.id.playlist_select_all_checkbox);
		threadsSeekBar = dialogView.findViewById(R.id.playlist_threads_seekbar);
		loadingBar = dialogView.findViewById(R.id.playlist_loading_bar);
		recyclerView = dialogView.findViewById(R.id.playlist_items_recycler);

		dialog = new MaterialAlertDialogBuilder(context)
						.setView(dialogView)
						.setCancelable(true)
						.create();
		dialog.setOnDismissListener(ignored -> {
			if (backgroundBatch || batchReq != null || batchRunning) {
				dialog = null;
				return;
			}
			loadingCanceled = true;
			if (batchExtractionSession != null) batchExtractionSession.cancel();
			disposeResources();
		});

		threadCount = prefs.getThreadCount();
		primaryMediaMode = prefs.getPrimaryMediaMode();
		subtitleEnabled = prefs.isSubtitleEnabled();
		thumbnailEnabled = prefs.isThumbnailEnabled();
		if (threadsSeekBar != null) threadsSeekBar.setProgress(threadCount - 1);
		if (threadsCountView != null) threadsCountView.setText(String.valueOf(threadCount));

		TypedValue value = new TypedValue();
		context.getTheme().resolveAttribute(attr.colorPrimary, value, true);
		toggleSelectedColor = value.data;
		toggleUnselectedColor = context.getColor(android.R.color.darker_gray);

		if (recyclerView != null) {
			recyclerView.setLayoutManager(new LinearLayoutManager(context));
			recyclerView.setAdapter(adapter);
			recyclerView.setItemAnimator(null);
			recyclerView.setHasFixedSize(false);
			recyclerView.setNestedScrollingEnabled(true);
			recyclerView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
		}
		if (selectAllCheckBox != null) {
			selectAllCheckBox.setOnClickListener(v -> {
				if (isUiLockedForBatch()) return;
				manualSelectionChanged = true;
				boolean shouldSelectAll = countSelectedReadyItems() < countReadyItems();
				for (PlaylistDownloadItem item : items) {
					if (!item.isReady()) continue;
					item.setSelected(shouldSelectAll);
				}
				refreshItems();
				updateSelectionSummary();
				updatePrimaryActionState();
			});
		}
		if (videoButton != null) {
			videoButton.setOnClickListener(v -> {
				if (isUiLockedForBatch()) return;
				primaryMediaMode = primaryMediaMode == DownloadSelectionConfig.PrimaryMediaMode.VIDEO
								? DownloadSelectionConfig.PrimaryMediaMode.NONE
								: DownloadSelectionConfig.PrimaryMediaMode.VIDEO;
				prefs.setPrimaryMediaMode(primaryMediaMode);
				updateToggleButtons();
				updatePrimaryActionState();
			});
		}
		if (audioButton != null) {
			audioButton.setOnClickListener(v -> {
				if (isUiLockedForBatch()) return;
				primaryMediaMode = primaryMediaMode == DownloadSelectionConfig.PrimaryMediaMode.AUDIO
								? DownloadSelectionConfig.PrimaryMediaMode.NONE
								: DownloadSelectionConfig.PrimaryMediaMode.AUDIO;
				prefs.setPrimaryMediaMode(primaryMediaMode);
				updateToggleButtons();
				updatePrimaryActionState();
			});
		}
		if (subtitleButton != null) {
			subtitleButton.setOnClickListener(v -> {
				if (isUiLockedForBatch()) return;
				subtitleEnabled = !subtitleEnabled;
				prefs.setSubtitleEnabled(subtitleEnabled);
				updateToggleButtons();
				updatePrimaryActionState();
			});
		}
		if (thumbnailButton != null) {
			thumbnailButton.setOnClickListener(v -> {
				if (isUiLockedForBatch()) return;
				thumbnailEnabled = !thumbnailEnabled;
				prefs.setThumbnailEnabled(thumbnailEnabled);
				updateToggleButtons();
				updatePrimaryActionState();
			});
		}
		if (threadsSeekBar != null) {
			threadsSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
				@Override
				public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
					threadCount = progress + 1;
					prefs.setThreadCount(threadCount);
					if (threadsCountView != null) threadsCountView.setText(String.valueOf(threadCount));
				}

				@Override
				public void onStartTrackingTouch(SeekBar seekBar) {
				}

				@Override
				public void onStopTrackingTouch(SeekBar seekBar) {
				}
			});
		}
		if (cancelButton != null) {
			cancelButton.setOnClickListener(v -> {
				if (dialog != null) dialog.dismiss();
			});
		}
		if (downloadButton != null) {
			downloadButton.setOnClickListener(v -> {
				if (isUiLockedForBatch()) return;
				DownloadSelectionConfig config = new DownloadSelectionConfig(
								primaryMediaMode,
								subtitleEnabled,
								thumbnailEnabled,
								threadCount);
				if (!config.hasAnyOutputEnabled()) {
					ToastUtils.show(context, R.string.select_something_first);
					return;
				}
				List<Integer> selectedIndexes = new ArrayList<>();
				Map<Integer, String> requestedNames = new LinkedHashMap<>();
				for (PlaylistDownloadItem item : items) {
					if (!item.isReady() || !item.isSelected()) continue;
					selectedIndexes.add(item.getPlaylistIndex());
					requestedNames.put(
									item.getPlaylistIndex(),
									taskFactory.buildPlaylistBaseName(
													item.getTitle(),
													item.getAuthor()));
				}
				if (selectedIndexes.isEmpty()) {
					ToastUtils.show(context, R.string.select_something_first);
					return;
				}
				long now = System.currentTimeMillis();
				PlaylistDownloadItem firstItem = findItem(selectedIndexes.get(0));
				String parentTitle = playlistTitle != null && !playlistTitle.isBlank()
								? playlistTitle
								: seededTitle != null && !seededTitle.isBlank()
								? seededTitle
								: context.getString(R.string.playlist_download_title_placeholder);
				DownloadRecord record = new DownloadRecord();
				record.setTaskId("playlist:" + (expectedPlaylistId != null && !expectedPlaylistId.isBlank()
								? expectedPlaylistId
								: "local-queue") + ":" + now);
				record.setVideoId(firstItem == null ? "" : firstItem.getVideoId());
				record.setType(DownloadType.PLAYLIST);
				record.setStatus(DownloadStatus.QUEUED);
				record.setProgress(0);
				record.setFileName(parentTitle);
				record.setOutputPath("");
				record.setCreatedAt(now);
				record.setUpdatedAt(now);
				record.setTitle(parentTitle);
				record.setThumbnailUrl(firstItem == null ? null : firstItem.getThumbnailUrl());
				record.setItemCount(selectedIndexes.size());
				record.setDoneCount(0);
				record.setFailedCount(0);
				record.setRunningCount(selectedIndexes.size());
				record.setSealed(false);
				BatchRequest batchRequest = new BatchRequest(
								selectedIndexes,
								taskFactory.planPlaylistNames(requestedNames),
								config,
								record);
				batchReq = batchRequest;
				backgroundBatch = true;
				ToastUtils.show(context, R.string.playlist_download_started);
				if (isBound && downloadService != null) {
					startBatchPreparation(batchRequest);
				} else {
					ToastUtils.show(context, R.string.preparing_download);
					if (!bindingRequested) {
						bindingRequested = context.bindService(
										new Intent(context, DownloadService.class),
										connection,
										Context.BIND_AUTO_CREATE);
					}
				}
				if (dialog != null) dialog.dismiss();
			});
		}
		if (!bindingRequested) {
			bindingRequested = context.bindService(
							new Intent(context, DownloadService.class),
							connection,
							Context.BIND_AUTO_CREATE);
		}
		updatePlaylistTitle();
		updateSelectionSummary();
		updateToggleButtons();
		updateStatusText();
		updatePrimaryActionState();
		dialog.show();
		AlertDialog activeDialog = dialog;
		if (activeDialog != null) {
			int originalLeft = dialogView.getPaddingLeft();
			int originalTop = dialogView.getPaddingTop();
			int originalRight = dialogView.getPaddingRight();
			int originalBottom = ViewUtils.dpToPx(context, 24);
			ViewCompat.setOnApplyWindowInsetsListener(dialogView, (view, insets) -> {
				Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
				view.setPadding(originalLeft, originalTop, originalRight, originalBottom + systemBars.bottom);
				return insets;
			});
			ViewCompat.requestApplyInsets(dialogView);
			dialogView.post(() -> {
				if (resourcesDisposed || dialog != activeDialog || activeDialog.getWindow() == null) return;
				final WindowManager.LayoutParams layoutParams = activeDialog.getWindow().getAttributes();
				layoutParams.height = context.getResources().getDisplayMetrics().heightPixels
								- ViewUtils.dpToPx(context, 72f);
				activeDialog.getWindow().setAttributes(layoutParams);
			});
		}
		if (seededItems != null) {
			if (resourcesDisposed) return;
			playlistTitle = seededTitle;
			updatePlaylistTitle();
			items.clear();
			items.addAll(seededItems);
			loadingTerminal = true;
			terminalLoadMessage = null;
			refreshItems();
			updateSelectionSummary();
			updateStatusText();
			updatePrimaryActionState();
			return;
		}
		startPlaylistLoad(6);
	}

	private void startPlaylistLoad(int retriesRemaining) {
		if (loadingCanceled || resourcesDisposed) return;
		String expectedPlaylistIdJson = gson.toJson(expectedPlaylistId);
		String script = String.format(Locale.US, """
						(function() {
						  const expectedPlaylistId = %s;
						  const runsText = (value) => {
						    if (!value) return '';
						    if (typeof value.simpleText === 'string') return value.simpleText;
						    if (Array.isArray(value.runs)) {
						      return value.runs.map(run => run?.text ?? '').join('');
						    }
						    return '';
						  };
						  const playlistIdFromUrl = (input) => {
						    if (typeof input !== 'string' || input.length === 0) return '';
						    try {
						      return new URL(input, location.origin).searchParams.get('list') || '';
						    } catch (e) {
						      return '';
						    }
						  };
						  const pagePlaylistId = globalThis.ytInitialData?.contents?.singleColumnWatchNextResults?.playlist?.playlist?.playlistId || '';
						  const playlistId = pagePlaylistId || playlistIdFromUrl(location.href);
						  if (expectedPlaylistId && playlistId && expectedPlaylistId !== playlistId) {
						    return JSON.stringify({ ok: false, title: document.title, items: [] });
						  }
						  const normalizeRenderer = (renderer, index) => {
						    if (!renderer?.videoId) return null;
						    const relativeUrl = renderer.navigationEndpoint?.commandMetadata?.webCommandMetadata?.url;
						    return {
						      index,
						      videoId: renderer.videoId,
						      url: typeof relativeUrl === 'string' && relativeUrl.length > 0
						              ? new URL(relativeUrl, location.origin).toString()
						              : `https://www.youtube.com/watch?v=${renderer.videoId}`,
						      title: runsText(renderer.title),
						      author: runsText(renderer.shortBylineText || renderer.longBylineText),
						      thumbnailUrl: Array.isArray(renderer.thumbnail?.thumbnails) && renderer.thumbnail.thumbnails.length > 0
						              ? renderer.thumbnail.thumbnails[renderer.thumbnail.thumbnails.length - 1].url
						              : '',
						      durationText: runsText(renderer.lengthText),
						      selected: renderer.selected === true
						    };
						  };
						  const normalizeEntries = (entries) => {
						    if (!Array.isArray(entries)) return [];
						    return entries.map((entry, index) => {
						      const renderer = entry?.playlistPanelVideoRenderer || entry?.playlistVideoRenderer;
						      return normalizeRenderer(renderer, index);
						    }).filter(Boolean);
						  };
						  const queue = [globalThis.ytInitialData];
						  const visited = new WeakSet();
						  let matchedTitle = '';
						  let items = [];
						  while (queue.length > 0) {
						    const node = queue.shift();
						    if (!node || typeof node !== 'object' || visited.has(node)) continue;
						    visited.add(node);
						    if (Array.isArray(node)) {
						      const normalized = normalizeEntries(node);
						      if (normalized.length > 0) {
						        items = normalized;
						        break;
						      }
						      for (const child of node) {
						        if (child && typeof child === 'object') queue.push(child);
						      }
						      continue;
						    }
						    if (Array.isArray(node.contents)) {
						      const normalized = normalizeEntries(node.contents);
						      if (normalized.length > 0) {
						        matchedTitle = runsText(node.title);
						        items = normalized;
						        break;
						      }
						    }
						    for (const child of Object.values(node)) {
						      if (child && typeof child === 'object') queue.push(child);
						    }
						  }
						  if (items.length === 0) {
						    return JSON.stringify({ ok: false, title: document.title, items: [] });
						  }
						  return JSON.stringify({
						    ok: true,
						    title: matchedTitle || document.title,
						    items
						  });
						})();
						""", expectedPlaylistIdJson);
		if (sourceWebView != null) {
			sourceWebView.evaluateJavascript(script, rawResult -> handlePlaylistPayloadResult(rawResult, retriesRemaining));
			return;
		}
		if (tabManager == null) {
			finishLoading(context.getString(R.string.playlist_download_failed_initial), false);
			return;
		}
		tabManager.evalWatchJs(script, rawResult -> handlePlaylistPayloadResult(rawResult, retriesRemaining));
	}

	private void handlePlaylistPayloadResult(@Nullable String rawResult, int retriesRemaining) {
		if (loadingCanceled || resourcesDisposed) return;
		final JsonObject payload;
		if (rawResult == null || rawResult.isBlank() || "null".equals(rawResult)) {
			payload = null;
		} else {
			JsonObject parsedPayload;
			try {
				String json = gson.fromJson(rawResult, String.class);
				if (json == null || json.isBlank()) {
					parsedPayload = null;
				} else {
					JsonObject candidate = gson.fromJson(json, JsonObject.class);
					parsedPayload = candidate == null || candidate.isJsonNull() ? null : candidate;
				}
			} catch (Exception ignored) {
				parsedPayload = null;
			}
			payload = parsedPayload;
		}
		if (payload == null) {
			if (retriesRemaining > 0) {
				handler.postDelayed(() -> {
					if (loadingCanceled || resourcesDisposed) return;
					startPlaylistLoad(retriesRemaining - 1);
				}, PLAYLIST_LOAD_RETRY_DELAY_MS);
				return;
			}
			finishLoading(context.getString(R.string.playlist_download_failed_initial), false);
			return;
		}
		JsonArray payloadItems = payload.has("items") && payload.get("items").isJsonArray()
						? payload.getAsJsonArray("items")
						: null;
		if (!payload.has("ok") || !payload.get("ok").getAsBoolean() || payloadItems == null || payloadItems.isEmpty()) {
			if (retriesRemaining > 0) {
				handler.postDelayed(() -> {
					if (loadingCanceled || resourcesDisposed) return;
					startPlaylistLoad(retriesRemaining - 1);
				}, PLAYLIST_LOAD_RETRY_DELAY_MS);
				return;
			}
			finishLoading(context.getString(R.string.playlist_download_empty), false);
			return;
		}
		playlistTitle = payload.has("title") && !payload.get("title").isJsonNull()
						? payload.get("title").getAsString()
						: null;
		updatePlaylistTitle();
		List<PlaylistDownloadItem> result = new ArrayList<>();
		int playlistIndex = 0;
		for (int i = 0; i < payloadItems.size(); i++) {
			if (!payloadItems.get(i).isJsonObject()) continue;
			JsonObject payloadItem = payloadItems.get(i).getAsJsonObject();
			String videoId = payloadItem.has("videoId") && !payloadItem.get("videoId").isJsonNull()
							? payloadItem.get("videoId").getAsString()
							: null;
			String url = payloadItem.has("url") && !payloadItem.get("url").isJsonNull()
							? payloadItem.get("url").getAsString()
							: null;
			if (videoId == null || videoId.isBlank() || url == null || url.isBlank()) continue;
			PlaylistDownloadItem item = new PlaylistDownloadItem(playlistIndex++, videoId, url);
			item.setTitle(payloadItem.has("title") && !payloadItem.get("title").isJsonNull()
							? payloadItem.get("title").getAsString()
							: null);
			item.setAuthor(payloadItem.has("author") && !payloadItem.get("author").isJsonNull()
							? payloadItem.get("author").getAsString()
							: null);
			item.setThumbnailUrl(payloadItem.has("thumbnailUrl") && !payloadItem.get("thumbnailUrl").isJsonNull()
							? payloadItem.get("thumbnailUrl").getAsString()
							: null);
			long durationSeconds = 0L;
			String durationText = payloadItem.has("durationText") && !payloadItem.get("durationText").isJsonNull()
							? payloadItem.get("durationText").getAsString()
							: null;
			if (durationText != null && !durationText.isBlank()) {
				for (String part : durationText.trim().split(":")) {
					try {
						durationSeconds = durationSeconds * 60L + Integer.parseInt(part.trim());
					} catch (NumberFormatException ignored) {
						durationSeconds = 0L;
						break;
					}
				}
			}
			item.setDurationSeconds(durationSeconds);
			item.setAvailabilityStatus(PlaylistDownloadItem.AvailabilityStatus.READY);
			item.setSelected(!manualSelectionChanged);
			result.add(item);
		}
		items.clear();
		items.addAll(result);
		refreshItems();
		updateSelectionSummary();
		updateStatusText();
		updatePrimaryActionState();
		finishLoading(null, !payloadItems.isEmpty());
	}

	private void finishLoading(@Nullable String terminalLoadMessage, boolean hadRows) {
		if (resourcesDisposed) return;
		this.terminalLoadMessage = terminalLoadMessage;
		loadingTerminal = true;
		if (!hadRows && terminalLoadMessage != null) {
			items.clear();
			refreshItems();
		}
		updateSelectionSummary();
		updateStatusText();
		updatePrimaryActionState();
	}

	private void startBatchPreparation(@NonNull BatchRequest batchRequest) {
		if (resourcesDisposed) return;
		batchReq = null;
		batchRunning = true;
		batchCanceled = false;
		parentRecord = batchRequest.parent;
		batchPreparedCount = 0;
		batchTotalCount = batchRequest.selectedIndexes.size();
		batchExtractionSession = new ExtractionSession();
		lockUiForBatch(true);
		updateStatusText();
		updatePrimaryActionState();
		DownloadService service = downloadService;
		if (service != null) service.upsertPlaylistRecord(batchRequest.parent);
		executor.submit(() -> runBatchPreparation(batchRequest));
	}

	private void runBatchPreparation(@NonNull BatchRequest batchRequest) {
		int queuedRows = 0;
		int partialRows = 0;
		int failedRows = 0;
		Set<Integer> processedIndexes = new HashSet<>();
		int requestedOutputs = 0;
		if (batchRequest.config.primaryMediaMode() != DownloadSelectionConfig.PrimaryMediaMode.NONE)
			requestedOutputs++;
		if (batchRequest.config.subtitleEnabled()) requestedOutputs++;
		if (batchRequest.config.thumbnailEnabled()) requestedOutputs++;

		for (int playlistIndex : batchRequest.selectedIndexes) {
			if (batchCanceled || resourcesDisposed) break;
			postRowBatchStatus(playlistIndex, PlaylistDownloadItem.BatchResultStatus.PREPARING, null);
			PlaylistDownloadItem item = findItem(playlistIndex);
			if (item == null) continue;
			try {
				PlaybackDetails playbackDetails = youtubeExtractor.getInfo(
								item.getVideoUrl(),
								batchExtractionSession).get();
				if (batchCanceled || resourcesDisposed) break;
				List<Task> tasks = taskFactory.buildPlaylistTasksForItem(
								playbackDetails,
								batchRequest.config,
								playlistIndex,
								batchRequest.plannedNames.getOrDefault(playlistIndex, item.getTitle()),
								DownloadStorageUtils.getWorkingDirectory(context),
								batchRequest.parent.getTaskId());
				if (tasks.isEmpty()) {
					failedRows++;
					postRowBatchStatus(playlistIndex, PlaylistDownloadItem.BatchResultStatus.FAILED, null);
				} else {
					DownloadService service = downloadService;
					if (service == null) {
						failedRows++;
						postRowBatchStatus(playlistIndex, PlaylistDownloadItem.BatchResultStatus.FAILED, null);
						break;
					}
					service.download(tasks);
					if (tasks.size() < requestedOutputs) {
						partialRows++;
						postRowBatchStatus(playlistIndex, PlaylistDownloadItem.BatchResultStatus.PARTIAL, null);
					} else {
						queuedRows++;
						postRowBatchStatus(playlistIndex, PlaylistDownloadItem.BatchResultStatus.QUEUED, null);
					}
				}
				processedIndexes.add(playlistIndex);
			} catch (final java.util.concurrent.ExecutionException e) {
				Throwable cause = e.getCause();
				if (cause instanceof InterruptedIOException || cause instanceof InterruptedException) {
					Thread.currentThread().interrupt();
					if (batchCanceled || resourcesDisposed) {
						postRowBatchStatus(playlistIndex, PlaylistDownloadItem.BatchResultStatus.CANCELED, null);
						processedIndexes.add(playlistIndex);
						break;
					}
					failedRows++;
					postRowBatchStatus(playlistIndex, PlaylistDownloadItem.BatchResultStatus.FAILED, null);
					processedIndexes.add(playlistIndex);
					continue;
				}
				throw new RuntimeException(cause);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				if (batchCanceled || resourcesDisposed) {
					postRowBatchStatus(playlistIndex, PlaylistDownloadItem.BatchResultStatus.CANCELED, null);
					processedIndexes.add(playlistIndex);
					break;
				}
				failedRows++;
				postRowBatchStatus(playlistIndex, PlaylistDownloadItem.BatchResultStatus.FAILED, null);
				processedIndexes.add(playlistIndex);
			} catch (Exception e) {
				failedRows++;
				postRowBatchStatus(playlistIndex, PlaylistDownloadItem.BatchResultStatus.FAILED, e.getMessage());
				processedIndexes.add(playlistIndex);
			} finally {
				batchPreparedCount++;
				handler.post(() -> {
					updateStatusText();
					updateSelectionSummary();
				});
			}
		}

		boolean canceled = batchCanceled || resourcesDisposed;
		if (canceled) {
			for (int playlistIndex : batchRequest.selectedIndexes) {
				if (processedIndexes.contains(playlistIndex)) continue;
				postRowBatchStatus(playlistIndex, PlaylistDownloadItem.BatchResultStatus.CANCELED, null);
			}
		}

		int addedRows = queuedRows + partialRows;
		int finalPartialRows = partialRows;
		int finalFailedRows = failedRows;
		handler.post(() -> {
			if (batchExtractionSession != null) {
				batchExtractionSession.cancel();
				batchExtractionSession = null;
			}
			batchRunning = false;
			batchCanceled = false;
			batchPreparedCount = batchTotalCount;
			lockUiForBatch(false);
			updateStatusText();
			updateSelectionSummary();
			updatePrimaryActionState();
			DownloadService service = downloadService;
			DownloadRecord parent = parentRecord;
			if (service != null && parent != null) {
				parent.setSealed(true);
				parent.setUpdatedAt(System.currentTimeMillis());
				service.upsertPlaylistRecord(parent);
				service.refreshPlaylistRecord(parent.getTaskId());
			}
			StringBuilder builder = new StringBuilder(canceled
							? context.getString(R.string.playlist_download_batch_added_before_cancel, addedRows)
							: context.getString(R.string.playlist_download_batch_added, addedRows));
			if (finalPartialRows > 0) {
				builder.append(", ")
								.append(context.getString(R.string.playlist_download_batch_segment_partial, finalPartialRows));
			}
			if (finalFailedRows > 0) {
				builder.append(", ")
								.append(context.getString(R.string.playlist_download_batch_segment_failed, finalFailedRows));
			}
			ToastUtils.show(context, builder);
			disposeResources();
		});
	}

	private void postRowBatchStatus(int playlistIndex,
	                                @NonNull PlaylistDownloadItem.BatchResultStatus batchResultStatus,
	                                @Nullable String failureReason) {
		handler.post(() -> {
			PlaylistDownloadItem item = findItem(playlistIndex);
			if (item == null || resourcesDisposed) return;
			item.setBatchResultStatus(batchResultStatus);
			if (failureReason != null && !failureReason.isBlank()) item.setFailureReason(failureReason);
			refreshItems();
		});
	}

	private void refreshItems() {
		adapter.replaceAll(items);
	}

	private void updatePlaylistTitle() {
		if (playlistTitleView == null) return;
		playlistTitleView.setText(
						playlistTitle == null || playlistTitle.isBlank()
										? context.getString(R.string.playlist_download_title_placeholder)
										: playlistTitle);
	}

	private void updateSelectionSummary() {
		if (summaryView == null) return;
		if (batchRunning) {
			summaryView.setText(context.getString(
							R.string.playlist_download_prepare_progress,
							Math.min(batchPreparedCount, batchTotalCount),
							Math.max(batchTotalCount, 1)));
			return;
		}
		if (!loadingTerminal) {
			summaryView.setText(R.string.playlist_download_loading);
			return;
		}
		if (items.isEmpty()) {
			summaryView.setText(terminalLoadMessage == null
							? context.getString(R.string.playlist_download_empty)
							: terminalLoadMessage);
			return;
		}
		summaryView.setText(context.getString(
						R.string.playlist_download_summary,
						countSelectedReadyItems(),
						countReadyItems(),
						items.size()));
	}

	private void updateStatusText() {
		if (loadingBar == null || recyclerView == null) return;
		if (batchRunning) {
			loadingBar.setVisibility(View.VISIBLE);
			recyclerView.setVisibility(View.VISIBLE);
			return;
		}

		loadingBar.setVisibility(loadingTerminal ? View.GONE : View.VISIBLE);
		if (!loadingTerminal) {
			recyclerView.setVisibility(View.VISIBLE);
			return;
		}

		if (items.isEmpty()) {
			recyclerView.setVisibility(View.GONE);
			return;
		}

		recyclerView.setVisibility(View.VISIBLE);
	}

	private void updatePrimaryActionState() {
		int selectedReadyCount = countSelectedReadyItems();
		DownloadSelectionConfig config = new DownloadSelectionConfig(
						primaryMediaMode,
						subtitleEnabled,
						thumbnailEnabled,
						threadCount);
		boolean enabled = !isUiLockedForBatch()
						&& loadingTerminal
						&& selectedReadyCount > 0
						&& config.hasAnyOutputEnabled();

		if (downloadButton != null) {
			downloadButton.setText(R.string.download);
			downloadButton.setEnabled(enabled);
		}
		if (cancelButton != null) {
			cancelButton.setText(R.string.cancel);
		}
		if (selectAllCheckBox != null) {
			int readyCount = countReadyItems();
			selectAllCheckBox.setEnabled(!isUiLockedForBatch() && readyCount > 0);
			selectAllCheckBox.setChecked(readyCount > 0 && selectedReadyCount == readyCount);
		}
	}

	private int countSelectedReadyItems() {
		int count = 0;
		for (PlaylistDownloadItem item : items) {
			if (item.isReady() && item.isSelected()) count++;
		}
		return count;
	}

	private int countReadyItems() {
		int count = 0;
		for (PlaylistDownloadItem item : items) {
			if (item.isReady()) count++;
		}
		return count;
	}

	private void updateToggleButtons() {
		if (videoButton != null) {
			videoButton.setBackgroundColor(
							primaryMediaMode == DownloadSelectionConfig.PrimaryMediaMode.VIDEO
											? toggleSelectedColor
											: toggleUnselectedColor);
		}
		if (audioButton != null) {
			audioButton.setBackgroundColor(
							primaryMediaMode == DownloadSelectionConfig.PrimaryMediaMode.AUDIO
											? toggleSelectedColor
											: toggleUnselectedColor);
		}
		if (subtitleButton != null) {
			subtitleButton.setBackgroundColor(subtitleEnabled ? toggleSelectedColor : toggleUnselectedColor);
		}
		if (thumbnailButton != null) {
			thumbnailButton.setBackgroundColor(thumbnailEnabled ? toggleSelectedColor : toggleUnselectedColor);
		}
	}

	private void lockUiForBatch(boolean locked) {
		adapter.setInteractionEnabled(!locked);
		if (threadsSeekBar != null) threadsSeekBar.setEnabled(!locked);
		if (selectAllCheckBox != null) selectAllCheckBox.setEnabled(!locked && countReadyItems() > 0);
		if (videoButton != null) videoButton.setEnabled(!locked);
		if (audioButton != null) audioButton.setEnabled(!locked);
		if (subtitleButton != null) subtitleButton.setEnabled(!locked);
		if (thumbnailButton != null) thumbnailButton.setEnabled(!locked);
		updatePrimaryActionState();
	}

	private boolean isUiLockedForBatch() {
		return batchRunning || batchReq != null;
	}

	@Nullable
	private PlaylistDownloadItem findItem(int playlistIndex) {
		for (PlaylistDownloadItem item : items) {
			if (item.getPlaylistIndex() == playlistIndex) return item;
		}
		return null;
	}

	private void disposeResources() {
		if (resourcesDisposed) return;
		resourcesDisposed = true;
		loadingCanceled = true;
		batchReq = null;
		if (batchExtractionSession != null) {
			batchExtractionSession.cancel();
			batchExtractionSession = null;
		}
		parentRecord = null;
		backgroundBatch = false;
		executor.shutdownNow();
		downloadService = null;
		isBound = false;
		if (bindingRequested) {
			try {
				context.unbindService(connection);
			} catch (IllegalArgumentException ignored) {
			}
			bindingRequested = false;
		}
		dialog = null;
	}

/**
 * Value object for app logic.
 */
	private record BatchRequest(@NonNull List<Integer> selectedIndexes,
	                            @NonNull Map<Integer, String> plannedNames,
	                            @NonNull DownloadSelectionConfig config,
	                            @NonNull DownloadRecord parent) {
	}




}


