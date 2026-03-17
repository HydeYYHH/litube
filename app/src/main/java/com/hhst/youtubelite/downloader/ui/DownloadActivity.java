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
import android.graphics.Color;
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
import android.widget.ImageView;
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
import com.hhst.youtubelite.downloader.ui.DownloadDialog;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.squareup.picasso.Picasso;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
@UnstableApi
public class DownloadActivity extends AppCompatActivity {

    @Inject DownloadHistoryRepository historyRepository;
    @Inject YoutubeExtractor youtubeExtractor;

    private DownloadRecordsAdapter adapter;
    private DownloadService downloadService;
    private boolean isBound;
    private String filterFolder = null;

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

        filterFolder = getIntent().getStringExtra("folder_name");

        final View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            var systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        final MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        if (filterFolder != null) {
            toolbar.setTitle(filterFolder);
        } else {
            toolbar.getMenu().add(Menu.NONE, 1, Menu.NONE, R.string.clear_history)
                    .setIcon(R.drawable.ic_clear)
                    .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS);
        }

        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                showClearHistoryDialog();
                return true;
            }
            return false;
        });

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DownloadRecordsAdapter(actions, filterFolder == null);
        recyclerView.setAdapter(adapter);
    }

    private final DownloadRecordsAdapter.Actions actions = new DownloadRecordsAdapter.Actions() {
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

        @Override public void onOpenFolder(String folderName) {
            Intent intent = new Intent(DownloadActivity.this, DownloadActivity.class);
            intent.putExtra("folder_name", folderName);
            startActivity(intent);
        }

        @Override public void onDeleteFolder(List<DownloadRecord> children) {
            if (children.isEmpty()) return;

            String folderName = children.get(0).getFileName().contains("_")
                    ? children.get(0).getFileName().split("_")[0] : "Downloads";

            new MaterialAlertDialogBuilder(DownloadActivity.this)
                    .setTitle(R.string.delete_record)
                    .setMessage("Delete this playlist?")
                    .setPositiveButton(R.string.delete_record, (d, w) -> {
                        if (isBound && downloadService != null) {
                            downloadService.cancelByPrefix(folderName);
                        }

                        for (DownloadRecord r : children) {
                            FileUtils.deleteQuietly(new File(r.getOutputPath()));
                            historyRepository.remove(r.getTaskId());
                        }
                        loadRecords();
                    }).setNegativeButton(R.string.cancel, null).show();
        }
    };

    @Override protected void onResume() { super.onResume(); loadRecords(); }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!Objects.equals(intent.getAction(), DownloadService.ACTION_DOWNLOAD_RECORD_UPDATED)) return;
            loadRecords();
        }
    };

    @Override protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, receiver, new IntentFilter(DownloadService.ACTION_DOWNLOAD_RECORD_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED);
        bindService(new Intent(this, DownloadService.class), connection, BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        super.onStop();
        unregisterReceiver(receiver);
        if (isBound) unbindService(connection);
    }

    private void loadRecords() {
        List<DownloadRecord> all = historyRepository.getAllSorted();
        if (filterFolder != null) {
            List<DownloadRecord> filtered = new ArrayList<>();
            for (DownloadRecord r : all) {
                String folder = r.getFileName().contains("_") ? r.getFileName().split("_")[0] : "Downloads";
                if (folder.equals(filterFolder)) filtered.add(r);
            }
            adapter.setItems(filtered);
        } else {
            adapter.setItems(all);
        }
        findViewById(R.id.emptyView).setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void showDeleteDialog(DownloadRecord record) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_delete_record, null);
        MaterialCheckBox cb = view.findViewById(R.id.checkbox_delete_file);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.delete_record).setView(view)
                .setPositiveButton(R.string.delete_record, (d, w) -> {
                    if (isBound) downloadService.cancel(record.getTaskId());
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

    private static class DownloadRecordsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_ITEM = 0;
        private static final int TYPE_FOLDER = 1;

        private final List<Object> displayItems = new ArrayList<>();
        private final Actions actions;
        private final boolean useGrouping;

        DownloadRecordsAdapter(Actions actions, boolean useGrouping) {
            this.actions = actions;
            this.useGrouping = useGrouping;
        }

        @SuppressLint("NotifyDataSetChanged")
        void setItems(List<DownloadRecord> allRecords) {
            displayItems.clear();
            if (!useGrouping) {
                displayItems.addAll(allRecords);
            } else {
                Map<String, List<DownloadRecord>> groups = new HashMap<>();
                List<String> groupOrder = new ArrayList<>();
                for (DownloadRecord r : allRecords) {
                    String folder = r.getFileName().contains("_") ? r.getFileName().split("_")[0] : "Downloads";
                    if (!groups.containsKey(folder)) {
                        groups.put(folder, new ArrayList<>());
                        groupOrder.add(folder);
                    }
                    groups.get(folder).add(r);
                }
                for (String folderName : groupOrder) {
                    List<DownloadRecord> children = groups.get(folderName);
                    if (children.size() > 1 && !folderName.equals("Downloads")) {
                        displayItems.add(new FolderHeader(folderName, children));
                    } else {
                        displayItems.addAll(children);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @Override public int getItemViewType(int position) {
            return displayItems.get(position) instanceof FolderHeader ? TYPE_FOLDER : TYPE_ITEM;
        }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(p.getContext());
            if (viewType == TYPE_FOLDER) return new FolderVH(inflater.inflate(R.layout.item_download_folder, p, false));
            return new ItemVH(inflater.inflate(R.layout.item_download_record, p, false));
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int p) {
            if (h instanceof FolderVH) ((FolderVH) h).bind((FolderHeader) displayItems.get(p), actions);
            else ((ItemVH) h).bind((DownloadRecord) displayItems.get(p), actions);
        }

        @Override public int getItemCount() { return displayItems.size(); }

        static class FolderHeader {
            String name; List<DownloadRecord> children;
            FolderHeader(String n, List<DownloadRecord> c) { name = n; children = c; }
        }

        static class FolderVH extends RecyclerView.ViewHolder {
            TextView title, subtitle; ShapeableImageView icon; ImageButton more;
            FolderVH(View v) {
                super(v);
                title = v.findViewById(R.id.title); subtitle = v.findViewById(R.id.subtitle);
                icon = v.findViewById(R.id.thumbnail); more = v.findViewById(R.id.more);
            }
            void bind(FolderHeader f, Actions a) {
                title.setText(f.name);
                subtitle.setText(f.children.size() + " videos");
                if (!f.children.isEmpty()) {
                    String firstVid = f.children.get(0).getVid().split(":")[0];
                    Picasso.get().load("https://i.ytimg.com/vi/" + firstVid + "/mqdefault.jpg").into(icon);
                }
                itemView.setOnClickListener(v -> a.onOpenFolder(f.name));
                more.setOnClickListener(v -> {
                    PopupMenu p = new PopupMenu(v.getContext(), v);
                    p.getMenu().add("Delete");
                    p.setOnMenuItemClickListener(item -> { a.onDeleteFolder(f.children); return true; });
                    p.show();
                });
            }
        }

        static class ItemVH extends RecyclerView.ViewHolder {
            ShapeableImageView thumb; TextView title, subtitle, size;
            LinearProgressIndicator progress; ImageButton more;
            ItemVH(View v) {
                super(v);
                thumb = v.findViewById(R.id.thumbnail); title = v.findViewById(R.id.title);
                subtitle = v.findViewById(R.id.subtitle); size = v.findViewById(R.id.size_downloaded);
                progress = v.findViewById(R.id.progress); more = v.findViewById(R.id.more);
            }
            void bind(DownloadRecord r, Actions a) {
                title.setText(r.getFileName());
                DownloadStatus s = r.getStatus();
                subtitle.setText((s == DownloadStatus.RUNNING ? r.getProgress() + "%" : s.name()) + " • " + r.getType().name());

                if (s == DownloadStatus.COMPLETED) subtitle.setTextColor(Color.parseColor("#4CAF50"));
                else if (s == DownloadStatus.FAILED) subtitle.setTextColor(Color.parseColor("#F44336"));
                else subtitle.setTextColor(Color.LTGRAY);

                size.setText(String.format(Locale.US, "%.1f", r.getDownloadedSize()/1048576.0) + (r.getTotalSize()>0 ? " / " + String.format(Locale.US, "%.1f", r.getTotalSize()/1048576.0) : "") + " MB");
                progress.setVisibility((s == DownloadStatus.RUNNING || s == DownloadStatus.QUEUED || s == DownloadStatus.MERGING) ? View.VISIBLE : View.GONE);
                if (s == DownloadStatus.RUNNING) progress.setProgressCompat(r.getProgress(), true);
                Picasso.get().load("https://i.ytimg.com/vi/" + r.getVid().split(":")[0] + "/mqdefault.jpg").into(thumb);

                more.setOnClickListener(v -> {
                    PopupMenu p = new PopupMenu(v.getContext(), v);
                    Menu m = p.getMenu();
                    if (s == DownloadStatus.COMPLETED) { m.add(0, 0, 0, "Open"); m.add(0, 6, 1, "Redownload"); }
                    else if (s == DownloadStatus.RUNNING) { m.add(0, 1, 0, "Pause"); m.add(0, 3, 1, "Cancel"); }
                    else if (s == DownloadStatus.PAUSED) { m.add(0, 2, 0, "Resume"); m.add(0, 3, 1, "Cancel"); }
                    if (s == DownloadStatus.FAILED || s == DownloadStatus.CANCELED) { m.add(0, 7, 0, "Retry"); m.add(0, 6, 1, "Redownload"); }
                    m.add(0, 4, 2, "Delete"); m.add(0, 5, 3, "Copy Video ID");
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

        interface Actions {
            void onOpen(DownloadRecord r); void onCancel(DownloadRecord r);
            void onPause(DownloadRecord r); void onResume(DownloadRecord r);
            void onRetry(DownloadRecord r); void onRedownload(DownloadRecord r);
            void onCopyVid(DownloadRecord r); void onDelete(DownloadRecord r);
            void onOpenFolder(String folderName); void onDeleteFolder(List<DownloadRecord> children);
        }
    }
}
