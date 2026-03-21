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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
@UnstableApi
@SuppressLint("UnstableApi")
public class DownloadActivity extends AppCompatActivity {

    @Inject DownloadHistoryRepository historyRepository;
    @Inject YoutubeExtractor youtubeExtractor;

    private DownloadRecordsAdapter adapter;
    private DownloadService downloadService;
    private boolean isBound;
    private String filterFolder = null;
    private MaterialToolbar toolbar;
    private MaterialCheckBox selectAllCheckbox;
    private View selectionHeader;

    private final Set<String> selectedIds = new HashSet<>();

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
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        selectAllCheckbox = findViewById(R.id.toolbar_checkbox);
        selectionHeader = findViewById(R.id.selection_header);

        final View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            var systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        toolbar.setNavigationOnClickListener(v -> {
            if (!selectedIds.isEmpty()) {
                clearSelection();
            } else {
                finish();
            }
        });

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setItemAnimator(null);

        adapter = new DownloadRecordsAdapter(actions, filterFolder == null);
        recyclerView.setAdapter(adapter);

        if (selectAllCheckbox != null) {
            selectAllCheckbox.setOnClickListener(v -> {
                if (selectAllCheckbox.isChecked()) {
                    List<DownloadRecord> currentItems = getCurrentlyDisplayedRecords();
                    for (DownloadRecord r : currentItems) selectedIds.add(r.getTaskId());
                } else {
                    selectedIds.clear();
                }
                updateUIState();
                adapter.notifyDataSetChanged();
            });
        }

        updateUIState();
    }

    private List<DownloadRecord> getCurrentlyDisplayedRecords() {
        List<DownloadRecord> all = historyRepository.getAllSorted();
        if (filterFolder == null) return all;
        
        List<DownloadRecord> filtered = new ArrayList<>();
        for (DownloadRecord r : all) {
            String fn = r.getFileName();
            if (fn.startsWith("Playlist_") && fn.contains(" - ")) {
                String folder = fn.substring(9, fn.indexOf(" - "));
                if (folder.equals(filterFolder)) filtered.add(r);
            }
        }
        return filtered;
    }

    private void clearSelection() {
        selectedIds.clear();
        updateUIState();
        adapter.notifyDataSetChanged();
    }

    private void updateUIState() {
        boolean isSelecting = !selectedIds.isEmpty();

        if (!isSelecting) {
            toolbar.setTitle(filterFolder != null ? filterFolder : getString(R.string.downloads_default_title));
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
            if (selectionHeader != null) selectionHeader.setVisibility(View.GONE);
        } else {
            toolbar.setTitle("");
            toolbar.setNavigationIcon(R.drawable.ic_close);
            if (selectionHeader != null) {
                selectionHeader.setVisibility(View.VISIBLE);
                List<DownloadRecord> currentItems = getCurrentlyDisplayedRecords();
                selectAllCheckbox.setChecked(!currentItems.isEmpty() && selectedIds.size() >= currentItems.size());
            }
        }
        invalidateOptionsMenu();
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean selecting = !selectedIds.isEmpty();
        setMenuVisible(menu, R.id.action_pause_all, !selecting);
        setMenuVisible(menu, R.id.action_resume_all, !selecting);
        setMenuVisible(menu, R.id.action_delete_all, !selecting);
        setMenuVisible(menu, R.id.action_clear_history, !selecting);
        setMenuVisible(menu, R.id.action_pause_selected, selecting);
        setMenuVisible(menu, R.id.action_resume_selected, selecting);
        setMenuVisible(menu, R.id.action_retry_selected, selecting);
        setMenuVisible(menu, R.id.action_cancel_selected, selecting);
        setMenuVisible(menu, R.id.action_delete_selected, selecting);
        return super.onPrepareOptionsMenu(menu);
    }

    private void setMenuVisible(Menu menu, int id, boolean visible) {
        MenuItem item = menu.findItem(id);
        if (item != null) item.setVisible(visible);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.download_history_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_pause_all) {
            performBatch(DownloadStatus.RUNNING, tid -> downloadService.pause(tid));
        } else if (id == R.id.action_resume_all) {
            performBatch(DownloadStatus.PAUSED, tid -> downloadService.resume(tid));
        } else if (id == R.id.action_delete_all) {
            confirmDelete(getCurrentlyDisplayedRecords(), true);
        } else if (id == R.id.action_pause_selected) {
            for (String tid : selectedIds) downloadService.pause(tid);
            clearSelection();
        } else if (id == R.id.action_resume_selected || id == R.id.action_retry_selected) {
            for (String tid : selectedIds) downloadService.resume(tid);
            clearSelection();
        } else if (id == R.id.action_cancel_selected) {
            for (String tid : selectedIds) downloadService.cancel(tid);
            clearSelection();
        } else if (id == R.id.action_delete_selected) {
            List<DownloadRecord> targets = new ArrayList<>();
            for (String tid : selectedIds) {
                DownloadRecord r = historyRepository.findByTaskId(tid);
                if (r != null) targets.add(r);
            }
            confirmDelete(targets, false);
        } else if (id == R.id.action_clear_history) {
            showClearHistoryDialog();
        }
        return super.onOptionsItemSelected(item);
    }

    private void performBatch(DownloadStatus filter, java.util.function.Consumer<String> action) {
        if (isBound && downloadService != null) {
            List<DownloadRecord> list = getCurrentlyDisplayedRecords();
            for (int i = list.size() - 1; i >= 0; i--) {
                DownloadRecord r = list.get(i);
                if (r.getStatus() == filter || (filter == DownloadStatus.RUNNING && r.getStatus() == DownloadStatus.QUEUED)) {
                    action.accept(r.getTaskId());
                }
            }
        }
    }

    private void confirmDelete(List<DownloadRecord> targets, boolean isAll) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_delete_record, null);
        MaterialCheckBox cb = view.findViewById(R.id.checkbox_delete_file);
        if (isAll) cb.setText(R.string.delete_local_files);
        
        new MaterialAlertDialogBuilder(this)
                .setTitle(isAll ? "Delete All" : "Delete Selected")
                .setView(view)
                .setPositiveButton("Delete", (d, w) -> {
                    if (isBound) {
                        for (DownloadRecord r : targets) {
                            downloadService.cancel(r.getTaskId());
                            if (cb.isChecked()) FileUtils.deleteQuietly(new File(r.getOutputPath()));
                            historyRepository.remove(r.getTaskId());
                        }
                    }
                    clearSelection();
                    loadRecords();
                }).setNegativeButton("Cancel", null).show();
    }

    private final DownloadRecordsAdapter.Actions actions = new DownloadRecordsAdapter.Actions() {
        @Override public void onOpen(DownloadRecord r) { openRecordFile(r); }
        @Override public void onCancel(DownloadRecord r) { if (isBound) downloadService.cancel(r.getTaskId()); }
        @Override public void onPause(DownloadRecord r) { if (isBound) downloadService.pause(r.getTaskId()); }
        @Override public void onResume(DownloadRecord r) { if (isBound) downloadService.resume(r.getTaskId()); }
        @Override public void onRetry(DownloadRecord r) { if (isBound) downloadService.resume(r.getTaskId()); }
        @Override public void onRedownload(DownloadRecord r) {
            String url = "https://m.youtube.com/watch?v=" + r.getVid().split(":")[0];
            new DownloadDialog(url, DownloadActivity.this, youtubeExtractor).show();
        }
        @Override public void onDelete(DownloadRecord r) { showDeleteDialog(r); }
        @Override public void onToggleSelection(DownloadRecord r) { toggleSelection(r.getTaskId()); }
        @Override public void onLongClick(DownloadRecord r) { toggleSelection(r.getTaskId()); }
        @Override public boolean isSelected(DownloadRecord r) { return selectedIds.contains(r.getTaskId()); }
        @Override public boolean isInSelectionMode() { return !selectedIds.isEmpty(); }
        @Override public void onOpenFolder(String f) {
            Intent i = new Intent(DownloadActivity.this, DownloadActivity.class);
            i.putExtra("folder_name", f);
            startActivity(i);
        }
        @Override public void onDeleteFolder(List<DownloadRecord> c) {
            if (c.isEmpty()) return;
            String prefix = "Playlist_" + c.get(0).getFileName().split(" - ")[0];
            new MaterialAlertDialogBuilder(DownloadActivity.this).setTitle("Delete Folder").setMessage("Delete folder content?")
                    .setPositiveButton("Delete", (d, w) -> {
                        if (isBound) downloadService.cancelByPrefix(prefix);
                        for (DownloadRecord r : c) {
                            FileUtils.deleteQuietly(new File(r.getOutputPath()));
                            historyRepository.remove(r.getTaskId());
                        }
                        loadRecords();
                    }).setNegativeButton("Cancel", null).show();
        }
    };

    private void toggleSelection(String id) {
        if (selectedIds.contains(id)) selectedIds.remove(id);
        else selectedIds.add(id);
        updateUIState();
        adapter.notifyDataSetChanged();
    }

    private void showDeleteDialog(DownloadRecord record) {
        List<DownloadRecord> list = new ArrayList<>();
        list.add(record);
        confirmDelete(list, false);
    }

    private void showClearHistoryDialog() {
        new MaterialAlertDialogBuilder(this).setTitle("Clear Finished").setMessage("Remove finished items from list?")
                .setPositiveButton("Clear", (d, w) -> {
                    for (DownloadRecord r : historyRepository.getAllSorted()) {
                        DownloadStatus s = r.getStatus();
                        if (s == DownloadStatus.COMPLETED || s == DownloadStatus.FAILED || s == DownloadStatus.CANCELED)
                            historyRepository.remove(r.getTaskId());
                    }
                    loadRecords();
                }).setNegativeButton("Cancel", null).show();
    }

    private void openRecordFile(DownloadRecord record) {
        File file = new File(record.getOutputPath());
        if (!file.exists()) return;
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString()));
        Intent intent = new Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).setDataAndType(uri, mime != null ? mime : "*/*");
        try { startActivity(intent); } catch (Exception ignored) {}
    }

    @Override protected void onResume() { super.onResume(); loadRecords(); }
    @Override protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, receiver, new IntentFilter(DownloadService.ACTION_DOWNLOAD_RECORD_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED);
        bindService(new Intent(this, DownloadService.class), connection, BIND_AUTO_CREATE);
    }
    @Override protected void onStop() { super.onStop(); unregisterReceiver(receiver); if (isBound) unbindService(connection); }

    private void loadRecords() {
        adapter.setItems(getCurrentlyDisplayedRecords());
        findViewById(R.id.emptyView).setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String tid = intent.getStringExtra(DownloadService.EXTRA_TASK_ID);
            if (tid != null) {
                DownloadRecord r = historyRepository.findByTaskId(tid);
                if (r != null) {
                    RecyclerView rv = findViewById(R.id.recyclerView);
                    for (int i = 0; i < rv.getChildCount(); i++) {
                        RecyclerView.ViewHolder holder = rv.getChildViewHolder(rv.getChildAt(i));
                        if (holder instanceof DownloadRecordsAdapter.ItemVH vh) {
                            if (tid.equals(vh.currentTaskId)) {
                                vh.updateProgressUI(r);
                                return;
                            }
                        }
                    }
                }
            }
            loadRecords();
        }
    };

    private static class DownloadRecordsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_ITEM = 0, TYPE_FOLDER = 1;
        private final List<Object> displayItems = new ArrayList<>();
        private final Actions actions;
        private final boolean useGrouping;

        DownloadRecordsAdapter(Actions a, boolean g) { this.actions = a; this.useGrouping = g; }

        @SuppressLint("NotifyDataSetChanged")
        void setItems(List<DownloadRecord> records) {
            displayItems.clear();
            if (!useGrouping) displayItems.addAll(records);
            else {
                Map<String, List<DownloadRecord>> groups = new LinkedHashMap<>();
                List<DownloadRecord> individuals = new ArrayList<>();
                for (DownloadRecord r : records) {
                    String fn = r.getFileName();
                    if (fn.startsWith("Playlist_") && fn.contains(" - ")) {
                        String folder = fn.substring(9, fn.indexOf(" - "));
                        groups.computeIfAbsent(folder, k -> new ArrayList<>()).add(r);
                    } else { individuals.add(r); }
                }
                for (Map.Entry<String, List<DownloadRecord>> e : groups.entrySet()) {
                    if (e.getValue().size() > 1) displayItems.add(new FolderHeader(e.getKey(), e.getValue()));
                    else individuals.addAll(e.getValue());
                }
                displayItems.addAll(individuals);
            }
            notifyDataSetChanged();
        }

        @Override public int getItemViewType(int p) { return displayItems.get(p) instanceof FolderHeader ? TYPE_FOLDER : TYPE_ITEM; }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            LayoutInflater inf = LayoutInflater.from(p.getContext());
            if (vt == TYPE_FOLDER) return new FolderVH(inf.inflate(R.layout.item_download_folder, p, false));
            return new ItemVH(inf.inflate(R.layout.item_download_record, p, false));
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int p) {
            if (h instanceof FolderVH) ((FolderVH) h).bind((FolderHeader) displayItems.get(p), actions);
            else ((ItemVH) h).bind((DownloadRecord) displayItems.get(p), actions);
        }
        @Override public int getItemCount() { return displayItems.size(); }

        static class FolderHeader { String name; List<DownloadRecord> children; FolderHeader(String n, List<DownloadRecord> c) { name = n; children = c; } }

        static class FolderVH extends RecyclerView.ViewHolder {
            TextView title, subtitle; ShapeableImageView icon; ImageButton more;
            FolderVH(View v) { super(v); title = v.findViewById(R.id.title); subtitle = v.findViewById(R.id.subtitle); icon = v.findViewById(R.id.thumbnail); more = v.findViewById(R.id.more); }
            void bind(FolderHeader f, Actions a) {
                title.setText(f.name);
                subtitle.setText(itemView.getContext().getString(R.string.videos_count, f.children.size()));
                if (!f.children.isEmpty()) Picasso.get().load("https://i.ytimg.com/vi/" + f.children.get(0).getVid().split(":")[0] + "/mqdefault.jpg").into(icon);
                itemView.setOnClickListener(v -> a.onOpenFolder(f.name));
                more.setOnClickListener(v -> {
                    PopupMenu p = new PopupMenu(v.getContext(), v); p.getMenu().add("Delete");
                    p.setOnMenuItemClickListener(i -> { a.onDeleteFolder(f.children); return true; }); p.show();
                });
            }
        }

        static class ItemVH extends RecyclerView.ViewHolder {
            ShapeableImageView thumb; TextView title, subtitle, size; LinearProgressIndicator progress; ImageButton more; MaterialCheckBox checkBox;
            String currentTaskId;
            DownloadRecord currentRecord;

            ItemVH(View v) { super(v); thumb = v.findViewById(R.id.thumbnail); title = v.findViewById(R.id.title); subtitle = v.findViewById(R.id.subtitle); size = v.findViewById(R.id.size_downloaded); progress = v.findViewById(R.id.progress); more = v.findViewById(R.id.more); checkBox = v.findViewById(R.id.checkbox); }

            void bind(DownloadRecord r, Actions a) {
                this.currentRecord = r;
                this.currentTaskId = r.getTaskId();
                String fn = r.getFileName();
                if (fn.startsWith("Playlist_") && fn.contains(" - ")) fn = fn.substring(fn.indexOf(" - ") + 3);
                title.setText(fn);
                updateProgressUI(r);
                Picasso.get().load("https://i.ytimg.com/vi/" + r.getVid().split(":")[0] + "/mqdefault.jpg").into(thumb);

                boolean selecting = a.isInSelectionMode();
                checkBox.setVisibility(selecting ? View.VISIBLE : View.GONE);
                checkBox.setOnCheckedChangeListener(null);
                checkBox.setChecked(a.isSelected(r));
                checkBox.setOnCheckedChangeListener((bv, is) -> a.onToggleSelection(r));
                itemView.setOnClickListener(v -> { if (selecting) a.onToggleSelection(r); else if (r.getStatus() == DownloadStatus.COMPLETED) a.onOpen(r); });
                itemView.setOnLongClickListener(v -> { if (!selecting) { a.onLongClick(r); return true; } return false; });
                more.setVisibility(selecting ? View.GONE : View.VISIBLE);
                more.setOnClickListener(v -> {
                    PopupMenu p = new PopupMenu(v.getContext(), v); Menu m = p.getMenu();
                    DownloadStatus s = currentRecord.getStatus();
                    if (s == DownloadStatus.COMPLETED) { m.add(0, 0, 0, "Open"); m.add(0, 6, 1, "Redownload"); }
                    else if (s == DownloadStatus.RUNNING || s == DownloadStatus.QUEUED || s == DownloadStatus.MERGING) { m.add(0, 1, 0, "Pause"); m.add(0, 3, 1, "Cancel"); }
                    else if (s == DownloadStatus.PAUSED) { m.add(0, 2, 0, "Resume"); m.add(0, 3, 1, "Cancel"); }
                    if (s == DownloadStatus.FAILED || s == DownloadStatus.CANCELED) { m.add(0, 7, 0, "Retry"); m.add(0, 6, 1, "Redownload"); }
                    m.add(0, 4, 2, "Delete"); m.add(0, 5, 3, "Copy Video ID");
                    p.setOnMenuItemClickListener(item -> {
                        switch (item.getItemId()) {
                            case 0: a.onOpen(currentRecord); break;
                            case 1: a.onPause(currentRecord); break;
                            case 2: a.onResume(currentRecord); break;
                            case 3: a.onCancel(currentRecord); break;
                            case 4: a.onDelete(currentRecord); break;
                            case 5:
                                ClipboardManager cm = (ClipboardManager) v.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(ClipData.newPlainText("vid", currentRecord.getVid().split(":")[0]));
                                Toast.makeText(v.getContext(), "ID Copied", Toast.LENGTH_SHORT).show();
                                break;
                            case 6: a.onRedownload(currentRecord); break;
                            case 7: a.onRetry(currentRecord); break;
                        }
                        return true;
                    }); p.show();
                });
            }

            private String getStatusString(Context context, DownloadStatus status, int progress) {
                return switch (status) {
                    case QUEUED -> context.getString(R.string.status_queued);
                    case RUNNING -> context.getString(R.string.status_downloading, progress);
                    case MERGING -> context.getString(R.string.status_merging);
                    case COMPLETED -> context.getString(R.string.status_completed);
                    case FAILED -> context.getString(R.string.status_failed);
                    case CANCELED -> context.getString(R.string.status_cancelled);
                    case PAUSED -> context.getString(R.string.status_paused);
                    default -> status.name();
                };
            }

            private String getTypeString(Context context, DownloadType type, String quality) {
                String typeStr = switch (type) {
                    case VIDEO -> context.getString(R.string.type_video);
                    case AUDIO -> context.getString(R.string.type_audio);
                    case SUBTITLE -> context.getString(R.string.type_subtitle);
                    case THUMBNAIL -> context.getString(R.string.type_thumbnail);
                    default -> type.name();
                };
                return (type == DownloadType.VIDEO && quality != null) ? typeStr + " (" + quality + ")" : typeStr;
            }

            void updateProgressUI(DownloadRecord r) {
                this.currentRecord = r;
                Context context = itemView.getContext();
                DownloadStatus s = r.getStatus();
                subtitle.setText(context.getString(R.string.download_status_with_type, getStatusString(context, s, r.getProgress()), getTypeString(context, r.getType(), r.getQuality())));
                if (s == DownloadStatus.COMPLETED) subtitle.setTextColor(Color.parseColor("#4CAF50"));
                else if (s == DownloadStatus.FAILED) subtitle.setTextColor(Color.parseColor("#F44336"));
                else subtitle.setTextColor(Color.LTGRAY);
                double downloadedMb = r.getDownloadedSize() / 1048576.0;
                if (r.getTotalSize() > 0) {
                    double totalMb = r.getTotalSize() / 1048576.0;
                    size.setText(context.getString(R.string.size_mb_ratio, downloadedMb, totalMb));
                } else {
                    size.setText(context.getString(R.string.size_mb, downloadedMb));
                }
                progress.setVisibility((s == DownloadStatus.RUNNING || s == DownloadStatus.QUEUED || s == DownloadStatus.MERGING) ? View.VISIBLE : View.GONE);
                if (s == DownloadStatus.RUNNING) progress.setProgressCompat(r.getProgress(), true);
                else if (s == DownloadStatus.QUEUED || s == DownloadStatus.MERGING) progress.setIndeterminate(true);
            }
        }
        interface Actions { void onOpen(DownloadRecord r); void onCancel(DownloadRecord r); void onPause(DownloadRecord r); void onResume(DownloadRecord r); void onRetry(DownloadRecord r); void onRedownload(DownloadRecord r); void onDelete(DownloadRecord r); void onOpenFolder(String f); void onDeleteFolder(List<DownloadRecord> c); void onLongClick(DownloadRecord r); void onToggleSelection(DownloadRecord r); boolean isSelected(DownloadRecord r); boolean isInSelectionMode(); }
    }
}