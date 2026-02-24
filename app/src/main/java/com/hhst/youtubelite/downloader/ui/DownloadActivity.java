package com.hhst.youtubelite.downloader.ui;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.downloader.core.history.DownloadHistoryRepository;
import com.hhst.youtubelite.downloader.core.history.DownloadRecord;
import com.hhst.youtubelite.downloader.core.history.DownloadStatus;
import com.hhst.youtubelite.downloader.core.history.DownloadType;
import com.hhst.youtubelite.downloader.service.DownloadService;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.squareup.picasso.Picasso;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
@UnstableApi
public class DownloadActivity extends AppCompatActivity {
    private static final int MENU_CLEAR_HISTORY = 1;

    @Inject DownloadHistoryRepository historyRepository;
    @Inject YoutubeExtractor youtubeExtractor;

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

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_download);

        final View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            var systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        final MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.getMenu().add(Menu.NONE, MENU_CLEAR_HISTORY, Menu.NONE, R.string.clear_history)
                .setIcon(R.drawable.ic_clear)
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS);

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
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Objects.equals(intent.getAction(), DownloadService.ACTION_DOWNLOAD_RECORD_UPDATED)) return;
            final String taskId = intent.getStringExtra(DownloadService.EXTRA_TASK_ID);
            if (taskId == null) return;

            final DownloadRecord updated = historyRepository.findByTaskId(taskId);
            if (updated != null) {
                runOnUiThread(() -> {
                    adapter.upsert(updated);
                    updateEmptyState();
                });
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        loadRecords();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, receiver, new IntentFilter(DownloadService.ACTION_DOWNLOAD_RECORD_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED);
        bindService(new Intent(this, DownloadService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
    }

    private void loadRecords() {
        final List<DownloadRecord> list = historyRepository.getAllSorted();
        adapter.setItems(list);
        updateEmptyState();
    }

    private final DownloadRecordsAdapter adapter = new DownloadRecordsAdapter(new ArrayList<>(), new DownloadRecordsAdapter.Actions() {
        @Override public void onOpen(DownloadRecord record) { openRecordFile(record); }
        @Override public void onCancel(DownloadRecord record) { if (isBound && downloadService != null) downloadService.cancel(record.getTaskId()); }

        @Override public void onRedownload(DownloadRecord record) {
            String cleanVid = record.getVid().split(":")[0];
            String url = "https://m.youtube.com/watch?v=" + cleanVid;
            new DownloadDialog(url, DownloadActivity.this, youtubeExtractor).show();
        }

        @Override public void onCopyVid(DownloadRecord record) {
            final ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                String cleanVid = record.getVid().split(":")[0];
                clipboard.setPrimaryClip(ClipData.newPlainText("vid", cleanVid));
                Toast.makeText(DownloadActivity.this, R.string.vid_copied, Toast.LENGTH_SHORT).show();
            }
        }
        @Override public void onDelete(DownloadRecord record) { showDeleteDialog(record); }
    });

    private void updateEmptyState() {
        final boolean isEmpty = adapter.getItemCount() == 0;
        emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void showDeleteDialog(@NonNull final DownloadRecord record) {
        final View view = LayoutInflater.from(this).inflate(R.layout.dialog_delete_record, null);
        final MaterialCheckBox checkbox = view.findViewById(R.id.checkbox_delete_file);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_record)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_record, (d, w) -> {
                    if (isBound && downloadService != null) {
                        downloadService.cancel(record.getTaskId());
                    }
                    if (checkbox.isChecked()) {
                        FileUtils.deleteQuietly(new File(record.getOutputPath()));
                    }
                    historyRepository.remove(record.getTaskId());
                    loadRecords();
                }).show();
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

    private void openRecordFile(@NonNull final DownloadRecord record) {
        final File file = new File(record.getOutputPath());
        if (!file.exists()) {
            Toast.makeText(this, R.string.file_not_found, Toast.LENGTH_SHORT).show();
            return;
        }
        final Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
        final String ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
        final String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);

        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setDataAndType(uri, type != null ? type : "*/*");
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.application_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    private static final class DownloadRecordsAdapter extends RecyclerView.Adapter<DownloadRecordsAdapter.VH> {
        @NonNull private final List<DownloadRecord> items;
        @NonNull private final Actions actions;

        private DownloadRecordsAdapter(@NonNull List<DownloadRecord> items, @NonNull Actions actions) {
            this.items = items;
            this.actions = actions;
        }

        @SuppressLint("NotifyDataSetChanged")
        void setItems(@NonNull List<DownloadRecord> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        void upsert(@NonNull DownloadRecord record) {
            for (int i = 0; i < items.size(); i++) {
                if (Objects.equals(items.get(i).getTaskId(), record.getTaskId())) {
                    items.set(i, record);
                    notifyItemChanged(i);
                    return;
                }
            }
            items.add(0, record);
            notifyItemInserted(0);
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_download_record, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.bind(items.get(position), actions);
        }

        @Override public int getItemCount() { return items.size(); }

        interface Actions {
            void onOpen(DownloadRecord record);
            void onCancel(DownloadRecord record);
            void onRedownload(DownloadRecord record);
            void onCopyVid(DownloadRecord record);
            void onDelete(DownloadRecord record);
        }

        static final class VH extends RecyclerView.ViewHolder {
            private final ShapeableImageView thumbnail;
            private final TextView title, subtitle, sizeDownloaded;
            private final LinearProgressIndicator progress;
            private final ImageButton more;

            VH(@NonNull View itemView) {
                super(itemView);
                thumbnail = itemView.findViewById(R.id.thumbnail);
                title = itemView.findViewById(R.id.title);
                subtitle = itemView.findViewById(R.id.subtitle);
                sizeDownloaded = itemView.findViewById(R.id.size_downloaded);
                progress = itemView.findViewById(R.id.progress);
                more = itemView.findViewById(R.id.more);
            }

            private String formatVal(long bytes) {
                return String.format(Locale.US, "%.1f", bytes / 1024.0 / 1024.0);
            }

            void bind(@NonNull final DownloadRecord record, @NonNull final Actions actions) {
                title.setText(record.getFileName());

                final DownloadStatus s = record.getStatus();
                final boolean isCompleted = s == DownloadStatus.COMPLETED;
                final boolean isActive = s == DownloadStatus.RUNNING || s == DownloadStatus.QUEUED || s == DownloadStatus.MERGING;

                Context ctx = itemView.getContext();
                String statusText = buildStatusText(ctx, record);
                String typeText = localizeType(ctx, record.getType());
                subtitle.setText(ctx.getString(R.string.download_status_with_type, statusText, typeText));

	            String downloadedStr = formatVal(record.getDownloadedSize());
	            if (record.getTotalSize() > 0) {
                    // Logic: current / total MB (%)
		            String totalStr = formatVal(record.getTotalSize());
                    sizeDownloaded.setText(itemView.getContext().getString(R.string.download_progress_with_total, downloadedStr, totalStr, record.getProgress()));
                } else {
		            sizeDownloaded.setText(itemView.getContext().getString(R.string.download_progress_simple, downloadedStr));
                }

                progress.setVisibility(isActive ? View.VISIBLE : View.GONE);
                if (isActive) {
                    progress.setIndeterminate(s == DownloadStatus.MERGING || s == DownloadStatus.QUEUED);
                    if (!progress.isIndeterminate()) progress.setProgressCompat(record.getProgress(), true);
                }

                // FIXED THUMBNAIL: Strips suffix from ID for high-quality MQ endpoint
                String cleanVid = record.getVid().split(":")[0];
                String thumbUrl = "https://i.ytimg.com/vi/" + cleanVid + "/mqdefault.jpg";
                Picasso.get().load(thumbUrl)
                        .placeholder(R.drawable.ic_launcher_foreground)
                        .error(R.drawable.ic_launcher_foreground)
                        .into(thumbnail);

                more.setOnClickListener(v -> showPopupMenu(v, record, actions));
                itemView.setOnClickListener(v -> {
                    if (isCompleted) actions.onOpen(record);
                    else showPopupMenu(more, record, actions);
                });
            }

            private String buildStatusText(Context ctx, DownloadRecord record) {
                return switch (record.getStatus()) {
                    case RUNNING -> ctx.getString(R.string.status_downloading, record.getProgress());
                    case QUEUED -> ctx.getString(R.string.status_queued);
                    case MERGING -> ctx.getString(R.string.status_merging);
                    case COMPLETED -> ctx.getString(R.string.status_completed);
                    case FAILED -> ctx.getString(R.string.status_failed);
                    case CANCELED -> ctx.getString(R.string.status_cancelled);
                    case PAUSED -> ctx.getString(R.string.status_paused);
                };
            }

            private String localizeType(Context ctx, DownloadType type) {
                return switch (type) {
                    case VIDEO -> ctx.getString(R.string.type_video);
                    case AUDIO -> ctx.getString(R.string.type_audio);
                    case SUBTITLE -> ctx.getString(R.string.type_subtitle);
                    case THUMBNAIL -> ctx.getString(R.string.type_thumbnail);
                };
            }

            private void showPopupMenu(View anchor, DownloadRecord record, Actions actions) {
                PopupMenu popup = new PopupMenu(anchor.getContext(), anchor);
                Menu menu = popup.getMenu();
                DownloadStatus s = record.getStatus();

                if (s == DownloadStatus.COMPLETED) {
                    menu.add(0, 0, 0, "Open File");
                }

                if (!isActive(s)) {
                    menu.add(0, 4, 1, "Redownload");
                } else {
                    menu.add(0, 5, 1, "Cancel Download");
                }

                menu.add(0, 1, 2, "Copy Video ID");
                menu.add(0, 3, 3, "Delete");

                popup.setOnMenuItemClickListener(item -> {
                    switch (item.getItemId()) {
                        case 0 -> actions.onOpen(record);
                        case 1 -> actions.onCopyVid(record);
                        case 3 -> actions.onDelete(record);
                        case 4 -> actions.onRedownload(record);
                        case 5 -> actions.onCancel(record);
                    }
                    return true;
                });
                popup.show();
            }

            private boolean isActive(DownloadStatus s) {
                return s == DownloadStatus.RUNNING || s == DownloadStatus.QUEUED || s == DownloadStatus.MERGING;
            }
        }
    }
}
