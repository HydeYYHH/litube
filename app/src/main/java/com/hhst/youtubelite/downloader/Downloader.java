package com.hhst.youtubelite.downloader;


import android.util.Log;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.youtubedl_android.mapper.VideoFormat;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.io.File;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

/**
 * the main class for download video and audio
 */
public class Downloader {

    private static String cookie;

    public static void setCookie(String cookie){
        Downloader.cookie = cookie;
    }
    /**
     *
     * @param video_url not the video id but the whole url
     * @return DownloadDetails contains everything we need
     */
    public static DownloadDetails info(String video_url)
            throws YoutubeDL.CanceledException, YoutubeDLException, InterruptedException {
        YoutubeDLRequest request = new YoutubeDLRequest(video_url);
        if (cookie != null)
            request.addOption("--add-header", String.format("Cookie:\"%s\"", cookie));
        VideoInfo info = YoutubeDL.getInstance().getInfo(request);
        DownloadDetails details = new DownloadDetails();
        details.setId(info.getId());
        details.setTitle(info.getTitle());
        details.setAuthor(info.getUploader());
        details.setDescription(info.getDescription());
        details.setThumbnail(info.getThumbnail());
        details.setFormats(info.getFormats());
        return details;
    }

    public static void download(
            String processId,
            String video_url,
            VideoFormat video_format,
            File output,
            Function3<Float, Long, String, Unit> callback
    ) throws YoutubeDL.CanceledException, InterruptedException, YoutubeDLException {
        YoutubeDLRequest request = new YoutubeDLRequest(video_url);
        if (cookie != null)
            request.addOption("--add-header", String.format("Cookie:\"%s\"", cookie));
        request.addOption("--no-mtime");
        request.addOption("--embed-thumbnail");
        request.addOption("--concurrent-fragments", 8);
        request.addOption("-f","bestaudio[ext=m4a]");
        request.addOption("-o", output.getAbsolutePath() + ".m4a");
        if (video_format != null && video_format.getFormatId() != null) {
            request.addOption("-f", String.format("%s+bestaudio[ext=m4a]", video_format.getFormatId()));
            request.addOption("-o", output.getAbsolutePath());
        }
        YoutubeDLResponse response = YoutubeDL.getInstance().execute(request, processId, callback);
        Log.d("yt-dlp download command", response.getCommand().toString());
    }

    public static boolean cancel(String processId) {
        return YoutubeDL.getInstance().destroyProcessById(processId);
    }

}
