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

    private final DownloadRecordsAdapter adapter = new DownloadRecordsAdapter(new ArrayList<>(), new DownloadRecordsAdapter.Actions() {
        @Override public void onOpen(DownloadRecord record) { openRecordFile(record); }
        @Override public void onCancel(DownloadRecord record) { if (isBound) downloadService.cancel(record.getTaskId()); }
        @Override public void onPause(DownloadRecord record) { if (isBound) downloadService.pause(record.getTaskId()); }
        @Override public void onResume(DownloadRecord record) { if (isBound) downloadService.resume(record.getTaskId()); }
        @Override public void onRetry(DownloadRecord record) { if (isBound) downloadService.resume(record.getTaskId()); }
        @Override public void onRedownload(DownloadRecord record) {
            String url = "https://m.youtube.com/watch?v=" + record.getVid().split(":")[0];
            new DownloadDialog(url, DownloadActivity.this, youtubeExtractor).show();
        }
        @Override public void onCopyVid(DownloadRecord record) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("vid", record.getVid().split(":")[0]));
                Toast.makeText(DownloadActivity.this, R.string.vid_copied, Toast.LENGTH_SHORT).show();
            }
        }
        @Override public void onDelete(DownloadRecord record) { showDeleteDialog(record); }
    });

    @Override protected void onResume() { super.onResume(); loadRecords(); }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String taskId = intent.getStringExtra(DownloadService.EXTRA_TASK_ID);
            if (taskId == null) return;
            DownloadRecord updated = historyRepository.findByTaskId(taskId);
            if (updated != null) runOnUiThread(() -> adapter.upsert(updated));
        }
    };

    @Override protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, receiver, new IntentFilter(DownloadService.ACTION_DOWNLOAD_RECORD_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED);
        bindService(new Intent(this, DownloadService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        super.onStop();
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        if (isBound) unbindService(connection);
    }

    private void loadRecords() {
        List<DownloadRecord> list = historyRepository.getAllSorted();
        adapter.setItems(list);
        emptyView.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showDeleteDialog(DownloadRecord record) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_delete_record, null);
        MaterialCheckBox cb = view.findViewById(R.id.checkbox_delete_file);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.delete_record).setView(view)
                .setPositiveButton(R.string.delete_record, (d, w) -> {
                    if (isBound && downloadService != null) downloadService.cancel(record.getTaskId());
                    if (cb.isChecked()) FileUtils.deleteQuietly(new File(record.getOutputPath()));
                    historyRepository.remove(record.getTaskId());
                    loadRecords();
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void showClearHistoryDialog() {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.clear_history).setMessage(R.string.clear_history_confirmation)
                .setPositiveButton(R.string.clear, (d, w) -> { historyRepository.clear(); loadRecords(); }).show();
    }

    private void openRecordFile(DownloadRecord record) {
        File file = new File(record.getOutputPath());
        if (!file.exists()) { Toast.makeText(this, R.string.file_not_found, Toast.LENGTH_SHORT).show(); return; }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString()));
        Intent intent = new Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).setDataAndType(uri, mime != null ? mime : "*/*");
        try { startActivity(intent); } catch (Exception e) { Toast.makeText(this, R.string.application_not_found, Toast.LENGTH_SHORT).show(); }
    }

    private static class DownloadRecordsAdapter extends RecyclerView.Adapter<DownloadRecordsAdapter.VH> {
        private final List<DownloadRecord> items;
        private final Actions actions;

        DownloadRecordsAdapter(List<DownloadRecord> items, Actions actions) {
            this.items = items;
            this.actions = actions;
        }

        @SuppressLint("NotifyDataSetChanged")
        void setItems(List<DownloadRecord> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        void upsert(DownloadRecord record) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).getTaskId().equals(record.getTaskId())) {
                    items.set(i, record);
                    notifyItemChanged(i);
                    return;
                }
            }
            items.add(0, record);
            notifyItemInserted(0);
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_download_record, p, false));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int p) {
            h.bind(items.get(p), actions);
        }

        @Override public int getItemCount() { return items.size(); }

        interface Actions {
            void onOpen(DownloadRecord r);
            void onCancel(DownloadRecord r);
            void onPause(DownloadRecord r);
            void onResume(DownloadRecord r);
            void onRetry(DownloadRecord r);
            void onRedownload(DownloadRecord r);
            void onCopyVid(DownloadRecord r);
            void onDelete(DownloadRecord r);
        }

        static class VH extends RecyclerView.ViewHolder {
            ShapeableImageView thumb;
            TextView title, subtitle, size;
            LinearProgressIndicator progress;
            ImageButton more;

            VH(View v) {
                super(v);
                thumb = v.findViewById(R.id.thumbnail);
                title = v.findViewById(R.id.title);
                subtitle = v.findViewById(R.id.subtitle);
                size = v.findViewById(R.id.size_downloaded);
                progress = v.findViewById(R.id.progress);
                more = v.findViewById(R.id.more);
            }

            private String formatMB(long bytes) {
                return String.format(Locale.US, "%.1f", bytes / 1024.0 / 1024.0);
            }

            void bind(DownloadRecord r, Actions a) {
                title.setText(r.getFileName());
                final DownloadStatus status = r.getStatus();

                String typeStr = r.getType().name();
                String statusStr = (status == DownloadStatus.RUNNING) ? r.getProgress() + "%" : status.name();
                subtitle.setText(statusStr + " • " + typeStr);

                if (r.getTotalSize() > 0) {
                    size.setText(formatMB(r.getDownloadedSize()) + " / " + formatMB(r.getTotalSize()) + " MB");
                } else {
                    size.setText(formatMB(r.getDownloadedSize()) + " MB");
                }

                progress.setVisibility((status == DownloadStatus.RUNNING || status == DownloadStatus.QUEUED || status == DownloadStatus.MERGING) ? View.VISIBLE : View.GONE);
                if (progress.getVisibility() == View.VISIBLE && status == DownloadStatus.RUNNING) {
                    progress.setProgressCompat(r.getProgress(), true);
                }

                Picasso.get().load("https://i.ytimg.com/vi/" + r.getVid().split(":")[0] + "/mqdefault.jpg").into(thumb);

                more.setOnClickListener(v -> {
                    PopupMenu p = new PopupMenu(v.getContext(), v);
                    Menu m = p.getMenu();

                    if (status == DownloadStatus.COMPLETED) {
                        m.add(0, 0, 0, "Open");
                        m.add(0, 6, 1, "Redownload");
                    }
                    else if (status == DownloadStatus.RUNNING) {
                        m.add(0, 1, 0, "Pause");
                        m.add(0, 3, 1, "Cancel");
                    }
                    else if (status == DownloadStatus.PAUSED) {
                        m.add(0, 2, 0, "Resume");
                        m.add(0, 3, 1, "Cancel");
                    }

                    if (status == DownloadStatus.FAILED || status == DownloadStatus.CANCELED) {
                        m.add(0, 7, 0, "Retry");
                        m.add(0, 6, 1, "Redownload");
                    }

                    m.add(0, 4, 2, "Delete");
                    m.add(0, 5, 3, "Copy Video ID");

                    p.setOnMenuItemClickListener(item -> {
                        switch (item.getItemId()) {
                            case 0: a.onOpen(r); break;
                            case 1: a.onPause(r); break;
                            case 2: a.onResume(r); break;
                            case 3: a.onCancel(r); break;
                            case 4: a.onDelete(r); break;
                            case 5: a.onCopyVid(r); break;
                            case 6: a.onRedownload(r); break;
                            case 7: a.onRetry(r); break;
                        }
                        return true;
                    });
                    p.show();
                });
            }
        }
    }
}