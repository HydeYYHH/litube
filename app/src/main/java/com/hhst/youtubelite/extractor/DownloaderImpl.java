package com.hhst.youtubelite.extractor;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

public class DownloaderImpl extends Downloader {
	public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";
	private static DownloaderImpl instance;

	// Singleton instance accessor
	public static DownloaderImpl getInstance() {
		if (instance == null) instance = new DownloaderImpl();
		return instance;
	}

	@Override
	public Response execute(Request request) throws java.io.IOException {
		URL url = new URL(request.url());
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();

		String method = request.httpMethod() != null ? request.httpMethod().toUpperCase() : "GET";
		connection.setRequestMethod(method);

		for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
			for (String value : header.getValue())
				connection.addRequestProperty(header.getKey(), value);
		}

		byte[] data = request.dataToSend();
		if (data != null && !method.equals("GET") && !method.equals("HEAD")) {
			connection.setDoOutput(true);
			try (OutputStream os = connection.getOutputStream()) {
				os.write(data);
				os.flush();
			}
		}

		BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
		StringBuilder responseBuilder = new StringBuilder();
		String line;
		while ((line = reader.readLine()) != null) responseBuilder.append(line).append('\n');
		reader.close();

		Map<String, List<String>> headerFields = connection.getHeaderFields();
		return new Response(connection.getResponseCode(), connection.getResponseMessage(), headerFields, responseBuilder.toString(), connection.getURL().toString());
	}
}
