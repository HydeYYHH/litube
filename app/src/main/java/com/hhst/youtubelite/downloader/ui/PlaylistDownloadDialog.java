package com.hhst.youtubelite.downloader.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.util.UnstableApi;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.downloader.core.Task;
import com.hhst.youtubelite.downloader.service.DownloadService;
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.tencent.mmkv.MMKV;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UnstableApi
public class PlaylistDownloadDialog {

    private final Context context;
    private final List<StreamInfoItem> items;
    private final String playlistName;
    private final YoutubeExtractor extractor;
    private final DownloadService downloadService;
    private final MMKV kv = MMKV.defaultMMKV();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final ExecutorService pool = Executors.newFixedThreadPool(3);

    public PlaylistDownloadDialog(Context context, List<StreamInfoItem> items, String playlistName,
                                  YoutubeExtractor extractor, DownloadService downloadService) {
        this.context = context;
        this.items = items;
        this.playlistName = playlistName;
        this.extractor = extractor;
        this.downloadService = downloadService;
    }

    public void show() {
        String[] titles = new String[items.size()];
        boolean[] checked = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            titles[i] = items.get(i).getName();
            checked[i] = true;
        }

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dpToPx(20), dpToPx(16), dpToPx(20), 0);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tv = new TextView(context);
        tv.setText("Select Videos");
        tv.setTextSize(18);
        tv.setTextColor(Color.WHITE);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(tv);

        CheckBox allCb = new CheckBox(context);
        allCb.setChecked(true);
        allCb.setText("All");
        allCb.setTextColor(Color.WHITE);
        header.addView(allCb);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setCustomTitle(header)
                .setMultiChoiceItems(titles, checked, (d, i, b) -> checked[i] = b)
                .setPositiveButton("Next", null)
                .setNegativeButton("Cancel", null)
                .create();

        allCb.setOnCheckedChangeListener((v, is) -> {
            for (int i = 0; i < checked.length; i++) {
                checked[i] = is;
                dialog.getListView().setItemChecked(i, is);
            }
        });

        dialog.setOnShowListener(d -> {
            styleButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
            styleButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE));

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                List<StreamInfoItem> selected = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    if (checked[i]) selected.add(items.get(i));
                }
                if (!selected.isEmpty()) {
                    dialog.dismiss();
                    showQualityDialog(selected);
                }
            });
        });

        dialog.show();
    }

    private void showQualityDialog(List<StreamInfoItem> selected) {
        RadioGroup rg = new RadioGroup(context);
        rg.setPadding(dpToPx(24), dpToPx(12), dpToPx(24), dpToPx(12));

        String lastRes = kv.decodeString("last_download_res", "720p");
        List<String> resOptions = List.of("144p", "240p", "360p", "480p", "720p", "1080p");

        for (String res : resOptions) {
            MaterialRadioButton rb = new MaterialRadioButton(context);
            rb.setText(res);
            rb.setTextColor(Color.WHITE);
            rb.setTag(res);
            rg.addView(rb);
            if (res.equals(lastRes)) rb.setChecked(true);
        }

        AlertDialog qDialog = new MaterialAlertDialogBuilder(context)
                .setTitle("Choose Quality")
                .setView(rg)
                .setPositiveButton("Download", null)
                .setNegativeButton("Cancel", null)
                .create();

        qDialog.setOnShowListener(d -> {
            styleButton(qDialog.getButton(AlertDialog.BUTTON_POSITIVE));
            styleButton(qDialog.getButton(AlertDialog.BUTTON_NEGATIVE));

            qDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                int id = rg.getCheckedRadioButtonId();
                if (id == -1) return;
                String res = (String) rg.findViewById(id).getTag();
                kv.encode("last_download_res", res);
                qDialog.dismiss();
                startBulkDownload(selected, res);
            });
        });
        qDialog.show();
    }

    private void startBulkDownload(List<StreamInfoItem> selected, String res) {
        Toast.makeText(context, "Enqueuing " + selected.size() + " items...", Toast.LENGTH_LONG).show();
        int threads = kv.decodeInt("download_thread_count", 4);
        String sanitizedPl = playlistName.replaceAll("[<>:\"/|?*]", "_");

        for (StreamInfoItem item : selected) {
            pool.execute(() -> {
                try {
                    StreamDetails si = extractor.getStreamInfo(item.getUrl());
                    VideoStream vs = si.getVideoStreams().stream()
                            .filter(s -> s.getResolution().equals(res))
                            .min(Comparator.comparingInt(s -> s.getFormat() == MediaFormat.MPEG_4 ? 0 : 1))
                            .orElse(si.getVideoStreams().get(0));

                    AudioStream as = si.getAudioStreams().stream()
                            .filter(s -> s.getFormat() == MediaFormat.M4A)
                            .findFirst().orElse(si.getAudioStreams().get(0));

                    File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), context.getString(R.string.app_name));
                    if (!dir.exists()) dir.mkdirs();

                    String sanitizedTitle = item.getName().replaceAll("[<>:\"/|?*]", "_");
                    String finalName = "Playlist_" + sanitizedPl + " - " + sanitizedTitle;

                    Task t = new Task(YoutubeExtractor.getVideoId(item.getUrl()) + ":v", vs, as, null, null, finalName, dir, threads, vs.getResolution());
                    mainHandler.post(() -> { if (downloadService != null) downloadService.download(List.of(t)); });
                    Thread.sleep(1000);
                } catch (Exception ignored) {}
            });
        }
    }

    private void styleButton(Button btn) {
        if (btn instanceof MaterialButton) {
            MaterialButton mBtn = (MaterialButton) btn;
            mBtn.setBackgroundTintList(ColorStateList.valueOf(Color.WHITE));
            mBtn.setTextColor(Color.BLACK);
            mBtn.setCornerRadius(dpToPx(24));
            mBtn.setAllCaps(false);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mBtn.getLayoutParams();
            lp.setMargins(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
            mBtn.setLayoutParams(lp);
        }
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }
}