package com.hhst.youtubelite.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import lombok.Getter;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

public final class DownloaderImpl extends Downloader {
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";

    @Getter
    private static DownloaderImpl instance;
    private final OkHttpClient client;

    private DownloaderImpl(final OkHttpClient.Builder builder) {
        this.client = builder
                .readTimeout(5, TimeUnit.SECONDS)
                .connectTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    /**
     * It's recommended to call exactly once in the entire lifetime of the application.
     *
     * @param builder if null, default builder will be used
     * @return a new instance of {@link DownloaderImpl}
     */
    public static DownloaderImpl init(@Nullable final OkHttpClient.Builder builder) {
        instance = new DownloaderImpl(builder != null ? builder : new OkHttpClient.Builder());
        return instance;
    }

    @Override
    public Response execute(@NonNull final Request request) throws IOException {
        final String httpMethod = request.httpMethod() != null ? request.httpMethod() : "GET";
        final String url = request.url();
        final Map<String, List<String>> headers = request.headers();
        final byte[] dataToSend = request.dataToSend();

        RequestBody requestBody = null;
        if (dataToSend != null) requestBody = RequestBody.create(dataToSend);

        final okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder().method(httpMethod, requestBody).url(url).addHeader("User-Agent", USER_AGENT);

        headers.forEach((headerName, headerValueList) -> {
            requestBuilder.removeHeader(headerName);
            headerValueList.forEach(headerValue -> requestBuilder.addHeader(headerName, headerValue));
        });

        try (okhttp3.Response response = client.newCall(requestBuilder.build()).execute()) {
            String responseBodyToReturn = null;
            try (ResponseBody body = response.body()) {
                if (body != null) responseBodyToReturn = body.string();
            }

            final String latestUrl = response.request().url().toString();
            return new Response(response.code(), response.message(), response.headers().toMultimap(), responseBodyToReturn, latestUrl);
        }
    }
}