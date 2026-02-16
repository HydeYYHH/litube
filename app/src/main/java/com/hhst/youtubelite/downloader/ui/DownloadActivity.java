package com.hhst.youtubelite.downloader.ui;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.downloader.core.history.DownloadHistoryRepository;
import com.hhst.youtubelite.downloader.core.history.DownloadRecord;
import com.hhst.youtubelite.downloader.core.history.DownloadStatus;
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
    @NonNull
    private static String guessMimeType(@NonNull final File file) {
        final String name = file.getName();
        final int dot = name.lastIndexOf('.');
        if (dot < 0) return "*/*";
        final String ext = name.substring(dot + 1).toLowerCase(Locale.US);
        final String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return type != null ? type : "*/*";
    }
    @NonNull
    private static String buildWatchUrl(@NonNull final String vid) {
        return "https://m.youtube.com/watch?v=" + vid;
    }
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_download);
        final View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        final com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.getMenu().add(Menu.NONE, MENU_CLEAR_HISTORY, Menu.NONE, R.string.clear_history).setIcon(R.drawable.ic_clear).setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS);
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
    private final android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Objects.equals(intent.getAction(), DownloadService.ACTION_DOWNLOAD_RECORD_UPDATED)) return;
            final String taskId = intent.getStringExtra(DownloadService.EXTRA_TASK_ID);
            if (taskId == null) return;
            final DownloadRecord updated = historyRepository.findByTaskId(taskId);
            if (updated == null) {
                loadRecords();
                return;
            }
            adapter.upsert(updated);
            updateEmptyState();
        }
    };
    @Override
    protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, receiver, new android.content.IntentFilter(DownloadService.ACTION_DOWNLOAD_RECORD_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED);
        bindService(new Intent(this, DownloadService.class), connection, Context.BIND_AUTO_CREATE);
        loadRecords();
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
        @Override public void onRetry(DownloadRecord record) { new DownloadDialog(buildWatchUrl(record.getVid()), DownloadActivity.this, youtubeExtractor).show(); }
        @Override public void onCopyVid(DownloadRecord record) {
            final ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return;
            clipboard.setPrimaryClip(ClipData.newPlainText("vid", record.getVid()));
            Toast.makeText(DownloadActivity.this, R.string.vid_copied, Toast.LENGTH_SHORT).show();
        }
        @Override public void onDelete(DownloadRecord record) { showDeleteDialog(record); }
    });
    private void updateEmptyState() {
        final boolean isEmpty = adapter.getItemCount() == 0;
        emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }
    private void showDeleteDialog(@NonNull final DownloadRecord record) {
        final View view = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_delete_record, null);
        final MaterialCheckBox checkbox = view.findViewById(R.id.checkbox_delete_file);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.delete_record).setView(view).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.delete_record, (d, w) -> {
            if (isBound && downloadService != null) {
                final DownloadStatus s = record.getStatus();
                if (s == DownloadStatus.QUEUED || s == DownloadStatus.RUNNING || s == DownloadStatus.MERGING) downloadService.cancel(record.getTaskId());
            }
            if (checkbox.isChecked()) FileUtils.deleteQuietly(new File(record.getOutputPath()));
            historyRepository.remove(record.getTaskId());
            loadRecords();
        }).show();
    }
    private void showClearHistoryDialog() {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.clear_history).setMessage(R.string.clear_history_confirmation).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.clear, (d, w) -> {
            historyRepository.clear();
            loadRecords();
            Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show();
        }).show();
    }
    private void openRecordFile(@NonNull final DownloadRecord record) {
        final File file = new File(record.getOutputPath());
        if (!file.exists()) { Toast.makeText(this, R.string.file_not_found, Toast.LENGTH_SHORT).show(); return; }
        final Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setDataAndType(uri, guessMimeType(file));
        try { startActivity(intent); } catch (Exception e) { Toast.makeText(this, R.string.application_not_found, Toast.LENGTH_SHORT).show(); }
    }
    private static final class DownloadRecordsAdapter extends RecyclerView.Adapter<DownloadRecordsAdapter.VH> {
        @NonNull private final List<DownloadRecord> items;
        @NonNull private final Actions actions;
        private DownloadRecordsAdapter(@NonNull final List<DownloadRecord> items, @NonNull final Actions actions) { this.items = items; this.actions = actions; }
        @SuppressLint("NotifyDataSetChanged")
        void setItems(@NonNull final List<DownloadRecord> newItems) { items.clear(); items.addAll(newItems); notifyDataSetChanged(); }
        void upsert(@NonNull final DownloadRecord record) {
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
        @NonNull @Override public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            final View v = android.view.LayoutInflater.from(parent.getContext()).inflate(R.layout.item_download_record, parent, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(@NonNull VH holder, int position) { holder.bind(items.get(position), actions); }
        @Override public int getItemCount() { return items.size(); }
        interface Actions {
            void onOpen(DownloadRecord record);
            void onCancel(DownloadRecord record);
            void onRetry(DownloadRecord record);
            void onCopyVid(DownloadRecord record);
            void onDelete(DownloadRecord record);
        }
        static final class VH extends RecyclerView.ViewHolder {
            private final com.google.android.material.imageview.ShapeableImageView thumbnail;
            private final TextView title;
            private final TextView subtitle;
            private final TextView sizeDownloaded;
            private final com.google.android.material.progressindicator.LinearProgressIndicator progress;
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
            private String formatMB(long bytes) { return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0); }
            void bind(@NonNull final DownloadRecord record, @NonNull final Actions actions) {
                title.setText(record.getFileName());
                subtitle.setText(VH.buildSubtitle(itemView.getContext(), record));
                if (sizeDownloaded != null) {
                    if (record.getTotalSize() > 0) {
                        sizeDownloaded.setText(String.format(Locale.US, "%s / %s", formatMB(record.getDownloadedSize()), formatMB(record.getTotalSize())));
                    } else {
                        sizeDownloaded.setText(formatMB(record.getDownloadedSize()));
                    }
                }
                final boolean showProgress = record.getStatus() == DownloadStatus.QUEUED || record.getStatus() == DownloadStatus.RUNNING || record.getStatus() == DownloadStatus.MERGING;
                progress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
                if (showProgress) {
                    progress.setIndeterminate(record.getStatus() == DownloadStatus.MERGING);
                    if (record.getStatus() != DownloadStatus.MERGING) progress.setProgressCompat(record.getProgress(), true);
                }
                final String thumbUrl = "https://i.ytimg.com/vi/" + record.getVid() + "/hqdefault.jpg";
                Picasso.get().load(thumbUrl).placeholder(R.drawable.ic_launcher_foreground).error(R.drawable.ic_launcher_foreground).into(thumbnail);
                itemView.setOnClickListener(v -> { if (record.getStatus() == DownloadStatus.COMPLETED) actions.onOpen(record); else VH.showActions(more, record, actions); });
                more.setOnClickListener(v -> VH.showActions(v, record, actions));
            }
            private static String buildSubtitle(@NonNull final Context context, @NonNull final DownloadRecord record) {
                final String status = switch (record.getStatus()) {
                    case QUEUED -> context.getString(R.string.status_queued);
                    case RUNNING -> context.getString(R.string.status_downloading, record.getProgress());
                    case MERGING -> context.getString(R.string.status_merging);
                    case COMPLETED -> context.getString(R.string.status_completed);
                    case CANCELED -> context.getString(R.string.status_cancelled);
                    case FAILED -> context.getString(R.string.status_failed);
                };
                final String type = switch (record.getType()) {
                    case VIDEO -> context.getString(R.string.type_video);
                    case AUDIO -> context.getString(R.string.type_audio);
                    case SUBTITLE -> context.getString(R.string.type_subtitle);
                    case THUMBNAIL -> context.getString(R.string.type_thumbnail);
                };
                return status + " • " + type;
            }
            private static void showActions(@NonNull final View anchor, @NonNull final DownloadRecord record, @NonNull final Actions actions) {
                final PopupMenu popup = new PopupMenu(anchor.getContext(), anchor);
                final android.view.Menu menu = popup.getMenu();
                if (record.getStatus() == DownloadStatus.COMPLETED) menu.add(0, 0, 0, R.string.open_file);
                if (record.getStatus() == DownloadStatus.RUNNING || record.getStatus() == DownloadStatus.QUEUED || record.getStatus() == DownloadStatus.MERGING) menu.add(0, 1, 1, R.string.cancel_download);
                menu.add(0, 2, 2, R.string.copy_vid);
                menu.add(0, 3, 3, R.string.retry_download);
                menu.add(0, 4, 4, R.string.delete_record);
                popup.setOnMenuItemClickListener(item -> {
                    switch (item.getItemId()) {
                        case 0 -> actions.onOpen(record);
                        case 1 -> actions.onCancel(record);
                        case 2 -> actions.onCopyVid(record);
                        case 3 -> actions.onRetry(record);
                        case 4 -> actions.onDelete(record);
                    }
                    return true;
                });
                popup.show();
            }
        }
    }
}