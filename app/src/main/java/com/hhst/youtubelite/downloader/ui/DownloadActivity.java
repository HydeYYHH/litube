package com.hhst.youtubelite.downloader.ui;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.downloader.core.DownloadTaskIdHelper;
import com.hhst.youtubelite.downloader.core.history.DownloadHistoryRepository;
import com.hhst.youtubelite.downloader.core.history.DownloadRecord;
import com.hhst.youtubelite.downloader.core.history.DownloadStatus;
import com.hhst.youtubelite.downloader.core.history.DownloadType;
import com.hhst.youtubelite.downloader.service.DownloadService;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.util.DownloadStorageUtils;
import com.hhst.youtubelite.util.ImageUtils;
import com.hhst.youtubelite.util.PermissionUtils;
import com.hhst.youtubelite.util.ToastUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Screen that shows download history and batch actions.
 */
@AndroidEntryPoint
@UnstableApi
public class DownloadActivity extends AppCompatActivity implements DownloadPermissionHost {
	public static final String EXTRA_PARENT_ID = "extra_parent_id";
	public static final String EXTRA_PARENT_TITLE = "extra_parent_title";
	private static final int MENU_CLEAR_HISTORY = 1;
	@NonNull
	private final Handler handler = new Handler(Looper.getMainLooper());
	@NonNull
	private final ExecutorService recordExecutor = Executors.newSingleThreadExecutor();
	@NonNull
	private final Object refreshLock = new Object();
	@NonNull
	private final Set<String> pendingTaskIds = new LinkedHashSet<>();
	@Inject
	DownloadHistoryRepository historyRepository;
	@Inject
	YoutubeExtractor youtubeExtractor;
	private RecyclerView recyclerView;
	private TextView emptyView;
	private DownloadService downloadService;
	private boolean isBound;
	private final ServiceConnection connection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			downloadService = ((DownloadService.DownloadBinder) service).getService();
			isBound = true;
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			downloadService = null;
			isBound = false;
		}
	};
	private boolean refreshScheduled;
	@Nullable
	private String parentId;
	@Nullable
	private Runnable pendingPermissionAction;

	@NonNull
	private static String getCanonicalVideoId(@NonNull DownloadRecord record) {
		String videoId = record.getVideoId();
		if (videoId.length() == 11) return videoId;
		return DownloadTaskIdHelper.extractVidId(record.getTaskId());
	}

	@NonNull
	private static String getTitle(@NonNull DownloadRecord record) {
		String title = record.getTitle();
		return title != null && !title.isBlank() ? title : record.getFileName();
	}	private final BroadcastReceiver receiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (!Objects.equals(intent.getAction(), DownloadService.ACTION_DOWNLOAD_RECORD_UPDATED))
				return;
			scheduleRecordRefresh(intent.getStringExtra(DownloadService.EXTRA_TASK_ID));
		}
	};

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		EdgeToEdge.enable(this);
		setContentView(R.layout.activity_download);
		parentId = getIntent().getStringExtra(EXTRA_PARENT_ID);
		String parentTitle = getIntent().getStringExtra(EXTRA_PARENT_TITLE);

		View root = findViewById(R.id.root);
		ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
			var systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
			v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
			return insets;
		});

		MaterialToolbar toolbar = findViewById(R.id.toolbar);
		toolbar.setNavigationOnClickListener(v -> finish());
		toolbar.setTitle(isDetailMode()
						? (parentTitle == null || parentTitle.isBlank()
						? getString(R.string.playlist_download_title_placeholder)
						: parentTitle)
						: getString(R.string.download_history));
		if (!isDetailMode()) {
			toolbar.getMenu().add(Menu.NONE, MENU_CLEAR_HISTORY, Menu.NONE, R.string.clear_history)
							.setIcon(R.drawable.ic_clear)
							.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM);
		}
		toolbar.setOnMenuItemClickListener(item -> {
			if (item.getItemId() == MENU_CLEAR_HISTORY) {
				showClearHistoryDialog();
				return true;
			}
			return false;
		});

		recyclerView = findViewById(R.id.recyclerView);
		emptyView = findViewById(R.id.emptyView);
		recyclerView.setLayoutManager(new LinearLayoutManager(this));
		recyclerView.setAdapter(adapter);
		if (recyclerView.getItemAnimator() instanceof SimpleItemAnimator animator) {
			animator.setSupportsChangeAnimations(false);
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		loadRecords();
	}	private final DownloadRecordsAdapter adapter = new DownloadRecordsAdapter(new ArrayList<>(), new DownloadRecordsAdapter.Actions() {
		@Override
		public void onOpen(DownloadRecord record) {
			if (record.getType() == DownloadType.PLAYLIST) {
				openPlaylist(record);
				return;
			}
			openRecordFile(record);
		}

		@Override
		public void onCancel(DownloadRecord record) {
			cancelRecord(record);
		}

		@Override
		public void onRetry(DownloadRecord record) {
			onRedownload(record);
		}

		@Override
		public void onRedownload(DownloadRecord record) {
			if (record.getType() == DownloadType.PLAYLIST) return;
			String videoId = getCanonicalVideoId(record);
			String url = "https://m.youtube.com/watch?v=" + videoId;
			new DownloadDialog(url, DownloadActivity.this, youtubeExtractor).show();
		}

		@Override
		public void onCopyVid(DownloadRecord record) {
			if (record.getType() == DownloadType.PLAYLIST) return;
			ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
			if (clipboard != null) {
				String videoId = getCanonicalVideoId(record);
				clipboard.setPrimaryClip(ClipData.newPlainText("videoId", videoId));
				ToastUtils.show(DownloadActivity.this, R.string.vid_copied);
			}
		}

		@Override
		public void onDelete(DownloadRecord record) {
			showDeleteDialog(record);
		}
	});

	@Override
	protected void onStart() {
		super.onStart();
		ContextCompat.registerReceiver(this, receiver, new IntentFilter(DownloadService.ACTION_DOWNLOAD_RECORD_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED);
		bindService(new Intent(this, DownloadService.class), connection, Context.BIND_AUTO_CREATE);
	}

	@Override
	protected void onStop() {
		super.onStop();
		try {
			unregisterReceiver(receiver);
		} catch (Exception ignored) {
		}
		if (isBound) {
			unbindService(connection);
			isBound = false;
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		handler.removeCallbacksAndMessages(null);
		recordExecutor.shutdownNow();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode,
	                                       @NonNull String[] permissions,
	                                       @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode != PermissionUtils.REQUEST_STORAGE_PERMISSION) return;
		Runnable action = pendingPermissionAction;
		pendingPermissionAction = null;
		if (grantResults.length == 0) return;
		for (int result : grantResults) {
			if (result != PackageManager.PERMISSION_GRANTED) return;
		}
		if (action != null) action.run();
	}

	private boolean isDetailMode() {
		return parentId != null && !parentId.isBlank();
	}

	private void loadRecords() {
		recordExecutor.execute(() -> {
			List<DownloadRecord> verified = loadVisibleRecords();
			runOnUiThread(() -> {
				adapter.setItems(verified);
				updateEmptyState();
			});
		});
	}

	private void scheduleRecordRefresh(@Nullable String taskId) {
		if (taskId == null || taskId.isBlank()) {
			loadRecords();
			return;
		}
		synchronized (refreshLock) {
			pendingTaskIds.add(taskId);
			if (refreshScheduled) return;
			refreshScheduled = true;
		}
		handler.postDelayed(() -> {
			final List<String> taskIds;
			synchronized (refreshLock) {
				refreshScheduled = false;
				if (pendingTaskIds.isEmpty()) return;
				taskIds = new ArrayList<>(pendingTaskIds);
				pendingTaskIds.clear();
			}
			recordExecutor.execute(() -> {
				List<RecordMutation> mutations = buildRecordMutations(taskIds);
				runOnUiThread(() -> applyRecordMutations(mutations));
			});
		}, 80L);
	}

	@NonNull
	private List<DownloadRecord> loadVisibleRecords() {
		List<DownloadRecord> source = isDetailMode()
						? historyRepository.getChildrenSorted(parentId)
						: historyRepository.getRootsSorted();
		List<DownloadRecord> verified = new ArrayList<>(source.size());
		for (DownloadRecord record : source) {
			DownloadRecord checked = verifyRecord(record);
			if (checked != null) verified.add(checked);
		}
		return verified;
	}

	@Nullable
	private DownloadRecord verifyRecord(@NonNull DownloadRecord record) {
		if (record.getType() != DownloadType.PLAYLIST
						&& record.getStatus() == DownloadStatus.COMPLETED
						&& DownloadStorageUtils.doesNotExist(this, record.getOutputPath())) {
			historyRepository.remove(record.getTaskId());
			return null;
		}
		return record;
	}

	@NonNull
	private List<RecordMutation> buildRecordMutations(@NonNull List<String> taskIds) {
		List<DownloadRecord> allRecords = historyRepository.getAllSorted();
		Map<String, DownloadRecord> recordsById = new LinkedHashMap<>(allRecords.size());
		for (DownloadRecord record : allRecords) {
			recordsById.put(record.getTaskId(), record);
		}

		Set<String> targetIds = new LinkedHashSet<>();
		if (isDetailMode()) {
			for (String taskId : taskIds) {
				DownloadRecord updated = recordsById.get(taskId);
				if (updated == null) {
					targetIds.add(taskId);
					continue;
				}
				if (Objects.equals(parentId, updated.getParentId())) targetIds.add(updated.getTaskId());
			}
		} else {
			for (String taskId : taskIds) {
				DownloadRecord updated = recordsById.get(taskId);
				if (updated == null) {
					targetIds.add(taskId);
					continue;
				}
				String updatedParentId = updated.getParentId();
				if (updatedParentId != null && !updatedParentId.isBlank()) {
					targetIds.add(updatedParentId);
					continue;
				}
				targetIds.add(updated.getTaskId());
			}
		}

		List<RecordMutation> mutations = new ArrayList<>(targetIds.size());
		for (String taskId : targetIds) {
			DownloadRecord updated = recordsById.get(taskId);
			DownloadRecord checked = updated == null ? null : verifyRecord(updated);
			if (checked == null) {
				mutations.add(RecordMutation.remove(taskId));
			} else {
				mutations.add(RecordMutation.upsert(checked));
			}
		}
		return mutations;
	}

	private void applyRecordMutations(@NonNull List<RecordMutation> mutations) {
		if (mutations.isEmpty()) return;
		for (RecordMutation mutation : mutations) {
			if (mutation.remove) {
				adapter.removeItemByTaskId(mutation.taskId);
			} else if (mutation.record != null) {
				adapter.upsertItem(mutation.record);
			}
		}
		updateEmptyState();
	}

	private void updateEmptyState() {
		boolean isEmpty = adapter.getItemCount() == 0;
		emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
		recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
	}

	private void openPlaylist(@NonNull DownloadRecord record) {
		Intent intent = new Intent(this, DownloadActivity.class);
		intent.putExtra(EXTRA_PARENT_ID, record.getTaskId());
		intent.putExtra(EXTRA_PARENT_TITLE, getTitle(record));
		startActivity(intent);
	}

	private void cancelRecord(@NonNull DownloadRecord record) {
		if (!isBound || downloadService == null) return;
		if (record.getType() == DownloadType.PLAYLIST) {
			for (DownloadRecord child : historyRepository.getChildrenSorted(record.getTaskId())) {
				downloadService.cancel(child.getTaskId());
				scheduleRecordRefresh(child.getTaskId());
			}
			scheduleRecordRefresh(record.getTaskId());
			return;
		}
		downloadService.cancel(record.getTaskId());
		scheduleRecordRefresh(record.getTaskId());
	}

	private void showDeleteDialog(@NonNull DownloadRecord record) {
		View view = LayoutInflater.from(this).inflate(R.layout.dialog_delete_record, null);
		MaterialCheckBox checkbox = view.findViewById(R.id.checkbox_delete_file);
		new MaterialAlertDialogBuilder(this)
						.setTitle(R.string.delete_record)
						.setView(view)
						.setNegativeButton(R.string.cancel, null)
						.setPositiveButton(R.string.delete_record, (d, w) -> {
							deleteRecord(record, checkbox.isChecked());
							loadRecords();
						}).show();
	}

	private void deleteRecord(@NonNull DownloadRecord record, boolean deleteFiles) {
		if (record.getType() == DownloadType.PLAYLIST) {
			List<DownloadRecord> children = historyRepository.getChildrenSorted(record.getTaskId());
			if (isBound && downloadService != null) {
				for (DownloadRecord child : children) {
					downloadService.cancel(child.getTaskId());
				}
			}
			if (deleteFiles) {
				for (DownloadRecord child : children) {
					if (!child.getOutputPath().isBlank()) {
						DownloadStorageUtils.delete(this, child.getOutputPath());
					}
				}
			}
			historyRepository.removeWithChildren(record.getTaskId());
			adapter.removeItemByTaskId(record.getTaskId());
			updateEmptyState();
			return;
		}

		if (isBound && downloadService != null) {
			downloadService.cancel(record.getTaskId());
		}
		if (deleteFiles) {
			DownloadStorageUtils.delete(this, record.getOutputPath());
		}
		historyRepository.remove(record.getTaskId());
		adapter.removeItemByTaskId(record.getTaskId());
		updateEmptyState();
	}

	private void showClearHistoryDialog() {
		new MaterialAlertDialogBuilder(this)
						.setTitle(R.string.clear_history)
						.setMessage(R.string.clear_history_confirmation)
						.setNegativeButton(R.string.cancel, null)
						.setPositiveButton(R.string.clear, (d, w) -> {
							historyRepository.clear();
							loadRecords();
						}).show();
	}

	private void openRecordFile(@NonNull DownloadRecord record) {
		if (DownloadStorageUtils.doesNotExist(this, record.getOutputPath())) {
			ToastUtils.show(this, R.string.file_not_found);
			loadRecords();
			return;
		}
		Uri uri = DownloadStorageUtils.getOpenUri(this, record.getOutputPath());
		if (uri == null) {
			ToastUtils.show(this, R.string.file_not_found);
			loadRecords();
			return;
		}
		String type = DownloadStorageUtils.getMimeType(this, record.getOutputPath(), record.getFileName());

		Intent intent = new Intent(Intent.ACTION_VIEW);
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		intent.setDataAndType(uri, type != null ? type : "*/*");
		try {
			startActivity(intent);
		} catch (Exception e) {
			ToastUtils.show(this, R.string.application_not_found);
		}
	}

	private int color(@AttrRes int attrId) {
		TypedValue value = new TypedValue();
		getTheme().resolveAttribute(attrId, value, true);
		return value.data;
	}

	@Override
	public void requestDownloadStoragePermission(@NonNull Runnable onGranted) {
		if (!PermissionUtils.needsLegacyStoragePermission()
						|| PermissionUtils.hasDownloadStoragePermission(this)) {
			onGranted.run();
			return;
		}
		pendingPermissionAction = onGranted;
		ActivityCompat.requestPermissions(this, PermissionUtils.downloadStoragePermissions(), PermissionUtils.REQUEST_STORAGE_PERMISSION);
	}

/**
 * Value object for app logic.
 */
	private record RecordMutation(@NonNull String taskId, @Nullable DownloadRecord record,
	                              boolean remove) {

		@NonNull
		private static RecordMutation upsert(@NonNull DownloadRecord record) {
			return new RecordMutation(record.getTaskId(), record, false);
		}

		@NonNull
		private static RecordMutation remove(@NonNull String taskId) {
			return new RecordMutation(taskId, null, true);
		}
	}

/**
 * Component that handles app logic.
 */
	private final class DownloadRecordsAdapter extends RecyclerView.Adapter<DownloadRecordsAdapter.VH> {
		private static final Object PAYLOAD_RECORD_PROGRESS = new Object();
		@NonNull
		private final List<DownloadRecord> items;
		@NonNull
		private final Actions actions;

		private DownloadRecordsAdapter(@NonNull List<DownloadRecord> items,
		                               @NonNull Actions actions) {
			this.items = items;
			this.actions = actions;
		}

		void setItems(@NonNull List<DownloadRecord> newItems) {
			List<DownloadRecord> oldItems = new ArrayList<>(items);
			final DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
				@Override
				public int getOldListSize() {
					return oldItems.size();
				}

				@Override
				public int getNewListSize() {
					return newItems.size();
				}

				@Override
				public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
					return Objects.equals(
									oldItems.get(oldItemPosition).getTaskId(),
									newItems.get(newItemPosition).getTaskId());
				}

				@Override
				public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
					return Objects.equals(oldItems.get(oldItemPosition), newItems.get(newItemPosition));
				}
			});
			items.clear();
			items.addAll(newItems);
			diffResult.dispatchUpdatesTo(this);
		}

		void upsertItem(@NonNull DownloadRecord record) {
			int index = indexOf(record.getTaskId());
			if (index >= 0) {
				items.set(index, record);
				notifyItemChanged(index, PAYLOAD_RECORD_PROGRESS);
				return;
			}
			int insertIndex = findInsertIndex(record);
			items.add(insertIndex, record);
			notifyItemInserted(insertIndex);
		}

		void removeItemByTaskId(@NonNull String taskId) {
			int index = indexOf(taskId);
			if (index < 0) return;
			items.remove(index);
			notifyItemRemoved(index);
		}

		private int findInsertIndex(@NonNull DownloadRecord record) {
			for (int i = 0; i < items.size(); i++) {
				if (record.getCreatedAt() > items.get(i).getCreatedAt()) return i;
			}
			return items.size();
		}

		private int indexOf(@NonNull String taskId) {
			for (int i = 0; i < items.size(); i++) {
				if (Objects.equals(items.get(i).getTaskId(), taskId)) return i;
			}
			return -1;
		}

		@NonNull
		@Override
		public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_download_record, parent, false);
			return new VH(v);
		}

		@Override
		public void onBindViewHolder(@NonNull VH holder, int position) {
			holder.bind(items.get(position), actions);
		}

		@Override
		public void onBindViewHolder(@NonNull VH holder,
		                             final int position,
		                             @NonNull List<Object> payloads) {
			if (payloads.isEmpty()) {
				onBindViewHolder(holder, position);
				return;
			}
			holder.bindDynamic(items.get(position), actions);
		}

		@Override
		public int getItemCount() {
			return items.size();
		}

/**
 * Contract for app logic.
 */
		interface Actions {
			void onOpen(DownloadRecord record);

			void onCancel(DownloadRecord record);

			void onRetry(DownloadRecord record);

			void onRedownload(DownloadRecord record);

			void onCopyVid(DownloadRecord record);

			void onDelete(DownloadRecord record);
		}

/**
 * Component that handles app logic.
 */
		final class VH extends RecyclerView.ViewHolder {
			private final ShapeableImageView thumbnail;
			private final TextView title;
			private final TextView sizeDownloaded;
			private final TextView statusChip;
			private final TextView typeChip;
			private final LinearProgressIndicator progress;
			private final ImageButton more;

			VH(@NonNull View itemView) {
				super(itemView);
				thumbnail = itemView.findViewById(R.id.thumbnail);
				title = itemView.findViewById(R.id.title);
				sizeDownloaded = itemView.findViewById(R.id.size_downloaded);
				statusChip = itemView.findViewById(R.id.status_chip);
				typeChip = itemView.findViewById(R.id.type_chip);
				progress = itemView.findViewById(R.id.progress);
				more = itemView.findViewById(R.id.more);
			}

			private String formatMB(long bytes) {
				return String.format(Locale.US, "%.1f", bytes / 1024.0 / 1024.0);
			}

			void bind(@NonNull DownloadRecord record, @NonNull Actions actions) {
				title.setText(getTitle(record));
				typeChip.setText(localizeType(record.getType()));
				bindTypeChip(typeChip);
				bindDynamic(record, actions);

				String thumbUrl = record.getThumbnailUrl();
				if (thumbUrl != null && !thumbUrl.isBlank()) {
					ImageUtils.loadThumb(thumbnail, thumbUrl);
				} else {
					String videoId = getCanonicalVideoId(record);
					if (videoId.isBlank()) ImageUtils.showThumb(thumbnail);
					else
						ImageUtils.loadThumb(thumbnail, "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg");
				}

			}

			void bindDynamic(@NonNull DownloadRecord record, @NonNull Actions actions) {
				DownloadStatus status = record.getStatus();
				boolean isPlaylist = record.getType() == DownloadType.PLAYLIST;
				boolean isCompleted = status == DownloadStatus.COMPLETED;
				boolean isActive = isActive(record);

				statusChip.setText(buildStatusText(record));
				bindStatusChip(statusChip, status);

				if (isPlaylist) {
					sizeDownloaded.setText(getString(
									R.string.download_playlist_summary,
									record.getDoneCount(),
									record.getItemCount(),
									record.getProgress()));
					progress.setVisibility(record.getItemCount() > 0 ? View.VISIBLE : View.GONE);
					progress.setIndeterminate(false);
					progress.setProgressCompat(record.getProgress(), false);
				} else {
					if (record.getTotalSize() > 0) {
						sizeDownloaded.setText(getString(
										R.string.download_progress_with_total,
										formatMB(record.getDownloadedSize()),
										formatMB(record.getTotalSize()),
										record.getProgress()));
					} else {
						sizeDownloaded.setText(getString(
										R.string.download_progress_simple,
										formatMB(record.getDownloadedSize())));
					}
					progress.setVisibility(isActive ? View.VISIBLE : View.GONE);
					if (isActive) {
						progress.setIndeterminate(status == DownloadStatus.MERGING || status == DownloadStatus.QUEUED);
						if (!progress.isIndeterminate())
							progress.setProgressCompat(record.getProgress(), false);
					}
				}

				more.setOnClickListener(v -> showPopupMenu(v, record, actions));
				itemView.setOnClickListener(v -> {
					if (isPlaylist || isCompleted) actions.onOpen(record);
					else showPopupMenu(more, record, actions);
				});
			}

			@NonNull
			private String buildStatusText(@NonNull DownloadRecord record) {
				return switch (record.getStatus()) {
					case RUNNING -> getString(R.string.status_downloading_simple);
					case QUEUED -> getString(R.string.status_queued);
					case MERGING -> getString(R.string.status_merging);
					case COMPLETED -> getString(R.string.status_completed);
					case FAILED -> getString(R.string.status_failed);
					case CANCELED -> getString(R.string.status_cancelled);
					case PAUSED -> getString(R.string.status_paused);
				};
			}

			@NonNull
			private String localizeType(@NonNull DownloadType type) {
				return switch (type) {
					case PLAYLIST -> getString(R.string.type_playlist);
					case VIDEO -> getString(R.string.type_video);
					case AUDIO -> getString(R.string.type_audio);
					case SUBTITLE -> getString(R.string.type_subtitle);
					case THUMBNAIL -> getString(R.string.type_thumbnail);
				};
			}

			private void bindStatusChip(@NonNull TextView chip, @NonNull DownloadStatus status) {
				final int bg;
				final int fg;
				switch (status) {
					case COMPLETED -> {
						bg = color(com.google.android.material.R.attr.colorSecondaryContainer);
						fg = color(com.google.android.material.R.attr.colorOnSecondaryContainer);
					}
					case FAILED, CANCELED -> {
						bg = color(com.google.android.material.R.attr.colorErrorContainer);
						fg = color(com.google.android.material.R.attr.colorOnErrorContainer);
					}
					default -> {
						bg = color(com.google.android.material.R.attr.colorPrimaryContainer);
						fg = color(com.google.android.material.R.attr.colorOnPrimaryContainer);
					}
				}
				ViewCompat.setBackgroundTintList(chip, ColorStateList.valueOf(bg));
				chip.setTextColor(fg);
			}

			private void bindTypeChip(@NonNull TextView chip) {
				ViewCompat.setBackgroundTintList(chip, ColorStateList.valueOf(color(com.google.android.material.R.attr.colorSurfaceContainerHighest)));
				chip.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
			}

			private boolean isActive(@NonNull DownloadRecord record) {
				DownloadStatus status = record.getStatus();
				return status == DownloadStatus.RUNNING
								|| status == DownloadStatus.QUEUED
								|| status == DownloadStatus.MERGING
								|| status == DownloadStatus.PAUSED;
			}

			private void showPopupMenu(@NonNull View anchor,
			                           @NonNull DownloadRecord record,
			                           @NonNull Actions actions) {
				PopupMenu popup = new PopupMenu(anchor.getContext(), anchor);
				Menu menu = popup.getMenu();
				DownloadStatus status = record.getStatus();

				if (record.getType() == DownloadType.PLAYLIST) {
					if (isActive(record)) {
						menu.add(0, 5, 0, "Cancel Download");
					}
					menu.add(0, 3, 1, "Delete");
				} else {
					if (status == DownloadStatus.COMPLETED) {
						menu.add(0, 0, 0, "Open File");
						menu.add(0, 4, 1, "Redownload");
					} else if (status == DownloadStatus.FAILED || status == DownloadStatus.CANCELED) {
						menu.add(0, 2, 0, "Retry Download");
						menu.add(0, 4, 1, "Redownload");
					} else {
						menu.add(0, 5, 0, "Cancel Download");
					}
					menu.add(0, 1, 2, "Copy Video ID");
					menu.add(0, 3, 3, "Delete");
				}

				popup.setOnMenuItemClickListener(item -> {
					switch (item.getItemId()) {
						case 0 -> actions.onOpen(record);
						case 1 -> actions.onCopyVid(record);
						case 2 -> actions.onRetry(record);
						case 3 -> actions.onDelete(record);
						case 4 -> actions.onRedownload(record);
						case 5 -> actions.onCancel(record);
					}
					return true;
				});
				popup.show();
			}
		}
	}






}
