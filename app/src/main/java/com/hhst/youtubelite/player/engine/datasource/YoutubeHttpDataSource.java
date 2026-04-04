/*
 * Based on ExoPlayer's DefaultHttpDataSource.
 */

package com.hhst.youtubelite.player.engine.datasource;

import static androidx.media3.datasource.DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS;
import static androidx.media3.datasource.DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getAndroidUserAgent;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getIosUserAgent;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isAndroidStreamingUrl;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isIosStreamingUrl;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isWebEmbeddedPlayerStreamingUrl;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isWebStreamingUrl;

import android.net.Uri;
import android.util.Log;
import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DataSpec.HttpMethod;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.HttpUtil;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HttpHeaders;
import com.hhst.youtubelite.util.StreamIOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.NoRouteToHostException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * An {@link HttpDataSource} that uses Android's {@link HttpURLConnection}, based on
 * {@link DefaultHttpDataSource}, for YouTube streams.
 */
@UnstableApi
public final class YoutubeHttpDataSource extends BaseDataSource implements HttpDataSource {

	private static final String TAG = "YTLPlayback";
	private static final int MAX_REDIRECTS = 20;
	private static final int HTTP_STATUS_TEMPORARY_REDIRECT = 307;
	private static final int HTTP_STATUS_PERMANENT_REDIRECT = 308;
	private static final byte[] POST_BODY = new byte[]{0x78, 0};
	private final boolean allowCrossProtocolRedirects;
	private final boolean rangeParameterEnabled;
	private final boolean rnParameterEnabled;
	private final int connectTimeoutMillis;
	private final int readTimeoutMillis;
	@Nullable
	private final RequestProperties defaultRequestProperties;
	private final RequestProperties requestProperties;
	private final boolean keepPostFor302Redirects;
	private final String userAgent;

	@Nullable
	private DataSpec dataSpec;
	@Nullable
	private HttpURLConnection connection;
	@Nullable
	private InputStream inputStream;
	private boolean opened;
	private int responseCode;
	private long bytesToRead;
	private long bytesRead;
	private long requestNumber;

	private YoutubeHttpDataSource(int connectTimeoutMillis, int readTimeoutMillis, boolean allowCrossProtocolRedirects, boolean rangeParameterEnabled, boolean rnParameterEnabled, @Nullable RequestProperties defaultRequestProperties, boolean keepPostFor302Redirects, String userAgent) {
		super(true);
		this.connectTimeoutMillis = connectTimeoutMillis;
		this.readTimeoutMillis = readTimeoutMillis;
		this.allowCrossProtocolRedirects = allowCrossProtocolRedirects;
		this.rangeParameterEnabled = rangeParameterEnabled;
		this.rnParameterEnabled = rnParameterEnabled;
		this.defaultRequestProperties = defaultRequestProperties;
		this.requestProperties = new RequestProperties();
		this.keepPostFor302Redirects = keepPostFor302Redirects;
		this.userAgent = userAgent;
		this.requestNumber = 0;
	}

	private static boolean isCompressed(HttpURLConnection connection) {
		String contentEncoding = connection.getHeaderField("Content-Encoding");
		return "gzip".equalsIgnoreCase(contentEncoding);
	}

	@NonNull
	private static String summarize(@NonNull byte[] data) {
		if (data.length == 0) return "<empty>";
		int size = Math.min(data.length, 160);
		String text = new String(data, 0, size);
		return data.length > size ? text + "..." : text;
	}

	@Override
	@Nullable
	public Uri getUri() {
		return connection == null ? null : Uri.parse(connection.getURL().toString());
	}

	@Override
	public int getResponseCode() {
		return connection == null || responseCode <= 0 ? -1 : responseCode;
	}

	@NonNull
	@Override
	public Map<String, List<String>> getResponseHeaders() {
		if (connection == null) return ImmutableMap.of();
		return new NullFilteringHeadersMap(connection.getHeaderFields());
	}

	@Override
	public void setRequestProperty(@NonNull String name, @NonNull String value) {
		Preconditions.checkNotNull(name);
		Preconditions.checkNotNull(value);
		requestProperties.set(name, value);
	}

	@Override
	public void clearRequestProperty(@NonNull String name) {
		Preconditions.checkNotNull(name);
		requestProperties.remove(name);
	}

	@Override
	public void clearAllRequestProperties() {
		requestProperties.clear();
	}

	@Override
	public long open(@NonNull DataSpec dataSpecParameter) throws HttpDataSourceException {
		this.dataSpec = dataSpecParameter;
		bytesRead = 0;
		bytesToRead = 0;
		transferInitializing(dataSpecParameter);
		Log.d(TAG, "open uri=" + dataSpecParameter.uri
						+ " method=" + dataSpecParameter.httpMethod
						+ " position=" + dataSpecParameter.position
						+ " length=" + dataSpecParameter.length
						+ " rangeParam=" + rangeParameterEnabled
						+ " rnParam=" + rnParameterEnabled);

		HttpURLConnection httpURLConnection;
		String responseMessage;
		try {
			this.connection = makeConnection(dataSpec);
			httpURLConnection = this.connection;
			responseCode = httpURLConnection.getResponseCode();
			responseMessage = httpURLConnection.getResponseMessage();
		} catch (IOException e) {
			closeConnectionQuietly();
			Log.e(TAG, "open failed uri=" + dataSpecParameter.uri, e);
			throw HttpDataSourceException.createForIOException(e, dataSpec, HttpDataSourceException.TYPE_OPEN);
		}

		Log.d(TAG, "response code=" + responseCode
						+ " message=" + responseMessage
						+ " url=" + httpURLConnection.getURL());

		if (responseCode < 200 || responseCode > 299) {
			final Map<String, List<String>> headers = httpURLConnection.getHeaderFields();
			if (responseCode == 416) {
				long documentSize = HttpUtil.getDocumentSize(httpURLConnection.getHeaderField(HttpHeaders.CONTENT_RANGE));
				if (dataSpecParameter.position == documentSize) {
					opened = true;
					transferStarted(dataSpecParameter);
					return dataSpecParameter.length != C.LENGTH_UNSET ? dataSpecParameter.length : 0;
				}
			}

			InputStream errorStream = httpURLConnection.getErrorStream();
			byte[] errorResponseBody = errorStream != null ? StreamIOUtils.readInputStreamToBytes(errorStream) : Util.EMPTY_BYTE_ARRAY;
			Log.w(TAG, "non-2xx response code=" + responseCode
							+ " url=" + httpURLConnection.getURL()
							+ " location=" + httpURLConnection.getHeaderField(HttpHeaders.LOCATION)
							+ " errorBody=" + summarize(errorResponseBody));

			closeConnectionQuietly();
			IOException cause = responseCode == 416 ? new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) : null;
			throw new InvalidResponseCodeException(responseCode, responseMessage, cause, headers, dataSpec, errorResponseBody);
		}


		long bytesToSkip;
		if (!rangeParameterEnabled)
			bytesToSkip = responseCode == 200 && dataSpecParameter.position != 0 ? dataSpecParameter.position : 0;
		else bytesToSkip = 0;

		boolean isCompressed = isCompressed(httpURLConnection);
		if (!isCompressed) {
			if (dataSpecParameter.length != C.LENGTH_UNSET) bytesToRead = dataSpecParameter.length;
			else {
				long contentLength = HttpUtil.getContentLength(httpURLConnection.getHeaderField(HttpHeaders.CONTENT_LENGTH), httpURLConnection.getHeaderField(HttpHeaders.CONTENT_RANGE));
				bytesToRead = contentLength != C.LENGTH_UNSET ? (contentLength - bytesToSkip) : C.LENGTH_UNSET;
			}
		} else bytesToRead = dataSpecParameter.length;

		try {
			inputStream = httpURLConnection.getInputStream();
			if (isCompressed) inputStream = new GZIPInputStream(inputStream);
		} catch (IOException e) {
			closeConnectionQuietly();
			throw new HttpDataSourceException(e, dataSpec, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, HttpDataSourceException.TYPE_OPEN);
		}

		opened = true;
		transferStarted(dataSpecParameter);

		try {
			skipFully(bytesToSkip, dataSpec);
		} catch (IOException e) {
			closeConnectionQuietly();
			if (e instanceof HttpDataSourceException) throw (HttpDataSourceException) e;
			throw new HttpDataSourceException(e, dataSpec, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, HttpDataSourceException.TYPE_OPEN);
		}

		return bytesToRead;
	}

	@Override
	public int read(@NonNull byte[] buffer, int offset, int length) throws HttpDataSourceException {
		try {
			return readInternal(buffer, offset, length);
		} catch (IOException e) {
			throw HttpDataSourceException.createForIOException(e, Util.castNonNull(dataSpec), HttpDataSourceException.TYPE_READ);
		}
	}

	@Override
	public void close() throws HttpDataSourceException {
		try {
			InputStream input = this.inputStream;
			if (input != null) {
				try {
					input.close();
				} catch (IOException e) {
					throw new HttpDataSourceException(e, Util.castNonNull(dataSpec), PlaybackException.ERROR_CODE_IO_UNSPECIFIED, HttpDataSourceException.TYPE_CLOSE);
				}
			}
		} finally {
			inputStream = null;
			closeConnectionQuietly();
			if (opened) {
				opened = false;
				transferEnded();
			}
		}
	}

	@NonNull
	private HttpURLConnection makeConnection(@NonNull DataSpec dataSpecToUse) throws IOException {
		URL url = new URL(dataSpecToUse.uri.toString());
		@HttpMethod int httpMethod = dataSpecToUse.httpMethod;
		long position = dataSpecToUse.position;
		long length = dataSpecToUse.length;
		boolean allowGzip = dataSpecToUse.isFlagSet(DataSpec.FLAG_ALLOW_GZIP);

		if (!allowCrossProtocolRedirects && !keepPostFor302Redirects)
			return makeConnection(url, position, length, allowGzip, true, dataSpecToUse.httpRequestHeaders);

		// Follow redirects manually so POST and range handling stay intact.
		int redirectCount = 0;
		while (redirectCount++ <= MAX_REDIRECTS) {
			HttpURLConnection connection = makeConnection(url, position, length, allowGzip, false, dataSpecToUse.httpRequestHeaders);
			int code = connection.getResponseCode();
			String location = connection.getHeaderField(HttpHeaders.LOCATION);

			if ((httpMethod == DataSpec.HTTP_METHOD_GET || httpMethod == DataSpec.HTTP_METHOD_HEAD)
							&& (code == HttpURLConnection.HTTP_MULT_CHOICE
							|| code == HttpURLConnection.HTTP_MOVED_PERM
							|| code == HttpURLConnection.HTTP_MOVED_TEMP
							|| code == HttpURLConnection.HTTP_SEE_OTHER
							|| code == HTTP_STATUS_TEMPORARY_REDIRECT
							|| code == HTTP_STATUS_PERMANENT_REDIRECT)) {
				connection.disconnect();
				url = handleRedirect(url, location, dataSpecToUse);
			} else if (httpMethod == DataSpec.HTTP_METHOD_POST
							&& (code == HttpURLConnection.HTTP_MULT_CHOICE
							|| code == HttpURLConnection.HTTP_MOVED_PERM
							|| code == HttpURLConnection.HTTP_MOVED_TEMP
							|| code == HttpURLConnection.HTTP_SEE_OTHER)) {
				connection.disconnect();
				if (!(keepPostFor302Redirects && code == HttpURLConnection.HTTP_MOVED_TEMP)) {
					httpMethod = DataSpec.HTTP_METHOD_GET;
				}
				url = handleRedirect(url, location, dataSpecToUse);
			} else return connection;
		}

		throw new HttpDataSourceException(new NoRouteToHostException("Too many redirects: " + redirectCount), dataSpecToUse, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSourceException.TYPE_OPEN);
	}

	@NonNull
	private HttpURLConnection makeConnection(@NonNull URL url, long position, long length, boolean allowGzip, boolean followRedirects, final Map<String, String> requestParameters) throws IOException {
		String requestUrl = url.toString();

		boolean isVideoPlaybackUrl = url.getPath().startsWith("/videoplayback");
		if (isVideoPlaybackUrl && rnParameterEnabled && !requestUrl.contains("&rn=")) {
			requestUrl += "&rn=" + requestNumber;
			++requestNumber;
		}

		if (rangeParameterEnabled && isVideoPlaybackUrl && (position != 0 || length != C.LENGTH_UNSET)) {
			requestUrl += "&range=" + position + (length != C.LENGTH_UNSET ? "-" + (position + length - 1) : "-");
		}
		Log.d(TAG, "connect url=" + requestUrl
						+ " followRedirects=" + followRedirects
						+ " isVideoPlayback=" + isVideoPlaybackUrl
						+ " headers=" + requestParameters.size());

		HttpURLConnection conn = (HttpURLConnection) new URL(requestUrl).openConnection();
		conn.setConnectTimeout(connectTimeoutMillis);
		conn.setReadTimeout(readTimeoutMillis);

		Map<String, String> requestHeaders = new HashMap<>();
		if (defaultRequestProperties != null)
			requestHeaders.putAll(defaultRequestProperties.getSnapshot());
		requestHeaders.putAll(requestProperties.getSnapshot());
		requestHeaders.putAll(requestParameters);

		String cookies = CookieManager.getInstance().getCookie(requestUrl);
		if (cookies != null && !cookies.isEmpty())
			requestHeaders.put(HttpHeaders.COOKIE, cookies);

		for (final Map.Entry<String, String> property : requestHeaders.entrySet())
			conn.setRequestProperty(property.getKey(), property.getValue());

		if (!rangeParameterEnabled) {
			String rangeHeader = HttpUtil.buildRangeRequestHeader(position, length);
			if (rangeHeader != null) conn.setRequestProperty(HttpHeaders.RANGE, rangeHeader);
		}

		if (isWebStreamingUrl(requestUrl) || isWebEmbeddedPlayerStreamingUrl(requestUrl)) {
			conn.setRequestProperty(HttpHeaders.ORIGIN, "https://www.youtube.com");
			conn.setRequestProperty(HttpHeaders.REFERER, "https://www.youtube.com");
			conn.setRequestProperty(HttpHeaders.SEC_FETCH_DEST, "empty");
			conn.setRequestProperty(HttpHeaders.SEC_FETCH_MODE, "cors");
			conn.setRequestProperty(HttpHeaders.SEC_FETCH_SITE, "cross-site");
		}

		conn.setRequestProperty(HttpHeaders.TE, "trailers");
		conn.setRequestProperty(HttpHeaders.ACCEPT, "*/*");

		boolean isAndroidStreamingUrl = isAndroidStreamingUrl(requestUrl);
		boolean isIosStreamingUrl = isIosStreamingUrl(requestUrl);
		if (isAndroidStreamingUrl)
			conn.setRequestProperty(HttpHeaders.USER_AGENT, getAndroidUserAgent(null));
		else if (isIosStreamingUrl)
			conn.setRequestProperty(HttpHeaders.USER_AGENT, getIosUserAgent(null));
		else
			conn.setRequestProperty(HttpHeaders.USER_AGENT, userAgent);

		conn.setRequestProperty(HttpHeaders.ACCEPT_ENCODING, allowGzip ? "gzip" : "identity");
		conn.setInstanceFollowRedirects(followRedirects);
		if (isVideoPlaybackUrl) {
			conn.setRequestMethod("POST");
			conn.setDoOutput(true);
			conn.setFixedLengthStreamingMode(POST_BODY.length);
			try (OutputStream os = conn.getOutputStream()) {
				os.write(POST_BODY);
			}
		} else {
			conn.setRequestMethod("GET");
			conn.connect();
		}
		return conn;
	}

	@NonNull
	private URL handleRedirect(URL originalUrl, @Nullable String location, DataSpec dataSpecToHandleRedirect) throws HttpDataSourceException {
		if (location == null)
			throw new HttpDataSourceException("Null location redirect", dataSpecToHandleRedirect, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSourceException.TYPE_OPEN);

		try {
			return new URL(originalUrl, location);
		} catch (MalformedURLException e) {
			throw new HttpDataSourceException(e, dataSpecToHandleRedirect, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSourceException.TYPE_OPEN);
		}
	}

	private void skipFully(long bytesToSkip, DataSpec dataSpec) throws IOException {
		if (bytesToSkip == 0) return;
		byte[] skipBuffer = new byte[4096];
		long bytesSkipped = 0;
		while (bytesSkipped < bytesToSkip) {
			int readLength = (int) Math.min(bytesToSkip - bytesSkipped, skipBuffer.length);
			if (inputStream == null) throw new IOException("InputStream is null");
			int read = inputStream.read(skipBuffer, 0, readLength);
			if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
			if (read == -1)
				throw new HttpDataSourceException(dataSpec, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, HttpDataSourceException.TYPE_OPEN);
			bytesSkipped += read;
			bytesRead += read;
		}
	}

	private int readInternal(final byte[] buffer, int offset, int readLength) throws IOException {
		if (readLength == 0) return 0;
		if (bytesToRead != C.LENGTH_UNSET) {
			long bytesRemaining = bytesToRead - bytesRead;
			if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;
			int bytesToReadInt = (int) Math.min(readLength, bytesRemaining);
			int read = Util.castNonNull(inputStream).read(buffer, offset, bytesToReadInt);
			if (read == -1) return C.RESULT_END_OF_INPUT;
			bytesRead += read;
			bytesTransferred(read);
			return read;
		}
		int read = Util.castNonNull(inputStream).read(buffer, offset, readLength);
		if (read == -1) return C.RESULT_END_OF_INPUT;
		bytesRead += read;
		bytesTransferred(read);
		return read;
	}

	private void closeConnectionQuietly() {
		if (connection != null) {
			try {
				connection.disconnect();
			} catch (Exception e) {
				Log.e(TAG, "Unexpected error while disconnecting", e);
			}
			connection = null;
		}
	}

/**
 * Component that handles app logic.
 */
	public static final class Factory implements HttpDataSource.Factory {

		private final RequestProperties defaultRequestProperties;
		private final boolean allowCrossProtocolRedirects;
		private final boolean keepPostFor302Redirects;
		private final String userAgent;
		private boolean rangeParameterEnabled;
		private boolean rnParameterEnabled;

		private int connectTimeoutMs;
		private int readTimeoutMs;

		public Factory(String userAgent) {
			this.userAgent = userAgent;
			defaultRequestProperties = new RequestProperties();
			connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MILLIS;
			readTimeoutMs = DEFAULT_READ_TIMEOUT_MILLIS;
			allowCrossProtocolRedirects = false;
			keepPostFor302Redirects = false;
			rangeParameterEnabled = false;
			rnParameterEnabled = false;
		}

		@NonNull
		@Override
		public Factory setDefaultRequestProperties(@NonNull Map<String, String> defaultRequestPropertiesMap) {
			defaultRequestProperties.clearAndSet(defaultRequestPropertiesMap);
			return this;
		}

		public Factory setConnectTimeoutMs(int connectTimeoutMsValue) {
			connectTimeoutMs = connectTimeoutMsValue;
			return this;
		}

		public Factory setReadTimeoutMs(int readTimeoutMsValue) {
			readTimeoutMs = readTimeoutMsValue;
			return this;
		}

		public Factory setRangeParameterEnabled(boolean rangeParameterEnabled) {
			this.rangeParameterEnabled = rangeParameterEnabled;
			return this;
		}

		public Factory setRnParameterEnabled(boolean rnParameterEnabled) {
			this.rnParameterEnabled = rnParameterEnabled;
			return this;
		}

		@NonNull
		@Override
		public YoutubeHttpDataSource createDataSource() {
			return new YoutubeHttpDataSource(connectTimeoutMs, readTimeoutMs, allowCrossProtocolRedirects, rangeParameterEnabled, rnParameterEnabled, defaultRequestProperties, keepPostFor302Redirects, userAgent);
		}
	}
}
