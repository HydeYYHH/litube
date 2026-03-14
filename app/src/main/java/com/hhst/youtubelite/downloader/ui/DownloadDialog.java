package com.hhst.youtubelite.downloader.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.util.UnstableApi;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.downloader.core.Task;
import com.hhst.youtubelite.downloader.service.DownloadService;
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.extractor.VideoDetails;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.gallery.GalleryActivity;
import com.squareup.picasso.Picasso;
import com.tencent.mmkv.MMKV;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UnstableApi
public class DownloadDialog {
    private static final String KEY_THREAD_COUNT = "download_thread_count";
    private static final String KEY_LAST_VIDEO_RES = "last_download_res";
    private static final String KEY_LAST_AUDIO_BITRATE = "last_download_audio_bitrate";

    private final Context context;
    private static final ExecutorService dialogExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final View dialogView;
    private final MMKV kv = MMKV.defaultMMKV();

    private VideoDetails videoDetails;
    private StreamDetails streamDetails;
    private boolean videoSel, thumbSel, audioSel, subtitleSel;
    private VideoStream videoSelStream;
    private AudioStream audioSelStream;
    private SubtitlesStream subtitleSelStream;
    private int threadCount = 4;
    private DownloadService downloadService;
    private boolean isBound = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            downloadService = ((DownloadService.DownloadBinder) service).getService();
            isBound = true;
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
        }
    };

    public DownloadDialog(String url, Context context, YoutubeExtractor youtubeExtractor) {
        this.context = context;
        this.dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_download, new FrameLayout(context), false);
        context.bindService(new Intent(context, DownloadService.class), connection, Context.BIND_AUTO_CREATE);

        dialogExecutor.execute(() -> {
            try {
                videoDetails = youtubeExtractor.getVideoInfo(url);
                updateUI();
            } catch (Exception e) {
                Log.e("DownloadDialog", "Video info fetch failed", e);
            }
        });

        dialogExecutor.execute(() -> {
            try {
                streamDetails = youtubeExtractor.getStreamInfo(url);
                updateUI();
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(context, R.string.unable_to_get_stream_info, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void updateUI() {
        mainHandler.post(() -> {
            ProgressBar progressBar = dialogView.findViewById(R.id.loadingBar);
            if (videoDetails != null) {
                progressBar.setVisibility(View.GONE);
                ImageView imageView = dialogView.findViewById(R.id.download_image);
                EditText editText = dialogView.findViewById(R.id.download_edit_text);

                Picasso.get().load(videoDetails.getThumbnail()).into(imageView);
                if (editText.getText().toString().isEmpty()) {
                    editText.setText(String.format("%s-%s", videoDetails.getTitle(), videoDetails.getAuthor()));
                }

                imageView.setOnClickListener(v -> {
                    Intent intent = new Intent(context, GalleryActivity.class);
                    ArrayList<String> urls = new ArrayList<>();
                    urls.add(videoDetails.getThumbnail());
                    intent.putStringArrayListExtra("thumbnails", urls);
                    intent.putExtra("filename", videoDetails.getTitle());
                    context.startActivity(intent);
                });
            }

            if (streamDetails != null) {
                dialogExecutor.execute(() -> restorePreferences(
                        dialogView.findViewById(R.id.button_video),
                        dialogView.findViewById(R.id.button_audio)
                ));
            }
        });
    }

    public void show() {
        EditText editText = dialogView.findViewById(R.id.download_edit_text);
        Button videoButton = dialogView.findViewById(R.id.button_video);
        Button thumbnailButton = dialogView.findViewById(R.id.button_thumbnail);
        Button audioButton = dialogView.findViewById(R.id.button_audio);
        Button subtitleButton = dialogView.findViewById(R.id.button_subtitle);
        Button downloadButton = dialogView.findViewById(R.id.button_download);
        Button cancelButton = dialogView.findViewById(R.id.button_cancel);
        SeekBar threadsSeekBar = dialogView.findViewById(R.id.threads_seekbar);
        TextView threadsCountText = dialogView.findViewById(R.id.threads_count);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.download))
                .setView(dialogView)
                .create();

        threadCount = kv.decodeInt(KEY_THREAD_COUNT, 4);
        threadsSeekBar.setProgress(threadCount - 1);
        threadsCountText.setText(String.valueOf(threadCount));

        int grayColor = context.getColor(android.R.color.darker_gray);
        videoButton.setBackgroundColor(grayColor);
        audioButton.setBackgroundColor(grayColor);
        thumbnailButton.setBackgroundColor(grayColor);
        subtitleButton.setBackgroundColor(grayColor);

        videoButton.setOnClickListener(v -> {
            if (streamDetails != null) showVideoQualityDialog(videoButton);
            else Toast.makeText(context, "Loading streams...", Toast.LENGTH_SHORT).show();
        });

        audioButton.setOnClickListener(v -> {
            if (streamDetails != null) showAudioSelectionDialog(audioButton);
            else Toast.makeText(context, "Loading streams...", Toast.LENGTH_SHORT).show();
        });

        subtitleButton.setOnClickListener(v -> {
            if (streamDetails != null) showSubtitleSelectionDialog(subtitleButton);
            else Toast.makeText(context, "Loading streams...", Toast.LENGTH_SHORT).show();
        });

        thumbnailButton.setOnClickListener(v -> {
            thumbSel = !thumbSel;
            int themeColor = thumbSel ? android.R.color.white : android.R.color.darker_gray;
            int textColor = thumbSel ? android.R.color.black : android.R.color.white;
            thumbnailButton.setBackgroundColor(context.getColor(themeColor));
            thumbnailButton.setTextColor(context.getColor(textColor));
        });

        threadsSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean f) {
                threadCount = p + 1;
                threadsCountText.setText(String.valueOf(threadCount));
                kv.encode(KEY_THREAD_COUNT, threadCount);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        downloadButton.setOnClickListener(v -> {
            if (videoDetails == null) return;
            if (videoSelStream != null) kv.encode(KEY_LAST_VIDEO_RES, videoSelStream.getResolution());
            if (audioSelStream != null) kv.encode(KEY_LAST_AUDIO_BITRATE, audioSelStream.getAverageBitrate());

            String fileName = sanitizeFileName(editText.getText().toString().isEmpty() ? videoDetails.getTitle() : editText.getText().toString());
            if (isBound && downloadService != null) downloadService.download(getTasks(fileName));
            dialog.dismiss();
        });

        cancelButton.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(di -> {
            if (isBound) {
                context.unbindService(connection);
                isBound = false;
            }
        });
        dialog.show();
    }

    private void restorePreferences(Button vBtn, Button aBtn) {
        if (streamDetails == null) return;

        String lastRes = kv.decodeString(KEY_LAST_VIDEO_RES, "");
        int lastBitrate = kv.decodeInt(KEY_LAST_AUDIO_BITRATE, -1);

        for (VideoStream vs : streamDetails.getVideoStreams()) {
            if (vs.getFormat() == MediaFormat.MPEG_4 && vs.getResolution().equals(lastRes)) {
                videoSelStream = vs;
                videoSel = true;
                mainHandler.post(() -> {
                    vBtn.setBackgroundColor(context.getColor(android.R.color.white));
                    vBtn.setTextColor(context.getColor(android.R.color.black));
                });
                break;
            }
        }

        for (AudioStream as : streamDetails.getAudioStreams()) {
            if (as.getFormat() == MediaFormat.M4A && as.getAverageBitrate() == lastBitrate) {
                audioSelStream = as;
                audioSel = true;
                mainHandler.post(() -> {
                    aBtn.setBackgroundColor(context.getColor(android.R.color.white));
                    aBtn.setTextColor(context.getColor(android.R.color.black));
                });
                break;
            }
        }
    }

    private void showVideoQualityDialog(Button btn) {
        View v = LayoutInflater.from(context).inflate(R.layout.quality_selector, null);
        AlertDialog d = new MaterialAlertDialogBuilder(context).setTitle(R.string.video_quality).setView(v).create();
        v.findViewById(R.id.button_cancel).setOnClickListener(v1 -> d.dismiss());
        setupVideoContainer(v, d, btn);
        d.show();
    }

    private void setupVideoContainer(View viewRoot, AlertDialog d, Button btn) {
        LinearLayout container = viewRoot.findViewById(R.id.quality_container);
        ProgressBar loading = viewRoot.findViewById(R.id.loadingBar2);
        if (streamDetails == null) return;

        loading.setVisibility(View.GONE);
        final CheckBox[] refs = new CheckBox[1];
        long audioSize = streamDetails.getAudioStreams().isEmpty() ? 0 : streamDetails.getAudioStreams().get(0).getItagItem().getContentLength();

        for (VideoStream s : streamDetails.getVideoStreams()) {
            if (s.getFormat() != MediaFormat.MPEG_4) continue;
            CheckBox cb = new CheckBox(context);
            cb.setText(String.format(Locale.US, "%s (%s)", s.getResolution(), formatSize(s.getItagItem().getContentLength() + audioSize)));
            cb.setOnCheckedChangeListener((v, is) -> {
                if (is) {
                    if (refs[0] != null) refs[0].setChecked(false);
                    videoSelStream = s;
                    refs[0] = cb;
                } else if (refs[0] == cb) {
                    videoSelStream = null;
                    refs[0] = null;
                }
            });
            container.addView(cb);
            if (videoSelStream != null && videoSelStream.getResolution().equals(s.getResolution())) {
                cb.setChecked(true);
                refs[0] = cb;
            }
        }

        viewRoot.findViewById(R.id.button_confirm).setOnClickListener(v1 -> {
            videoSel = refs[0] != null;
            int themeColor = videoSel ? android.R.color.white : android.R.color.darker_gray;
            int textColor = videoSel ? android.R.color.black : android.R.color.white;
            btn.setBackgroundColor(context.getColor(themeColor));
            btn.setTextColor(context.getColor(textColor));

            if (videoSel && audioSelStream == null && !streamDetails.getAudioStreams().isEmpty()) {
                showAudioTrackSelectionForVideo(streamDetails.getAudioStreams(), d::dismiss);
            } else {
                d.dismiss();
            }
        });
    }

    private void showAudioTrackSelectionForVideo(List<AudioStream> streams, Runnable onSelected) {
        View v = LayoutInflater.from(context).inflate(R.layout.quality_selector, null);
        AlertDialog d = new MaterialAlertDialogBuilder(context).setTitle("Select Audio Track").setView(v).setCancelable(false).create();
        setupAudioTrackSelection(v, d, streams, onSelected);
        d.show();
    }

    private void showAudioSelectionDialog(Button btn) {
        View v = LayoutInflater.from(context).inflate(R.layout.quality_selector, null);
        AlertDialog d = new MaterialAlertDialogBuilder(context).setTitle(R.string.audio_track).setView(v).create();
        v.findViewById(R.id.button_cancel).setOnClickListener(v1 -> d.dismiss());
        setupAudioContainer(v, d, btn);
        d.show();
    }

    private void setupAudioContainer(View dialogView, AlertDialog d, Button btn) {
        LinearLayout container = dialogView.findViewById(R.id.quality_container);
        ProgressBar loading = dialogView.findViewById(R.id.loadingBar2);
        if (streamDetails == null) return;

        loading.setVisibility(View.GONE);
        final CheckBox[] refs = new CheckBox[1];
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
                } else if (refs[0] == cb) {
                    audioSelStream = null;
                    refs[0] = null;
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
            int themeColor = audioSel ? android.R.color.white : android.R.color.darker_gray;
            int textColor = audioSel ? android.R.color.black : android.R.color.white;
            btn.setBackgroundColor(context.getColor(themeColor));
            btn.setTextColor(context.getColor(textColor));
            d.dismiss();
        });
    }

    private void setupAudioTrackSelection(View viewRoot, AlertDialog d, List<AudioStream> streams, Runnable onSelected) {
        LinearLayout container = viewRoot.findViewById(R.id.quality_container);
        viewRoot.findViewById(R.id.loadingBar2).setVisibility(View.GONE);
        final CheckBox[] refs = new CheckBox[1];
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
            if (audioSelStream != null && audioSelStream.getAverageBitrate() == s.getAverageBitrate()) {
                cb.setChecked(true);
                refs[0] = cb;
            } else if (refs[0] == null) {
                cb.setChecked(true);
                audioSelStream = s;
                refs[0] = cb;
            }
        }
        viewRoot.findViewById(R.id.button_cancel).setOnClickListener(v1 -> d.dismiss());
        viewRoot.findViewById(R.id.button_confirm).setOnClickListener(v1 -> {
            if (onSelected != null) onSelected.run();
            d.dismiss();
        });
    }

    private void showSubtitleSelectionDialog(Button btn) {
        View v = LayoutInflater.from(context).inflate(R.layout.quality_selector, null);
        AlertDialog d = new MaterialAlertDialogBuilder(context).setTitle(R.string.subtitles).setView(v).create();
        v.findViewById(R.id.button_cancel).setOnClickListener(v1 -> d.dismiss());
        setupSubtitleContainer(v, d, btn);
        d.show();
    }

    private void setupSubtitleContainer(View viewRoot, AlertDialog d, Button btn) {
        LinearLayout container = viewRoot.findViewById(R.id.quality_container);
        viewRoot.findViewById(R.id.loadingBar2).setVisibility(View.GONE);
        if (streamDetails == null) return;

        final CheckBox[] refs = new CheckBox[1];
        for (SubtitlesStream s : streamDetails.getSubtitles()) {
            CheckBox cb = new CheckBox(context);
            cb.setText(s.getDisplayLanguageName());
            cb.setOnCheckedChangeListener((v1, is) -> {
                if (is) {
                    if (refs[0] != null) refs[0].setChecked(false);
                    subtitleSelStream = s;
                    refs[0] = cb;
                } else if (refs[0] == cb) {
                    subtitleSelStream = null;
                    refs[0] = null;
                }
            });
            container.addView(cb);
            if (subtitleSelStream != null && subtitleSelStream.getLanguageTag().equals(s.getLanguageTag())) {
                cb.setChecked(true);
                refs[0] = cb;
            }
        }
        viewRoot.findViewById(R.id.button_confirm).setOnClickListener(v1 -> {
            subtitleSel = refs[0] != null;
            int themeColor = subtitleSel ? android.R.color.white : android.R.color.darker_gray;
            int textColor = subtitleSel ? android.R.color.black : android.R.color.white;
            btn.setBackgroundColor(context.getColor(themeColor));
            btn.setTextColor(context.getColor(textColor));
            d.dismiss();
        });
    }

    private String sanitizeFileName(String f) {
        return f.replaceAll("[<>:\"/|?*]", "_");
    }

    private String formatSize(long bytes) {
        return bytes <= 0 ? "Unknown" : String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    @NonNull
    private List<Task> getTasks(String f) {
        List<Task> t = new ArrayList<>();
        File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), context.getString(R.string.app_name));
        if (!d.exists()) d.mkdirs();
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
